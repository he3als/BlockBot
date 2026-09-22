package io.github.quiltservertools.blockbotdiscord.translation

import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.contents.TranslatableContents

/**
 * Resolve pack keys, including arguments inside vanilla translations, without
 * changing Minecraft's global Language or mutating the original component.
 */
internal fun Component.withResourcePackTranslations(translations: Map<String, String>): Component {
    if (translations.isEmpty()) return this
    val contents = contents
    val result = if (contents is TranslatableContents) {
        val args = contents.args.map {
            if (it is Component) it.withResourcePackTranslations(translations) else it
        }.toTypedArray()
        val template = translations[contents.key]
        if (template == null) {
            // Leave missing keys (and their explicit fallback) to the server.
            MutableComponent.create(TranslatableContents(contents.key, contents.fallback, args))
        } else {
            formatTranslation(template, args)
        }
    } else {
        plainCopy()
    }
    result.style = style
    siblings.forEach { result.append(it.withResourcePackTranslations(translations)) }
    return result
}

private val formatToken = Regex("%(?:(\\d+)\\$)?([A-Za-z%]|$)")
private val numericToken = Regex("%(\\d+\\$)?[\\d.]*[df]")

private fun formatTranslation(value: String, args: Array<out Any?>): MutableComponent {
    // Language.loadFromJson applies the same numeric-format normalization.
    val template = numericToken.replace(value) { "%${it.groupValues[1]}s" }
    val result = Component.empty()
    var end = 0
    var nextArgument = 0
    try {
        for (token in formatToken.findAll(template)) {
            val literal = template.substring(end, token.range.first)
            require('%' !in literal)
            result.append(literal)
            when {
                token.value == "%%" -> result.append("%")
                token.groupValues[2] == "s" -> {
                    val index = token.groupValues[1].takeIf { it.isNotEmpty() }?.let { it.toInt() - 1 }
                        ?: nextArgument++
                    require(index in args.indices)
                    val argument = args[index]
                    result.append(if (argument is Component) argument else Component.literal(argument.toString()))
                }
                else -> throw IllegalArgumentException("Invalid translation format")
            }
            end = token.range.last + 1
        }
        val remaining = template.substring(end)
        require('%' !in remaining)
        result.append(remaining)
        return result
    } catch (_: IllegalArgumentException) {
        // Match Minecraft's graceful fallback for invalid formats/missing args.
        return Component.literal(template)
    }
}
