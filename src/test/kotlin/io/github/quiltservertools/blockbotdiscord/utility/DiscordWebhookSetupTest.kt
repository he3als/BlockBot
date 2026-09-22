package io.github.quiltservertools.blockbotdiscord.utility

import dev.kord.common.entity.DiscordChannel
import dev.kord.core.Kord
import dev.kord.core.behavior.execute
import dev.kord.core.cache.data.ChannelData
import dev.kord.core.entity.channel.TextChannel
import dev.kordex.core.utils.ensureWebhook
import io.github.quiltservertools.blockbotdiscord.translation.DiscordPackEmojis
import io.github.quiltservertools.blockbotdiscord.translation.EmojiUsageStore
import io.github.quiltservertools.blockbotdiscord.translation.ResourcePackGlyphs
import io.github.quiltservertools.blockbotdiscord.translation.advancementEmbed
import io.github.quiltservertools.blockbotdiscord.translation.iconPack
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.http.content.TextContent
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscordWebhookSetupTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `real KordEx webhook setup recovers from 503 and sends using updated Kord`() = runBlocking {
        var lookups = 0
        var creations = 0
        var sends = 0
        var emojiUploads = 0
        var fullInventoryResponses = 0
        var emojiDeletes = 0
        var emojiName = ""
        val oldEmojiName = "bb_" + "0".repeat(29)
        var lastSend = ""
        val engine = MockEngine { request ->
            val headers = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                request.url.encodedPath.endsWith("/gateway/bot") -> respond(
                    """{"url":"wss://example.invalid","shards":1,"session_start_limit":{"total":1000,"remaining":1000,"reset_after":1000,"max_concurrency":1}}""",
                    headers = headers,
                )
                request.url.encodedPath.endsWith("/channels/123/webhooks") && request.method == HttpMethod.Get -> {
                    if (++lookups == 1) respond("unavailable", HttpStatusCode.ServiceUnavailable)
                    else respond(if (creations == 0) "[]" else "[$webhook]", headers = headers)
                }
                request.url.encodedPath.endsWith("/channels/123/webhooks") && request.method == HttpMethod.Post -> {
                    creations++
                    respond(webhook, headers = headers)
                }
                request.url.encodedPath.endsWith("/webhooks/456/test-token") -> {
                    sends++
                    lastSend = (request.body as TextContent).text
                    respond(message, headers = headers)
                }
                request.url.encodedPath.endsWith("/applications/123456789012345678/emojis") -> {
                    if (request.method == HttpMethod.Get) {
                        respond(if (emojiUploads == 0 && fullInventoryResponses == 0) """{"items":[]}"""
                            else if (emojiUploads == 0) """{"items":[{"id":"888","name":"$oldEmojiName"}]}"""
                            else """{"items":[{"id":"987","name":"$emojiName"}]}""", headers = headers)
                    } else if (fullInventoryResponses++ == 0) {
                        respond("""{"code":30008,"message":"Maximum number of emojis reached"}""",
                            HttpStatusCode.BadRequest, headers)
                    } else {
                        val data = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        emojiName = data.getValue("name").jsonPrimitive.content
                        assertTrue(data.getValue("image").jsonPrimitive.content.startsWith("data:image/png;base64,"))
                        emojiUploads++
                        respond("""{"id":"987","name":"$emojiName"}""", headers = headers)
                    }
                }
                request.url.encodedPath.endsWith("/applications/123456789012345678/emojis/888") &&
                    request.method == HttpMethod.Delete -> {
                    emojiDeletes++
                    respond("", HttpStatusCode.NoContent)
                }
                else -> error("Unexpected mocked request: ${request.method} ${request.url.encodedPath}")
            }
        }
        // A synthetic token whose first segment encodes a numeric bot ID.
        val kord = Kord("MTIzNDU2Nzg5MDEyMzQ1Njc4.test.signature") {
            httpClient = HttpClient(engine)
        }
        try {
            val data = Json.decodeFromString<DiscordChannel>(
                """{"id":"123","type":0,"guild_id":"42","application_id":null}"""
            )
            val channel = TextChannel(ChannelData.from(data), kord)
            val waits = mutableListOf<Long>()
            val ready = retryDiscordSetup({}, { waits.add(it) }) { ensureWebhook(channel, "BlockBot") }
            assertEquals(listOf(1000L), waits)
            assertEquals(1, creations)
            // Repeated setup must find the existing webhook instead of creating another.
            ensureWebhook(channel, "BlockBot")
            assertEquals(1, creations)
            val sent = ready.execute("test-token") { content = "smoke" }
            assertEquals("smoke", sent.content)
            assertEquals(1, sends)
            val glyphs = ResourcePackGlyphs.readPack(iconPack(directory))
            val usageFile = directory.resolve("discord/123456789012345678/emoji-usage.json")
            val emojis = DiscordPackEmojis(kord, warn = { error(it) }, usageFile = usageFile)
            emojis.withMessage {
                val embed = advancementEmbed("Sophed has made the advancement [The Warding Metal]", "\ue00f Silver",
                    "https://example.invalid/avatar.png") { emojis.render(it, glyphs) }
                ready.execute("test-token") { embeds = mutableListOf(embed) }
            }
            assertEquals(1, emojiUploads)
            assertEquals(1, emojiDeletes)
            assertTrue(lastSend.contains("<:$emojiName:987> Silver"))
            assertTrue(!lastSend.contains("footer"))
            assertEquals(1L, EmojiUsageStore(usageFile, warn = { error(it) }).load().emojis.getValue(emojiName).count)
            // Restarting the relay discovers and reuses the application-owned emoji.
            assertEquals("<:$emojiName:987> Silver", DiscordPackEmojis(kord, warn = { error(it) }).render("\ue00f Silver", glyphs))
            assertEquals(1, emojiUploads)
        } finally {
            kord.shutdown()
        }
    }

    private val webhook = """{
        "id":"456","type":1,"guild_id":"42","channel_id":"123",
        "name":"BlockBot","avatar":null,"token":"test-token","application_id":null
    }"""
    private val message = """{
        "id":"789","channel_id":"123","author":{"id":"456","username":"BlockBot","discriminator":"0","avatar":null},
        "content":"smoke","timestamp":"2026-09-22T00:00:00Z","edited_timestamp":null,
        "tts":false,"mention_everyone":false,"mentions":[],"mention_roles":[],
        "attachments":[],"embeds":[],"pinned":false,"type":0
    }"""
}
