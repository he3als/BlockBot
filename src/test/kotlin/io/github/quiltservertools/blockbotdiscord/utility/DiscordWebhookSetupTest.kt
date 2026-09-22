package io.github.quiltservertools.blockbotdiscord.utility

import dev.kord.common.entity.DiscordChannel
import dev.kord.core.Kord
import dev.kord.core.behavior.execute
import dev.kord.core.cache.data.ChannelData
import dev.kord.core.entity.channel.TextChannel
import dev.kordex.core.utils.ensureWebhook
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscordWebhookSetupTest {
    @Test
    fun `real KordEx webhook setup recovers from 503 and sends using updated Kord`() = runBlocking {
        var lookups = 0
        var creations = 0
        var sends = 0
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
                    respond(message, headers = headers)
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
