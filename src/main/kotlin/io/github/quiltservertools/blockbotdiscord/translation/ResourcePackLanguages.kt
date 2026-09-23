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

internal object ResourcePackLanguages {
    const val DEFAULT_LANGUAGE = "en_us"
    const val MAX_FILE_BYTES = 4 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    private const val MAX_KEYS = 200_000
    private const val MAX_LANGUAGES = 1024
    private val languageCode = Regex("[a-z][a-z0-9_.-]{1,63}")
    private val languagePath = Regex("assets/([a-z0-9_.-]+)/lang/([a-z][a-z0-9_.-]{1,63})\\.json")
    private val englishFallbacks = listOf("en_us", "en_gb", "en_ca", "en_au", "en_nz")

    fun normalizeLanguage(value: String): String? =
        value.trim().lowercase().takeIf(languageCode::matches)

    fun select(languages: Map<String, Map<String, String>>, language: String): Map<String, String> {
        val otherEnglish = languages.keys.filter { it.startsWith("en_") && it !in englishFallbacks }.sorted()
        val priority = (listOf(language) + englishFallbacks + otherEnglish).distinct()
        val translations = mutableMapOf<String, String>()
        for (locale in priority) {
            languages[locale]?.forEach { (key, value) -> translations.putIfAbsent(key, value) }
        }
        return java.util.Map.copyOf(translations)
    }

    fun readPack(path: Path): Map<String, Map<String, String>> = ZipFile(path.toFile()).use { zip ->
        if (zip.size() > 100_000) throw PackTranslationException("Too many ZIP entries")
        val entries = zip.entries().asSequence().filter { entry ->
            val match = languagePath.matchEntire(entry.name)
            !entry.isDirectory && match != null && match.groupValues[1] !in setOf(".", "..")
        }.toList()
        if (entries.size > MAX_LANGUAGES) throw PackTranslationException("Too many language files")
        if (entries.map { it.name }.distinct().size != entries.size) {
            throw PackTranslationException("Duplicate language ZIP paths")
        }

        val languages = mutableMapOf<String, Map<String, String>>()
        var totalBytes = 0
        var totalKeys = 0
        val byLocale = entries.groupBy { languagePath.matchEntire(it.name)!!.groupValues[2] }
        for ((locale, files) in byLocale.toSortedMap()) {
            val translations = mutableMapOf<String, String>()
            // Later resource paths win within a language, regardless of ZIP order.
            for (entry in files.sortedBy { it.name }) {
                if (entry.size > MAX_FILE_BYTES) throw PackTranslationException("Language file is too large")
                val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_FILE_BYTES + 1) }
                totalBytes += bytes.size
                if (bytes.size > MAX_FILE_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                    throw PackTranslationException("Language data exceeds size limit")
                }
                if (bytes.size.toLong() != entry.size || CRC32().apply { update(bytes) }.value != entry.crc) {
                    throw PackTranslationException("Corrupt language ZIP entry")
                }
                translations.putAll(readJson(bytes.inputStream().reader(Charsets.UTF_8)))
                checkSize(totalKeys + translations.size)
            }
            totalKeys += translations.size
            languages[locale] = java.util.Map.copyOf(translations)
        }
        java.util.Map.copyOf(languages)
    }

    fun readCache(reader: Reader): Map<String, Map<String, String>> = JsonReader(reader).use { json ->
        json.strictness = Strictness.STRICT
        val languages = mutableMapOf<String, Map<String, String>>()
        var totalKeys = 0
        json.beginObject()
        while (json.hasNext()) {
            val language = json.nextName()
            if (!languageCode.matches(language) || language in languages || languages.size >= MAX_LANGUAGES) {
                throw PackTranslationException("Invalid cached language")
            }
            val translations = readTranslations(json, cache = true)
            totalKeys += translations.size
            checkSize(totalKeys)
            languages[language] = java.util.Map.copyOf(translations)
        }
        json.endObject()
        checkEnd(json)
        java.util.Map.copyOf(languages)
    }

    fun readJson(reader: Reader): Map<String, String> = JsonReader(reader).use { json ->
        json.strictness = Strictness.STRICT
        readTranslations(json, cache = false).also { checkEnd(json) }
    }

    private fun readTranslations(json: JsonReader, cache: Boolean): Map<String, String> {
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
                checkSize(result.size)
            } else {
                if (cache) throw PackTranslationException("Invalid cached translation value")
                json.skipValue()
            }
        }
        json.endObject()
        return result
    }

    private fun checkEnd(json: JsonReader) {
        if (json.peek() != JsonToken.END_DOCUMENT) throw PackTranslationException("Trailing language JSON data")
    }

    private fun checkSize(count: Int) {
        if (count > MAX_KEYS) throw PackTranslationException("Too many translation keys")
    }
}
