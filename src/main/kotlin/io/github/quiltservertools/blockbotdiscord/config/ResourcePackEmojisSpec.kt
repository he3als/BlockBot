package io.github.quiltservertools.blockbotdiscord.config

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.ConfigSpec
import io.github.quiltservertools.blockbotdiscord.translation.EmojiLayout
import io.github.quiltservertools.blockbotdiscord.translation.ResourcePackGlyphs

object ResourcePackEmojisSpec : ConfigSpec() {
    val enabled by optional(true)
    val emojiPrefix by optional("bb_", name = "prefix")
    val coloured by optional(true)
    val automaticLayout by optional(true)
    val scale by optional(1.0)
    val baselineOffset by optional(0.0)
    val padding by optional(ResourcePackGlyphs.DEFAULT_PADDING)
}

internal fun Config.resourcePackEmojiLayout(embed: Boolean): EmojiLayout? {
    if (!this[ResourcePackEmojisSpec.automaticLayout]) return null
    val context = if (embed) EmojiLayout.Context.EMBED else EmojiLayout.Context.CHAT
    return EmojiLayout(
        context,
        scale = this[ResourcePackEmojisSpec.scale],
        baselineOffset = this[ResourcePackEmojisSpec.baselineOffset],
    )
}
