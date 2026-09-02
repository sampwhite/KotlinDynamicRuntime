package com.dynamicruntime.common.util

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.KdrException

/**
 * Renders Markdown to HTML. Pure, transpile-safe Kotlin (no `java.*`, no reflection) in the kernel, so the
 * Kotlin/JS frontend and the JVM backend render identically -- the frontend needs this for both halves of the
 * content story: the Markdown *values* inside a fragment file (see [parseMarkdownFragments]) and whole
 * Markdown *documents* served as pages.
 *
 * ## Supported
 * ATX headings (`#`..`######`), paragraphs, fenced code blocks (``` ```), flat bullet (`-`/`*`/`+`) and
 * ordered (`1.`) lists, blockquotes (`>`), horizontal rules (`---`/`***`/`___`), GitHub-style pipe tables (a
 * header row, a `| --- |` delimiter row, then body rows -- alignment taken from the delimiter's colons), and
 * the inline constructs: code spans (`` `x` ``), links (`[text](url)`), bold (`**x**`/`__x__`), and italic
 * (`*x*`/`_x_`).
 *
 * Deliberately **not** supported (add when the copy needs it): nested lists, reference links, images, setext
 * headings, and raw inline HTML -- raw HTML is escaped rather than passed through, so a fragment or document
 * can never inject markup.
 *
 * ## Safety
 * All text is HTML-escaped, and link URLs are restricted to http/https/mailto or a relative path
 * (see [safeUrl]) -- a `javascript:` URL renders inert. Content today is our own resources, but it is served
 * to a browser, so it is treated as untrusted.
 *
 * ## Link resolution
 * [resolveUrl] is an optional hook, applied to each link's raw target *before* [safeUrl] (so it can never
 * reintroduce an unsafe scheme). When null, a link's URL is used as written -- the default, and what fragment
 * copy and other in-app Markdown want. A *document* served to the frontend passes a resolver (see
 * [resolveDocLink]) that rewrites the file's repo-relative interior links to an in-app document or the source
 * repository, since a relative href written for a Git checkout points nowhere from inside the app (issue #492).
 */
fun String.renderMarkdown(resolveUrl: ((String) -> String)? = null): String {
    val lines = this.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val sb = StringBuilder()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        i = when {
            isBlankLine(line) -> i + 1
            fenceMarker(line) != null -> appendFencedCode(sb, lines, i)
            headingLevel(line) > 0 -> appendHeading(sb, line, i, resolveUrl)
            isHorizontalRule(line) -> appendHr(sb, i)
            bulletContent(line) != null -> appendList(sb, lines, i, ordered = false, resolveUrl)
            orderedContent(line) != null -> appendList(sb, lines, i, ordered = true, resolveUrl)
            isQuoteLine(line) -> appendQuote(sb, lines, i, resolveUrl)
            isTableAt(lines, i) -> appendTable(sb, lines, i, resolveUrl)
            else -> appendParagraph(sb, lines, i, resolveUrl)
        }
    }
    return sb.toString()
}

/**
 * Renders only the **inline** constructs of [this] -- code spans, links, bold, italic -- with no surrounding
 * block element. The counterpart of [renderMarkdown] for a *phrase* that already sits inside markup the caller
 * owns: a line of copy dropped into an existing paragraph, label, or menu item, where a `<p>` (let alone a
 * `<div>`) would be wrong or invalid.
 *
 * Same safety as [renderMarkdown] -- it shares the renderer -- so all text is escaped and link URLs are
 * restricted. Block syntax is not interpreted: a leading `#` or `-` is simply text.
 */
fun String.renderMarkdownInline(resolveUrl: ((String) -> String)? = null): String = renderInline(this, 0, resolveUrl)

// --- block constructs -------------------------------------------------------------------------------------

/** Whether [line] starts a block that terminates a running paragraph. */
@KdrPrivate
fun startsBlock(line: String): Boolean =
    isBlankLine(line) || fenceMarker(line) != null || headingLevel(line) > 0 || isHorizontalRule(line) ||
        bulletContent(line) != null || orderedContent(line) != null || isQuoteLine(line)

/** The backtick/tilde run opening a fenced code block, or null when [line] is not a fence. */
@KdrPrivate
fun fenceMarker(line: String): String? {
    val t = line.trimStart()
    return when {
        t.startsWith("```") -> "```"
        t.startsWith("~~~") -> "~~~"
        else -> null
    }
}

