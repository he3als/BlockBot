package io.github.quiltservertools.blockbotdiscord.translation

import dev.kord.rest.builder.message.EmbedBuilder
import dev.vankka.mcdiscordreserializer.discord.DiscordSerializer
import net.kyori.adventure.text.Component

/** Emoji render in embed descriptions, but not in author names or footers. */
internal suspend fun advancementEmbed(
    title: String,
    details: String,
    avatar: String,
    render: suspend (String) -> String,
): EmbedBuilder {
    val titleHasGlyphs = title.codePoints().anyMatch(ResourcePackGlyphs::isPrivateUse)
    val detailsHaveGlyphs = details.codePoints().anyMatch(ResourcePackGlyphs::isPrivateUse)
    return EmbedBuilder().apply {
        if (titleHasGlyphs) {
            description = render(escapeMarkdown(title) + "\n" + escapeMarkdown(details))
            thumbnail { url = avatar }
        } else {
            author {
                name = title
                icon = avatar
            }
            if (detailsHaveGlyphs) description = render(escapeMarkdown(details))
            else footer { text = details }
        }
    }
}

private val plainTextSerializer = DiscordSerializer()
private fun escapeMarkdown(text: String): String = plainTextSerializer.serialize(Component.text(text))
