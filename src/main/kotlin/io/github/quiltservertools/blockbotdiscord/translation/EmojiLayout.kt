package io.github.quiltservertools.blockbotdiscord.translation

import kotlin.math.floor
import kotlin.math.roundToInt

// gg sans 2.001: https://discord.com/assets/66d715454104d24e.woff2
// Font units: 1000 per em, cap height 690, ascent 1018, descent 316.
internal data class EmojiLayout(
    val context: Context,
    val fontSize: Double = context.fontSize,
    val lineHeight: Double = context.lineHeight,
    val emojiSize: Double = context.emojiSize,
    val scale: Double = 1.0,
    val baselineOffset: Double = 0.0,
) {
    enum class Context(val fontSize: Double, val lineHeight: Double, val emojiSize: Double) {
        CHAT(16.0, 22.0, 22.0),
        EMBED(14.0, 18.0, 18.0),
    }

    fun isValid(): Boolean = fontSize in 8.0..48.0 && lineHeight in 8.0..96.0 &&
        emojiSize in 8.0..128.0 && scale in 0.25..2.0 && baselineOffset in -16.0..16.0

    val capHeight: Double get() = fontSize * 690 / 1000 * scale

    val baseline: Double get() {
        // Discord wraps bottom-aligned emoji in a baseline-aligned inline-block.
        // Match Chromium's font-metric rounding and half-leading placement.
        val ascent = (fontSize * 1018 / 1000).roundToInt()
        val descent = (fontSize * 316 / 1000).roundToInt()
        val leading = lineHeight - ascent - descent
        val belowBaseline = descent + leading - floor(leading / 2)
        return emojiSize - belowBaseline + baselineOffset
    }
}
