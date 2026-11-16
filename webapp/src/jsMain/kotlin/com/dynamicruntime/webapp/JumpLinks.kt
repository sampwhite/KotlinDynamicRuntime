package com.dynamicruntime.webapp

/**
 * A Markdown link, not an image (no `!` before it): its text, which may hold escaped characters (`\[`, `\]`) as a label
 * written by [jumpRowLinks] does, then its target, the `(...)` after the `[...]` up to whitespace (a title may follow)
 * or the close. Every `]` is escaped, since a browser reads the pattern in Unicode mode, where a bare one is a syntax
 * error.
 */
private val markdownLink = Regex("""(?<!!)\[((?:\\.|[^\]\\])*)\]\(\s*([^)\s]*)[^)]*\)""")

/** A line that starts a list item: `-`, `*`, or `+` then a space, or `1.` then a space, after any indent. */
private val listItemStart = Regex("""^\s*(?:[-*+]|\d+[.)])\s+""")

/**
 * The jump row's links (issues #1576, #1636, #1794, #1795): the row's [copy], drawn as written -- what it links to, it
 * names on purpose -- then [links] to the page's drawn parts, each `label` to the part's element id, written in the
 * copy's own style: as list items after a list, or after a row written on one line, separated as `·`. With no copy, the
 * links alone, as a list. A label's brackets are escaped, so a part's words cannot break the link. A part the copy
 * already links to (`(#home.card.assistant)`) is not added again (issue #1652), so the copy can place a part's link
 * where it wants it -- first, say. Null when the row holds no link (an image is not one), so no empty strip is drawn.
 * Pure, covered under `jsNodeTest`.
 */
fun jumpRowLinks(copy: String?, links: List<Pair<String, String>>): String? {
    val added = links.filterNot { (_, id) -> copy?.contains("(#$id)") == true }
    fun link(label: String, id: String) = "[" + label.replace("[", "\\[").replace("]", "\\]") + "](#$id)"
    val asItems = added.joinToString("\n") { (label, id) -> "- " + link(label, id) }
    val lines = copy?.trimEnd()?.lines().orEmpty()
    val row = when {
        added.isEmpty() -> copy
        copy.isNullOrBlank() -> asItems
        listItemStart.containsMatchIn(lines.last()) -> lines.joinToString("\n") + "\n" + asItems
        else -> lines.joinToString("\n") + added.joinToString("") { (label, id) -> " · " + link(label, id) }
    }
    return row?.takeIf { markdownLink.containsMatchIn(it) }
}
