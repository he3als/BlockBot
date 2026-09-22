package io.github.quiltservertools.blockbotdiscord.utility

import dev.kord.common.entity.DiscordChannel
import dev.kord.common.entity.DiscordGuild
import dev.kord.common.entity.optional.OptionalSnowflake
import dev.kord.core.cache.data.ChannelData
import dev.kord.gateway.Event
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class DiscordPayloadTest {
    // Same parser settings as Kord's gateway; do not mask required-field errors.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `channel application ID accepts missing null and valid values`() {
        val missing = json.decodeFromString<DiscordChannel>("""{"id":"123","type":0}""")
        assertSame(OptionalSnowflake.Missing, missing.applicationId)
        val absent = json.decodeFromString<DiscordChannel>("""{"id":"123","type":0,"application_id":null}""")
        assertNull(absent.applicationId)
        assertNull(ChannelData.from(absent).applicationId)
        val present = json.decodeFromString<DiscordChannel>("""{"id":"123","type":0,"application_id":"456"}""")
        assertEquals(456uL, present.applicationId?.value?.value)
        assertFailsWith<SerializationException> {
            json.decodeFromString<DiscordChannel>("""{"id":null,"type":0}""")
        }
    }

    @Test
    fun `guild with nullable channel application ID decodes through gateway dispatch`() {
        val guild = """{
            "id":"42","name":"Test","icon":null,"home_header":null,"owner_id":"1",
            "region":"deprecated","afk_channel_id":null,"afk_timeout":300,
            "verification_level":0,"default_message_notifications":0,"explicit_content_filter":0,
            "roles":[],"emojis":[],"features":[],"mfa_level":0,"application_id":null,
            "system_channel_id":null,"system_channel_flags":0,"rules_channel_id":null,
            "vanity_url_code":null,"description":null,"banner":null,"premium_tier":0,
            "preferred_locale":"en-US","public_updates_channel_id":null,"nsfw_level":0,
            "premium_progress_bar_enabled":false,"safety_alerts_channel_id":null,
            "channels":[{"id":"123","type":0,"application_id":null}]
        }"""
        val decoded = json.decodeFromString<DiscordGuild>(guild)
        assertNull(decoded.channels.value!!.single().applicationId)
        json.decodeFromString(Event.DeserializationStrategy, """{"op":0,"t":"GUILD_CREATE","s":1,"d":$guild}""")
    }
}
