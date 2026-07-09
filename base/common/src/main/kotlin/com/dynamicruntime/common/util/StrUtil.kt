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
