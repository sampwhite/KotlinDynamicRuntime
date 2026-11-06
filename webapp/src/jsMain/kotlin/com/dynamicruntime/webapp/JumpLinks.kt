package com.dynamicruntime.webapp

/**
 * A Markdown link, not an image (no `!` before it): its text, then its target, the `(...)` after the `[...]` up to
 * whitespace (a title may follow) or the close. Every `]` is escaped, since a browser reads the pattern in Unicode
 * mode, where a bare one is a syntax error.
 */
private val markdownLink = Regex("""(?<!!)\[([^\]]*)\]\(\s*([^)\s]*)[^)]*\)""")

/** A line that starts a list item: `-`, `*`, or `+` then a space, or `1.` then a space, after any indent. */
private val listItemStart = Regex("""^\s*(?:[-*+]|\d+[.)])\s+""")

/** What may stand between the links of a row written on one line: spaces and a separator such as `·` or `|`. */
private const val separatorChars = " \t·•|,;/–—-"

/**
 * The home page's jump links (issue #1576), from the copy's `home.jumpLinks` ([source]), keeping only what leads
 * somewhere on this page for this caller. [drawn] is the ids of the sections the page draws ([publicHomeSections]).
 *
 * - A list item whose every link is an in-page anchor (`#id`) for a section not in [drawn] is dropped, along with
 *   the lines continuing it and any items nested under it. So the copy names every section once, and no caller is
 *   offered a link to nothing.
 * - Anywhere else -- a row written on one line, or an item that also links elsewhere -- such a link is removed when a
 *   separator or the line's end follows it, along with that separator, and is otherwise left as its words with no
 *   link, so a sentence still reads.
 * - Everything else is kept as written: a link to a document (`/docs/...`), to a page (`/wa/...`), or outside the
 *   site, and any prose.
 * - Null when no link is left (an image is not one), or [source] is blank, so no empty strip is drawn. That is how a
 *   client's copy removes the row: it sets the key to nothing.
 *
 * Pure, covered under `jsNodeTest`.
 */
fun jumpLinksFor(source: String?, drawn: Set<String>): String? {
    if (source.isNullOrBlank()) return null
    val kept = mutableListOf<String>()
    // The indent of a dropped list item while the lines below it are its own (more indented); null otherwise.
    var droppedIndent: Int? = null
    for (line in source.lines()) {
        val indent = line.length - line.trimStart().length
        val dropped = droppedIndent
        if (dropped != null && !line.isBlank()) {
            if (indent > dropped) continue
            droppedIndent = null
        }
        val marker = listItemStart.find(line)
        if (marker != null && leadsNowhere(line, drawn)) {
            droppedIndent = indent
            continue
        }
        val prefix = marker?.value ?: line.substring(0, indent)
        val body = withoutHiddenLinks(line.substring(prefix.length), drawn)
        // A line that held only links to sections not drawn goes; one that was blank stays, as paragraph breaks.
        if (body.isBlank() && line.isNotBlank()) continue
        kept.add(prefix + body)
    }
    return kept.joinToString("\n").takeIf { markdownLink.containsMatchIn(it) }
}

/** Whether [target] is an in-page anchor for a section not in [drawn]. */
private fun hiddenAnchor(target: String, drawn: Set<String>): Boolean = target.startsWith("#") && target.substring(1) !in drawn

/** Whether every link in [line] is an anchor to a section not drawn -- and it has at least one. */
private fun leadsNowhere(line: String, drawn: Set<String>): Boolean {
    val targets = markdownLink.findAll(line).map { it.groupValues[2] }.toList()
    return targets.isNotEmpty() && targets.all { hiddenAnchor(it, drawn) }
}

/** Whether [text] is only spaces and separators: what stands between links in a row, or nothing at all. */
private fun isSeparator(text: String): Boolean = text.all { it in separatorChars }

/**
 * [body] (a line after its list marker or indent) with each link to a section not drawn taken out. Followed by a
 * separator or the line's end, it is removed with the separator, so a row `[A](#a) · [B](#b)` reads `[B](#b)`;
 * followed by words, it is left as its words, so a sentence still reads. A trailing separator left behind goes too.
 */
private fun withoutHiddenLinks(body: String, drawn: Set<String>): String {
    val links = markdownLink.findAll(body).toList()
    if (links.none { hiddenAnchor(it.groupValues[2], drawn) }) return body
    val out = StringBuilder()
    // The text since the last link written out, not yet written itself.
    var pending = body.substring(0, links[0].range.first)
    for ((i, link) in links.withIndex()) {
        val after = body.substring(link.range.last + 1, links.getOrNull(i + 1)?.range?.first ?: body.length)
        when {
            !hiddenAnchor(link.groupValues[2], drawn) -> {
                out.append(pending).append(link.value)
                pending = after
            }
            // Followed by a separator or the line's end: removed, and what was before it carries on to the next link.
            isSeparator(after) -> Unit
            // Followed by words: left as its own words, so the sentence still reads.
            else -> pending += link.groupValues[1] + after
        }
    }
    if (!isSeparator(pending)) out.append(pending)
    return out.toString()
}

/**
 * The copy's jump links ([source]) with links to cards added (issue #1636), each `label` to the card's element id,
 * written in the copy's own style: as list items after a list, or after a row written on one line, separated as `·`.
 * With no copy, the links alone, as a list. A label's brackets are escaped, so a card's words cannot break the link.
 * A card the copy already links to (`(#home.card.register)`) is not added again (issue #1652), so the copy can place a
 * card's link where it wants it -- first, say. The result goes through [jumpLinksFor] like the copy's own, with the
 * cards' ids among what is drawn. Pure, covered under `jsNodeTest`.
 */
fun withCardJumpLinks(source: String?, cardLinks: List<Pair<String, String>>): String? {
    val links = cardLinks.filterNot { (_, id) -> source?.contains("(#$id)") == true }
    if (links.isEmpty()) return source
    fun link(label: String, id: String) = "[" + label.replace("[", "\\[").replace("]", "\\]") + "](#$id)"
    val asItems = links.joinToString("\n") { (label, id) -> "- " + link(label, id) }
    if (source.isNullOrBlank()) return asItems
    val lines = source.trimEnd().lines()
    return if (listItemStart.containsMatchIn(lines.last())) {
        lines.joinToString("\n") + "\n" + asItems
    } else {
        lines.joinToString("\n") + links.joinToString("") { (label, id) -> " · " + link(label, id) }
    }
}
