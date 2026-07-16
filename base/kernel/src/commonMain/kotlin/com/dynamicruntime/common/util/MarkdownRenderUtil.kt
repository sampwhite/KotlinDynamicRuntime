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
 * ordered (`1.`) lists, blockquotes (`>`), horizontal rules (`---`/`***`/`___`), and the inline constructs:
 * code spans (`` `x` ``), links (`[text](url)`), bold (`**x**`/`__x__`), and italic (`*x*`/`_x_`).
 *
 * Deliberately **not** supported (add when the copy needs it): tables, nested lists, reference links, images,
 * setext headings, and raw inline HTML -- raw HTML is escaped rather than passed through, so a fragment or
 * document can never inject markup.
 *
 * ## Safety
 * All text is HTML-escaped, and link URLs are restricted to http/https/mailto or a relative path
 * (see [safeUrl]) -- a `javascript:` URL renders inert. Content today is our own resources, but it is served
 * to a browser, so it is treated as untrusted.
 */
fun String.renderMarkdown(): String {
    val lines = this.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val sb = StringBuilder()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        i = when {
            isBlankLine(line) -> i + 1
            fenceMarker(line) != null -> appendFencedCode(sb, lines, i)
            headingLevel(line) > 0 -> appendHeading(sb, line, i)
            isHorizontalRule(line) -> appendHr(sb, i)
            bulletContent(line) != null -> appendList(sb, lines, i, ordered = false)
            orderedContent(line) != null -> appendList(sb, lines, i, ordered = true)
            isQuoteLine(line) -> appendQuote(sb, lines, i)
            else -> appendParagraph(sb, lines, i)
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
fun String.renderMarkdownInline(): String = renderInline(this, 0)

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
fun appendHeading(sb: StringBuilder, line: String, index: Int): Int {
    val level = headingLevel(line)
    // Trailing hashes are a closing sequence in ATX headings; drop them.
    val text = line.substring(level).trim().trimEnd('#').trim()
    sb.append("<h").append(level).append('>')
        .append(renderInline(text, 0))
        .append("</h").append(level).append(">\n")
    return index + 1
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
fun appendList(sb: StringBuilder, lines: List<String>, start: Int, ordered: Boolean): Int {
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
        while (j < lines.size && !startsBlock(lines[j])) {
            parts.add(lines[j].trim())
            j++
        }
        sb.append("<li>").append(renderInline(parts.joinToString(" "), 0)).append("</li>\n")
        i = j
    }
    sb.append("</").append(tag).append(">\n")
    return i
}

@KdrPrivate
fun isQuoteLine(line: String): Boolean = line.trimStart().startsWith(">")

/** Emits a blockquote from consecutive `>` lines; returns the resume index. */
@KdrPrivate
fun appendQuote(sb: StringBuilder, lines: List<String>, start: Int): Int {
    val parts = mutableListOf<String>()
    var i = start
    while (i < lines.size && isQuoteLine(lines[i])) {
        parts.add(lines[i].trimStart().removePrefix(">").trim())
        i++
    }
    sb.append("<blockquote>").append(renderInline(parts.joinToString(" "), 0)).append("</blockquote>\n")
    return i
}

/** Emits a paragraph: consecutive lines until a blank line or the start of another block. */
@KdrPrivate
fun appendParagraph(sb: StringBuilder, lines: List<String>, start: Int): Int {
    val parts = mutableListOf(lines[start].trim())
    var i = start + 1
    while (i < lines.size && !startsBlock(lines[i])) {
        parts.add(lines[i].trim())
        i++
    }
    sb.append("<p>").append(renderInline(parts.joinToString(" "), 0)).append("</p>\n")
    return i
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
fun renderInline(text: String, depth: Int): String {
    if (depth > maxInlineDepth) {
        throw KdrException.mkConv("Markdown inline nesting exceeded $maxInlineDepth levels.")
    }
    val sb = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val consumed = when (c) {
            '`' -> appendCodeSpan(sb, text, i)
            '[' -> appendLink(sb, text, i, depth)
            '*', '_' -> appendEmphasis(sb, text, i, depth)
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
fun appendLink(sb: StringBuilder, text: String, start: Int, depth: Int): Int {
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
    sb.append("<a href=\"").append(escapeHtml(safeUrl(url))).append("\">")
        .append(renderInline(text.substring(start + 1, close), depth + 1))
        .append("</a>")
    return paren - start + 1
}

/**
 * Emits `**bold**`/`__bold__` or `*italic*`/`_italic_`; returns the characters consumed, or 0 when [start]
 * opens no closed run. An `_` run must start at a word boundary, so `snake_case_names` stays literal.
 */
@KdrPrivate
fun appendEmphasis(sb: StringBuilder, text: String, start: Int, depth: Int): Int {
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
        .append(renderInline(text.substring(from, end), depth + 1))
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
