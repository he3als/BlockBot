package io.github.quiltservertools.blockbotdiscord.translation

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class EnglishTranslationsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `accepts only string values`() {
        assertEquals(
            mapOf("title" to "Light a Campfire", "empty" to ""),
            EnglishTranslations.readJson(
                """{"title":"Light a Campfire","number":7,"bool":true,"null":null,"array":[],"object":{},"empty":""}""".reader()
            )
        )
    }

    @Test
    fun `malformed files reject the candidate instead of publishing a partial map`() {
        for (json in listOf("{", "[]", "{\"key\":\"ok\",}", "{key:'unquoted'}", "{} {}")) {
            assertFails { EnglishTranslations.readJson(json.reader()) }
        }
        assertFails {
            EnglishTranslations.readPack(packOf(directory, "en_us" to """{"title":"Good"}""", "en_gb" to "{"))
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
            EnglishTranslations.readPack(pack)
        )
    }

    @Test
    fun `oversized compressed language files are rejected`() {
        val pack = packOf(directory, "en_us" to " ".repeat(EnglishTranslations.MAX_FILE_BYTES + 1))
        assertFailsWith<PackTranslationException> { EnglishTranslations.readPack(pack) }
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
        assertFailsWith<PackTranslationException> { EnglishTranslations.readPack(path) }
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
