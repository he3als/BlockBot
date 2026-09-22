package io.github.quiltservertools.blockbotdiscord.translation

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.Reader
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipFile

internal class PackTranslationException(message: String) : IOException(message)

internal object EnglishTranslations {
    const val MAX_FILE_BYTES = 4 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    private const val MAX_KEYS = 200_000
    private val languagePath = Regex("assets/([a-z0-9_.-]+)/lang/(en_[a-z0-9_.-]+)\\.json")
    private val preferredLocales = listOf("en_us", "en_gb", "en_ca", "en_au", "en_nz")

    fun readPack(path: Path): Map<String, String> = ZipFile(path.toFile()).use { zip ->
        if (zip.size() > 100_000) throw PackTranslationException("Too many ZIP entries")
        val entries = zip.entries().asSequence().filter { entry ->
            val match = languagePath.matchEntire(entry.name)
            !entry.isDirectory && match != null && match.groupValues[1] !in setOf(".", "..")
        }.toList()
        if (entries.size > 1024) throw PackTranslationException("Too many English language files")
        if (entries.map { it.name }.distinct().size != entries.size) {
            throw PackTranslationException("Duplicate English language ZIP paths")
        }

        // Locale priority comes before namespace priority. Within a locale, later
        // lexicographical resource paths win, independent of ZIP insertion order.
        val byLocale = entries.groupBy { languagePath.matchEntire(it.name)!!.groupValues[2] }
        val locales = preferredLocales + (byLocale.keys - preferredLocales.toSet()).sorted()
        val translations = mutableMapOf<String, String>()
        var totalBytes = 0
        for (locale in locales) {
            val localeTranslations = mutableMapOf<String, String>()
            for (entry in byLocale[locale].orEmpty().sortedBy { it.name }) {
                if (entry.size > MAX_FILE_BYTES) throw PackTranslationException("English language file is too large")
                val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_FILE_BYTES + 1) }
                totalBytes += bytes.size
                if (bytes.size > MAX_FILE_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                    throw PackTranslationException("English language data exceeds size limit")
                }
                if (bytes.size.toLong() != entry.size || CRC32().apply { update(bytes) }.value != entry.crc) {
                    throw PackTranslationException("Corrupt English language ZIP entry")
                }
                localeTranslations.putAll(readJson(bytes.inputStream().reader(Charsets.UTF_8)))
                checkSize(localeTranslations)
            }
            localeTranslations.forEach { (key, value) -> translations.putIfAbsent(key, value) }
            checkSize(translations)
        }
        translations
    }

    fun readJson(reader: Reader, cache: Boolean = false): Map<String, String> = JsonReader(reader).use { json ->
        json.strictness = Strictness.STRICT
        if (json.peek() != JsonToken.BEGIN_OBJECT) throw PackTranslationException("Expected a language JSON object")
        val result = mutableMapOf<String, String>()
        json.beginObject()
        while (json.hasNext()) {
            val key = json.nextName()
            if (key.length > 1024) throw PackTranslationException("Translation key is too long")
            if (json.peek() == JsonToken.STRING) {
                val value = json.nextString()
                if (value.length > 32_768) throw PackTranslationException("Translation value is too long")
                result[key] = value
                checkSize(result)
            } else {
                if (cache) throw PackTranslationException("Invalid cached translation value")
                json.skipValue()
            }
        }
        json.endObject()
        if (json.peek() != JsonToken.END_DOCUMENT) throw PackTranslationException("Trailing language JSON data")
        result
    }

    private fun checkSize(translations: Map<String, String>) {
        if (translations.size > MAX_KEYS) throw PackTranslationException("Too many translation keys")
    }
}
