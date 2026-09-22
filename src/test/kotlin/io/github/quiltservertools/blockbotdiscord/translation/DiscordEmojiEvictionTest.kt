package io.github.quiltservertools.blockbotdiscord.translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordEmojiEvictionTest {
    private val images = (1..4).associate { (0xe000 + it) to byteArrayOf(it.toByte()) }
    private val glyphs = images.mapValues { Base64.getEncoder().encodeToString(it.value) }
    private fun name(index: Int) = DiscordPackEmojis.emojiName(images.getValue(0xe000 + index))

    @Test
    fun `full inventory replaces the least frequently used icon even if used more recently`() = runBlocking {
        val inventory = Inventory(2)
        val service = inventory.service()
        service.render("\ue001\ue001\ue001", glyphs)
        service.render("\ue002", glyphs)
        val result = service.render("\ue003", glyphs)
        assertEquals(listOf(name(2)), inventory.deleted)
        assertTrue(name(1) in inventory.remote)
        assertTrue(result.contains(name(3)))
        assertEquals(2, inventory.remote.size)
    }

    @Test
    fun `equal usage evicts the least recently used icon and protects all icons in the current message`() = runBlocking {
        val inventory = Inventory(2)
        val service = inventory.service()
        service.render("\ue001", glyphs)
        service.render("\ue002", glyphs)
        service.render("\ue003\ue001", glyphs)
        assertEquals(listOf(name(2)), inventory.deleted) // 1 is needed later in the current message.
        val another = Inventory(2)
        val otherService = another.service()
        otherService.render("\ue001", glyphs)
        otherService.render("\ue002", glyphs)
        otherService.render("\ue003", glyphs)
        assertEquals(listOf(name(1)), another.deleted)
    }

    @Test
    fun `unrelated application emoji are never evicted even when all slots are full`() = runBlocking {
        val inventory = Inventory(2)
        inventory.remote["unrelated"] = "<:unrelated:50>"
        inventory.remote["bb_manually_uploaded"] = "<:bb_manually_uploaded:51>"
        val service = inventory.service()
        repeat(2) { assertEquals("Silver", service.render("\ue001 Silver", glyphs)) }
        assertTrue(inventory.deleted.isEmpty())
        assertEquals(1, inventory.warnings.size)
    }

    @Test
    fun `unknown remote usage starts at zero and is evicted before a frequently used icon`() = runBlocking {
        val inventory = Inventory(2)
        inventory.remote[name(1)] = "<:${name(1)}:1>"
        inventory.remote[name(2)] = "<:${name(2)}:2>"
        val service = inventory.service()
        service.render("\ue001", glyphs)
        service.render("\ue003", glyphs)
        assertEquals(listOf(name(2)), inventory.deleted)
    }

    @Test
    fun `Discord full slot response refreshes stale inventory and replaces one managed icon`() = runBlocking {
        val inventory = Inventory(1)
        inventory.remote[name(1)] = "<:${name(1)}:1>"
        // The local capacity estimate is larger than Discord's actual available inventory.
        val service = inventory.service(localCapacity = 2000)
        assertTrue(service.render("\ue002", glyphs).contains(name(2)))
        assertEquals(listOf(name(1)), inventory.deleted)
        assertTrue(inventory.warnings.isEmpty())
    }

    @Test
    fun `pending messages keep their icons until send completes and release on cancellation`() = runBlocking {
        val inventory = Inventory(1)
        val service = inventory.service()
        val prepared = CompletableDeferred<Unit>()
        val sendFinished = CompletableDeferred<Unit>()
        val sending = async {
            service.withMessage {
                service.render("\ue001", glyphs)
                prepared.complete(Unit)
                sendFinished.await()
            }
        }
        prepared.await()
        assertEquals("Silver", service.render("\ue002 Silver", glyphs))
        assertTrue(inventory.deleted.isEmpty())
        sending.cancel()
        sending.join()
        // No five-minute API backoff for a temporary lack of evictable slots.
        assertTrue(service.render("\ue002", glyphs).contains(name(2)))
        assertEquals(listOf(name(1)), inventory.deleted)
        assertFalse(name(1) in inventory.remote)
    }

    @Test
    fun `queued send waits for prior message response deletion and replacement upload in that order`() = runBlocking {
        withTimeout(5000) {
            val events = mutableListOf<String>()
            val oldMention = "<:${name(1)}:101>"
            val newMention = "<:${name(2)}:102>"
            val remote = mutableMapOf(name(1) to oldMention)
            val firstSending = CompletableDeferred<Unit>()
            val firstDelivered = CompletableDeferred<Unit>()
            val deleting = CompletableDeferred<Unit>()
            val deleted = CompletableDeferred<Unit>()
            val uploading = CompletableDeferred<Unit>()
            val uploaded = CompletableDeferred<Unit>()
            val service = DiscordPackEmojis(
                list = { remote.toMap() },
                create = { name, _ ->
                    events.add("upload started")
                    uploading.complete(Unit)
                    uploaded.await()
                    remote[name] = newMention
                    events.add("upload finished")
                    newMention
                },
                warn = { error(it) },
                capacity = 1,
                delete = { mention ->
                    assertEquals(oldMention, mention)
                    events.add("delete started")
                    deleting.complete(Unit)
                    deleted.await()
                    remote.remove(name(1))
                    events.add("delete finished")
                },
            )
            val first = async {
                service.withMessage {
                    assertEquals(oldMention, service.render("\ue001", glyphs))
                    events.add("first send started")
                    firstSending.complete(Unit)
                    firstDelivered.await()
                    events.add("first send finished")
                }
            }
            firstSending.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                service.withMessage {
                    assertEquals(newMention, service.render("\ue002", glyphs))
                    assertEquals(newMention, remote[name(2)])
                    events.add("second send")
                }
            }
            assertEquals(listOf("first send started"), events)
            firstDelivered.complete(Unit)
            first.await()
            deleting.await()
            assertFalse("second send" in events)
            assertFalse("upload started" in events)
            deleted.complete(Unit)
            uploading.await()
            assertFalse("second send" in events)
            uploaded.complete(Unit)
            second.await()
            assertEquals(listOf("first send started", "first send finished", "delete started", "delete finished",
                "upload started", "upload finished", "second send"), events)
        }
    }

    @Test
    fun `cancelled queued message never uploads or sends and cancellation releases the queue`() = runBlocking {
        withTimeout(5000) {
            val inventory = Inventory(1)
            val service = inventory.service()
            val sending = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            val first = async {
                service.withMessage {
                    service.render("\ue001", glyphs)
                    sending.complete(Unit)
                    response.await()
                }
            }
            sending.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                service.withMessage { error("Cancelled message must not be prepared or sent") }
            }
            second.cancel()
            second.join()
            assertTrue(inventory.deleted.isEmpty())
            first.cancel()
            first.join()
            assertTrue(service.withMessage { service.render("\ue002", glyphs) }.contains(name(2)))
        }
    }

    @Test
    fun `ambiguous deletion failure cannot send a cached mention for the possibly deleted emoji`() = runBlocking {
        val remote = mutableMapOf(name(1) to "<:${name(1)}:101>")
        val service = DiscordPackEmojis(
            list = { remote.toMap() },
            create = { _, _ -> error("No upload after failed deletion") },
            warn = {},
            capacity = 1,
            delete = {
                remote.remove(name(1))
                throw IOException("Discord deleted it but the response was lost")
            },
        )
        service.withMessage { service.render("\ue001", glyphs) }
        assertEquals("Silver", service.withMessage { service.render("\ue002 Silver", glyphs) })
        assertEquals("Silver", service.withMessage { service.render("\ue001 Silver", glyphs) })
    }

    private class Inventory(val limit: Int) {
        val remote = mutableMapOf<String, String>()
        val deleted = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        private var id = 100

        fun service(localCapacity: Int = limit) = DiscordPackEmojis(
            list = { remote.toMap() },
            create = { name, _ ->
                if (remote.size >= limit) throw EmojiCapacityException()
                "<:$name:${id++}>".also { remote[name] = it }
            },
            warn = warnings::add,
            delete = { mention ->
                val name = remote.entries.single { it.value == mention }.key
                deleted.add(name)
                remote.remove(name)
            },
            capacity = localCapacity,
        )
    }
}