/** Emits a fenced code block (verbatim, escaped, no inline processing); returns the resume index. */
@KdrPrivate
fun appendFencedCode(sb: StringBuilder, lines: List<String>, start: Int): Int {
    val marker = fenceMarker(lines[start]) ?: return start + 1
    // The text after the fence is the info string; its first word is the language.
    val language = lines[start].trimStart().removePrefix(marker).trim().substringBefore(' ')
    val body = mutableListOf<String>()
    var i = start + 1
    while (i < lines.size && fenceMarker(lines[i]) != marker) {
        body.add(lines[i])
        i++
    }
    sb.append("<pre><code")
    if (language.isNotEmpty()) {
        sb.append(" class=\"language-").append(escapeHtml(language)).append('"')
    }
    sb.append('>').append(escapeHtml(body.joinToString("\n"))).append("</code></pre>\n")
    // Skip the closing fence when there is one; an unterminated block simply ends at the last line.
    return if (i < lines.size) i + 1 else i
}

/** The ATX heading level of [line] (1..6), or 0 when it is not a heading. */
@KdrPrivate
fun headingLevel(line: String): Int {
    var n = 0
    while (n < line.length && line[n] == '#') {
        n++
    }
    // A heading is 1..6 hashes followed by a space (`#foo` is ordinary text).
    return if (n in 1..6 && n < line.length && line[n] == ' ') n else 0
}

@KdrPrivate
fun appendHeading(sb: StringBuilder, line: String, index: Int, resolveUrl: ((String) -> String)? = null): Int {
    val level = headingLevel(line)
    // Trailing hashes are a closing sequence in ATX headings; drop them.
    val text = line.substring(level).trim().trimEnd('#').trim()
    // An id per heading so a same-document `#anchor` link (a doc's table of contents) has something to target.
    // The slug matches the anchors an authored document already carries, since those were minted from the same
    // headings (issue #492).
    val slug = headingSlug(text)
    sb.append("<h").append(level)
    if (slug.isNotEmpty()) {
        sb.append(" id=\"").append(escapeHtml(slug)).append('"')
    }
    sb.append('>')
        .append(renderInline(text, 0, resolveUrl))
        .append("</h").append(level).append(">\n")
    return index + 1
}

/**
 * A heading's anchor slug, matching the GitHub scheme documents are authored against: lower-cased, every
 * character that is not a letter, digit, hyphen, or underscore dropped (Markdown emphasis/code markers with
 * them), and spaces turned to hyphens. So `## Validation happens` -> `validation-happens`, which lines up with
 * the `#validation-happens` link a table of contents already carries. A heading with unusual inline markup can
 * slug imperfectly; the cost is a link that scrolls nowhere, never one that misbehaves.
 */
@KdrPrivate
fun headingSlug(text: String): String {
    val sb = StringBuilder(text.length)
    for (c in text.lowercase()) {
        when {
            c.isLetterOrDigit() || c == '-' || c == '_' -> sb.append(c)
            c == ' ' -> sb.append('-')
            // else: punctuation and inline-markup characters are dropped
        }
    }
    return sb.toString()
}

/** Whether [line] is a horizontal rule: three or more `-`, `*`, or `_` and nothing else. */
@KdrPrivate
fun isHorizontalRule(line: String): Boolean {
    val t = line.trim()
    if (t.length < 3) {
        return false
    }
    val c = t[0]
    return (c == '-' || c == '*' || c == '_') && t.all { it == c }
}

@KdrPrivate
fun appendHr(sb: StringBuilder, index: Int): Int {
    sb.append("<hr/>\n")
    return index + 1
}

/** The content of a bullet-list item (`- x`/`* x`/`+ x`), or null when [line] is not one. */
@KdrPrivate
fun bulletContent(line: String): String? {
    val t = line.trimStart()
    if (t.length < 2 || t[1] != ' ') {
        return null
    }
    val c = t[0]
    // A `* * *` rule also starts with "* "; rules win.
    return if ((c == '-' || c == '*' || c == '+') && !isHorizontalRule(line)) t.substring(2).trim() else null
}

/** The content of an ordered-list item (`1. x`), or null when [line] is not one. */
@KdrPrivate
fun orderedContent(line: String): String? {
    val t = line.trimStart()
    val dot = t.indexOf('.')
    if (dot <= 0 || dot + 1 >= t.length || t[dot + 1] != ' ') {
        return null
    }
    val digits = t.substring(0, dot)
    return if (digits.all { it.isDigit() }) t.substring(dot + 2).trim() else null
}

