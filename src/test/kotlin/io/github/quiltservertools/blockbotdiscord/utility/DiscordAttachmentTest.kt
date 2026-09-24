package io.github.quiltservertools.blockbotdiscord.utility

import com.sun.net.httpserver.HttpServer
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.cache.data.AttachmentData
import dev.kord.core.entity.Attachment
import io.github.quiltservertools.blockbotdiscord.translation.ComponentTranslationsTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.HoverEvent
import net.minecraft.world.item.component.ItemLore
import org.junit.jupiter.api.BeforeAll
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscordAttachmentTest {
    private lateinit var kord: Kord
    private lateinit var server: HttpServer
    private val base: String get() = "http://127.0.0.1:${server.address.port}"

    @BeforeTest
    fun setup() = runBlocking {
        kord = Kord("MTIzNDU2Nzg5MDEyMzQ1Njc4.test.signature") {
            httpClient = HttpClient(MockEngine {
                respond(
                    """{"url":"wss://example.invalid","shards":1,"session_start_limit":{"total":1000,"remaining":1000,"reset_after":1000,"max_concurrency":1}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            })
        }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @AfterTest
    fun cleanup() = runBlocking {
        server.stop(0)
        kord.shutdown()
    }

    @Test
    fun `PNG preview uses the original URL and retains its signed query`() = runBlocking {
        val png = png(860, 484)
        var requests = 0
        server.createContext("/original") { exchange ->
            assertEquals("ex=123&is=456&hm=signature", exchange.requestURI.rawQuery)
            requests++
            exchange.sendResponseHeaders(200, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
        }
        val url = "$base/original?ex=123&is=456&hm=signature"
        for (interpolate in listOf(false, true)) {
            val component = attachment(url, proxy = "$base/unsupported-proxy").toMinecraftMessage(true, interpolate) { error(it) }
            assertEquals("[image.png]", component.string)
            assertEquals(URI(url), (component.style.clickEvent as ClickEvent.OpenUrl).uri())
            assertTrue(component.style.hoverEvent is HoverEvent.ShowItem)
            val item = (component.style.hoverEvent as HoverEvent.ShowItem).item()
            val lore = item.components().entrySet().single { it.key == DataComponents.LORE }.value.get() as ItemLore
            assertEquals(22, lore.lines().size)
            assertTrue(lore.lines().all { it.string.length == 40 })
        }
        assertEquals(2, requests)
    }

    @Test
    fun `unsupported and corrupt images keep links and allow following attachments`() = runBlocking {
        serve("/unsupported", "not an image".toByteArray())
        serve("/corrupt", png(10, 10).copyOf(33))
        serve("/valid", png(10, 10))
        val warnings = mutableListOf<String>()
        val components = listOf("unsupported", "corrupt", "valid").map {
            attachment("$base/$it?token=secret").toMinecraftMessage(true, false, warnings::add)
        }
        assertEquals(3, components.size)
        assertTrue(components.all { it.string == "[image.png]" && it.style.clickEvent is ClickEvent.OpenUrl })
        assertNull(components[0].style.hoverEvent)
        assertNull(components[1].style.hoverEvent)
        assertNotNull(components[2].style.hoverEvent)
        assertEquals(2, warnings.size)
        assertTrue(warnings.none { it.contains("secret") || it.contains(base) })
    }

    @Test
    fun `disabled previews non-images and large attachments do not fetch`() = runBlocking {
        var requests = 0
        server.createContext("/") { exchange -> requests++; exchange.close() }
        val components = listOf(
            attachment(base).toMinecraftMessage(false, false),
            attachment(base, name = "file.txt").toMinecraftMessage(true, false),
            attachment(base, size = 8 * 1024 * 1024).toMinecraftMessage(true, false),
        )
        assertTrue(components.all { it.style.hoverEvent == null && it.style.clickEvent is ClickEvent.OpenUrl })
        assertEquals(0, requests)
    }

    @Test
    fun `HTTP errors redirects and oversized responses retain the attachment link`() = runBlocking {
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "file:///private")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/large") { exchange ->
            exchange.sendResponseHeaders(200, 9L * 1024 * 1024)
            exchange.close()
        }
        server.createContext("/chunked") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.use { it.write(ByteArray(8 * 1024 * 1024 + 1)) }
            } catch (_: java.io.IOException) {
                // The client closes the connection when the size limit is reached.
            } finally {
                exchange.close()
            }
        }
        for (path in listOf("missing", "redirect", "large", "chunked")) {
            val warnings = mutableListOf<String>()
            val component = attachment("$base/$path").toMinecraftMessage(true, false, warnings::add)
            assertEquals("[image.png]", component.string)
            assertNull(component.style.hoverEvent)
            assertEquals(1, warnings.size)
        }
    }

    @Test
    fun `oversized image dimensions are rejected before allocating pixels`() {
        val image = png(10, 10)
        ByteBuffer.wrap(image, 16, 4).putInt(100_000)
        assertFailsWith<Exception> { decodeAttachmentImage(image) }
    }

    @Test
    fun `stalled image reads time out without dropping the attachment`() = runBlocking {
        val release = CountDownLatch(1)
        server.createContext("/stall") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 100)
                exchange.responseBody.write(0)
                exchange.responseBody.flush()
                release.await(10, TimeUnit.SECONDS)
            } finally {
                exchange.close()
            }
        }
        try {
            val warnings = mutableListOf<String>()
            val component = attachment("$base/stall").toMinecraftMessage(true, false, warnings::add)
            assertEquals("[image.png]", component.string)
            assertNull(component.style.hoverEvent)
            assertEquals(1, warnings.size)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `coroutine cancellation is not converted to a successful relay`() = runBlocking {
        val child = launch {
            currentCoroutineContext().cancel()
            assertFailsWith<CancellationException> {
                attachment(base).toMinecraftMessage(true, false) { error("Cancellation must not warn") }
            }
        }
        child.join()
        assertTrue(child.isCancelled)
    }

    private fun attachment(url: String, proxy: String = url, size: Int = 1, name: String = "image.png") = Attachment(
        AttachmentData(id = Snowflake(1), filename = name, size = size, url = url, proxyUrl = proxy), kord
    )

    private fun serve(path: String, bytes: ByteArray) {
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, 0xabcdef)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() = ComponentTranslationsTest.bootstrapMinecraft()
    }
}
