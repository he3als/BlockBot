package io.github.quiltservertools.blockbotdiscord.translation

import com.google.gson.Gson
import java.net.URI
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
    private val configDirectory: Path,
    private val info: (String) -> Unit,
    private val warn: (String) -> Unit,
    private val download: (URI, Path) -> Unit = ResourcePackDownload::download,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private data class Source(val hash: String, val url: URI)
    private data class Snapshot(val hash: String?, val translations: Map<String, String>)

    @Volatile
    private var active = Snapshot(null, emptyMap())
    val translations: Map<String, String> get() = active.translations

    private val packDirectory = configDirectory.resolve("betterserverpacks")
    private val cacheDirectory = configDirectory.resolve("blockbot-resourcepack-cache/v1")
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
            val values = load(source)
            // /pack reload may have run again while this pack was downloading.
            if (Thread.currentThread().isInterrupted || readSource() != source) return
            active = Snapshot(source.hash, java.util.Map.copyOf(values))
            failedSource = null
            retryAfter = Long.MIN_VALUE
            info("Loaded ${values.size} translations from server resource pack")
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
        val propertiesFile = packDirectory.resolve("config.properties")
        val hashFile = packDirectory.resolve("pack.sha1")
        if (!Files.exists(propertiesFile) || !Files.exists(hashFile)) return null
        val properties = Properties().apply {
            Files.newInputStream(propertiesFile).use { input ->
                val bytes = input.readNBytes(64 * 1024 + 1)
                if (bytes.size > 64 * 1024) throw PackTranslationException("Better Server Packs config is too large")
                load(bytes.inputStream())
            }
        }
        val url = properties.getProperty("url")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val hash = Files.newInputStream(hashFile).use {
            val bytes = it.readNBytes(129)
            if (bytes.size > 128) throw PackTranslationException("Invalid Better Server Packs SHA-1")
            bytes.toString(Charsets.US_ASCII).trim()
        }
        if (!HASH.matches(hash)) throw PackTranslationException("Invalid Better Server Packs SHA-1")
        val uri = URI(url)
        ResourcePackDownload.validateUrl(uri)
        return Source(hash, uri)
    }

    private fun load(source: Source): Map<String, String> {
        val cacheFile = cacheDirectory.resolve(source.hash).resolve(CACHE_FILE)
        if (Files.exists(cacheFile)) {
            try {
                if (Files.size(cacheFile) > MAX_CACHE_BYTES) throw PackTranslationException("Translation cache is too large")
                val cached = Files.newBufferedReader(cacheFile).use { EnglishTranslations.readJson(it, cache = true) }
                try {
                    Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(System.currentTimeMillis()))
                } catch (_: Exception) {
                    // Cache bookkeeping must not force a download of a valid map.
                    warn("Could not update resource-pack translation cache timestamp")
                }
                info("Using cached resource-pack translations for ${source.hash}")
                return cached
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
            Files.createDirectories(cacheFile.parent)
            val temporary = Files.createTempFile(cacheFile.parent, "english-", ".tmp")
            try {
                Files.newBufferedWriter(temporary).use { Gson().toJson(values, it) }
                if (Files.size(temporary) > MAX_CACHE_BYTES) throw PackTranslationException("Translation cache is too large")
                Files.move(temporary, cacheFile, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
            return values
        } finally {
            Files.deleteIfExists(pack)
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
        const val MAX_CACHE_BYTES = 64L * 1024 * 1024
    }
}
