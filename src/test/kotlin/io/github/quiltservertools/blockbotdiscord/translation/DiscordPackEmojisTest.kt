package io.github.quiltservertools.blockbotdiscord.translation

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.source.toml
import io.github.quiltservertools.blockbotdiscord.config.ResourcePackEmojisSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscordPackEmojisTest {
    private val artwork = byteArrayOf(1, 2, 3)
    private val glyphs = mapOf(0xe00f to Base64.getEncoder().encodeToString(artwork))
    private val mention = "<:${DiscordPackEmojis.emojiName(artwork)}:123>"

    @Test
    fun `concurrent messages create one application emoji and reuse it after restart`() = runBlocking {
        val remote = mutableMapOf<String, String>()
        var uploads = 0
        fun service() = DiscordPackEmojis({ remote.toMap() }, { name, _ ->
            uploads++
            delay(1)
            mention.also { remote[name] = it }
        }, { error(it) })
        val first = service()
        val messages = List(8) { async { first.render("\ue00f Silver \ue00f", glyphs) } }.awaitAll()
        assertTrue(messages.all { it == "$mention Silver $mention" })
        assertEquals(1, uploads)
        assertEquals("$mention Silver", service().render("\ue00f Silver", glyphs))
        assertEquals(1, uploads)
    }

    @Test
    fun `artwork changes create a new emoji without deleting old messages icons`() = runBlocking {
        val names = mutableListOf<String>()
        val service = DiscordPackEmojis({ emptyMap() }, { name, _ -> names.add(name); "<:$name:123>" }, {})
        service.render("\ue00f", glyphs)
        service.render("\ue00f", mapOf(0xe00f to Base64.getEncoder().encodeToString(byteArrayOf(4, 5, 6))))
        assertEquals(2, names.distinct().size)
    }

    @Test
    fun `missing spacing and supplementary glyphs are hidden without affecting standard emojis`() = runBlocking {
        val service = DiscordPackEmojis({ error("No network needed") }, { _, _ -> error("No upload needed") }, {})
        assertEquals("Silver 🔥 <:existing:123>", service.render("\ue00f Silver 🔥 <:existing:123>\uDB80\uDC00", emptyMap()))
        assertEquals("ab", service.render("a\ue010b", mapOf(0xe010 to "")))
        assertEquals("  unchanged 🔥  ", service.render("  unchanged 🔥  ", glyphs))
    }

    @Test
    fun `API failure preserves readable message and delays retries`() = runBlocking {
        var now = 0L
        var calls = 0
        val warnings = mutableListOf<String>()
        val service = DiscordPackEmojis({ calls++; throw IOException("secret URL") }, { _, _ -> error("No upload") }, warnings::add, { now })
        repeat(2) { assertEquals("Silver", service.render("\ue00f Silver", glyphs)) }
        assertEquals(1, calls)
        assertEquals(1, warnings.size)
        assertTrue(!warnings.single().contains("secret"))
        now = TimeUnit.MINUTES.toNanos(6)
        service.render("\ue00f Silver", glyphs)
        assertEquals(2, calls)
    }

    @Test
    fun `upload timeout sends text and cancellation still propagates`() = runBlocking<Unit> {
        val service = DiscordPackEmojis({ delay(1000); emptyMap() }, { _, _ -> error("No upload") }, {}, timeoutMillis = 10)
        assertEquals("Silver", service.render("\ue00f Silver", glyphs))
        val cancelled = DiscordPackEmojis({ throw CancellationException() }, { _, _ -> error("No upload") }, {})
        assertFailsWith<CancellationException> { cancelled.render("\ue00f", glyphs) }
    }

    @Test
    fun `used icons stay available when creation of another icon fails`() = runBlocking {
        var calls = 0
        val service = DiscordPackEmojis({ emptyMap() }, { _, _ -> if (++calls == 1) mention else throw IOException() }, {})
        assertEquals(mention, service.render("\ue00f", glyphs))
        val both = glyphs + (0xe010 to Base64.getEncoder().encodeToString(byteArrayOf(9)))
        assertEquals("$mention Silver", service.render("\ue00f Silver\ue010", both))
        assertEquals("$mention Silver", service.render("\ue00f Silver\ue010", both))
        assertEquals(2, calls)
    }

    @Test
    fun `advancement glyphs use description and remain unescaped while surrounding text is escaped`() = runBlocking {
        val service = DiscordPackEmojis({ mapOf(DiscordPackEmojis.emojiName(artwork) to mention) }, { _, _ -> error("No upload") }, {})
        val title = "Sophed has made the advancement [The Warding Metal]"
        val embed = advancementEmbed(title, "\ue00f Silver, a *once-common* Apotropaic Metal", "https://example.invalid/avatar.png") {
            service.render(it, glyphs)
        }
        assertEquals(title, embed.author!!.name)
        assertEquals("$mention Silver, a \\*once-common\\* Apotropaic Metal", embed.description)
        assertNull(embed.footer)
        val withTitleIcon = advancementEmbed("\ue00f Title", "Description", "https://example.invalid/avatar.png") {
            service.render(it, glyphs)
        }
        assertEquals("$mention Title\nDescription", withTitleIcon.description)
        assertNull(withTitleIcon.author)
    }

    @Test
    fun `ordinary advancement layout stays unchanged`() = runBlocking {
        val embed = advancementEmbed("Stone Age", "Mine stone", "https://example.invalid/avatar.png") { error("No rendering needed") }
        assertEquals("Stone Age", embed.author!!.name)
        assertEquals("Mine stone", embed.footer!!.text)
        assertNull(embed.description)
    }

    @Test
    fun `emoji expansion respects Discord limits without cutting ordinary text or emoji markup`() = runBlocking {
        val service = DiscordPackEmojis({ mapOf(DiscordPackEmojis.emojiName(artwork) to mention) }, { _, _ -> error("No upload") }, {})
        val rendered = service.render("\ue00f".repeat(100) + " Silver 🔥", glyphs)
        assertTrue(rendered.length <= 2000)
        assertTrue(rendered.endsWith(" Silver 🔥"))
        assertEquals(0, rendered.removeSuffix(" Silver 🔥").length % mention.length)
        assertTrue(service.render("\ue00f".repeat(100), glyphs, maxLength = 4096).length > 2000)
    }

    @Test
    fun `new uploads are bounded while previously uploaded icons remain usable beyond the batch`() = runBlocking {
        val icons = (0..20).associate { (0xe000 + it) to Base64.getEncoder().encodeToString(byteArrayOf(it.toByte())) }
        val text = buildString { icons.keys.forEach { appendCodePoint(it) } }
        var uploads = 0
        val service = DiscordPackEmojis({ emptyMap() }, { name, _ -> uploads++; "<:$name:123>" }, {})
        val first = service.render(text, icons)
        assertEquals(16, uploads)
        val second = service.render(text, icons)
        assertEquals(21, uploads)
        assertTrue(second.length > first.length)
    }

    @Test
    fun `emoji settings have backwards compatible defaults and support TOML overrides`() {
        val defaults = Config { addSpec(ResourcePackEmojisSpec) }
        assertEquals(true, defaults[ResourcePackEmojisSpec.enabled])
        assertEquals("bb_", defaults[ResourcePackEmojisSpec.emojiPrefix])
        val overrides = defaults.from.toml.string("""
            [ResourcePackEmojis]
            enabled = false
            prefix = "kleis_"
        """.trimIndent())
        assertEquals(false, overrides[ResourcePackEmojisSpec.enabled])
        assertEquals("kleis_", overrides[ResourcePackEmojisSpec.emojiPrefix])
    }

    @Test
    fun `disabled icons make no API calls and custom prefixes name new emoji`() = runBlocking {
        var lookups = 0
        val names = mutableListOf<String>()
        val service = DiscordPackEmojis({ lookups++; emptyMap() }, { name, _ -> names.add(name); "<:$name:123>" }, {})
        assertEquals("Silver", service.render("\ue00f Silver", glyphs, enabled = false))
        assertEquals(0, lookups)
        service.render("\ue00f", glyphs, prefix = "kleis_")
        assertTrue(names.single().startsWith("kleis_"))
        assertEquals(32, names.single().length)
        service.render("\ue00f", glyphs, prefix = "bb_")
        assertTrue(names.last().startsWith("bb_"))
        assertEquals(2, names.size)
    }

    @Test
    fun `invalid prefix warns once and uses the default without breaking relay`() = runBlocking {
        val names = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val service = DiscordPackEmojis({ emptyMap() }, { name, _ -> names.add(name); "<:$name:123>" }, warnings::add)
        repeat(2) { service.render("\ue00f", glyphs, prefix = "has spaces!") }
        assertTrue(names.single().startsWith("bb_"))
        assertEquals(1, warnings.size)
    }
}
