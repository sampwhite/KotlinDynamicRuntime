package com.dynamicruntime.common.util

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.KdrException

/**
 * What a surface tells the renderer (issue #795). The renderer knows the syntax; the surface knows what a link
 * points at from where it is rendered, and what a role looks like there.
 */
class MarkdownHooks(
    /**
     * Applied to each link's and image's raw target *before* [safeUrl], so it can never reintroduce an unsafe
     * scheme. Null uses a target as written -- the default, and what fragment copy wants; a document served to
     * the frontend passes a resolver that retargets its repo-relative links (issue #492).
     */
    val resolveUrl: ((String) -> String)? = null,
    /**
     * Realizes a bracketed span's roles (see [MDR]) around its already-rendered inner HTML, or returns null for the
     * default: `<span class="md-role">…</span>`, which the frontend styles by class. A mail passes a hook that
     * emits inline styles instead, since mail clients strip stylesheets. The hook receives rendered, escaped
     * HTML and must return HTML; it never sees raw text, so the escaping rule that protects every fragment
     * holds through it.
     */
    val decorateSpan: ((roles: List<String>, innerHtml: String) -> String?)? = null,
)

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
 * (`*x*`/`_x_`) -- and, past standard Markdown, two Pandoc-style **attributed** constructs (issue #795): a
 * bracketed span `[text]{.role}`, and an image `![alt](src){.role width=240}`. See [parseAttrBlock] for what an
 * attribute block may hold, and [MarkdownHooks.decorateSpan] for how a surface realizes a role. The same source
 * renders to plain text with [renderMarkdownText].
 *
 * Deliberately **not** supported (add when the copy needs it): nested lists, reference links, setext headings,
 * and raw inline HTML -- raw HTML is escaped rather than passed through, so a fragment or document can never
 * inject markup.
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
fun String.renderMarkdown(resolveUrl: ((String) -> String)? = null): String = renderMarkdown(MarkdownHooks(resolveUrl))

/**
 * [renderMarkdown] with every hook (issue #795): [MarkdownHooks.resolveUrl] for link and image targets, and
 * [MarkdownHooks.decorateSpan] for what a role means on this surface.
 */
fun String.renderMarkdown(hooks: MarkdownHooks): String {
    val lines = this.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val sb = StringBuilder()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        i = when {
            isBlankLine(line) -> i + 1
            fenceMarker(line) != null -> appendFencedCode(sb, lines, i)
            headingLevel(line) > 0 -> appendHeading(sb, line, i, hooks)
            isHorizontalRule(line) -> appendHr(sb, i)
            bulletContent(line) != null -> appendList(sb, lines, i, ordered = false, hooks)
            orderedContent(line) != null -> appendList(sb, lines, i, ordered = true, hooks)
            isQuoteLine(line) -> appendQuote(sb, lines, i, hooks)
            isTableAt(lines, i) -> appendTable(sb, lines, i, hooks)
            else -> appendParagraph(sb, lines, i, hooks)
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
fun String.renderMarkdownInline(resolveUrl: ((String) -> String)? = null): String = renderInline(this, 0, MarkdownHooks(resolveUrl))

/** [renderMarkdownInline] with every hook (issue #795). */
fun String.renderMarkdownInline(hooks: MarkdownHooks): String = renderInline(this, 0, hooks)

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
fun appendHeading(sb: StringBuilder, line: String, index: Int, hooks: MarkdownHooks): Int {
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
        .append(renderInline(text, 0, hooks))
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
fun appendList(sb: StringBuilder, lines: List<String>, start: Int, ordered: Boolean, hooks: MarkdownHooks): Int {
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
        sb.append("<li>").append(renderInline(parts.joinToString(" "), 0, hooks)).append("</li>\n")
        i = j
    }
    sb.append("</").append(tag).append(">\n")
    return i
}

@KdrPrivate
fun isQuoteLine(line: String): Boolean = line.trimStart().startsWith(">")

/** Emits a blockquote from consecutive `>` lines; returns the resume index. */
@KdrPrivate
fun appendQuote(sb: StringBuilder, lines: List<String>, start: Int, hooks: MarkdownHooks): Int {
    val parts = mutableListOf<String>()
    var i = start
    while (i < lines.size && isQuoteLine(lines[i])) {
        parts.add(lines[i].trimStart().removePrefix(">").trim())
        i++
    }
    sb.append("<blockquote>").append(renderInline(parts.joinToString(" "), 0, hooks)).append("</blockquote>\n")
    return i
}

/** Emits a paragraph: consecutive lines until a blank line or the start of another block. */
@KdrPrivate
fun appendParagraph(sb: StringBuilder, lines: List<String>, start: Int, hooks: MarkdownHooks): Int {
    val parts = mutableListOf(lines[start].trim())
    var i = start + 1
    while (i < lines.size && !startsBlock(lines[i]) && !isTableAt(lines, i)) {
        parts.add(lines[i].trim())
        i++
    }
    sb.append("<p>").append(renderInline(parts.joinToString(" "), 0, hooks)).append("</p>\n")
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
fun appendTable(sb: StringBuilder, lines: List<String>, start: Int, hooks: MarkdownHooks): Int {
    val headers = splitTableRow(lines[start])
    val aligns = splitTableRow(lines[start + 1]).map { cellAlign(it) }
    val cols = headers.size
    sb.append("<div class=\"md-table-scroll\">\n<table>\n<thead>\n<tr>")
    for (c in 0 until cols) {
        appendTableCell(sb, "th", headers[c], aligns.getOrNull(c), hooks)
    }
    sb.append("</tr>\n</thead>\n")
    val body = StringBuilder()
    var i = start + 2
    while (i < lines.size && lines[i].contains('|') && !startsBlock(lines[i])) {
        val cells = splitTableRow(lines[i])
        body.append("<tr>")
        for (c in 0 until cols) {
            appendTableCell(body, "td", cells.getOrElse(c) { "" }, aligns.getOrNull(c), hooks)
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
fun appendTableCell(sb: StringBuilder, tag: String, raw: String, align: String?, hooks: MarkdownHooks) {
    sb.append('<').append(tag)
    if (align != null) {
        sb.append(" style=\"text-align:").append(align).append('"')
    }
    sb.append('>').append(renderInline(raw, 0, hooks)).append("</").append(tag).append('>')
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
fun renderInline(text: String, depth: Int, hooks: MarkdownHooks): String {
    if (depth > maxInlineDepth) {
        throw KdrException.mkConv("Markdown inline nesting exceeded $maxInlineDepth levels.")
    }
    val sb = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val consumed = when (c) {
            '`' -> appendCodeSpan(sb, text, i)
            '!' -> appendImage(sb, text, i, hooks)
            '[' -> appendLink(sb, text, i, depth, hooks).takeIf { it > 0 } ?: appendSpan(sb, text, i, depth, hooks)
            '*', '_' -> appendEmphasis(sb, text, i, depth, hooks)
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
fun appendLink(sb: StringBuilder, text: String, start: Int, depth: Int, hooks: MarkdownHooks): Int {
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
    val resolved = hooks.resolveUrl?.invoke(url) ?: url
    sb.append("<a href=\"").append(escapeHtml(safeUrl(resolved))).append("\">")
        .append(renderInline(text.substring(start + 1, close), depth + 1, hooks))
        .append("</a>")
    return paren - start + 1
}

/**
 * What an attribute block `{.role width=240}` holds (issue #795): roles, and the whitelisted numeric attributes.
 * Closed by construction -- see [parseAttrBlock].
 */
class MdAttrs(val roles: List<String>, val width: Int?, val height: Int?)

/**
 * Parses a Pandoc-style attribute block opening at [start] (`text[start] == '{'`): whitespace-separated tokens,
 * each a `.role` (a name matching `[A-Za-z][A-Za-z0-9-]*`) or `key=value` with [MDR.width] / [MDR.height] and a
 * non-negative integer. Returns the attributes and the block's length, or null when [start] opens no
 * well-formed block -- unclosed, spanning a line, empty, or holding a token of any other shape -- in which case
 * the caller renders the text as literal, like every other unrecognized construct.
 *
 * **Closed by construction.** Only role names and two numeric attributes ever come out, so nothing here can
 * carry a style, an event handler or raw markup into the output: a `{style=...}` is a malformed block and stays
 * text. Whitelisting the attribute *names* while dropping unknown ones would leave `{.role foo=bar}` valid; the
 * rule is stricter than that, since a misspelled `widht=` silently dropped is a placement that silently fails.
 */
@KdrPrivate
fun parseAttrBlock(text: String, start: Int): Pair<MdAttrs, Int>? {
    if (start >= text.length || text[start] != '{') return null
    val end = text.indexOf('}', start + 1)
    if (end < 0 || text.substring(start + 1, end).contains('\n')) return null
    val tokens = text.substring(start + 1, end).trim().split(' ', '\t').filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    val roles = mutableListOf<String>()
    var width: Int? = null
    var height: Int? = null
    for (token in tokens) {
        when {
            token.startsWith(".") && isRoleName(token.substring(1)) -> roles.add(token.substring(1))
            token.startsWith("${MDR.width}=") -> width = token.substringAfter('=').toIntOrNull()?.takeIf { it >= 0 } ?: return null
            token.startsWith("${MDR.height}=") -> height = token.substringAfter('=').toIntOrNull()?.takeIf { it >= 0 } ?: return null
            else -> return null
        }
    }
    return MdAttrs(roles, width, height) to (end - start + 1)
}

@KdrPrivate
fun isRoleName(name: String): Boolean =
    name.isNotEmpty() && name[0].isLetter() && name.all { it.isLetterOrDigit() || it == '-' }

/**
 * Emits a bracketed span `[text]{.role}` (issue #795); returns the characters consumed, or 0 when [start] opens
 * no span -- no closing bracket, or no well-formed attribute block right after it. Tried after [appendLink],
 * so `[text](url)` is a link and `[text]` alone is literal text, as before. A span with no role at all is
 * literal too: an empty decoration says nothing. Width and height on a span are accepted by the block and
 * dropped: they place an image, not a phrase.
 */
@KdrPrivate
fun appendSpan(sb: StringBuilder, text: String, start: Int, depth: Int, hooks: MarkdownHooks): Int {
    val close = text.indexOf(']', start + 1)
    if (close < 0 || close + 1 >= text.length || text[close + 1] != '{') return 0
    val (attrs, attrLength) = parseAttrBlock(text, close + 1) ?: return 0
    if (attrs.roles.isEmpty()) return 0
    val inner = renderInline(text.substring(start + 1, close), depth + 1, hooks)
    sb.append(hooks.decorateSpan?.invoke(attrs.roles, inner) ?: defaultSpan(attrs.roles, inner))
    return close + 1 + attrLength - start
}

/** The default rendering of a role: a class the frontend styles, prefixed so it never meets an app class. */
@KdrPrivate
fun defaultSpan(roles: List<String>, innerHtml: String): String =
    "<span class=\"" + roleClasses(roles) + "\">" + innerHtml + "</span>"

@KdrPrivate
fun roleClasses(roles: List<String>): String = roles.joinToString(" ") { MDR.classPrefix + escapeHtml(it) }

/**
 * Emits an image `![alt](src)`, with an optional attribute block `{.role width=240}` placing and sizing it
 * (issue #795); returns the characters consumed, or 0 when [start] opens no image. The source goes through
 * [MarkdownHooks.resolveUrl] then [safeUrl] as a link's target does; the alt text is escaped. Roles become
 * classes as a span's do, and width and height are emitted as the plain attributes they are -- numbers, so
 * nothing else can ride in on them.
 */
@KdrPrivate
fun appendImage(sb: StringBuilder, text: String, start: Int, hooks: MarkdownHooks): Int {
    if (start + 1 >= text.length || text[start + 1] != '[') return 0
    val close = text.indexOf(']', start + 2)
    if (close < 0 || close + 1 >= text.length || text[close + 1] != '(') return 0
    val paren = text.indexOf(')', close + 2)
    if (paren < 0) return 0
    val alt = text.substring(start + 2, close)
    val src = text.substring(close + 2, paren).trim().substringBefore(' ')
    val resolved = hooks.resolveUrl?.invoke(src) ?: src
    val attrs = parseAttrBlock(text, paren + 1)
    sb.append("<img src=\"").append(escapeHtml(safeUrl(resolved))).append("\" alt=\"").append(escapeHtml(alt)).append('"')
    attrs?.first?.let { a ->
        if (a.roles.isNotEmpty()) sb.append(" class=\"").append(roleClasses(a.roles)).append('"')
        a.width?.let { sb.append(" width=\"").append(it).append('"') }
        a.height?.let { sb.append(" height=\"").append(it).append('"') }
    }
    sb.append('>')
    return paren + 1 + (attrs?.second ?: 0) - start
}

/**
 * Emits `**bold**`/`__bold__` or `*italic*`/`_italic_`; returns the characters consumed, or 0 when [start]
 * opens no closed run. An `_` run must start at a word boundary, so `snake_case_names` stays literal.
 */
@KdrPrivate
fun appendEmphasis(sb: StringBuilder, text: String, start: Int, depth: Int, hooks: MarkdownHooks): Int {
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
        .append(renderInline(text.substring(from, end), depth + 1, hooks))
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

// --- plain text (issue #795) ------------------------------------------------------------------------------

/**
 * Renders Markdown to clean **plain text** -- the companion of [renderMarkdown], so one source gives a mail its
 * text part as well as its HTML part, and copy may carry emphasis and roles without either reaching a reader
 * as syntax.
 *
 * Block by block, as the HTML renderer walks them: a heading is its text on a line of its own; a paragraph's
 * wrapped lines join into one; a list is one `- item` (or `1. item`) per line; a quote is its lines, unmarked;
 * a fenced code block is its lines verbatim; a table is one row per line with cells joined by ` | `, the
 * delimiter row dropped; a horizontal rule is a blank line's worth of nothing. Blocks are separated by a blank
 * line. Inline: emphasis marks and role blocks are dropped (`[x]{.code}` is `x`), a code span is its text, a
 * link is `text (url)` -- or the bare URL when its text is the URL -- and an image is its alt text. Anything
 * unrecognized is kept as written, so a `${...}` block a fragment carries onward for the frontend passes
 * through untouched, as it does in the HTML renderer.
 */
fun String.renderMarkdownText(): String {
    val lines = this.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = mutableListOf<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        i = when {
            isBlankLine(line) -> i + 1
            fenceMarker(line) != null -> textFencedCode(blocks, lines, i)
            headingLevel(line) > 0 -> { blocks.add(renderInlineText(line.trim().trimStart('#').trim().trimEnd('#').trim(), 0)); i + 1 }
            isHorizontalRule(line) -> i + 1
            bulletContent(line) != null -> textList(blocks, lines, i, ordered = false)
            orderedContent(line) != null -> textList(blocks, lines, i, ordered = true)
            isQuoteLine(line) -> textQuote(blocks, lines, i)
            isTableAt(lines, i) -> textTable(blocks, lines, i)
            else -> textParagraph(blocks, lines, i)
        }
    }
    return blocks.joinToString("\n\n")
}

@KdrPrivate
fun textFencedCode(blocks: MutableList<String>, lines: List<String>, start: Int): Int {
    val marker = fenceMarker(lines[start]) ?: return start + 1
    val body = mutableListOf<String>()
    var i = start + 1
    while (i < lines.size && fenceMarker(lines[i]) != marker) {
        body.add(lines[i])
        i++
    }
    blocks.add(body.joinToString("\n"))
    return if (i < lines.size) i + 1 else i
}

@KdrPrivate
fun textList(blocks: MutableList<String>, lines: List<String>, start: Int, ordered: Boolean): Int {
    val items = mutableListOf<String>()
    var i = start
    while (i < lines.size) {
        val content = if (ordered) orderedContent(lines[i]) else bulletContent(lines[i])
        if (content == null) break
        val parts = mutableListOf(content)
        var j = i + 1
        while (j < lines.size && !startsBlock(lines[j]) && !isTableAt(lines, j)) {
            parts.add(lines[j].trim())
            j++
        }
        val text = renderInlineText(parts.joinToString(" "), 0)
        items.add(if (ordered) "${items.size + 1}. $text" else "- $text")
        i = j
    }
    blocks.add(items.joinToString("\n"))
    return i
}

@KdrPrivate
fun textQuote(blocks: MutableList<String>, lines: List<String>, start: Int): Int {
    val parts = mutableListOf<String>()
    var i = start
    while (i < lines.size && isQuoteLine(lines[i])) {
        parts.add(lines[i].trimStart().removePrefix(">").trim())
        i++
    }
    blocks.add(renderInlineText(parts.joinToString(" "), 0))
    return i
}

@KdrPrivate
fun textParagraph(blocks: MutableList<String>, lines: List<String>, start: Int): Int {
    val parts = mutableListOf(lines[start].trim())
    var i = start + 1
    while (i < lines.size && !startsBlock(lines[i]) && !isTableAt(lines, i)) {
        parts.add(lines[i].trim())
        i++
    }
    blocks.add(renderInlineText(parts.joinToString(" "), 0))
    return i
}

@KdrPrivate
fun textTable(blocks: MutableList<String>, lines: List<String>, start: Int): Int {
    val rows = mutableListOf(splitTableRow(lines[start]).joinToString(" | ") { renderInlineText(it, 0) })
    var i = start + 2
    while (i < lines.size && lines[i].contains('|') && !startsBlock(lines[i])) {
        rows.add(splitTableRow(lines[i]).joinToString(" | ") { renderInlineText(it, 0) })
        i++
    }
    blocks.add(rows.joinToString("\n"))
    return i
}

/** The inline constructs of [text] as plain text; see [renderMarkdownText]. [depth] bounds the nesting. */
@KdrPrivate
fun renderInlineText(text: String, depth: Int): String {
    if (depth > maxInlineDepth) {
        throw KdrException.mkConv("Markdown inline nesting exceeded $maxInlineDepth levels.")
    }
    val sb = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val consumed = when (c) {
            '`' -> textCodeSpan(sb, text, i)
            '!' -> textImage(sb, text, i)
            '[' -> textLink(sb, text, i, depth).takeIf { it > 0 } ?: textSpan(sb, text, i, depth)
            '*', '_' -> textEmphasis(sb, text, i, depth)
            else -> 0
        }
        if (consumed > 0) {
            i += consumed
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

@KdrPrivate
fun textCodeSpan(sb: StringBuilder, text: String, start: Int): Int {
    val end = text.indexOf('`', start + 1)
    if (end < 0) return 0
    sb.append(text, start + 1, end)
    return end - start + 1
}

/** A link as `text (url)`, or the bare URL when the text is the URL itself -- what a mail's `[url](url)` wants. */
@KdrPrivate
fun textLink(sb: StringBuilder, text: String, start: Int, depth: Int): Int {
    val close = text.indexOf(']', start + 1)
    if (close < 0 || close + 1 >= text.length || text[close + 1] != '(') return 0
    val paren = text.indexOf(')', close + 2)
    if (paren < 0) return 0
    val url = text.substring(close + 2, paren).trim().substringBefore(' ')
    val label = renderInlineText(text.substring(start + 1, close), depth + 1)
    sb.append(if (label == url || label.isEmpty()) url else "$label ($url)")
    return paren - start + 1
}

@KdrPrivate
fun textSpan(sb: StringBuilder, text: String, start: Int, depth: Int): Int {
    val close = text.indexOf(']', start + 1)
    if (close < 0 || close + 1 >= text.length || text[close + 1] != '{') return 0
    val (attrs, attrLength) = parseAttrBlock(text, close + 1) ?: return 0
    if (attrs.roles.isEmpty()) return 0
    sb.append(renderInlineText(text.substring(start + 1, close), depth + 1))
    return close + 1 + attrLength - start
}

@KdrPrivate
fun textImage(sb: StringBuilder, text: String, start: Int): Int {
    if (start + 1 >= text.length || text[start + 1] != '[') return 0
    val close = text.indexOf(']', start + 2)
    if (close < 0 || close + 1 >= text.length || text[close + 1] != '(') return 0
    val paren = text.indexOf(')', close + 2)
    if (paren < 0) return 0
    sb.append(text, start + 2, close)
    return paren + 1 + (parseAttrBlock(text, paren + 1)?.second ?: 0) - start
}

@KdrPrivate
fun textEmphasis(sb: StringBuilder, text: String, start: Int, depth: Int): Int {
    val c = text[start]
    if (c == '_' && start > 0 && isWordChar(text[start - 1])) return 0
    val double = start + 1 < text.length && text[start + 1] == c
    val marker = if (double) "$c$c" else "$c"
    val from = start + marker.length
    if (from >= text.length) return 0
    val end = text.indexOf(marker, from)
    if (end <= from) return 0
    if (c == '_' && end + marker.length < text.length && isWordChar(text[end + marker.length])) return 0
    sb.append(renderInlineText(text.substring(from, end), depth + 1))
    return end + marker.length - start
}
