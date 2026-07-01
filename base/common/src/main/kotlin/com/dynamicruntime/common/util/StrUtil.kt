package com.dynamicruntime.common.util

fun String.splitComma(): List<String> {
    if (this.isBlank()) return emptyList()
    return this.split(",").map {it.trim()}
}

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