/** Emits a flat list of consecutive items (nesting is not supported); returns the resume index. */
@KdrPrivate
fun appendList(sb: StringBuilder, lines: List<String>, start: Int, ordered: Boolean, resolveUrl: ((String) -> String)? = null): Int {
    val tag = if (ordered) "ol" else "ul"
    sb.append('<').append(tag).append(">\n")
    var i = start
    while (i < lines.size) {
        val content = if (ordered) orderedContent(lines[i]) else bulletContent(lines[i])
        if (content == null) {
            break
        }
        // An item's text may wrap onto following plain lines (a "lazy continuation").
        val parts = mutableListOf(content)
        var j = i + 1
        while (j < lines.size && !startsBlock(lines[j]) && !isTableAt(lines, j)) {
            parts.add(lines[j].trim())
            j++
        }
        sb.append("<li>").append(renderInline(parts.joinToString(" "), 0, resolveUrl)).append("</li>\n")
        i = j
    }
    sb.append("</").append(tag).append(">\n")
    return i
}

@KdrPrivate
fun isQuoteLine(line: String): Boolean = line.trimStart().startsWith(">")

/** Emits a blockquote from consecutive `>` lines; returns the resume index. */
@KdrPrivate
fun appendQuote(sb: StringBuilder, lines: List<String>, start: Int, resolveUrl: ((String) -> String)? = null): Int {
    val parts = mutableListOf<String>()
    var i = start
    while (i < lines.size && isQuoteLine(lines[i])) {
        parts.add(lines[i].trimStart().removePrefix(">").trim())
        i++
    }
    sb.append("<blockquote>").append(renderInline(parts.joinToString(" "), 0, resolveUrl)).append("</blockquote>\n")
    return i
}

/** Emits a paragraph: consecutive lines until a blank line or the start of another block. */
@KdrPrivate
fun appendParagraph(sb: StringBuilder, lines: List<String>, start: Int, resolveUrl: ((String) -> String)? = null): Int {
    val parts = mutableListOf(lines[start].trim())
    var i = start + 1
    while (i < lines.size && !startsBlock(lines[i]) && !isTableAt(lines, i)) {
        parts.add(lines[i].trim())
        i++
    }
    sb.append("<p>").append(renderInline(parts.joinToString(" "), 0, resolveUrl)).append("</p>\n")
    return i
}

// --- tables (GitHub-style pipe tables, issue #547) --------------------------------------------------------

/**
 * Whether a table begins at [i]: a header row (any non-blank line carrying a `|`) immediately followed by a
 * delimiter row ([isDelimiterRow]). The two-line requirement is the whole point -- a header row *without* a
 * following delimiter is ordinary prose, so a paragraph that merely contains a `|` is never mistaken for a
 * table. Needs the lookahead, which is why table detection lives here and not in the single-line [startsBlock];
 * the paragraph and list loops consult it directly so a table can still interrupt them.
 */
@KdrPrivate
fun isTableAt(lines: List<String>, i: Int): Boolean =
    i + 1 < lines.size && !isBlankLine(lines[i]) && lines[i].contains('|') && isDelimiterRow(lines[i + 1])

/**
 * Whether [line] is a table delimiter row: `|`-separated cells, each an optional leading colon, one or more
 * dashes, and an optional trailing colon (`---`, `:--`, `--:`, `:-:`). A `|` is required, which is what keeps a
 * bare `---`/`***` horizontal rule from reading as a one-column delimiter.
 */
@KdrPrivate
fun isDelimiterRow(line: String): Boolean {
    if (!line.contains('|')) {
        return false
    }
    val cells = splitTableRow(line)
    return cells.isNotEmpty() && cells.all { isDelimiterCell(it) }
}

@KdrPrivate
fun isDelimiterCell(cell: String): Boolean {
    val c = cell.trim()
    var start = 0
    var end = c.length
    if (end > start && c[start] == ':') start++
    if (end > start && c[end - 1] == ':') end--
    if (end <= start) {
        return false
    }
    for (k in start until end) {
        if (c[k] != '-') return false
    }
    return true
}

