package io.github.quiltservertools.blockbotdiscord.translation

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ResourcePackEmojiPaddingTest {
    @TempDir
    lateinit var directory: Path

    private fun glyphs(): Map<Int, String> {
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 128) for (x in 0 until 128) image.setRGB(x, y, 0x80804020.toInt())
        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return mapOf(0xe00f to Base64.getEncoder().encodeToString(output.toByteArray()))
    }

    @Test
    fun `padding uploads before sending reuses on restart and replaces old size safely at capacity`() = runBlocking {
        val glyphs = glyphs()
        val remote = mutableMapOf<String, String>()
        val events = mutableListOf<String>()
        val uploads = mutableListOf<ByteArray>()
        val store = EmojiUsageStore(directory.resolve("emoji-usage.json"), warn = { error(it) })
        fun service() = DiscordPackEmojis({ remote.toMap() }, { name, png ->
            assertTrue(remote.isEmpty())
            uploads.add(png)
            events.add("upload")
            "<:$name:${uploads.size}>".also { remote[name] = it }
        }, { error(it) }, delete = { mention ->
            assertEquals(remote.values.single(), mention)
            events.add("delete")
            remote.clear()
        }, capacity = 1, usageStore = store)
        val first = service()
        val padded = first.withMessage {
            first.render("\ue00f Silver", glyphs, padding = ResourcePackGlyphs.DEFAULT_PADDING).also {
                assertTrue(it.startsWith(remote.values.single()))
                events.add("send")
            }
        }
        assertEquals(listOf("upload", "send"), events)
        val png = ImageIO.read(uploads.single().inputStream())
        assertEquals(0, png.getRGB(0, 0))
        assertEquals(0x80804020.toInt(), png.getRGB(64, 64))
        assertEquals(1L, store.load().emojis.values.single().count)

        val restarted = service()
        assertEquals(padded, restarted.withMessage {
            restarted.render("\ue00f Silver", glyphs, padding = ResourcePackGlyphs.DEFAULT_PADDING)
        })
        assertEquals(1, uploads.size)
        assertEquals(2L, store.load().emojis.values.single().count)
        val fullSize = restarted.withMessage {
            restarted.render("\ue00f Silver", glyphs, padding = 0.0).also {
                assertTrue(it.startsWith(remote.values.single()))
                events.add("send")
            }
        }
        assertNotEquals(padded, fullSize)
        assertEquals(listOf("upload", "send", "delete", "upload", "send"), events)
        assertEquals(1L, store.load().emojis.values.single().count)
        assertEquals(0x80804020.toInt(), ImageIO.read(uploads.last().inputStream()).getRGB(0, 0))
    }

    @Test
    fun `invalid padding uses measured default and warns once while disabled icons do no image work`() = runBlocking {
        val glyphs = glyphs()
        val expected = Base64.getDecoder().decode(ResourcePackGlyphs.pad(glyphs.getValue(0xe00f), ResourcePackGlyphs.DEFAULT_PADDING))
        for (invalid in listOf(-1.0, 50.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val warnings = mutableListOf<String>()
            var uploads = 0
            val service = DiscordPackEmojis({ emptyMap() }, { name, png ->
                assertTrue(png.contentEquals(expected))
                uploads++
                "<:$name:1>"
            }, warnings::add)
            repeat(2) { service.withMessage { service.render("\ue00f Silver", glyphs, padding = invalid) } }
            assertEquals(1, uploads)
            assertEquals(1, warnings.size)
        }
        val disabled = DiscordPackEmojis({ error("No API request") }, { _, _ -> error("No upload") }, { error(it) })
        assertEquals("Silver", disabled.render("\ue00f Silver", mapOf(0xe00f to "bad PNG"), enabled = false, padding = Double.NaN))
    }

    @Test
    fun `automatic chat and embed artwork uses distinct IDs and replacement finishes before sending`() = runBlocking {
        val glyphs = glyphs()
        val remote = mutableMapOf<String, String>()
        val events = mutableListOf<String>()
        val store = EmojiUsageStore(directory.resolve("layout-usage.json"), warn = { error(it) })
        var uploads = 0
        fun service() = DiscordPackEmojis({ remote.toMap() }, { name, _ ->
            assertTrue(remote.isEmpty())
            events.add("upload")
            "<:$name:${++uploads}>".also { remote[name] = it }
        }, { error(it) }, delete = { mention ->
            assertEquals(remote.values.single(), mention)
            events.add("delete")
            remote.clear()
        }, capacity = 1, usageStore = store)
        val service = service()
        suspend fun send(context: EmojiLayout.Context) = service.withMessage {
            service.render("\ue00f Silver", glyphs, layout = EmojiLayout(context)).also {
                assertTrue(it.startsWith(remote.values.single()))
                events.add("send")
            }
        }
        val embed = send(EmojiLayout.Context.EMBED)
        val chat = send(EmojiLayout.Context.CHAT)
        assertNotEquals(embed, chat)
        assertEquals(listOf("upload", "send", "delete", "upload", "send"), events)
        val restarted = service()
        assertEquals(chat, restarted.withMessage {
            restarted.render("\ue00f Silver", glyphs, layout = EmojiLayout(EmojiLayout.Context.CHAT))
        })
        assertEquals(2, uploads)
        assertEquals(2L, store.load().emojis.values.single().count)
    }

    @Test
    fun `invalid automatic settings fall back per context once and ignore legacy padding`() = runBlocking {
        val glyphs = glyphs()
        for (context in EmojiLayout.Context.entries) {
            val warnings = mutableListOf<String>()
            val expected = Base64.getDecoder().decode(ResourcePackGlyphs.layout(glyphs.getValue(0xe00f), EmojiLayout(context)))
            var uploads = 0
            val service = DiscordPackEmojis({ emptyMap() }, { name, png ->
                assertTrue(png.contentEquals(expected))
                uploads++
                "<:$name:1>"
            }, warnings::add)
            repeat(2) {
                service.withMessage {
                    service.render("\ue00f Silver", glyphs, padding = Double.NaN,
                        layout = EmojiLayout(context, scale = Double.NaN))
                }
            }
            assertEquals(1, uploads)
            assertEquals(1, warnings.size)
        }
    }
}
