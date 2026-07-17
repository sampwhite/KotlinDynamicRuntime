package com.dynamicruntime.common.util

fun String.splitComma(): List<String> {
    if (this.isBlank()) return emptyList()
    return this.split(",").map {it.trim()}
}

/**
 * Whether this string is a legal variable name: non-empty, starting with a letter or underscore and
 * otherwise made of letters, digits, and underscores. Written from scratch (rather than porting dn's
 * JVM-only `isJavaName`) so it is KMP compatible -- `Char.isLetter`/`isLetterOrDigit` are common stdlib.
 */
fun String.isVariableName(): Boolean {
    if (isEmpty()) return false
    if (!(this[0].isLetter() || this[0] == '_')) return false
    return all { it.isLetterOrDigit() || it == '_' }
}

/**
 * Converts a camelCase identifier to lower_snake_case (e.g. `myField` -> `my_field`) by inserting an
 * underscore before each interior uppercase letter and lowercasing throughout. Used to turn code-side field
 * names into database column/table names when the target database does not preserve the case of the identifier.
 */
fun String.toLowerCaseIdentifier(): String {
    val sb = StringBuilder(length + 4)
    for ((i, ch) in this.withIndex()) {
        if (ch.isUpperCase()) {
            if (i > 0) sb.append('_')
            sb.append(ch.lowercaseChar())
        } else {
            sb.append(ch)
        }
    }
    return sb.toString()
}

/** Upper-cases the first character, leaving the rest unchanged (empty string passes through). */
fun String.capitalizeFirst(): String =
    if (isEmpty()) this else this[0].uppercaseChar() + substring(1)

/** Default cap for [sanitizeForDisplay]. */
const val defaultDisplayLen = 120

/**
 * Makes an untrusted string safe to display in the frontend -- including when the frontend renders it as
 * Markdown (issue #108). Used on user-supplied values substituted into error-message templates, so a value
 * like `[click](http://evil)` cannot become a link a user might trust.
 *
 * Three passes: (1) every run of whitespace (newlines and tabs included) collapses to a single space, trimmed
 * -- so a multi-line value becomes one tidy line rather than injecting breaks; (2) the characters that
 * structure Markdown links, images, autolinks, and code spans (`[ ] ( ) < > \``) are removed -- this keeps the
 * text but defuses the URL constructs; `*` and `_` are deliberately kept, since they only emphasize and are
 * common in real emails/usernames; (3) longer than [maxLen] is clipped with a trailing `…`.
 */
fun String.sanitizeForDisplay(maxLen: Int = defaultDisplayLen): String {
    val collapsed = trim().replace(whitespaceRun, " ").filterNot { it in markdownStructural }
    return if (collapsed.length <= maxLen) collapsed else collapsed.take(maxLen - 1).trimEnd() + "…"
}

private val whitespaceRun = Regex("\\s+")
private const val markdownStructural = "[]()<>`"

// Creating a format that defines options for both byte arrays and numeric values.
val customHexFormat = HexFormat {
    upperCase = true
}

fun String.encodeLiteral(): String  =
    StringBuilder(this.length + 10).appendLiteral(this).toString()

fun StringBuilder.appendLiteral(text: String): StringBuilder {
    for (ch in text) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\u000c' -> append("\\f")
            '\t' -> append("\\t")
            else -> {
                //Reference: http://www.unicode.org/versions/Unicode5.1.0/
                if ((ch <= '\u001F') || (ch in '\u007F'..'\u009F') || (ch in '\u2000'..'\u20FF')) {
                    append("\\u")
                    append(ch.toUpperHex())
                } else {
                    append(ch)
                }
            }
        }
    }
    return this
}

/** Formats to four characters of uppercase hex */
fun Char.toUpperHex(): String =
    this.code.toShort().toHexString(customHexFormat)