/**
 * Splits a table row into cell texts. One optional leading pipe and one optional *unescaped* trailing pipe are
 * dropped (leading/trailing pipes are optional in the grammar), the remaining unescaped `|` characters are the
 * separators, and `\|` becomes a literal `|` in a cell. Each cell is trimmed; other escapes are left for
 * [renderInline] to handle.
 */
@KdrPrivate
fun splitTableRow(line: String): List<String> {
    val t = line.trim()
    val from = if (t.startsWith("|")) 1 else 0
    val to = if (t.endsWith("|") && !t.endsWith("\\|")) t.length - 1 else t.length
    val inner = if (from <= to) t.substring(from, to) else ""
    val cells = mutableListOf<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < inner.length) {
        val c = inner[i]
        when {
            c == '\\' && i + 1 < inner.length && inner[i + 1] == '|' -> {
                cur.append('|'); i += 2
            }
            c == '|' -> {
                cells.add(cur.toString().trim()); cur.clear(); i++
            }
            else -> {
                cur.append(c); i++
            }
        }
    }
    cells.add(cur.toString().trim())
    return cells
}

/** The CSS text-align for a delimiter cell's colons: `:-:` center, `--:` right, `:--` left, plain `---` none. */
@KdrPrivate
fun cellAlign(delimiterCell: String): String? {
    val c = delimiterCell.trim()
    val left = c.startsWith(":")
    val right = c.endsWith(":")
    return when {
        left && right -> "center"
        right -> "right"
        left -> "left"
        else -> null
    }
}

/**
 * Emits a table from the header row at [start], the delimiter row at `start + 1`, and the body rows that follow
 * (consecutive lines carrying a `|`, stopping at a blank line or another block). The header decides the column
 * count; a body row with fewer cells is padded and one with more is truncated, so a ragged row renders rather
 * than throwing. Column alignment comes from the delimiter and is applied to every cell in the column. Wrapped
 * in an overflow-x box so a wide table scrolls inside the page rather than pushing it sideways. Returns the
 * resume index.
 */
@KdrPrivate
fun appendTable(sb: StringBuilder, lines: List<String>, start: Int, resolveUrl: ((String) -> String)? = null): Int {
    val headers = splitTableRow(lines[start])
    val aligns = splitTableRow(lines[start + 1]).map { cellAlign(it) }
    val cols = headers.size
    sb.append("<div class=\"md-table-scroll\">\n<table>\n<thead>\n<tr>")
    for (c in 0 until cols) {
        appendTableCell(sb, "th", headers[c], aligns.getOrNull(c), resolveUrl)
    }
    sb.append("</tr>\n</thead>\n")
    val body = StringBuilder()
    var i = start + 2
    while (i < lines.size && lines[i].contains('|') && !startsBlock(lines[i])) {
        val cells = splitTableRow(lines[i])
        body.append("<tr>")
        for (c in 0 until cols) {
            appendTableCell(body, "td", cells.getOrElse(c) { "" }, aligns.getOrNull(c), resolveUrl)
        }
        body.append("</tr>\n")
        i++
    }
    if (body.isNotEmpty()) {
        sb.append("<tbody>\n").append(body).append("</tbody>\n")
    }
    sb.append("</table>\n</div>\n")
    return i
}

/** Emits one `<th>`/`<td>` with optional alignment; the cell text goes through [renderInline] so links, code
 *  spans and emphasis work inside a cell and everything else is escaped. */
@KdrPrivate
fun appendTableCell(sb: StringBuilder, tag: String, raw: String, align: String?, resolveUrl: ((String) -> String)?) {
    sb.append('<').append(tag)
    if (align != null) {
        sb.append(" style=\"text-align:").append(align).append('"')
    }
    sb.append('>').append(renderInline(raw, 0, resolveUrl)).append("</").append(tag).append('>')
}

// --- inline constructs ------------------------------------------------------------------------------------

/** Guard on inline nesting (emphasis inside links inside emphasis ...) -- Markdown is external data, so the
 *  recursion carries an explicit depth and fails rather than running away. */
private const val maxInlineDepth = 20

/**
 * Renders the inline constructs of [text] to HTML, escaping everything else. Code spans are resolved first, so
 * a `*` inside `` `code` `` is never emphasis. [depth] bounds the nesting of links/emphasis.
 */
