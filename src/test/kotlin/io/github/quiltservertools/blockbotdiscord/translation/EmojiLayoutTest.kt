package io.github.quiltservertools.blockbotdiscord.translation

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.source.toml
import io.github.quiltservertools.blockbotdiscord.config.ResourcePackEmojisSpec
import io.github.quiltservertools.blockbotdiscord.config.resourcePackEmojiLayout
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmojiLayoutTest {
    @Test
    fun `font table model agrees with separately measured Discord baselines at six sizes`() {
        // Actual DOM baseline markers, 2026-09-23; gg sans source tables give
        // ascent 1018, descent 316 and cap height 690 for unitsPerEm 1000.
        for ((font, line, baseline) in listOf(
            Triple(14.0, 18.0, 14.0), Triple(16.0, 22.0, 16.0), Triple(12.0, 16.5, 12.0),
            Triple(18.0, 24.75, 18.0), Triple(20.0, 27.5, 20.0), Triple(24.0, 33.0, 24.0),
        )) {
            val layout = EmojiLayout(EmojiLayout.Context.CHAT, fontSize = font, lineHeight = line, emojiSize = line)
            assertEquals(baseline, layout.baseline)
        }
        assertEquals(9.66, EmojiLayout(EmojiLayout.Context.EMBED).capHeight, 0.00001)
        assertEquals(11.04, EmojiLayout(EmojiLayout.Context.CHAT).capHeight, 0.00001)
        // An emoji smaller than the line box still aligns its bottom to that line.
        assertEquals(12.0, EmojiLayout(EmojiLayout.Context.CHAT, emojiSize = 18.0).baseline)
    }

    @Test
    fun `automatic layouts anchor actual artwork at the baseline in chat and embeds`() {
        val square = artwork(Rectangle(16, 24, 96, 96))
        val embed = decode(ResourcePackGlyphs.layout(square, EmojiLayout(EmojiLayout.Context.EMBED)))
        val chat = decode(ResourcePackGlyphs.layout(square, EmojiLayout(EmojiLayout.Context.CHAT)))
        assertEquals(Rectangle(29, 31, 69, 69), bounds(embed)) // Bottom 100/128 * 18 = 14.0625px.
        assertEquals(Rectangle(32, 29, 64, 64), bounds(chat)) // Bottom 93/128 * 22 = 15.984375px.
        assertEquals(0x80804020.toInt(), chat.getRGB(64, 64))
        assertEquals(0, chat.getRGB(64, 93))
    }

    @Test
    fun `short wide and tall icons preserve aspect ratio and share the same baseline`() {
        val layout = EmojiLayout(EmojiLayout.Context.EMBED)
        val wide = decode(ResourcePackGlyphs.layout(artwork(Rectangle(0, 32, 128, 64)), layout))
        assertEquals(Rectangle(0, 36, 128, 64), bounds(wide)) // Width limits height; never cropped.
        val tall = decode(ResourcePackGlyphs.layout(artwork(Rectangle(32, 0, 64, 128)), layout))
        assertEquals(Rectangle(47, 31, 34, 69), bounds(tall))
        val small = decode(ResourcePackGlyphs.layout(artwork(Rectangle(64, 64, 8, 8)), layout))
        assertEquals(Rectangle(29, 31, 69, 69), bounds(small))
    }

    @Test
    fun `scale changes height while offset changes baseline without introducing blended pixels`() {
        val square = artwork(Rectangle(0, 0, 128, 128))
        val base = EmojiLayout(EmojiLayout.Context.EMBED)
        val scaled = decode(ResourcePackGlyphs.layout(square, base.copy(scale = 1.2)))
        assertEquals(Rectangle(23, 18, 82, 82), bounds(scaled))
        val shifted = decode(ResourcePackGlyphs.layout(square, base.copy(baselineOffset = 1.0)))
        assertEquals(Rectangle(29, 38, 69, 69), bounds(shifted))
        for (image in listOf(scaled, shifted)) for (y in 0 until 128) for (x in 0 until 128) {
            assertTrue(image.getRGB(x, y) in setOf(0, 0x80804020.toInt()))
        }
        val extreme = decode(ResourcePackGlyphs.layout(square, base.copy(scale = 2.0, baselineOffset = -16.0)))
        assertEquals(Rectangle(63, 0, 1, 1), bounds(extreme))
    }

    @Test
    fun `invalid numeric profiles reject safely and empty glyphs need no decoding`() {
        val base = EmojiLayout(EmojiLayout.Context.CHAT)
        for (bad in listOf(base.copy(scale = Double.NaN), base.copy(baselineOffset = Double.POSITIVE_INFINITY),
            base.copy(fontSize = 0.0), base.copy(lineHeight = 100.0), base.copy(emojiSize = -1.0))) {
            assertTrue(!bad.isValid())
            assertFailsWith<IllegalArgumentException> { ResourcePackGlyphs.layout("", bad) }
        }
        assertEquals("", ResourcePackGlyphs.layout("", base))
    }

    @Test
    fun `config automatically selects chat and embed profiles and preserves legacy padding opt out`() {
        val config = Config { addSpec(ResourcePackEmojisSpec) }
        assertEquals(EmojiLayout(EmojiLayout.Context.CHAT), config.resourcePackEmojiLayout(false))
        assertEquals(EmojiLayout(EmojiLayout.Context.EMBED), config.resourcePackEmojiLayout(true))
        val custom = config.from.toml.string("""
            [ResourcePackEmojis]
            scale = 1.2
            baselineOffset = -0.5
        """.trimIndent())
        assertEquals(EmojiLayout(EmojiLayout.Context.CHAT, scale = 1.2, baselineOffset = -0.5), custom.resourcePackEmojiLayout(false))
        assertEquals(14.0, custom.resourcePackEmojiLayout(true)!!.fontSize)
        assertNull(config.from.toml.string("[ResourcePackEmojis]\nautomaticLayout = false").resourcePackEmojiLayout(false))
    }

    private fun artwork(rect: Rectangle): String {
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
        for (y in rect.y until rect.y + rect.height) for (x in rect.x until rect.x + rect.width) image.setRGB(x, y, 0x80804020.toInt())
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun decode(encoded: String) = ImageIO.read(Base64.getDecoder().decode(encoded).inputStream())

    private fun bounds(image: BufferedImage): Rectangle {
        val points = (0 until 128).flatMap { y -> (0 until 128).filter { x -> image.getRGB(x, y).ushr(24) != 0 }.map { x -> x to y } }
        val left = points.minOf { it.first }
        val top = points.minOf { it.second }
        return Rectangle(left, top, points.maxOf { it.first } - left + 1, points.maxOf { it.second } - top + 1)
    }
}
