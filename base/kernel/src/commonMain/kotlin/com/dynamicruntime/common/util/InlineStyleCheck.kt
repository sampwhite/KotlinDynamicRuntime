package com.dynamicruntime.common.util

/**
 * What is wrong with [style], an inline style a client writes for its mails (issue #1800), or null when nothing is:
 * the body's, a panel's, and the accent and muted roles'. A style is free text that goes into a `style` attribute,
 * escaped, so it can never close the attribute; what it could still do is load something or hide something. So a style
 * is held to declarations (`property: value`, separated by `;`, which a quoted value may hold) that do neither:
 *
 * - **Nothing loads.** No function but a color's (`rgb`, `rgba`, `hsl`, `hsla`), which refuses `url(`, `expression(`,
 *   `image-set(`, and the rest; and no `behavior` or `-moz-binding`.
 * - **Nothing hides or moves.** No `display`, `position`, `visibility`, `opacity`, `clip`, or `clip-path`; no size of
 *   zero for the font (in `font-size` or the `font` shorthand), the line, or the height; no `transparent` text; and no
 *   negative `text-indent`, which pushes text out of sight.
 * - **Nothing is obscured.** No backslash (a CSS escape could spell a function this refuses), no comment, and none of
 *   `<`, `>`, `{`, `}`, or `@`.
 *
 * An empty style is no problem: it sets nothing. The message names the declaration, for a person fixing it. Not every
 * way to hide something is caught (a color the same as its background, a huge negative margin), so this stops the
 * plain cases, not a determined client, whose mails are its own.
 */
fun inlineStyleProblem(style: String): String? {
    if (style.isBlank()) return null
    for (c in obscuring) {
        if (c in style) return "The style uses '$c', which a mail's style may not hold."
    }
    if ("/*" in style) return "The style holds a comment, which a mail's style may not hold."
    for (raw in declarations(style)) {
        val declaration = raw.trim()
        if (declaration.isEmpty()) continue
        val colon = declaration.indexOf(':')
        val property = if (colon > 0) declaration.substring(0, colon).trim().lowercase() else ""
        if (property.isEmpty() || !property.all { it in 'a'..'z' || it == '-' }) {
            return "'$declaration' is not a declaration, a property and its value: 'color: #1f3b2d'."
        }
        if (property in refusedProperties) {
            return "'$declaration' sets '$property', which a mail's style may not set, since it can hide or move what " +
                "the mail shows."
        }
        val value = declaration.substring(colon + 1).trim().lowercase()
        for (match in functionCall.findAll(value)) {
            val name = match.groupValues[1]
            if (name !in allowedFunctions) {
                return "'$declaration' calls '$name(', and a mail's style may call only a color's function " +
                    "(${allowedFunctions.joinToString(", ")})."
            }
        }
        val hides = when (property) {
            in zeroSizedProperties -> zeroSize.matches(value)
            "font" -> zeroSize.matches(value.split(' ', '/').first())
            "color" -> value == "transparent"
            "text-indent" -> value.startsWith("-")
            else -> false
        }
        if (hides) return "'$declaration' hides the text it styles."
    }
    return null
}

/** [style] cut at each `;` that is not inside a quoted value, as CSS reads it. */
private fun declarations(style: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var quote: Char? = null
    for (c in style) {
        when {
            quote != null -> { if (c == quote) quote = null; sb.append(c) }
            c == '"' || c == '\'' -> { quote = c; sb.append(c) }
            c == ';' -> { out.add(sb.toString()); sb.clear() }
            else -> sb.append(c)
        }
    }
    out.add(sb.toString())
    return out
}

private const val obscuring = "\\<>{}@"

private val refusedProperties = setOf("display", "position", "visibility", "opacity", "clip", "clip-path", "behavior", "-moz-binding")

/** The properties that hide their text at a size of zero. */
private val zeroSizedProperties = setOf("font-size", "line-height", "height", "max-height")

private val allowedFunctions = listOf("rgb", "rgba", "hsl", "hsla")

private val functionCall = Regex("([a-z-]*)\\s*\\(")

/** A size of zero in any unit, `0`, `0px`, `.0em`, and not a keyword such as `small`. */
private val zeroSize = Regex("(0+\\.?0*|\\.0+)\\s*[a-z%]*")
