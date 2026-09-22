package io.github.quiltservertools.blockbotdiscord.translation

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.contents.PlainTextContents
import net.minecraft.network.chat.contents.TranslatableContents
import java.util.Optional

/** One message's colour variants, captured before Discord serialization discards text colours. */
internal class ResourcePackEmojiText(
    private val translations: Map<String, String>,
    private val originals: Map<Int, String>,
    private val coloured: Boolean,
    private val warn: (String) -> Unit,
) {
    private val variants = mutableMapOf<Pair<Int, Int>, Int>()
    private val images = originals.toMutableMap()
    val glyphs: Map<Int, String> get() = images
    private var nextAlias = 0x100000
    private var warned = false

    fun translate(component: Component): String = if (coloured) prepare(component).string
        else component.withResourcePackTranslations(translations).string

    fun prepare(component: Component): Component = if (coloured) prepare(component, Style.EMPTY) else component

    private fun prepare(component: Component, inherited: Style): Component {
        val effective = component.style.applyTo(inherited)
        val contents = component.contents
        val result = when (contents) {
            is PlainTextContents -> Component.literal(encode(contents.text(), effective))
            is TranslatableContents -> {
                // Match the serializer's fully substituted plain-text provider,
                // but visit styled arguments first to retain each glyph's colour.
                val resolved = component.plainCopy().withResourcePackTranslations(translations)
                val text = StringBuilder()
                resolved.visit(FormattedText.StyledContentConsumer<Unit> { style, value ->
                    text.append(encode(value, style))
                    Optional.empty()
                }, effective)
                Component.literal(text.toString())
            }
            else -> component.plainCopy()
        }
        result.style = component.style
        component.siblings.forEach { result.append(prepare(it, effective)) }
        return result
    }

    private fun encode(text: String, style: Style): String = buildString {
        text.codePoints().forEach { point ->
            if (!ResourcePackGlyphs.isPrivateUse(point)) {
                appendCodePoint(point)
                return@forEach
            }
            val original = originals[point] ?: return@forEach
            val colour = style.color?.value ?: 0xffffff
            if (colour == 0xffffff || original.isEmpty()) {
                appendCodePoint(point)
                return@forEach
            }
            val key = point to colour
            var alias = variants[key]
            if (alias == null && variants.size < 128) {
                try {
                    val tinted = ResourcePackGlyphs.tint(original, colour)
                    // Temporary private-use aliases survive Markdown escaping.
                    // Never collide with any actual glyph defined in the pack.
                    while (nextAlias in originals) nextAlias++
                    check(nextAlias <= 0x10fffd)
                    alias = nextAlias++
                    variants[key] = alias
                    images[alias] = tinted
                } catch (_: Exception) {
                    if (!warned) warn("Could not colour a resource-pack icon; using its original artwork")
                    warned = true
                }
            }
            appendCodePoint(alias ?: point)
        }
    }
}
