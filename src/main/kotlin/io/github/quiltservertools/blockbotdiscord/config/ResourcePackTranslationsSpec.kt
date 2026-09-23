package io.github.quiltservertools.blockbotdiscord.config

import com.uchuhimo.konf.ConfigSpec

object ResourcePackTranslationsSpec : ConfigSpec() {
    val enabled by optional(true)
    val language by optional("en_us")
}
