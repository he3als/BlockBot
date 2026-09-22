package io.github.quiltservertools.blockbotdiscord.translation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmojiUsagePersistenceTest {
    @TempDir
    lateinit var directory: Path
    private val usageFile get() = directory.resolve("discord/123/emoji-usage.json")
    private val warnings = mutableListOf<String>()
    private val remote = mutableMapOf<String, String>()
    private val deleted = mutableListOf<String>()
    private val images = (1..3).associate { (0xe000 + it) to byteArrayOf(it.toByte()) }
    private val glyphs = images.mapValues { Base64.getEncoder().encodeToString(it.value) }
    private fun name(index: Int, prefix: String = "bb_") = DiscordPackEmojis.emojiName(images.getValue(0xe000 + index), prefix)
    private fun store() = EmojiUsageStore(usageFile, warnings::add)
    private fun service(capacity: Int = 2000) = DiscordPackEmojis(
        list = { remote.toMap() },
        create = { name, _ -> "<:$name:123>".also { remote[name] = it } },
        warn = warnings::add,
        capacity = capacity,
        delete = { mention ->
            val name = remote.entries.single { it.value == mention }.key
            deleted.add(name)
            remote.remove(name)
        },
        usageStore = store(),
    )

    @Test
    fun `counts and eviction preference persist across a restart`() = runBlocking {
        val first = service(capacity = 2)
        first.withMessage { first.render("\ue001".repeat(4), glyphs) }
        first.withMessage { first.render("\ue002", glyphs) }
        val saved = store().load()
        assertEquals(4L, saved.emojis.getValue(name(1)).count)
        assertEquals(1L, saved.emojis.getValue(name(2)).count)
        val restarted = service(capacity = 2)
        restarted.withMessage { restarted.render("\ue003", glyphs) }
        assertEquals(listOf(name(2)), deleted)
        assertEquals(4L, store().load().emojis.getValue(name(1)).count)
        assertEquals(1L, store().load().emojis.getValue(name(3)).count)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `recency ties and previous managed prefixes survive restart`() = runBlocking {
        val first = service(capacity = 2)
        first.withMessage { first.render("\ue002", glyphs, prefix = "old_") }
        first.withMessage { first.render("\ue001", glyphs, prefix = "old_") }
        val restarted = service(capacity = 2)
        restarted.withMessage { restarted.render("\ue003", glyphs, prefix = "new_") }
        assertEquals(listOf(name(2, "old_")), deleted)
        assertEquals(setOf("old_", "new_"), store().load().prefixes)
        assertEquals(1L, store().load().emojis.getValue(name(3, "new_")).count)
    }

    @Test
    fun `concurrent messages do not lose increments and only emitted icons count`() = runBlocking {
        val service = service()
        List(20) { async(Dispatchers.Default) {
            service.withMessage { service.render("\ue001\ue001\ue001", glyphs) }
        } }.awaitAll()
        assertEquals(60L, store().load().emojis.getValue(name(1)).count)
        service.withMessage { service.render("\ue001".repeat(100), glyphs, maxLength = 50) }
        assertEquals(61L, store().load().emojis.getValue(name(1)).count)
        assertEquals(1, remote.size)
    }

    @Test
    fun `failed sends do not increase counts and nested send helpers count once`() = runBlocking<Unit> {
        val service = service()
        assertFailsWith<IOException> {
            service.withMessage {
                service.render("\ue001", glyphs)
                throw IOException("Send failed")
            }
        }
        assertTrue(store().load().emojis.isEmpty())
        service.withMessage { service.withMessage { service.render("\ue001", glyphs) } }
        assertEquals(1L, store().load().emojis.getValue(name(1)).count)
    }

    @Test
    fun `missing malformed or oversized usage file does not break rendering`() = runBlocking {
        assertEquals(EmojiUsageState(), store().load())
        Files.createDirectories(usageFile.parent)
        for (json in listOf(
            "{broken",
            """{"version":2,"sequence":0,"prefixes":[],"emojis":{}}""",
            """{"version":1,"sequence":0,"prefixes":[],"emojis":{"bb_test":{"count":-1,"lastUse":0}}}""",
            """{"version":1,"sequence":0,"prefixes":[],"emojis":{"bb_test":{"count":"1","lastUse":0}}}""",
            """{"version":1,"sequence":0,"prefixes":[],"emojis":{"bb_test":{"count":1,"lastUse":1}}}""",
            " ".repeat(2 * 1024 * 1024 + 1),
        )) {
            Files.writeString(usageFile, json)
            assertEquals(EmojiUsageState(), store().load())
        }
        val service = service()
        assertTrue(service.withMessage { service.render("\ue001", glyphs) }.contains(name(1)))
        assertEquals(1L, store().load().emojis.getValue(name(1)).count)
    }

    @Test
    fun `failed atomic replacement retains the old JSON and cleans up temporary files`() = runBlocking {
        val initial = EmojiUsageState(sequence = 1, prefixes = setOf("bb_"), emojis = mapOf(name(1) to EmojiUsage(2, 1)))
        assertTrue(store().save(initial))
        val before = Files.readString(usageFile)
        var fail = true
        val store = EmojiUsageStore(usageFile, warnings::add) { temporary, destination ->
            if (fail) throw IOException("Disk unavailable")
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        }
        val updated = initial.copy(sequence = 2, emojis = mapOf(name(1) to EmojiUsage(3, 2)))
        repeat(2) { assertFalse(store.save(updated)) }
        assertEquals(before, Files.readString(usageFile))
        assertEquals(1, warnings.size)
        Files.list(usageFile.parent).use { assertEquals(listOf(usageFile), it.toList()) }
        fail = false
        assertTrue(store.save(updated))
        assertEquals(updated, store.load())
    }

    @Test
    fun `persistence failure retains memory counts and retries after the next message`() = runBlocking {
        var fail = true
        val store = EmojiUsageStore(usageFile, warnings::add) { temporary, destination ->
            if (fail) throw IOException("Disk unavailable")
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        }
        val service = DiscordPackEmojis({ remote.toMap() }, { name, _ ->
            "<:$name:123>".also { remote[name] = it }
        }, warnings::add, usageStore = store)
        repeat(2) { service.withMessage { service.render("\ue001", glyphs) } }
        assertFalse(Files.exists(usageFile))
        fail = false
        service.withMessage { service.render("\ue001", glyphs) }
        assertEquals(3L, store.load().emojis.getValue(name(1)).count)
    }

    @Test
    fun `maximum persisted counters cannot overflow into negative usage`() = runBlocking {
        val maximum = EmojiUsageState(sequence = Long.MAX_VALUE, prefixes = setOf("bb_"),
            emojis = mapOf(name(1) to EmojiUsage(Long.MAX_VALUE, Long.MAX_VALUE)))
        store().save(maximum)
        remote[name(1)] = "<:${name(1)}:123>"
        val service = service()
        service.withMessage { service.render("\ue001", glyphs) }
        assertEquals(Long.MAX_VALUE, store().load().emojis.getValue(name(1)).count)
        assertEquals(2L, store().load().emojis.getValue(name(1)).lastUse)
    }
}
