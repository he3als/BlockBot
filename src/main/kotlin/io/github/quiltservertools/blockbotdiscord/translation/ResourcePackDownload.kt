package io.github.quiltservertools.blockbotdiscord.translation

import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal object ResourcePackDownload {
    private const val MAX_DOWNLOAD_BYTES = 256L * 1024 * 1024

    fun validateUrl(uri: URI) {
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host == null || uri.userInfo != null) {
            throw PackTranslationException("Resource-pack URL must be HTTP(S) without embedded credentials")
        }
    }

    fun download(url: URI, destination: Path) {
        var uri = url
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2)
        repeat(6) {
            validateUrl(uri)
            checkDeadline(deadline)
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "BlockBot resource-pack translations")
            try {
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                        ?: throw PackTranslationException("Resource-pack redirect has no location")
                    val next = uri.resolve(location)
                    if (uri.scheme.equals("https", true) && !next.scheme.equals("https", true)) {
                        throw PackTranslationException("Resource-pack redirect downgrades HTTPS")
                    }
                    uri = next
                } else {
                    if (status != 200) throw PackTranslationException("Resource-pack HTTP status $status")
                    if (connection.contentLengthLong > MAX_DOWNLOAD_BYTES) {
                        throw PackTranslationException("Resource-pack download exceeds 256 MiB")
                    }
                    connection.inputStream.use { input ->
                        Files.newOutputStream(destination).use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                checkDeadline(deadline)
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_DOWNLOAD_BYTES) {
                                    throw PackTranslationException("Resource-pack download exceeds 256 MiB")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    return
                }
            } finally {
                connection.disconnect()
            }
        }
        throw PackTranslationException("Too many resource-pack redirects")
    }

    private fun checkDeadline(deadline: Long) {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Resource-pack download interrupted")
        if (System.nanoTime() >= deadline) throw PackTranslationException("Resource-pack download timed out")
    }
}
