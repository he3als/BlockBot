package io.github.quiltservertools.blockbotdiscord.translation

import com.google.gson.Gson
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class ResourcePackLanguagesTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `accepts only string values`() {
        assertEquals(
            mapOf("title" to "Light a Campfire", "empty" to ""),
            ResourcePackLanguages.readJson(
                """{"title":"Light a Campfire","number":7,"bool":true,"null":null,"array":[],"object":{},"empty":""}""".reader()
            )
        )
    }

    @Test
    fun `malformed files reject the candidate instead of publishing a partial map`() {
        for (json in listOf("{", "[]", "{\"key\":\"ok\",}", "{key:'unquoted'}", "{} {}")) {
            assertFails { ResourcePackLanguages.readJson(json.reader()) }
        }
        assertFails {
            ResourcePackLanguages.readPack(packOf(directory, "en_us" to """{"title":"Good"}""", "en_gb" to "{"))
        }
    }

    @Test
    fun `English locales fill missing keys in priority order across all namespaces`() {
        val pack = zipOf(
            directory,
            "assets/a/lang/en_ud.json" to """{"us":"upside down","unusual":"Last resort"}""",
            "assets/a/lang/en_zz.json" to """{"alphabetical":"ZZ"}""",
            "assets/a/lang/en_ab.json" to """{"alphabetical":"AB","arbitrary":"Discovered"}""",
            "assets/a/lang/en_nz.json" to """{"nz":"NZ","au":"NZ","ca":"NZ"}""",
            "assets/a/lang/en_au.json" to """{"au":"AU","ca":"AU","gb":"AU"}""",
            "assets/a/lang/en_ca.json" to """{"ca":"CA","gb":"CA","us":"CA"}""",
            "assets/a/lang/en_gb.json" to """{"gb":"GB","us":"GB","crossNamespace":"GB"}""",
            "assets/z/lang/en_us.json" to """{"us":"US","crossNamespace":"US","collision":"Z"}""",
            "assets/a/lang/en_us.json" to """{"collision":"A"}""",
            "assets/minecraft/lang/en_us.json" to """{"vanillaOverride":"Pack"}""",
            "assets/a/lang/fr_fr.json" to """{"french":"Ignored"}""",
            "assets/../lang/en_us.json" to """{"traversal":"Ignored"}""",
            "../assets/a/lang/en_us.json" to """{"traversal":"Ignored"}""",
        )
        assertEquals(
            mapOf(
                "us" to "US", "gb" to "GB", "ca" to "CA", "au" to "AU", "nz" to "NZ",
                "crossNamespace" to "US", "collision" to "Z", "vanillaOverride" to "Pack",
                "alphabetical" to "AB", "arbitrary" to "Discovered", "unusual" to "Last resort",
            ),
            ResourcePackLanguages.select(ResourcePackLanguages.readPack(pack), "en_us")
        )
    }

    @Test
    fun `preferred language wins before English fallbacks and other languages stay unused`() {
        val pack = packOf(directory,
            "en_us" to """{"title":"Campfire","english":"US"}""",
            "en_gb" to """{"title":"GB","english":"GB","british":"GB"}""",
            "fr_fr" to """{"title":"Feu de camp","preferredOnly":"French"}""",
            "de_de" to """{"unrelated":"German"}""",
            "en_zz-extra" to """{"rare":"Discovered"}""",
        )
        val languages = ResourcePackLanguages.readPack(pack)
        assertEquals(
            mapOf("title" to "Feu de camp", "preferredOnly" to "French", "english" to "US", "british" to "GB", "rare" to "Discovered"),
            ResourcePackLanguages.select(languages, "fr_fr")
        )
        assertEquals("GB", ResourcePackLanguages.select(languages, "en_gb")["title"])
        assertEquals("Campfire", ResourcePackLanguages.select(languages, "es_es")["title"])
    }

    @Test
    fun `cached languages round trip and malformed cache values are rejected`() {
        val languages = mapOf("fr_fr" to mapOf("title" to "Feu de camp"), "en_us" to mapOf("title" to "Campfire"))
        assertEquals(languages, ResourcePackLanguages.readCache(Gson().toJson(languages).reader()))
        for (json in listOf(
            """{"fr_fr":{"title":false}}""", """{"en_us":"flat"}""", """{"../escape":{}}""",
            """{"en_us":{},"en_us":{}}""", "{} []",
        )) assertFails { ResourcePackLanguages.readCache(json.reader()) }
    }

    @Test
    fun `oversized compressed language files are rejected`() {
        val pack = packOf(directory, "en_us" to " ".repeat(ResourcePackLanguages.MAX_FILE_BYTES + 1))
        assertFailsWith<PackTranslationException> { ResourcePackLanguages.readPack(pack) }
    }

    @Test
    fun `corrupt ZIP entry is rejected even when its JSON still parses`() {
        val path = directory.resolve("corrupt.zip")
        val json = """{"key":"Original"}""".toByteArray()
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            val entry = ZipEntry("assets/test/lang/en_us.json").apply {
                method = ZipEntry.STORED
                size = json.size.toLong()
                crc = CRC32().apply { update(json) }.value
            }
            zip.putNextEntry(entry)
            zip.write(json)
            zip.closeEntry()
        }
        val bytes = Files.readAllBytes(path)
        val offset = bytes.indices.first { bytes[it] == '{'.code.toByte() }
        bytes[offset + 8] = 'X'.code.toByte()
        Files.write(path, bytes)
        assertFailsWith<PackTranslationException> { ResourcePackLanguages.readPack(path) }
    }
}

internal fun packOf(directory: Path, vararg languages: Pair<String, String>): Path = zipOf(
    directory, *languages.map { (locale, contents) -> "assets/kleispack/lang/$locale.json" to contents }.toTypedArray()
)

internal fun zipOf(directory: Path, vararg entries: Pair<String, String>): Path {
    val path = Files.createTempFile(directory, "test-pack-", ".zip")
    ZipOutputStream(Files.newOutputStream(path)).use { zip ->
        entries.forEach { (name, contents) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(contents.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    return path
}
