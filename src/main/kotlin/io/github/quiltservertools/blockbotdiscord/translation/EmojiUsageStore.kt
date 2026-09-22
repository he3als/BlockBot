package io.github.quiltservertools.blockbotdiscord.translation

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

internal data class EmojiUsage(val count: Long, val lastUse: Long)

internal data class EmojiUsageState(
    val version: Int = 1,
    val sequence: Long = 0,
    val prefixes: Set<String> = emptySet(),
    val emojis: Map<String, EmojiUsage> = emptyMap(),
)

/** Called under the emoji manager's mutex; no Minecraft-thread file I/O. */
internal class EmojiUsageStore(
    private val path: Path,
    private val warn: (String) -> Unit,
    private val replace: (Path, Path) -> Unit = { temporary, destination ->
        Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
    },
) {
    private var writeFailed = false

    suspend fun load(): EmojiUsageState = withContext(Dispatchers.IO) {
        if (!Files.exists(path)) return@withContext EmojiUsageState()
        try {
            val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_BYTES + 1) }
            require(bytes.size <= MAX_BYTES)
            val root = JsonReader(bytes.toString(Charsets.UTF_8).reader()).use { reader ->
                reader.strictness = Strictness.STRICT
                val json = JsonParser.parseReader(reader).asJsonObject
                require(reader.peek() == JsonToken.END_DOCUMENT)
                json
            }
            require(integer(root.get("version")) == 1L)
            val sequence = integer(root.get("sequence")).also { require(it >= 0) }
            val prefixes = root.getAsJsonArray("prefixes").map {
                require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
                it.asString
            }.toSet()
            require(prefixes.size <= 256 && prefixes.all { PREFIX.matches(it) })
            val entries = root.getAsJsonObject("emojis").entrySet()
            require(entries.size <= 4096)
            val emojis = entries.associate { (name, value) ->
                require(NAME.matches(name))
                val entry = value.asJsonObject
                val count = integer(entry.get("count"))
                val lastUse = integer(entry.get("lastUse"))
                require(count >= 0 && lastUse in 0..sequence)
                name to EmojiUsage(count, lastUse)
            }
            EmojiUsageState(sequence = sequence, prefixes = prefixes, emojis = emojis)
        } catch (_: Exception) {
            warn("Could not read Discord emoji usage JSON; starting with empty usage counts")
            EmojiUsageState()
        }
    }

    /** Atomic replacement retains the previous file if writing fails. */
    suspend fun save(state: EmojiUsageState): Boolean = withContext(Dispatchers.IO) {
        try {
            require(state.emojis.size <= 4096 && state.prefixes.size <= 256)
            val bytes = JSON.toJson(state).toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_BYTES)
            Files.createDirectories(path.parent)
            val temporary = Files.createTempFile(path.parent, "emoji-usage-", ".tmp")
            try {
                FileChannel.open(temporary, WRITE).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                replace(temporary, path)
            } finally {
                Files.deleteIfExists(temporary)
            }
            writeFailed = false
            true
        } catch (_: Exception) {
            if (!writeFailed) warn("Could not save Discord emoji usage JSON; keeping counts in memory and retrying on the next message")
            writeFailed = true
            false
        }
    }

    private companion object {
        val JSON = GsonBuilder().setPrettyPrinting().create()
        const val MAX_BYTES = 2 * 1024 * 1024
        val PREFIX = Regex("[A-Za-z0-9_]{1,16}")
        val NAME = Regex("[A-Za-z0-9_]{2,32}")

        fun integer(value: JsonElement): Long {
            require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
            return value.asString.toLong()
        }
    }
}
