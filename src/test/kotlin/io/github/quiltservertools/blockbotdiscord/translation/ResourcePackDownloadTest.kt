package io.github.quiltservertools.blockbotdiscord.translation

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResourcePackDownloadTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `only HTTP and HTTPS without embedded credentials are accepted`() {
        for (url in listOf("file:///etc/passwd", "ftp://example.org/pack", "https://user:pass@example.org/pack", "/relative")) {
            assertFailsWith<PackTranslationException> { ResourcePackDownload.validateUrl(URI(url)) }
        }
        ResourcePackDownload.validateUrl(URI("https://example.org/pack?signature=allowed"))
    }

    @Test
    fun `download follows bounded HTTP redirects and rejects errors or oversized responses`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/pack") { exchange ->
            exchange.sendResponseHeaders(200, 4)
            exchange.responseBody.use { it.write("pack".toByteArray()) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/pack")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/loop") { exchange ->
            exchange.responseHeaders.add("Location", "/loop")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/large") { exchange ->
            exchange.sendResponseHeaders(200, 257L * 1024 * 1024)
            exchange.responseBody.write(0)
            exchange.responseBody.flush()
            exchange.close()
        }
        server.createContext("/unsafe") { exchange ->
            exchange.responseHeaders.add("Location", "file:///etc/passwd")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val destination = directory.resolve("pack.tmp")
            ResourcePackDownload.download(URI("$base/redirect"), destination)
            assertEquals("pack", Files.readString(destination))
            for (path in listOf("missing", "loop", "large", "unsafe")) {
                assertFailsWith<PackTranslationException>(path) { ResourcePackDownload.download(URI("$base/$path"), destination) }
            }
        } finally {
            server.stop(0)
        }
    }
}
