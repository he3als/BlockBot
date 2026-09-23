package io.github.quiltservertools.blockbotdiscord.translation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.awt.AlphaComposite
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.Reader
import java.nio.file.Path
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.roundToInt

/** Bitmap icons in the default font, including references to fonts in other namespaces. */
internal object ResourcePackGlyphs {
    // Retained for configurations using centred padding instead of automatic layout.
    const val DEFAULT_PADDING = 23.1667
    private const val MAX_GLYPHS = 512
    private const val MAX_PNG_BYTES = 128 * 1024
    private val resourceId = Regex("(?:([a-z0-9_.-]+):)?([a-z0-9_./-]+)")

    fun isPrivateUse(codePoint: Int): Boolean = Character.getType(codePoint) == Character.PRIVATE_USE.toInt()

    /** Size the visible artwork, not its square container, and anchor its bottom to the text baseline. */
    fun layout(encoded: String, layout: EmojiLayout): String {
        require(layout.isValid()) { "Invalid emoji layout" }
        if (encoded.isEmpty()) return encoded
        val image = readIcon(encoded)
        val icon = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
        try {
            val bounds = visibleBounds(image) ?: return encoded
            val bottom = (128 * layout.baseline / layout.emojiSize).roundToInt().coerceIn(1, 128)
            val height = 128 * layout.capHeight / layout.emojiSize
            // Fit very wide or enlarged artwork without stretching or clipping.
            val scale = minOf(height / bounds.height, 128.0 / bounds.width, bottom.toDouble() / bounds.height)
            val targetWidth = (bounds.width * scale).roundToInt().coerceIn(1, 128)
            val targetHeight = (bounds.height * scale).roundToInt().coerceIn(1, bottom)
            val left = (128 - targetWidth) / 2
            val top = bottom - targetHeight
            val graphics = icon.createGraphics()
            try {
                graphics.composite = AlphaComposite.Src
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                graphics.drawImage(image, left, top, left + targetWidth, bottom,
                    bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height, null)
            } finally {
                graphics.dispose()
            }
            return writeIcon(icon)
        } finally {
            image.flush()
            icon.flush()
        }
    }

    /** Inset the cached square canvas without cropping again or changing its aspect ratio. */
    fun pad(encoded: String, percentage: Double): String {
        require(percentage.isFinite() && percentage in 0.0..49.0) { "Invalid icon padding" }
        val size = (128 * (1 - 2 * percentage / 100)).roundToInt()
        if (size == 128 || encoded.isEmpty()) return encoded
        val image = readIcon(encoded)
        val padded = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
        try {
            val graphics = padded.createGraphics()
            try {
                graphics.composite = AlphaComposite.Src
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                val offset = (128 - size) / 2
                graphics.drawImage(image, offset, offset, size, size, null)
            } finally {
                graphics.dispose()
            }
            return writeIcon(padded)
        } finally {
            image.flush()
            padded.flush()
        }
    }

