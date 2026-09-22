package io.github.quiltservertools.blockbotdiscord.utility

import dev.kord.rest.request.RestRequestException
import kotlinx.coroutines.delay
import java.io.IOException

/** Retry setup reads and ensureWebhook, before registering any event handlers. */
internal suspend fun <T> retryDiscordSetup(
    warn: (String) -> Unit,
    pause: suspend (Long) -> Unit = { delay(it) },
    action: suspend () -> T,
): T {
    var retries = 0
    while (true) {
        try {
            return action()
        } catch (exception: Exception) {
            val transient = when (exception) {
                is RestRequestException -> exception.status.code in 500..599
                is IOException -> true
                else -> false
            }
            if (!transient || retries == 4) throw exception
            val waitMillis = 1000L shl retries++
            val reason = if (exception is RestRequestException) "HTTP ${exception.status.code}" else "network error"
            warn("Discord relay setup failed ($reason); retrying in ${waitMillis / 1000}s (retry $retries/4)")
            pause(waitMillis)
        }
    }
}
