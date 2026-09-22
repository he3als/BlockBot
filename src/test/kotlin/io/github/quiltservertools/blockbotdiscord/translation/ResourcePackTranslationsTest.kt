package io.github.quiltservertools.blockbotdiscord.translation

import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResourcePackTranslationsTest {
    @TempDir
    lateinit var directory: Path
    private val warnings = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private val serverPropertiesFile: Path get() = directory.resolve("server.properties")

    private fun service(download: (URI, Path) -> Unit) =
        ResourcePackTranslations(directory, serverPropertiesFile, logs::add, warnings::add, download)

    private fun advertise(pack: Path, hash: String = sha1(pack)) {
        Files.writeString(serverPropertiesFile, "resource-pack=https\\://example.invalid/pack.zip\nresource-pack-sha1=$hash\n")
    }

    @Test
    fun `missing or blank resource pack settings are reported without repeated messages`() {
        val service = service { _, _ -> error("Must not download") }
        service.checkForUpdates()
        for (properties in listOf(
            "motd=No resource pack\n",
            "resource-pack=\nresource-pack-sha1=invalid\n",
            "resource-pack=https://example.invalid/pack.zip\n",
            "resource-pack=https://example.invalid/pack.zip\nresource-pack-sha1=\n",
        )) {
            Files.writeString(serverPropertiesFile, properties)
            service.checkForUpdates()
        }
        assertTrue(service.translations.isEmpty())
        assertTrue(warnings.isEmpty())
        assertTrue(logs.all { it.contains("inactive") })
        val count = logs.size
        service.checkForUpdates()
        assertEquals(count, logs.size)
    }

    private fun advertiseBetter(pack: Path, hash: String = sha1(pack)) {
        val bsp = Files.createDirectories(directory.resolve("betterserverpacks"))
        Files.writeString(bsp.resolve("config.properties"), "url=https://example.invalid/better.zip\n")
        Files.writeString(bsp.resolve("pack.sha1"), hash)
    }

    @Test
    fun `Better Server Packs has priority when both sources are configured`() {
        val vanilla = packOf(directory, "en_us" to """{"key":"Vanilla"}""")
        val better = packOf(directory, "en_us" to """{"key":"Better"}""")
        advertise(vanilla)
        advertiseBetter(better)
        val service = service { uri, destination ->
            assertEquals("/better.zip", uri.path)
            Files.copy(better, destination, REPLACE_EXISTING)
        }
        service.checkForUpdates()
        assertEquals("Better", service.translations["key"])
        assertTrue(logs.any { it.contains("Using Better Server Packs") })
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `Better Server Packs works alone and follows hash changes`() {
        var pack = packOf(directory, "en_us" to """{"key":"Old"}""")
        advertiseBetter(pack)
        val service = service { _, destination -> Files.copy(pack, destination, REPLACE_EXISTING) }
        service.checkForUpdates()
        assertEquals("Old", service.translations["key"])
        pack = packOf(directory, "en_gb" to """{"key":"New"}""")
        advertiseBetter(pack)
        service.checkForUpdates()
        assertEquals("New", service.translations["key"])
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `clearing Better Server Packs URL selects vanilla source`() {
        val vanilla = packOf(directory, "en_us" to """{"key":"Vanilla"}""")
        val better = packOf(directory, "en_us" to """{"key":"Better"}""")
        advertise(vanilla)
        advertiseBetter(better)
        val service = service { uri, destination ->
            Files.copy(if (uri.path == "/better.zip") better else vanilla, destination, REPLACE_EXISTING)
        }
        service.checkForUpdates()
        Files.writeString(directory.resolve("betterserverpacks/config.properties"), "url=\n")
        service.checkForUpdates()
        assertEquals("Vanilla", service.translations["key"])
    }

    @Test
    fun `configured Better Server Packs with missing or incorrect hash retains previous map`() {
        val pack = packOf(directory, "en_us" to """{"key":"Known good"}""")
        advertise(pack)
        var downloads = 0
        val service = service { _, destination ->
            downloads++
            Files.copy(pack, destination, REPLACE_EXISTING)
        }
        service.checkForUpdates()
        val previous = service.translations
        advertiseBetter(pack)
        Files.delete(directory.resolve("betterserverpacks/pack.sha1"))
        service.checkForUpdates()
        assertSame(previous, service.translations)
        assertEquals(1, downloads)
        advertiseBetter(pack, "0".repeat(40))
        service.checkForUpdates()
        assertSame(previous, service.translations)
        assertEquals(2, downloads)
        assertEquals(2, warnings.size)
    }

    @Test
    fun `invalid vanilla SHA-1 is rejected before downloading`() {
        val pack = packOf(directory, "en_us" to "{}")
        advertise(pack, "invalid")
        val service = service { _, _ -> error("Must not download") }
        service.checkForUpdates()
        assertTrue(service.translations.isEmpty())
        assertTrue(warnings.single().contains("Invalid resource-pack SHA-1"))
    }

    @Test
    fun `vanilla properties decode escaped UTF-8 and legacy URLs and normalize hashes`() {
        val pack = packOf(directory, "en_us" to """{"key":"Valid"}""")
        val expected = "https://example.invalid/päck.zip?signature=ok"
        for (charset in listOf(Charsets.UTF_8, Charsets.ISO_8859_1)) {
            val properties = "resource-pack=https\\://example.invalid/päck.zip?signature\\=ok\nresource-pack-sha1=${sha1(pack).uppercase()}\n"
            Files.write(serverPropertiesFile, properties.toByteArray(charset))
            var downloads = 0
            val service = service { uri, destination ->
                assertEquals(expected, uri.toString())
                downloads++
                Files.copy(pack, destination, REPLACE_EXISTING)
            }
            service.checkForUpdates()
            assertEquals(1, downloads)
            assertEquals("Valid", service.translations["key"])
            val cache = directory.resolve("blockbot-resourcepack-cache/v2/${sha1(pack)}/english-translations.json")
            assertTrue(Files.exists(cache))
            Files.delete(cache)
        }
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `cache survives restart and same hash does not download again`() {
        val pack = packOf(directory, "en_gb" to """{"key":"British fallback"}""")
        advertise(pack)
        var downloads = 0
        val service = service { _, destination ->
            downloads++
            Files.copy(pack, destination, REPLACE_EXISTING)
        }
        service.checkForUpdates()
        val logCount = logs.size
        service.checkForUpdates()
        assertEquals(logCount, logs.size)
        val restarted = service { _, _ -> error("Cache hit must not download") }
        restarted.checkForUpdates()
        assertEquals(1, downloads)
        assertEquals(mapOf("key" to "British fallback"), restarted.translations)
        assertTrue(logs.any { it.startsWith("Using cached") })
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `switching back to a cached hash replaces map without downloading`() {
        val first = packOf(directory, "en_us" to """{"key":"First"}""")
        val second = packOf(directory, "en_us" to """{"key":"Second"}""")
        var downloads = 0
        var pack = first
        val service = service { _, destination ->
            downloads++
            Files.copy(pack, destination, REPLACE_EXISTING)
        }
        for (candidate in listOf(first, second, first)) {
            pack = candidate
            advertise(pack)
            service.checkForUpdates()
        }
        assertEquals(2, downloads)
        assertEquals("First", service.translations["key"])
    }

    @Test
    fun `hash mismatch keeps old map and creates no cache or temporary files`() {
        val pack = packOf(directory, "en_us" to """{"key":"Old"}""")
        advertise(pack)
        var downloads = 0
        val service = service { _, destination ->
            downloads++
            Files.copy(pack, destination, REPLACE_EXISTING)
        }
        service.checkForUpdates()
        val old = service.translations
        val badHash = "0".repeat(40)
        advertise(pack, badHash)
        service.checkForUpdates()
        service.checkForUpdates()
        assertSame(old, service.translations)
        assertEquals(2, downloads)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("SHA-1 does not match"))
        assertFalse(Files.exists(directory.resolve("blockbot-resourcepack-cache/v2/$badHash/english-translations.json")))
        assertNoTemporaryFiles()
    }

    @Test
    fun `failed download is retried after backoff and does not log the URL`() {
        val pack = packOf(directory, "en_us" to """{"key":"Recovered"}""")
        advertise(pack)
        var now = 0L
        var downloads = 0
        val service = ResourcePackTranslations(directory, serverPropertiesFile, logs::add, warnings::add, { _, destination ->
            if (++downloads == 1) throw IOException("https://secret.invalid/?token=secret")
            Files.copy(pack, destination, REPLACE_EXISTING)
        }, { now })
        service.checkForUpdates()
        service.checkForUpdates()
        assertEquals(1, downloads)
        now += TimeUnit.MINUTES.toNanos(5)
        service.checkForUpdates()
        assertEquals(2, downloads)
        assertEquals("Recovered", service.translations["key"])
        assertFalse(warnings.single().contains("secret"))
        assertNoTemporaryFiles()
    }

    @Test
    fun `malformed new pack retains previous translations`() {
        var pack = packOf(directory, "en_us" to """{"key":"Old"}""")
        advertise(pack)
        val service = service { _, destination -> Files.copy(pack, destination, REPLACE_EXISTING) }
        service.checkForUpdates()
        val old = service.translations
        pack = packOf(directory, "en_us" to """{"key":"New"}""", "en_gb" to "{")
        advertise(pack)
        service.checkForUpdates()
        assertSame(old, service.translations)
        assertFalse(Files.exists(directory.resolve("blockbot-resourcepack-cache/v2/${sha1(pack)}/english-translations.json")))
        assertEquals(1, warnings.size)
        assertNoTemporaryFiles()
    }

    @Test
    fun `new hash atomically replaces map while readers retain the old snapshot`() {
        var pack = packOf(directory, "en_us" to """{"key":"Old","removed":"Old only"}""")
        advertise(pack)
        val downloading = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var blockDownload = false
        val service = service { _, destination ->
            if (blockDownload) {
                downloading.countDown()
                check(finish.await(10, TimeUnit.SECONDS))
            }
            Files.copy(pack, destination, REPLACE_EXISTING)
        }
        service.checkForUpdates()
        val old = service.translations
        pack = packOf(directory, "en_ca" to """{"key":"New","added":"New only"}""")
        advertise(pack)
        blockDownload = true
        val worker = Executors.newSingleThreadExecutor()
        try {
            val update = worker.submit(service::checkForUpdates)
            assertTrue(downloading.await(10, TimeUnit.SECONDS))
            assertSame(old, service.translations)
            finish.countDown()
            update.get(10, TimeUnit.SECONDS)
            assertNotSame(old, service.translations)
            assertEquals(mapOf("key" to "New", "added" to "New only"), service.translations)
            assertEquals(mapOf("key" to "Old", "removed" to "Old only"), old)
        } finally {
            finish.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun `hash changed during download does not activate an obsolete pack`() {
        val first = packOf(directory, "en_us" to """{"key":"First"}""")
        val second = packOf(directory, "en_us" to """{"key":"Second"}""")
        advertise(first)
        var pack = first
        val service = service { _, destination ->
            Files.copy(pack, destination, REPLACE_EXISTING)
            advertise(second)
        }
        service.checkForUpdates()
        assertTrue(service.translations.isEmpty())
        pack = second
        service.checkForUpdates()
        assertEquals("Second", service.translations["key"])
    }

    @Test
    fun `corrupt cache is replaced from a verified download`() {
        val pack = packOf(directory, "en_us" to """{"key":"Valid"}""")
        advertise(pack)
        val cache = directory.resolve("blockbot-resourcepack-cache/v2/${sha1(pack)}/english-translations.json")
        Files.createDirectories(cache.parent)
        Files.writeString(cache, """{"key":false}""")
        var downloads = 0
        val service = service { _, destination ->
            downloads++
            Files.copy(pack, destination, REPLACE_EXISTING)
        }
        service.checkForUpdates()
        assertEquals(1, downloads)
        assertEquals("Valid", service.translations["key"])
    }

    @Test
    fun `keeps at most three cached hashes`() {
        var pack = packOf(directory, "en_us" to "{}")
        val service = service { _, destination -> Files.copy(pack, destination, REPLACE_EXISTING) }
        repeat(5) {
            pack = packOf(directory, "en_us" to """{"version":"$it"}""")
            advertise(pack)
            service.checkForUpdates()
        }
        Files.list(directory.resolve("blockbot-resourcepack-cache/v2")).use { assertEquals(3L, it.count()) }
        assertEquals("4", service.translations["version"])
        assertTrue(warnings.isEmpty())
    }

    private fun assertNoTemporaryFiles() {
        Files.walk(directory).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().endsWith(".tmp") }) }
    }
}

internal fun sha1(path: Path): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(path)))
