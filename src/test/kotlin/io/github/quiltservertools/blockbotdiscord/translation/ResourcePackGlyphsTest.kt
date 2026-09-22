package io.github.quiltservertools.blockbotdiscord.translation

import com.google.gson.Gson
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourcePackGlyphsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `bitmap atlas follows cross namespace references with Unicode code points and provider precedence`() {
        val pack = iconPack(directory, """{"providers":[
            {"type":"space","advances":{"\ue010":-4}},
            {"type":"reference","id":"kleispack:icons"},
            {"type":"bitmap","file":"kleispack:font/icons.png","chars":["\ue00f\ue010","\udb80\udc00A"]}
        ]}""")
        val glyphs = ResourcePackGlyphs.readPack(pack)
        assertEquals(setOf(0xe00f, 0xe010, 0xf0000), glyphs.keys)
        assertEquals("", glyphs[0xe010])
        assertEquals(0xffff0000.toInt(), decoded(glyphs.getValue(0xe00f)).getRGB(64, 64))
        assertEquals(0xff0000ff.toInt(), decoded(glyphs.getValue(0xf0000)).getRGB(64, 64))
        val cached = ResourcePackGlyphs.readCache(Gson().toJson(glyphs).reader())
        assertEquals(glyphs, cached)
    }

    @Test
    fun `blank bitmap cells are omitted and ordinary Unicode is not converted`() {
        val image = BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(2, 0, 0xffabcdef.toInt())
        val pack = iconPack(directory, """{"providers":[
            {"type":"bitmap","file":"kleispack:font/icons.png","chars":["\ue00fA"]}
        ]}""", png(image))
        assertEquals(mapOf(0xe00f to ""), ResourcePackGlyphs.readPack(pack))
        assertTrue(!ResourcePackGlyphs.isPrivateUse(0x1f525)) // Normal fire emoji.
        assertTrue(ResourcePackGlyphs.isPrivateUse(0x100001)) // Supplementary PUA.
    }

    @Test
    fun `cropped and resized icons preserve transparent padding and translucent pixels`() {
        val image = BufferedImage(6, 6, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(1, 2, 0xffff0000.toInt())
        image.setRGB(2, 2, 0x8000ff00.toInt())
        image.setRGB(4, 2, 0xff0000ff.toInt())
        val pack = iconPack(directory, """{"providers":[
            {"type":"bitmap","file":"kleispack:font/icons.png","chars":["\ue00f"]}
        ]}""", png(image))
        val icon = decoded(ResourcePackGlyphs.readPack(pack).getValue(0xe00f))
        assertEquals(128, icon.width)
        assertEquals(128, icon.height)
        assertEquals(0, icon.getRGB(0, 0).ushr(24)) // Transparent canvas padding.
        assertEquals(255, icon.getRGB(0, 64).ushr(24))
        assertEquals(128, icon.getRGB(40, 64).ushr(24)) // Source translucency.
        assertEquals(0, icon.getRGB(80, 64).ushr(24)) // Transparent hole inside the artwork.
    }

    @Test
    fun `cyclic references terminate and missing vanilla font references are tolerated`() {
        val pack = iconPack(directory, """{"providers":[
            {"type":"reference","id":"minecraft:default"},
            {"type":"reference","id":"minecraft:include/default"},
            {"type":"reference","id":"kleispack:icons"}
        ]}""")
        assertEquals(3, ResourcePackGlyphs.readPack(pack).size)
    }

    @Test
    fun `unsafe texture paths malformed fonts and invalid grid sizes are rejected`() {
        for (font in listOf(
            "not json",
            """{"providers":[{"type":"reference","id":"kleispack:../icons"}]}""",
            """{"providers":[{"type":"bitmap","file":"kleispack:../icons.png","chars":["\ue00f"]}]}""",
            """{"providers":[{"type":"bitmap","file":"kleispack:font/icons.png","chars":["\ue00fAB"]}]}""",
        )) {
            assertFailsWith<Exception> { ResourcePackGlyphs.readPack(iconPack(directory, font)) }
        }
    }

    @Test
    fun `malformed optional font does not prevent activating English translations`() {
        val pack = iconPack(directory, "bad json")
        advertise(pack)
        val warnings = mutableListOf<String>()
        val service = ResourcePackTranslations(directory, directory.resolve("server.properties"), {}, warnings::add,
            { _, destination -> Files.copy(pack, destination, REPLACE_EXISTING) })
        service.checkForUpdates()
        assertEquals("\ue00f Silver", service.translations["description"])
        assertTrue(service.glyphs.isEmpty())
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("bitmap icons"))
    }

    @Test
    fun `glyph cache survives restart and changes with pack hash without stale icons`() {
        var pack = iconPack(directory)
        advertise(pack)
        var downloads = 0
        fun service() = ResourcePackTranslations(directory, directory.resolve("server.properties"), {}, {},
            { _, destination -> downloads++; Files.copy(pack, destination, REPLACE_EXISTING) })
        val first = service()
        first.checkForUpdates()
        val restarted = service()
        restarted.checkForUpdates()
        assertEquals(1, downloads)
        assertEquals(first.glyphs, restarted.glyphs)
        assertEquals(3, first.glyphs.size)
        pack = packOf(directory, "en_us" to """{"description":"No icons"}""")
        advertise(pack)
        restarted.checkForUpdates()
        assertEquals(2, downloads)
        assertTrue(restarted.glyphs.isEmpty())
        assertEquals("No icons", restarted.translations["description"])
        assertEquals(3, first.glyphs.size)
    }

    @Test
    fun `invalid cached icons are rejected`() {
        for (cache in listOf("""{"57359":false}""", """{"65":""}""", """{"57359":"not base64"}""", "{} []")) {
            assertFailsWith<Exception> { ResourcePackGlyphs.readCache(cache.reader()) }
        }
    }

    @Test
    fun `oversized PNG dimensions are rejected before decoding pixels`() {
        val texture = iconAtlas()
        ByteBuffer.wrap(texture, 16, 4).putInt(100_000)
        assertFailsWith<IllegalArgumentException> { ResourcePackGlyphs.readPack(iconPack(directory, texture = texture)) }
    }

    @Test
    fun `failed hash verification retains the previous icons along with the translations`() {
        var pack = iconPack(directory)
        advertise(pack)
        val service = ResourcePackTranslations(directory, directory.resolve("server.properties"), {}, {},
            { _, destination -> Files.copy(pack, destination, REPLACE_EXISTING) })
        service.checkForUpdates()
        val oldIcons = service.glyphs
        val advertised = packOf(directory, "en_us" to """{"description":"Expected"}""")
        advertise(advertised)
        pack = packOf(directory, "en_us" to """{"description":"Wrong download"}""")
        service.checkForUpdates()
        assertEquals(oldIcons, service.glyphs)
        assertEquals("\ue00f Silver", service.translations["description"])
    }

    private fun advertise(pack: Path) {
        Files.writeString(directory.resolve("server.properties"),
            "resource-pack=https://example.invalid/pack.zip\nresource-pack-sha1=${sha1(pack)}\n")
    }

    private fun decoded(value: String) = ImageIO.read(Base64.getDecoder().decode(value).inputStream())
}

