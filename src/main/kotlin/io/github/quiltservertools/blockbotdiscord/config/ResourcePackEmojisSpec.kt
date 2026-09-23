package io.github.quiltservertools.blockbotdiscord.config

import com.uchuhimo.konf.ConfigSpec
import io.github.quiltservertools.blockbotdiscord.translation.ResourcePackGlyphs

object ResourcePackEmojisSpec : ConfigSpec() {
    val enabled by optional(true)
    val emojiPrefix by optional("bb_", name = "prefix")
    val coloured by optional(true)
    val padding by optional(ResourcePackGlyphs.DEFAULT_PADDING)
}