@KdrPrivate
fun renderInline(text: String, depth: Int, resolveUrl: ((String) -> String)? = null): String {
    if (depth > maxInlineDepth) {
        throw KdrException.mkConv("Markdown inline nesting exceeded $maxInlineDepth levels.")
    }
    val sb = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val consumed = when (c) {
            '`' -> appendCodeSpan(sb, text, i)
            '[' -> appendLink(sb, text, i, depth, resolveUrl)
            '*', '_' -> appendEmphasis(sb, text, i, depth, resolveUrl)
            else -> 0
        }
        if (consumed > 0) {
            i += consumed
        } else {
            sb.append(escapeHtml(c.toString()))
            i++
        }
    }
    return sb.toString()
}

/** Emits a `` `code` `` span; returns the characters consumed, or 0 when [start] opens no closed span. */
@KdrPrivate
fun appendCodeSpan(sb: StringBuilder, text: String, start: Int): Int {
    val end = text.indexOf('`', start + 1)
    if (end < 0) {
        return 0
    }
    sb.append("<code>").append(escapeHtml(text.substring(start + 1, end))).append("</code>")
    return end - start + 1
}

/** Emits a `[label](url)` link; returns the characters consumed, or 0 when [start] opens no complete link. */
@KdrPrivate
fun appendLink(sb: StringBuilder, text: String, start: Int, depth: Int, resolveUrl: ((String) -> String)? = null): Int {
    val close = text.indexOf(']', start + 1)
    if (close < 0 || close + 1 >= text.length || text[close + 1] != '(') {
        return 0
    }
    val paren = text.indexOf(')', close + 2)
    if (paren < 0) {
        return 0
    }
    // A link title (`[t](url "title")`) is accepted and dropped; only the URL is used.
    val url = text.substring(close + 2, paren).trim().substringBefore(' ')
    // The resolver (if any) rewrites the target for where this is *rendered*; safeUrl still guards the result,
    // so a resolver can never turn a link into an executable scheme.
    val resolved = resolveUrl?.invoke(url) ?: url
    sb.append("<a href=\"").append(escapeHtml(safeUrl(resolved))).append("\">")
        .append(renderInline(text.substring(start + 1, close), depth + 1, resolveUrl))
        .append("</a>")
    return paren - start + 1
}

/**
 * Emits `**bold**`/`__bold__` or `*italic*`/`_italic_`; returns the characters consumed, or 0 when [start]
 * opens no closed run. An `_` run must start at a word boundary, so `snake_case_names` stays literal.
 */
@KdrPrivate
fun appendEmphasis(sb: StringBuilder, text: String, start: Int, depth: Int, resolveUrl: ((String) -> String)? = null): Int {
    val c = text[start]
    if (c == '_' && start > 0 && isWordChar(text[start - 1])) {
        return 0 // intra word underscore: not emphasis
    }
    val double = start + 1 < text.length && text[start + 1] == c
    val marker = if (double) "$c$c" else "$c"
    val from = start + marker.length
    if (from >= text.length) {
        return 0
    }
    val end = text.indexOf(marker, from)
    if (end <= from) {
        return 0 // no closing run, or an empty run (`**`)
    }
    if (c == '_' && end + marker.length < text.length && isWordChar(text[end + marker.length])) {
        return 0 // closing underscore is intra word
    }
    val tag = if (double) "strong" else "em"
    sb.append('<').append(tag).append('>')
        .append(renderInline(text.substring(from, end), depth + 1, resolveUrl))
        .append("</").append(tag).append('>')
    return end + marker.length - start
}

@KdrPrivate
fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

/**
 * A link URL restricted to schemes that cannot execute a script: http, https, and mailto, plus relative paths
 * and same-page fragments. Anything else (notably `javascript:`) becomes an inert empty target rather than
 * being dropped, so the link text still renders.
 */
@KdrPrivate
fun safeUrl(url: String): String {
    val u = url.trim()
    if (u.isEmpty()) {
        return ""
    }
    val colon = u.indexOf(':')
    val slash = u.indexOf('/')
    // No scheme (no colon before the first slash) => relative or fragment; allow it.
    if (colon < 0 || (slash in 0 until colon)) {
        return u
    }
    val scheme = u.substring(0, colon).lowercase()
    return if (scheme == "http" || scheme == "https" || scheme == "mailto") u else ""
}

/** Escapes the characters that could otherwise close or open markup in element text or an attribute value. */
@KdrPrivate
fun escapeHtml(text: String): String {
    val sb = StringBuilder(text.length)
    for (c in text) {
        when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}
