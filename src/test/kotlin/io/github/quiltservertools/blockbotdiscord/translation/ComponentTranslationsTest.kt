package io.github.quiltservertools.blockbotdiscord.translation

import dev.vankka.mcdiscordreserializer.discord.DiscordSerializer
import dev.vankka.mcdiscordreserializer.discord.DiscordSerializerOptions
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import net.minecraft.ChatFormatting
import net.minecraft.SharedConstants
import net.minecraft.core.RegistryAccess
import net.minecraft.core.HolderLookup
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComponentTranslationsTest {
    private val translations = mapOf(
        "adv.kleispack.light_campfire" to "Light a Campfire",
        "adv.kleispack.light_campfire.desc" to "Light a campfire for the first time",
        "log.kleispack.player_updated" to "%2\$s: %1\$s / %s / %2\$s / %%",
        "log.test.primitives" to "%s / %s / %s",
    )

    private val serializer = DiscordSerializer(
        DiscordSerializerOptions.defaults().withTranslationProvider {
            // Exercise the exact 4.3.0 provider contract used by BlockBotApiExtension.
            it.children(emptyList()).toNative(RegistryAccess.EMPTY).withResourcePackTranslations(translations).string
        }
    )

    @Test
    fun `custom advancement title inside vanilla announcement resolves on Discord`() {
        val announcement = Component.translatable(
            "chat.type.advancement.task",
            Component.literal("Sophed"),
            Component.literal("[").append(Component.translatable("adv.kleispack.light_campfire")).append("]")
        )
        assertEquals(
            "Sophed has made the advancement [Light a Campfire]",
            serializer.serialize(announcement.toAdventure(RegistryAccess.EMPTY))
        )
        assertEquals(
            "Light a campfire for the first time",
            Component.translatable("adv.kleispack.light_campfire.desc").withResourcePackTranslations(translations).string
        )
    }

    @Test
    fun `vanilla title and description retain server translations`() {
        for (key in listOf("advancements.story.mine_stone.title", "advancements.story.mine_stone.description")) {
            val component = Component.translatable(key)
            assertTrue(component.string != key)
            assertEquals(component.string, component.withResourcePackTranslations(translations).string)
            assertEquals(component.string, serializer.serialize(component.toAdventure(RegistryAccess.EMPTY)))
        }
        val missing = Component.translatableWithFallback("missing.key", "Explicit %s", "fallback")
        assertEquals("Explicit fallback", missing.withResourcePackTranslations(translations).string)
        assertEquals("unknown.key", Component.translatable("unknown.key").withResourcePackTranslations(translations).string)
    }

    @Test
    fun `provider substitutes indexed sequential repeated nested and percent arguments`() {
        val component = Component.translatable(
            "log.kleispack.player_updated",
            Component.literal("Sophed"),
            Component.translatable("adv.kleispack.light_campfire")
        )
        assertEquals(
            "Light a Campfire: Sophed / Sophed / Light a Campfire / %",
            serializer.serialize(component.toAdventure(RegistryAccess.EMPTY))
        )
    }

    @Test
    fun `provider handles Adventure numeric and boolean arguments through the native codec`() {
        assertEquals(
            "7 / true / 3.5",
            serializer.serialize(Component.translatable("log.test.primitives", 7, true, 3.5).toAdventure(RegistryAccess.EMPTY))
        )
    }

    @Test
    fun `native formatting agrees with Minecraft for valid and invalid templates`() {
        for (template in listOf("%s %s", "%2\$s %1\$s", "%% %s", "%s %1\$s %s", "%3\$s", "%0\$s", "%q", "bad %", "%999999999999\$s")) {
            val vanilla = Component.translatableWithFallback("test.format", template, "one", "two")
            assertEquals(
                vanilla.string,
                Component.translatable("test.format", "one", "two")
                    .withResourcePackTranslations(mapOf("test.format" to template)).string,
                template
            )
        }
        assertEquals(
            "7 / true / 3.5",
            Component.translatable("test.format", 7, true, 3.5)
                .withResourcePackTranslations(mapOf("test.format" to "%d / %s / %3\$.2f")).string
        )
    }

    @Test
    fun `children are emitted once and markdown escaping stays with the serializer`() {
        val component = Component.translatable("adv.kleispack.light_campfire")
            .append(Component.literal(" *once*"))
        assertEquals(
            "Light a Campfire \\*once\\*",
            serializer.serialize(component.toAdventure(RegistryAccess.EMPTY))
        )
    }

    @Test
    fun `resolution preserves styles without mutating input or global vanilla translations`() {
        val original = Component.translatable("adv.kleispack.light_campfire").withStyle(ChatFormatting.BOLD)
            .append(Component.literal(" child"))
        val translated = original.withResourcePackTranslations(translations)
        assertTrue(translated.style.isBold)
        assertEquals("Light a Campfire child", translated.string)
        assertEquals("adv.kleispack.light_campfire child", original.string)
        assertSame(original, original.withResourcePackTranslations(emptyMap()))
        val vanilla = Component.translatable("advancements.story.mine_stone.title")
        assertEquals("Custom", vanilla.withResourcePackTranslations(mapOf("advancements.story.mine_stone.title" to "Custom")).string)
        assertEquals("Stone Age", vanilla.string)
    }

    // The production conversion helpers share a file initializer with Fabric's
    // placeholder parser. Use the same codecs here without launching Fabric.
    private fun Component.toAdventure(registries: HolderLookup.Provider): net.kyori.adventure.text.Component =
        GsonComponentSerializer.gson().deserialize(
            ComponentSerialization.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), this)
                .getOrThrow().toString()
        )

    private fun net.kyori.adventure.text.Component.toNative(registries: HolderLookup.Provider): Component =
        ComponentSerialization.CODEC.parse(
            registries.createSerializationContext(JsonOps.INSTANCE),
            JsonParser.parseString(GsonComponentSerializer.gson().serialize(this))
        ).getOrThrow()

    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrapMinecraft() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
