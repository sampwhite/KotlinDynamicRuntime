package com.dynamicruntime.webapp

/**
 * A Markdown link's target: the `(...)` after a `[...]`, up to whitespace (a title may follow) or the close. Every `]`
 * is escaped, since a browser reads the pattern in Unicode mode, where a bare one is a syntax error.
 */
private val markdownLinkTarget = Regex("""\[[^\]]*\]\(\s*([^)\s]*)[^)]*\)""")

/** A line that starts a list item: `-`, `*`, or `+` then a space, or `1.` then a space, after any indent. */
private val listItemStart = Regex("""^\s*(?:[-*+]|\d+[.)])\s+""")

/**
 * The home page's jump links (issue #1576), from the copy's `home.jumpLinks` ([source]), keeping only what leads
 * somewhere on this page for this caller. [drawn] is the ids of the sections the page draws ([publicHomeSections]).
 *
 * - A list item whose every link is an in-page anchor (`#id`) for a section not in [drawn] is dropped, along with
 *   the lines continuing it. So the copy names every section once, and no caller is offered a link to nothing.
 * - Every other line is kept as written: a link to a document (`/docs/...`), to a page (`/wa/...`), or outside the
 *   site, and any prose.
 * - Null when no link is left, or [source] is blank, so no empty strip is drawn. That is how a client's copy removes
 *   the row: it sets the key to nothing.
 *
 * Pure, covered under `jsNodeTest`.
 */
fun jumpLinksFor(source: String?, drawn: Set<String>): String? {
    if (source.isNullOrBlank()) return null
    val kept = mutableListOf<String>()
    var dropping = false
    for (line in source.lines()) {
        if (listItemStart.containsMatchIn(line)) {
            dropping = leadsNowhere(line, drawn)
        } else if (line.isBlank() || !line.first().isWhitespace()) {
            // A blank line or unindented prose ends the item before it; an indented line continues it.
            dropping = false
        }
        if (!dropping) kept.add(line)
    }
    val result = kept.joinToString("\n")
    return result.takeIf { markdownLinkTarget.containsMatchIn(it) }
}

/** Whether every link in [line] is an anchor to a section not drawn -- and it has at least one. */
private fun leadsNowhere(line: String, drawn: Set<String>): Boolean {
    val targets = markdownLinkTarget.findAll(line).map { it.groupValues[1] }.toList()
    return targets.isNotEmpty() && targets.all { it.startsWith("#") && it.substring(1) !in drawn }
}