    private fun readIcon(encoded: String): BufferedImage {
        require(encoded.length <= (MAX_PNG_BYTES * 4 / 3 + 4)) { "Icon image exceeds size limit" }
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size <= MAX_PNG_BYTES) { "Icon image exceeds size limit" }
        return MemoryCacheImageInputStream(bytes.inputStream()).use { input ->
            val reader = ImageIO.getImageReadersByFormatName("png").next()
            try {
                reader.input = input
                require(reader.getWidth(0) == 128 && reader.getHeight(0) == 128) { "Invalid cached icon dimensions" }
                reader.read(0)
            } finally {
                reader.dispose()
            }
        }
    }

    private fun writeIcon(image: BufferedImage): String {
        val output = ByteArrayOutputStream()
        check(ImageIO.write(image, "png", output))
        require(output.size() <= MAX_PNG_BYTES) { "Icon image exceeds size limit" }
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    /** Minecraft multiplies the font texture's RGB by the component's text colour. */
    fun tint(encoded: String, colour: Int): String {
        if (colour == 0xffffff || encoded.isEmpty()) return encoded
        val image = readIcon(encoded)
        try {
            val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            for (index in pixels.indices) {
                val pixel = pixels[index]
                val red = ((pixel ushr 16) and 255) * ((colour ushr 16) and 255) / 255
                val green = ((pixel ushr 8) and 255) * ((colour ushr 8) and 255) / 255
                val blue = (pixel and 255) * (colour and 255) / 255
                pixels[index] = (pixel and 0xff000000.toInt()) or (red shl 16) or (green shl 8) or blue
            }
            // Always use ARGB: indexed PNGs may not have the tinted colours in their palette.
            val tinted = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
            try {
                tinted.setRGB(0, 0, 128, 128, pixels, 0, 128)
                return writeIcon(tinted)
            } finally {
                tinted.flush()
            }
        } finally {
            image.flush()
        }
    }

    fun readPack(path: Path): Map<Int, String> = ZipFile(path.toFile()).use { zip ->
        val result = mutableMapOf<Int, String>()
        val fonts = mutableSetOf<String>()
        var totalBytes = 0
        var totalPixels = 0L
        var providersRead = 0

        fun readEntry(name: String, limit: Int): ByteArray? {
            val entry = zip.getEntry(name) ?: return null
            require(!entry.isDirectory && entry.size <= limit) { "Oversized font entry" }
            val bytes = zip.getInputStream(entry).use { it.readNBytes(limit + 1) }
            totalBytes += bytes.size
            require(bytes.size <= limit && totalBytes <= 32 * 1024 * 1024) { "Font data exceeds size limit" }
            require(bytes.size.toLong() == entry.size && CRC32().apply { update(bytes) }.value == entry.crc) {
                "Corrupt font entry"
            }
            return bytes
        }

        fun put(codePoint: Int, png: String) {
            result.putIfAbsent(codePoint, png)
            require(result.size <= MAX_GLYPHS) { "Too many bitmap icons" }
        }

        fun readFont(id: String, depth: Int) {
            require(depth <= 16) { "Too many font references" }
            val fontPath = resourcePath(id, "font", ".json")
            if (!fonts.add(fontPath)) return // References can be cyclic or shared.
            require(fonts.size <= 128) { "Too many fonts" }
            val json = readEntry(fontPath, 1024 * 1024) ?: return
            val font = readObject(json.toString(Charsets.UTF_8).reader())
            for (element in font.getAsJsonArray("providers")) {
                require(++providersRead <= 2048) { "Too many font providers" }
                val provider = element.asJsonObject
                // Conditional fonts depend on client-only font settings. Use normal defaults.
                val filter = provider.getAsJsonObject("filter")
                if (filter != null && filter.entrySet().any { it.value.asBoolean }) continue
                when (provider.get("type").asString.removePrefix("minecraft:")) {
                    "reference" -> readFont(provider.get("id").asString, depth + 1)
                    "space" -> provider.getAsJsonObject("advances").keySet().forEach { character ->
                        val points = character.codePoints().toArray()
                        if (points.size == 1 && isPrivateUse(points[0])) put(points[0], "")
                    }
                    "bitmap" -> {
                        val rows = provider.getAsJsonArray("chars").map { it.asString.codePoints().toArray() }
                        require(rows.isNotEmpty() && rows[0].isNotEmpty() && rows.all { it.size == rows[0].size }) {
                            "Invalid bitmap grid"
                        }
                        if (rows.none { row -> row.any { isPrivateUse(it) && it !in result } }) continue
                        val texture = resourcePath(provider.get("file").asString, "textures")
                        require(texture.endsWith(".png")) { "Only PNG font textures are supported" }
                        val bytes = readEntry(texture, 16 * 1024 * 1024) ?: continue
                        val image = MemoryCacheImageInputStream(bytes.inputStream()).use { input ->
                            val reader = ImageIO.getImageReadersByFormatName("png").next()
                            try {
                                reader.input = input
                                val width = reader.getWidth(0)
                                val height = reader.getHeight(0)
                                totalPixels += width.toLong() * height
                                require(width in 1..4096 && height in 1..4096 &&
                                    width.toLong() * height <= 4_194_304 && totalPixels <= 16_777_216) {
                                    "Font texture dimensions exceed limit"
                                }
                                require(width % rows[0].size == 0 && height % rows.size == 0) { "Invalid bitmap grid size" }
                                reader.read(0)
                            } finally {
                                reader.dispose()
                            }
                        }
                        try {
                            val cellWidth = image.width / rows[0].size
                            val cellHeight = image.height / rows.size
                            rows.forEachIndexed { y, row -> row.forEachIndexed { x, codePoint ->
                                if (isPrivateUse(codePoint) && codePoint !in result) {
                                    put(codePoint, encodeIcon(image.getSubimage(x * cellWidth, y * cellHeight, cellWidth, cellHeight)))
                                }
                            } }
                        } finally {
                            image.flush()
                        }
                    }
                }
            }
        }

        require(zip.size() <= 100_000) { "Too many ZIP entries" }
        val names = mutableSetOf<String>()
        zip.entries().asSequence().forEach { require(names.add(it.name)) { "Duplicate ZIP paths" } }
        readFont("minecraft:default", 0)
        java.util.Map.copyOf(result)
    }

    private fun resourcePath(id: String, directory: String, suffix: String = ""): String {
        val match = resourceId.matchEntire(id) ?: throw IllegalArgumentException("Invalid font resource ID")
        val namespace = match.groupValues[1].ifEmpty { "minecraft" }
        val path = match.groupValues[2]
        require(namespace !in setOf(".", "..") && path.split('/').none { it in setOf("", ".", "..") }) {
            "Invalid font resource path"
        }
        return "assets/$namespace/$directory/$path$suffix"
    }

    private fun visibleBounds(image: BufferedImage): Rectangle? {
        var left = image.width
        var top = image.height
        var right = -1
        var bottom = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (image.getRGB(x, y).ushr(24) != 0) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        return if (right < left) null else Rectangle(left, top, right - left + 1, bottom - top + 1)
    }

    private fun encodeIcon(image: BufferedImage): String {
        val bounds = visibleBounds(image) ?: return ""
        val width = bounds.width
        val height = bounds.height
        val scale = 128.0 / maxOf(width, height)
        val targetWidth = maxOf(1, (width * scale).toInt())
        val targetHeight = maxOf(1, (height * scale).toInt())
        val icon = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
        val graphics = icon.createGraphics()
        try {
            // Retain the texture's alpha, including translucent pixels. Unused
            // canvas pixels remain transparent rather than acquiring a backdrop.
            graphics.composite = AlphaComposite.Src
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            val x = (128 - targetWidth) / 2
            val y = (128 - targetHeight) / 2
            graphics.drawImage(image, x, y, x + targetWidth, y + targetHeight,
                bounds.x, bounds.y, bounds.x + width, bounds.y + height, null)
        } finally {
            graphics.dispose()
        }
        return try {
            writeIcon(icon)
        } finally {
            icon.flush()
        }
    }

    fun readCache(reader: Reader): Map<Int, String> {
        val json = readObject(reader)
        require(json.size() <= MAX_GLYPHS) { "Too many cached icons" }
        return json.entrySet().associate { (key, value) ->
            val point = key.toInt()
            require(isPrivateUse(point) && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid cached icon" }
            val encoded = value.asString
            if (encoded.isNotEmpty()) readIcon(encoded).flush()
            point to encoded
        }
    }

    private fun readObject(reader: Reader): JsonObject = JsonReader(reader).use { json ->
        json.strictness = Strictness.STRICT
        val result = JsonParser.parseReader(json).asJsonObject
        require(json.peek() == JsonToken.END_DOCUMENT) { "Trailing font JSON data" }
        result
    }
}
