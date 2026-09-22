package io.github.quiltservertools.blockbotdiscord.config

import com.uchuhimo.konf.ConfigSpec

object ResourcePackEmojisSpec : ConfigSpec() {
    val enabled by optional(true)
    val emojiPrefix by optional("bb_", name = "prefix")
    val coloured by optional(true)
}
