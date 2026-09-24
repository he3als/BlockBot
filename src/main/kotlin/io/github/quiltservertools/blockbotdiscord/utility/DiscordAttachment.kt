package io.github.quiltservertools.blockbotdiscord.utility

import dev.kord.core.entity.Attachment
import io.github.quiltservertools.blockbotdiscord.logWarn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.minecraft.ChatFormatting
import net.minecraft.core.component.DataComponentPatch
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.ceil

private const val MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024
private const val MAX_IMAGE_PIXELS = 16_777_216L

internal suspend fun Attachment.toMinecraftMessage(
    appendImages: Boolean,
    interpolate: Boolean,
    warn: (String) -> Unit = ::logWarn,
): Component {
    val hover = if (appendImages && isImage && size < MAX_ATTACHMENT_BYTES) {
        withContext(Dispatchers.IO) {
            try {
                // The original URL preserves PNG/JPEG data; the media proxy may change its format.
                val image = readAttachmentImage(url)
                try {
                    imageHover(image, interpolate)
                } finally {
                    image.flush()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Do not include signed attachment URLs or response bodies in logs.
                warn("Could not preview a Discord attachment; keeping its download link")
                null
            }
        }
    } else null

    return Component.literal("[$filename]").withStyle {
        it.withColor(ChatFormatting.BLUE)
            .withClickEvent(ClickEvent.OpenUrl(URI(url)))
            .withHoverEvent(hover)
    }
}

private suspend fun readAttachmentImage(url: String): BufferedImage {
    val uri = URI(url)
    require(uri.scheme in setOf("https", "http") && uri.host != null && uri.userInfo == null)
    val connection = uri.toURL().openConnection() as HttpURLConnection
    connection.connectTimeout = 5000
    connection.readTimeout = 5000
    connection.instanceFollowRedirects = false
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
    try {
        if (connection.responseCode != 200) throw IOException("Attachment request failed")
        if (connection.contentLengthLong > MAX_ATTACHMENT_BYTES) throw IOException("Attachment is too large")
        val bytes = connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() >= deadline) throw IOException("Attachment request timed out")
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_ATTACHMENT_BYTES) throw IOException("Attachment is too large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return decodeAttachmentImage(bytes)
    } finally {
        connection.disconnect()
    }
}

internal fun decodeAttachmentImage(bytes: ByteArray): BufferedImage =
    MemoryCacheImageInputStream(bytes.inputStream()).use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) throw IOException("Unsupported attachment image")
        val reader = readers.next()
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width !in 1..8192 || height !in 1..8192 || width.toLong() * height > MAX_IMAGE_PIXELS) {
                throw IOException("Attachment image dimensions are too large")
            }
            reader.read(0) ?: throw IOException("Unreadable attachment image")
        } finally {
            reader.dispose()
        }
    }

internal fun imageHover(image: BufferedImage, interpolate: Boolean): HoverEvent {
    // Limit lore to 40 x 40 pixels to stay within Minecraft's message size limit.
    val step = ceil(maxOf(image.width, image.height) / 40.0).toInt().coerceAtLeast(1)
    val lines = mutableListOf<Component>()
    for (y in 0 until image.height step step) {
        val line = Component.empty().setStyle(Style.EMPTY.withItalic(false))
        for (x in 0 until image.width step step) {
            val rgb = if (interpolate && step > 1) {
                var red = 0
                var green = 0
                var blue = 0
                for (dx in 0 until step) {
                    for (dy in 0 until step) {
                        val colour = image.getRGB(minOf(x + dx, image.width - 1), minOf(y + dy, image.height - 1))
                        red += (colour ushr 16) and 255
                        green += (colour ushr 8) and 255
                        blue += colour and 255
                    }
                }
                val count = step * step
                ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
            } else image.getRGB(x, y) and 0xffffff
            line.append(Component.literal("█").setStyle(Style.EMPTY.withColor(rgb)))
        }
        lines.add(line)
    }
    val components = DataComponentPatch.builder()
        .set(DataComponents.LORE, ItemLore(lines))
        .set(DataComponents.CUSTOM_NAME, Component.empty())
        .build()
    return HoverEvent.ShowItem(ItemStackTemplate(Items.STICK, components))
}