internal fun iconPack(
    directory: Path,
    font: String = """{"providers":[{"type":"reference","id":"kleispack:icons"}]}""",
    texture: ByteArray = iconAtlas(),
): Path {
    val path = Files.createTempFile(directory, "icon-pack-", ".zip")
    val files = mapOf(
        "assets/minecraft/font/default.json" to font.toByteArray(),
        "assets/kleispack/font/icons.json" to """{"providers":[
            {"type":"bitmap","file":"kleispack:font/icons.png","chars":["\ue00f\ue010","\udb80\udc00A"]}
        ]}""".toByteArray(),
        "assets/kleispack/textures/font/icons.png" to texture,
        "assets/kleispack/lang/en_us.json" to """{"description":"\ue00f Silver"}""".toByteArray(),
    )
    ZipOutputStream(Files.newOutputStream(path)).use { zip ->
        files.forEach { (name, data) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(data)
            zip.closeEntry()
        }
    }
    return path
}

private fun iconAtlas(): ByteArray {
    val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
    for (y in 0..3) for (x in 0..3) {
        val color = when {
            y >= 2 -> 0xff0000ff
            x >= 2 -> 0xff00ff00
            else -> 0xffff0000
        }
        image.setRGB(x, y, color.toInt())
    }
    return png(image)
}

private fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
