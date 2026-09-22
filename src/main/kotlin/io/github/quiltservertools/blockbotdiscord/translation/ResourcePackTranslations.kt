package io.github.quiltservertools.blockbotdiscord.translation

import com.google.gson.Gson
import java.net.URI
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal class ResourcePackTranslations(
    configDirectory: Path,
    private val serverPropertiesFile: Path,
    private val info: (String) -> Unit,
    private val warn: (String) -> Unit,
    private val download: (URI, Path) -> Unit = ResourcePackDownload::download,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private data class Source(val hash: String, val url: URI, val origin: String)
    private data class Snapshot(
        val hash: String?,
        val translations: Map<String, String>,
        val glyphs: Map<Int, String>,
    )

    @Volatile
    private var active = Snapshot(null, emptyMap(), emptyMap())
    val translations: Map<String, String> get() = active.translations
    val glyphs: Map<Int, String> get() = active.glyphs

    private val betterPacksDirectory = configDirectory.resolve("betterserverpacks")
    private var sourceStatus: String? = null
    private val cacheDirectory = configDirectory.resolve("blockbot-resourcepack-cache/v2")
    private var executor: ScheduledExecutorService? = null
    private var observedHash: String? = null
    private var failedSource: Source? = null
    private var retryAfter = Long.MIN_VALUE

    fun start() {
        check(executor == null)
        executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "BlockBot resource-pack translations").apply { isDaemon = true }
        }.also { it.scheduleWithFixedDelay(::checkForUpdates, 0, 30, TimeUnit.SECONDS) }
    }

    override fun close() {
        executor?.shutdownNow()
    }

    // Only the background worker calls this in production. Exposed internally for
    // deterministic tests without timers, a Minecraft server, or an external host.
    internal fun checkForUpdates() {
        var source: Source? = null
        try {
            source = readSource() ?: return
            if (source.hash == active.hash) return
            if (source == failedSource && nanoTime() < retryAfter) return
            if (observedHash != source.hash) {
                info("Server resource-pack hash changed: ${observedHash ?: "none"} -> ${source.hash}")
                observedHash = source.hash
            }
            val candidate = load(source)
            // The selected source may have changed again during the download.
            if (Thread.currentThread().isInterrupted || readSource() != source) return
            active = candidate
            failedSource = null
            retryAfter = Long.MIN_VALUE
            info("Loaded ${candidate.translations.size} translations from server resource pack")
            info("Loaded ${candidate.glyphs.count { it.value.isNotEmpty() }} resource-pack icons for Discord")
            if (candidate.translations.isEmpty()) warn("No English translations found in server resource pack")
            pruneCache()
        } catch (exception: Exception) {
            if (Thread.currentThread().isInterrupted) return
            if (source != failedSource || nanoTime() >= retryAfter) {
                // Network/parser exception messages may contain signed URLs or
                // pack contents. Only our own controlled error messages are safe.
                val reason = if (exception is PackTranslationException) exception.message else exception.javaClass.simpleName
                warn("Failed to update resource-pack translations; retaining previous translations ($reason)")
                retryAfter = nanoTime() + TimeUnit.MINUTES.toNanos(5)
            }
            failedSource = source
        }
    }

    private fun readSource(): Source? {
        val betterProperties = betterPacksDirectory.resolve("config.properties")
        if (Files.exists(betterProperties)) {
            val url = readProperties(betterProperties).getProperty("url")?.trim().orEmpty()
            if (url.isNotEmpty()) {
                reportSource("Using Better Server Packs for resource-pack translations")
                val hashFile = betterPacksDirectory.resolve("pack.sha1")
                if (!Files.exists(hashFile)) {
                    throw PackTranslationException("Better Server Packs pack.sha1 is missing; run /pack reload")
                }
                val bytes = Files.newInputStream(hashFile).use { it.readNBytes(129) }
                if (bytes.size > 128) throw PackTranslationException("Better Server Packs pack.sha1 is too large")
                return source(url, bytes.toString(Charsets.US_ASCII).trim(), "Better Server Packs")
            }
        }
        val properties = if (Files.exists(serverPropertiesFile)) readProperties(serverPropertiesFile) else Properties()
        val url = properties.getProperty("resource-pack")?.trim().orEmpty()
        val hash = properties.getProperty("resource-pack-sha1")?.trim().orEmpty()
        if (url.isEmpty() || hash.isEmpty()) {
            val reason = if (url.isEmpty()) "no resource-pack URL configured" else "resource-pack-sha1 is missing"
            reportSource("Resource-pack translations inactive: $reason; retaining existing translations")
            return null
        }
        reportSource("Using server.properties for resource-pack translations")
        return source(url, hash, "server.properties")
    }

    private fun source(url: String, hash: String, origin: String): Source {
        val normalizedHash = hash.lowercase()
        if (!HASH.matches(normalizedHash)) throw PackTranslationException("Invalid resource-pack SHA-1 in $origin")
        val uri = URI(url)
        ResourcePackDownload.validateUrl(uri)
        return Source(normalizedHash, uri, origin)
    }

    private fun reportSource(status: String) {
        if (status != sourceStatus) {
            info(status)
            sourceStatus = status
        }
    }

    private fun readProperties(path: Path): Properties {
        val bytes = Files.newInputStream(path).use { it.readNBytes(64 * 1024 + 1) }
        if (bytes.size > 64 * 1024) throw PackTranslationException("Resource-pack properties file is too large")
        // Match vanilla's UTF-8 decoding with legacy ISO-8859-1 fallback.
        val contents = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            bytes.toString(Charsets.ISO_8859_1)
        }
        return Properties().apply { load(contents.reader()) }
    }

    private fun load(source: Source): Snapshot {
        val cacheFile = cacheDirectory.resolve(source.hash).resolve(CACHE_FILE)
        val glyphFile = cacheFile.resolveSibling(GLYPH_FILE)
        if (Files.exists(cacheFile)) {
            try {
                if (Files.size(cacheFile) > MAX_CACHE_BYTES) throw PackTranslationException("Translation cache is too large")
                val cached = Files.newBufferedReader(cacheFile).use { EnglishTranslations.readJson(it, cache = true) }
                if (Files.size(glyphFile) > MAX_CACHE_BYTES) throw PackTranslationException("Icon cache is too large")
                val glyphs = Files.newBufferedReader(glyphFile).use(ResourcePackGlyphs::readCache)
                try {
                    Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()))
                } catch (_: Exception) {
                    // Cache bookkeeping must not force a download of a valid map.
                    warn("Could not update resource-pack translation cache timestamp")
                }
                info("Using cached resource-pack translations for ${source.hash}")
                return snapshot(source.hash, cached, glyphs)
            } catch (_: Exception) {
                warn("Invalid resource-pack translation cache for ${source.hash}; downloading again")
            }
        }
        Files.createDirectories(cacheDirectory)
        val pack = Files.createTempFile(cacheDirectory, "pack-", ".tmp")
        try {
            info("Downloading server resource pack for ${source.hash}")
            download(source.url, pack)
            val digest = MessageDigest.getInstance("SHA-1")
            Files.newInputStream(pack).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            if (HexFormat.of().formatHex(digest.digest()) != source.hash) {
                throw PackTranslationException("Downloaded resource-pack SHA-1 does not match")
            }
            val values = EnglishTranslations.readPack(pack)
            val glyphs = try {
                ResourcePackGlyphs.readPack(pack)
            } catch (_: Exception) {
                // Optional client font data must not disable working translations.
                warn("Could not read resource-pack bitmap icons; using text without custom glyphs")
                emptyMap()
            }
            Files.createDirectories(cacheFile.parent)
            writeCache(glyphFile, glyphs)
            writeCache(cacheFile, values)
            return snapshot(source.hash, values, glyphs)
        } finally {
            Files.deleteIfExists(pack)
        }
    }

    private fun snapshot(hash: String, values: Map<String, String>, glyphs: Map<Int, String>) =
        Snapshot(hash, java.util.Map.copyOf(values), java.util.Map.copyOf(glyphs))

    private fun writeCache(path: Path, values: Map<*, *>) {
        val temporary = Files.createTempFile(path.parent, "cache-", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { Gson().toJson(values, it) }
            if (Files.size(temporary) > MAX_CACHE_BYTES) throw PackTranslationException("Resource-pack cache is too large")
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun pruneCache() {
        try {
            Files.list(cacheDirectory).use { paths ->
                paths.filter { HASH.matches(it.fileName.toString()) && it.fileName.toString() != active.hash }
                    .filter { Files.isRegularFile(it.resolve(CACHE_FILE)) }
                    .sorted(compareByDescending { Files.getLastModifiedTime(it.resolve(CACHE_FILE)) })
                    .skip(2)
                    .forEach {
                        Files.deleteIfExists(it.resolve(CACHE_FILE))
                        Files.deleteIfExists(it.resolve(GLYPH_FILE))
                        Files.deleteIfExists(it)
                    }
            }
        } catch (_: Exception) {
            warn("Could not remove obsolete resource-pack translation cache entries")
        }
    }

    private companion object {
        val HASH = Regex("[0-9a-f]{40}")
        const val CACHE_FILE = "english-translations.json"
        const val GLYPH_FILE = "font-glyphs.json"
        const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    }
}
