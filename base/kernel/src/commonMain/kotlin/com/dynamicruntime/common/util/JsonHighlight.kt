package com.dynamicruntime.common.util

/**
 * The class names a highlighted JSON block's runs are wrapped in (issue #1227). A stylesheet colors them; the HTML
 * carries the classification itself, so a page is highlighted wherever its HTML goes, not only where a script runs
 * over it afterward.
 */
@Suppress("ConstPropertyName")
object MdTok {
    const val key = "md-tok-key"
    const val string = "md-tok-string"
    const val number = "md-tok-number"
    const val literal = "md-tok-literal"
    const val punct = "md-tok-punct"
}

/**
 * [body], a JSON text, as HTML with each run wrapped in a span of its [MdTok] class (issue #1227): a string that is
 * followed by a colon is a key, any other string a string; a number; `true`, `false`, and `null`; and the
 * punctuation. Whitespace passes through as it is, so the layout the author chose is kept exactly, and every run
 * is HTML-escaped as plain text would be.
 *
 * This is a lexer, not a parser: it classifies runs and never checks that they add up to a document, so a block
 * with a trailing comma or an unbalanced brace is still highlighted. What it refuses is text that is not JSON at
 * the level of tokens -- an unterminated string, a comment, a bare word -- for which it returns null, and the
 * caller falls back to the plain rendering. That keeps a mislabelled fence readable rather than half-colored.
 */
fun highlightJson(body: String): String? {
    val sb = StringBuilder(body.length + body.length / 4)
    var i = 0
    val n = body.length
    fun wrap(cls: String, start: Int, end: Int) {
        sb.append("<span class=\"").append(cls).append("\">").append(escapeHtml(body.substring(start, end))).append("</span>")
    }
    while (i < n) {
        val c = body[i]
        when {
            c == ' ' || c == '\n' || c == '\r' || c == '\t' -> {
                val start = i
                while (i < n && (body[i] == ' ' || body[i] == '\n' || body[i] == '\r' || body[i] == '\t')) i++
                sb.append(body, start, i)
            }
            c == '"' -> {
                val start = i
                i++
                while (i < n && body[i] != '"') {
                    if (body[i] == '\\') i++
                    if (body[i] == '\n') return null
                    i++
                }
                if (i >= n) return null
                i++
                // A key is a string that a colon follows, whitespace allowed between.
                var j = i
                while (j < n && (body[j] == ' ' || body[j] == '\t')) j++
                wrap(if (j < n && body[j] == ':') MdTok.key else MdTok.string, start, i)
            }
            c == '{' || c == '}' || c == '[' || c == ']' || c == ':' || c == ',' -> {
                wrap(MdTok.punct, i, i + 1)
                i++
            }
            c == '-' || c in '0'..'9' -> {
                val start = i
                i++
                while (i < n && (body[i] in '0'..'9' || body[i] == '.' || body[i] == 'e' || body[i] == 'E' || body[i] == '+' || body[i] == '-')) i++
                if (i == start + 1 && c == '-') return null
                wrap(MdTok.number, start, i)
            }
            c.isLetter() -> {
                val start = i
                while (i < n && body[i].isLetter()) i++
                val word = body.substring(start, i)
                if (word != "true" && word != "false" && word != "null") return null
                wrap(MdTok.literal, start, i)
            }
            else -> return null
        }
    }
    return sb.toString()
}
