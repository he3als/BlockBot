package io.github.quiltservertools.blockbotdiscord.config

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.source.toml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourcePackConfigTest {
    private fun config() = Config {
        addSpec(ResourcePackTranslationsSpec)
        addSpec(ResourcePackEmojisSpec)
    }

    @Test
    fun `existing configs default to enabled English translations and icons`() {
        val config = config().from.toml.string("")
        assertTrue(config[ResourcePackTranslationsSpec.enabled])
        assertEquals("en_us", config[ResourcePackTranslationsSpec.language])
        assertTrue(config[ResourcePackEmojisSpec.enabled])
    }

    @Test
    fun `translation and icon options are independently configurable`() {
        val config = config().from.toml.string("""
            [ResourcePackTranslations]
            enabled = false
            language = "fr_fr"
            [ResourcePackEmojis]
            enabled = true
            prefix = "pack_"
        """.trimIndent())
        assertFalse(config[ResourcePackTranslationsSpec.enabled])
        assertEquals("fr_fr", config[ResourcePackTranslationsSpec.language])
        assertTrue(config[ResourcePackEmojisSpec.enabled])
        assertEquals("pack_", config[ResourcePackEmojisSpec.emojiPrefix])
    }
}
