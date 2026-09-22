package io.github.quiltservertools.blockbotdiscord.utility

import dev.kord.common.entity.DiscordChannel
import dev.kord.rest.request.HttpStatus
import dev.kord.rest.request.JsonRequest
import dev.kord.rest.request.RestRequestException
import dev.kord.rest.route.Route
import io.ktor.util.StringValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DiscordSetupRetryTest {
    @Test
    fun `temporary Discord 503 retries setup with bounded backoff`() = runBlocking {
        val waits = mutableListOf<Long>()
        val warnings = mutableListOf<String>()
        var attempts = 0
        val result = retryDiscordSetup(warnings::add, { waits.add(it) }) {
            if (++attempts < 3) throw restError(503)
            "ready"
        }
        assertEquals("ready", result)
        assertEquals(3, attempts)
        assertEquals(listOf(1000L, 2000L), waits)
        assertEquals(2, warnings.size)
        assertFalse(warnings.any { it.contains("secret") })
    }

    @Test
    fun `persistent server errors stop after five attempts`() = runBlocking {
        var attempts = 0
        val waits = mutableListOf<Long>()
        assertFailsWith<RestRequestException> {
            retryDiscordSetup({}, { waits.add(it) }) {
                attempts++
                throw restError(503)
            }
        }
        assertEquals(5, attempts)
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L), waits)
    }

    @Test
    fun `authentication and permission failures are not retried`() = runBlocking {
        for (status in listOf(400, 401, 403, 404, 429)) {
            var attempts = 0
            assertFailsWith<RestRequestException> {
                retryDiscordSetup({ error("Must not warn") }, { error("Must not wait") }) {
                    attempts++
                    throw restError(status)
                }
            }
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `network errors can recover while cancellation propagates`() = runBlocking<Unit> {
        var attempts = 0
        assertEquals("ready", retryDiscordSetup({}, {}) {
            if (++attempts == 1) throw IOException("connection lost")
            "ready"
        })
        assertFailsWith<CancellationException> {
            retryDiscordSetup({ error("Must not warn") }, { error("Must not wait") }) {
                throw CancellationException()
            }
        }
    }

    private fun restError(code: Int): RestRequestException {
        val request = JsonRequest<Unit, DiscordChannel>(
            Route.ChannelGet, emptyMap(), StringValues.Empty, StringValues.Empty, null,
        )
        return object : RestRequestException(request, HttpStatus(code, "secret")) {}
    }
}
