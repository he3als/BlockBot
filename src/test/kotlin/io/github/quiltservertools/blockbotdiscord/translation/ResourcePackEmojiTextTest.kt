package io.github.quiltservertools.blockbotdiscord.translation

import com.mojang.serialization.JsonOps
import dev.vankka.mcdiscordreserializer.discord.DiscordSerializer
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.minecraft.ChatFormatting
import net.minecraft.core.RegistryAccess
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import org.junit.jupiter.api.BeforeAll
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResourcePackEmojiTextTest {
    private val original = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB).let { image ->
        image.setRGB(64, 64, 0x80ffffff.toInt())
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        Base64.getEncoder().encodeToString(bytes)
    }
    private val glyphs = mapOf(0xe00f to original)
    private val translations = mapOf(
        "adv.kleispack.silver" to "The Warding Metal",
        "adv.kleispack.silver.desc" to "\ue00f Silver, a once-common Apotropaic Metal",
        "coloured.args" to "%2\$s / %1\$s / %2\$s / %%",
    )
    private fun text(coloured: Boolean = true, images: Map<Int, String> = glyphs) =
        ResourcePackEmojiText(translations, images, coloured) { error(it) }

    @Test
    fun `literal glyphs inherit parent text colours and children can override them`() {
        val text = text()
        val source = Component.empty().withStyle { it.withColor(0x123456) }
            .append(Component.literal("\ue00f"))
            .append(Component.literal("\ue00f").withStyle { it.withColor(0xabcdef) })
            .append(Component.literal("\ue00f"))
        val points = text.prepare(source).string.codePoints().toArray()
        assertNotEquals(points[0], points[1])
        assertEquals(points[0], points[2])
        assertEquals(0x123456, pixel(text, points[0]) and 0xffffff)
        assertEquals(0xabcdef, pixel(text, points[1]) and 0xffffff)
        assertEquals(128, pixel(text, points[0]).ushr(24))
        assertEquals("\ue00f\ue00f\ue00f", source.string)
    }

    @Test
    fun `translated advancement descriptions match the component text colour`() {
        val text = text()
        val source = Component.translatable("adv.kleispack.silver.desc").withStyle(ChatFormatting.GRAY)
        val description = text.translate(source)
        assertTrue(description.endsWith(" Silver, a once-common Apotropaic Metal"))
        assertEquals(0xaaaaaa, pixel(text, description.codePointAt(0)) and 0xffffff)
        val vanilla = Component.translatable("advancements.story.mine_stone.title")
        assertEquals(vanilla.string, text.translate(vanilla))
        assertEquals("missing.key", text.translate(Component.translatable("missing.key")))
    }

    @Test
    fun `translation arguments keep independent colours indexed substitution and children once`() {
        val text = text()
        val source = Component.translatable("coloured.args",
            Component.literal("\ue00f").withStyle(ChatFormatting.RED),
            Component.literal("\ue00f").withStyle(ChatFormatting.BLUE))
            .append(Component.literal(" child"))
        val result = text.translate(source)
        val points = result.codePoints().filter(ResourcePackGlyphs::isPrivateUse).toArray()
        assertEquals(3, points.size)
        assertEquals(points[0], points[2])
        assertEquals(0x5555ff, pixel(text, points[0]) and 0xffffff)
        assertEquals(0xff5555, pixel(text, points[1]) and 0xffffff)
        assertTrue(result.endsWith(" / % child"))
    }

    @Test
    fun `nested translations inherit colours while explicit argument colours take priority`() {
        val text = text()
        val arguments = Component.translatable("coloured.args",
            Component.literal("\ue00f"),
            Component.translatable("adv.kleispack.silver.desc").withStyle(ChatFormatting.BLUE))
        val source = Component.empty().withStyle(ChatFormatting.RED).append(arguments)
        val points = text.translate(source).codePoints().filter(ResourcePackGlyphs::isPrivateUse).toArray()
        assertEquals(3, points.size)
        assertEquals(0x5555ff, pixel(text, points[0]) and 0xffffff)
        assertEquals(0xff5555, pixel(text, points[1]) and 0xffffff)
        assertEquals(points[0], points[2])
    }

    @Test
    fun `disabled tint and white or absent colours reuse the exact original artwork`() {
        val disabled = text(coloured = false)
        val source = Component.literal("\ue00f").withStyle(ChatFormatting.RED)
        assertSame(source, disabled.prepare(source))
        assertEquals(glyphs, disabled.glyphs)
        val enabled = text()
        assertEquals("\ue00f", enabled.prepare(Component.literal("\ue00f")).string)
        assertEquals("\ue00f", enabled.prepare(Component.literal("\ue00f").withStyle(ChatFormatting.WHITE)).string)
        assertEquals(glyphs, enabled.glyphs)
    }

    @Test
    fun `temporary variants cannot overwrite actual supplementary glyphs from the pack`() {
        val text = text(images = glyphs + (0x100000 to original))
        val source = Component.literal("\ue00f\udbc0\udc00").withStyle(ChatFormatting.RED)
        val prepared = text.prepare(source).string.codePoints().toArray()
        assertTrue(prepared.all { it != 0x100000 })
        assertEquals(original, text.glyphs[0x100000])
        assertEquals(0, text.prepare(Component.literal("\udbc0\udc01")).string.length) // Unknown source glyph.
    }

    @Test
    fun `coloured glyphs survive real Discord serialization without escaping emoji markup`() = runBlocking {
        val text = text()
        val source = Component.literal("\ue00f *Silver*").withStyle(ChatFormatting.RED)
        val prepared = text.prepare(source)
        val json = ComponentSerialization.CODEC.encodeStart(
            RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE), prepared).getOrThrow().toString()
        val serialized = DiscordSerializer().serialize(GsonComponentSerializer.gson().deserialize(json))
        val uploaded = mutableListOf<ByteArray>()
        val remote = mutableMapOf<String, String>()
        val emojis = DiscordPackEmojis({ remote.toMap() }, { name, png ->
            uploaded.add(png)
            "<:$name:123>".also { remote[name] = it }
        }, { error(it) })
        val sent = emojis.withMessage { emojis.render(serialized, text.glyphs) }
        assertTrue(sent.startsWith("<:bb_"))
        assertTrue(sent.endsWith(" \\*Silver\\*"))
        assertEquals(0xff5555, ImageIO.read(uploaded.single().inputStream()).getRGB(64, 64) and 0xffffff)
        assertTrue(sent.codePoints().noneMatch(ResourcePackGlyphs::isPrivateUse))
        val another = text()
        val same = another.translate(source)
        emojis.withMessage { emojis.render(same, another.glyphs) }
        assertEquals(1, uploaded.size)
        val blue = another.translate(source.copy().withStyle(ChatFormatting.BLUE))
        emojis.withMessage { emojis.render(blue, another.glyphs) }
        assertEquals(2, uploaded.size)
    }

    private fun pixel(text: ResourcePackEmojiText, codePoint: Int): Int =
        ImageIO.read(Base64.getDecoder().decode(text.glyphs.getValue(codePoint)).inputStream()).getRGB(64, 64)

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapMinecraft() = ComponentTranslationsTest.bootstrapMinecraft()
    }
}
