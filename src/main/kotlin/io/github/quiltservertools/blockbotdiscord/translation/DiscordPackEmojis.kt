package io.github.quiltservertools.blockbotdiscord.translation

import dev.kord.core.Kord
import dev.kord.common.entity.Snowflake
import dev.kord.rest.Image
import dev.kord.rest.request.RestRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class EmojiCapacityException : IllegalStateException("Application emoji slots are full")
private class NoManagedSlotException : IllegalStateException("No managed emoji can be evicted")

/** Upload only icons actually used by a message; artwork hashes survive pack changes and restarts. */
internal class DiscordPackEmojis(
    private val list: suspend () -> Map<String, String>,
    private val create: suspend (name: String, png: ByteArray) -> String,
    private val warn: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val timeoutMillis: Long = 10_000,
    private val delete: suspend (mention: String) -> Unit = { error("Emoji deletion is unavailable") },
    private val info: (String) -> Unit = {},
    private val capacity: Int = 2000,
) {
    constructor(kord: Kord, warn: (String) -> Unit, info: (String) -> Unit = {}) : this(
        list = {
            kord.rest.application.getApplicationEmojis(kord.selfId).items.associate {
                it.name!! to "<:${it.name}:${it.id}>"
            }
        },
        create = { name, png ->
            val emoji = try {
                kord.rest.application.createApplicationEmoji(kord.selfId, name, Image.raw(png, Image.Format.PNG))
            } catch (exception: RestRequestException) {
                if (exception.error?.code?.code == 30008) throw EmojiCapacityException()
                throw exception
            }
            "<:$name:${emoji.id}>"
        },
        warn = warn,
        delete = { mention ->
            val id = Snowflake(mention.substringAfterLast(':').removeSuffix(">"))
            kord.rest.application.deleteApplicationEmoji(kord.selfId, id)
        },
        info = info,
    )

    private val mutex = Mutex()
    private var known: MutableMap<String, String>? = null
    private var refreshAfter = Long.MIN_VALUE
    private var retryAfter = Long.MIN_VALUE
    private var invalidPrefix: String? = null
    private var slotsUnavailable = false
    private data class Usage(var count: Long = 0, var lastUse: Long = 0)
    private val usage = mutableMapOf<String, Usage>()
    private var useOrder = 0L
    private val managedPrefixes = mutableSetOf<String>()
    private val pending = mutableMapOf<MessageScope, MutableSet<String>>()

    private class MessageScope(val owner: DiscordPackEmojis) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<MessageScope>
    }

    /** Keep rendered icons alive until the corresponding Discord send finishes. */
    suspend fun <T> withMessage(block: suspend () -> T): T {
        val scope = MessageScope(this)
        return try {
            withContext(scope) { block() }
        } finally {
            withContext(NonCancellable) { mutex.withLock { pending.remove(scope) } }
        }
    }

    suspend fun render(
        text: String,
        glyphs: Map<Int, String>,
        maxLength: Int = 2000,
        enabled: Boolean = true,
        prefix: String = DEFAULT_PREFIX,
    ): String {
        val points = text.codePoints().toArray()
        if (points.none(ResourcePackGlyphs::isPrivateUse)) return text
        if (!enabled) return withoutGlyphs(text)
        val scope = currentCoroutineContext()[MessageScope]?.takeIf { it.owner === this }
        val replacements = mutableMapOf<Int, String>()
        val completed = withTimeoutOrNull(timeoutMillis) {
            mutex.withLock {
                val namePrefix = if (VALID_PREFIX.matches(prefix)) {
                    invalidPrefix = null
                    prefix
                } else {
                    if (invalidPrefix != prefix) {
                        warn("Invalid ResourcePackEmojis.prefix; use 1-16 ASCII letters, digits, or underscores. Using bb_")
                        invalidPrefix = prefix
                    }
                    DEFAULT_PREFIX
                }
                managedPrefixes.add(namePrefix)
                try {
                    // A message cannot trigger an entire pack's worth of uploads.
                    val icons = points.distinct().filter { glyphs[it]?.isNotEmpty() == true }
                    if (icons.isEmpty()) return@withLock
                    val images = icons.associateWith { Base64.getDecoder().decode(glyphs.getValue(it)) }
                    val needed = images.values.map { emojiName(it, namePrefix) }.toSet()
                    if (scope != null) pending.getOrPut(scope) { mutableSetOf() }.addAll(needed)
                    // Previously uploaded icons remain usable during an API outage.
                    for ((point, png) in images) known?.get(emojiName(png, namePrefix))?.let { replacements[point] = it }
                    if (nanoTime() < retryAfter) return@withLock
                    if (known == null || nanoTime() >= refreshAfter) {
                        refresh()
                        replacements.clear()
                        for ((point, png) in images) known!![emojiName(png, namePrefix)]?.let { replacements[point] = it }
                    }
                    var uploads = 0
                    for ((point, png) in images) {
                        val name = emojiName(png, namePrefix)
                        if (name !in known!! && uploads >= 16) continue
                        val mention = known!![name] ?: run {
                            uploads++
                            createWithSpace(name, png, needed).also {
                                known!![name] = it
                                slotsUnavailable = false
                            }
                        }
                        replacements[point] = mention
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: NoManagedSlotException) {
                    if (!slotsUnavailable) {
                        warn("Discord emoji slots are full; no unused managed icon can be replaced. Sending readable text")
                        slotsUnavailable = true
                    }
                } catch (_: Exception) {
                    failed()
                } finally {
                    // Count rendered occurrences during this server run. Ties
                    // are broken by oldest use, then by name for unseen icons.
                    for (point in points) replacements[point]?.let { mention ->
                        val name = mention.substringAfter("<:").substringBeforeLast(':')
                        usage.getOrPut(name) { Usage() }.apply { count++; lastUse = ++useOrder }
                    }
                }
            }
            true
        }
        if (completed == null && mutex.tryLock()) {
            try {
                if (nanoTime() >= retryAfter) failed()
            } finally {
                mutex.unlock()
            }
        }
        // Emoji markup is much longer than a single glyph. Keep all ordinary
        // text and omit excess icons instead of overflowing Discord's limit.
        var remaining = maxLength - points.filterNot(ResourcePackGlyphs::isPrivateUse).sumOf(Character::charCount)
        return buildString {
            for (point in points) {
                if (ResourcePackGlyphs.isPrivateUse(point)) {
                    val replacement = replacements[point].orEmpty()
                    if (replacement.length <= remaining) {
                        append(replacement)
                        remaining -= replacement.length
                    }
                } else appendCodePoint(point)
            }
        }.trim()
    }

    private suspend fun refresh() {
        known = list().toMutableMap()
        usage.keys.retainAll(known!!.keys)
        refreshAfter = nanoTime() + TimeUnit.MINUTES.toNanos(5)
    }

    private suspend fun createWithSpace(name: String, png: ByteArray, needed: Set<String>): String {
        if (known!!.size >= capacity) {
            // Another process may have freed slots since our last list request.
            refresh()
            known!![name]?.let { return it }
            if (known!!.size >= capacity) evict(needed)
        }
        return try {
            create(name, png)
        } catch (_: EmojiCapacityException) {
            // Handle externally added emoji or a stale/full inventory from Discord.
            refresh()
            known!![name]?.let { return it }
            evict(needed)
            create(name, png) // One replacement attempt; other failures keep the text readable.
        }
    }

    private suspend fun evict(needed: Set<String>) {
        val victim = known!!.keys.asSequence()
            .filter { name -> managedPrefixes.any { prefix ->
                name.length == 32 && name.startsWith(prefix) && name.substring(prefix.length).matches(HEX_SUFFIX)
            } }
            .filter { it !in needed && pending.values.none { names -> it in names } }
            .minWithOrNull(compareBy<String> { usage[it]?.count ?: 0L }
                .thenBy { usage[it]?.lastUse ?: 0L }.thenBy { it })
            ?: throw NoManagedSlotException()
        delete(known!!.getValue(victim))
        known!!.remove(victim)
        usage.remove(victim)
        info("Removed least-used resource-pack Discord emoji to free a slot")
    }

    private fun failed() {
        retryAfter = nanoTime() + TimeUnit.MINUTES.toNanos(5)
        // Refresh after ambiguous failures so a timed-out creation is reused next time.
        refreshAfter = Long.MIN_VALUE
        warn("Could not prepare resource-pack Discord emoji; sending readable text and retrying after five minutes")
    }

    internal companion object {
        private const val DEFAULT_PREFIX = "bb_"
        private val VALID_PREFIX = Regex("[A-Za-z0-9_]{1,16}")
        private val HEX_SUFFIX = Regex("[0-9a-f]+")

        fun emojiName(png: ByteArray, prefix: String = DEFAULT_PREFIX): String =
            prefix + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)).take(32 - prefix.length)

        fun withoutGlyphs(text: String): String = buildString {
            text.codePoints().forEach { if (!ResourcePackGlyphs.isPrivateUse(it)) appendCodePoint(it) }
        }.trim()
    }
}
