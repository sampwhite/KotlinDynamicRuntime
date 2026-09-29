package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException

/**
 * A string field's JSON Schema `pattern`, compiled so it means the same on the JVM and in the browser (issue #823).
 *
 * JSON Schema specifies the ECMA-262 (JavaScript) regex dialect. The validator runs on the JVM, whose
 * `java.util.regex` is a different dialect, and in the browser, where Kotlin/JS compiles with the `u` flag -- the
 * strict one, which refuses much that Java reads as a literal. The same `pattern` read by each engine as it stands
 * would accept different values on the two sides, or compile on the backend and throw in the frontend's parse of
 * the served schema, taking the whole form down.
 *
 * So a pattern is translated into the part of the syntax both engines read alike, with ECMA-262's meaning where they
 * differ:
 *
 * - `$` is the end of the value. Java's `$` also matches just before a final line break, so `^[a-z]+$` would pass
 *   `"abc\n"` on the backend and fail it in the browser; it becomes `(?![\s\S])`, which is the end in both.
 * - `.` is any character but a line terminator (`\n`, `\r`, U+2028, U+2029). Java also excludes U+0085.
 * - `\s` / `\S` are ECMA-262's whitespace, which is Unicode-wide (a no-break space counts); Java's is ASCII only.
 *   Both are spelled out as an explicit class.
 * - A `\` before punctuation that is not regex syntax (`\@`, `\-` outside a class) is a literal in Java and an
 *   error under `u`, so it is written as the character itself.
 * - A lone `]` or `}` is a literal in Java and an error under `u`, so it is escaped.
 *
 * And what cannot be made to agree is **refused**, naming the construct: Java-only syntax (possessive quantifiers,
 * atomic groups, inline flags such as `(?i)`, `\A` / `\Z` / `\z` / `\G` / `\Q…\E`, class union and intersection,
 * POSIX and Java property names), escapes the two engines read differently (`\v`, `\0`, `\b` or `\S` inside a
 * class), a `{` that is not a quantifier, and an empty class. `\p{…}` is accepted with a Unicode general category
 * (`L`, `Lu`, `Nd`, …), which both engines name alike. `\d`, `\w` and `\b` are ASCII in both (Java's `\b` since
 * JDK 19). There are no flags: case-insensitivity is written as an alternation, `(?:A|a)`.
 *
 * A match is **unanchored**, as JSON Schema specifies: `pattern: "[0-9]"` accepts any value containing a digit.
 * Anchor with `^…$` to constrain the whole value.
 */
class SchPattern private constructor(
    /** The pattern as the schema declared it -- what a message or an export shows. */
    val source: String,
    private val regex: Regex,
) {
    /** Whether [value] contains a match, as JSON Schema's `pattern` asks. */
    fun matches(value: String): Boolean = regex.containsMatchIn(value)

    @Suppress("ConstPropertyName")
    companion object {
        /**
         * [source] compiled, or a [KdrException] saying what in it is not portable or not valid, prefixed by
         * [where] (a type or property, for the message).
         */
        fun compile(where: String, source: String): SchPattern {
            val translated = try {
                translate(source)
            } catch (e: PatternProblem) {
                throw KdrException.mkConv("$where has '${SCH.pattern}' '$source', which ${e.message}")
            }
            val regex = try {
                Regex(translated)
            } catch (e: Throwable) {
                // Throwable, not Exception: under Kotlin/JS the engine's SyntaxError is a native JS error, which
                // is not a Kotlin Exception.
                throw KdrException.mkConv("$where has '${SCH.pattern}' '$source', which is not a valid pattern: ${e.message}")
            }
            return SchPattern(source, regex)
        }

        /** ECMA-262's `\s`, as the body of a class -- the same set on both engines once written out. */
        private const val whitespace =
            """\t\n\u000b\f\r \u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"""

        /** ECMA-262's `.`: anything but a line terminator. */
        private const val anyButLineTerminator = """[^\n\r\u2028\u2029]"""

        /** The end of the value, and nothing else (Java's `$` also matches before a final line break). */
        private const val endOfValue = """(?![\s\S])"""

        /** Punctuation that is regex syntax, so escaping it means the literal character in both engines. */
        private const val syntaxChars = """^$\.*+?()[]{}|/"""

        /** The Unicode general categories, which `\p{…}` names the same way in both engines. */
        private val generalCategories = setOf(
            "L", "Lu", "Ll", "Lt", "Lm", "Lo", "M", "Mn", "Mc", "Me", "N", "Nd", "Nl", "No",
            "P", "Pc", "Pd", "Ps", "Pe", "Pi", "Pf", "Po", "S", "Sm", "Sc", "Sk", "So",
            "Z", "Zs", "Zl", "Zp", "C", "Cc", "Cf", "Cs", "Co", "Cn",
        )

        /** What sits between the braces of a `{n}` / `{n,}` / `{n,m}` quantifier. */
        private val quantifierBody = Regex("""\d+(,\d*)?""")

        private class PatternProblem(message: String) : Exception(message)

        private fun refuse(message: String): Nothing = throw PatternProblem(message)

        /** [source] rewritten into the syntax both engines read alike; see the class note for what changes. */
        private fun translate(source: String): String {
            val out = StringBuilder(source.length + 16)
            var i = 0
            var inClass = false
            // Where a class's content starts, just past `[` or `[^` -- a `]` there would close an empty class.
            var classStart = -1
            val n = source.length

            fun hexDigits(from: Int, count: Int): Boolean =
                from + count <= n && (from until from + count).all { source[it].isHexDigit() }

            /** After a quantifier: a lazy `?` is kept; a possessive `+` is Java's alone. */
            fun afterQuantifier() {
                if (i < n && source[i] == '?') {
                    out.append('?')
                    i++
                } else if (i < n && source[i] == '+') {
                    refuse("uses a possessive quantifier ('+' after a quantifier), which only Java has.")
                }
            }

            while (i < n) {
                val c = source[i]
                if (c == '\\') {
                    if (i + 1 >= n) refuse("ends with a lone '\\'.")
                    val e = source[i + 1]
                    i += 2
                    when {
                        e in "dDwWnrtf" -> out.append('\\').append(e)
                        e == 's' -> out.append(if (inClass) whitespace else "[$whitespace]")
                        e == 'S' -> {
                            if (inClass) refuse("uses '\\S' inside a class, which cannot be written the same way for both engines.")
                            out.append("[^$whitespace]")
                        }
                        e == 'b' || e == 'B' -> {
                            if (inClass) refuse("uses '\\$e' inside a class, which the two engines read differently.")
                            out.append('\\').append(e)
                        }
                        e in '1'..'9' -> {
                            if (inClass) refuse("uses a backreference inside a class.")
                            if (i < n && source[i].isDigit()) refuse("uses a backreference of more than one digit.")
                            out.append('\\').append(e)
                        }
                        e == 'x' -> {
                            if (!hexDigits(i, 2)) refuse("has a '\\x' escape without two hex digits.")
                            out.append("\\x").appendRange(source, i, i + 2)
                            i += 2
                        }
                        e == 'u' -> {
                            if (!hexDigits(i, 4)) refuse("has a '\\u' escape without four hex digits.")
                            out.append("\\u").appendRange(source, i, i + 4)
                            i += 4
                        }
                        e == 'c' -> {
                            if (i >= n || source[i] !in 'A'..'Z' && source[i] !in 'a'..'z') {
                                refuse("has a '\\c' escape without a letter.")
                            }
                            out.append("\\c").append(source[i])
                            i++
                        }
                        e == 'p' || e == 'P' -> {
                            val close = source.indexOf('}', i)
                            val name = if (i < n && source[i] == '{' && close > i) source.substring(i + 1, close) else null
                            if (name == null || name !in generalCategories) {
                                refuse(
                                    "uses '\\$e' with ${name?.let { "'$it'" } ?: "no name"}; only a Unicode general " +
                                        "category (such as L, Lu or Nd) is named the same way by both engines.",
                                )
                            }
                            out.append('\\').append(e).append('{').append(name).append('}')
                            i = close + 1
                        }
                        e == 'k' -> {
                            val close = source.indexOf('>', i)
                            if (inClass || i >= n || source[i] != '<' || close < 0) refuse("has a malformed '\\k' escape.")
                            out.append("\\k").appendRange(source, i, close + 1)
                            i = close + 1
                        }
                        e.isLetterOrDigit() -> refuse("uses '\\$e', which the two engines do not read alike.")
                        e == '-' -> if (inClass) out.append("\\-") else out.append('-')
                        e in syntaxChars -> out.append('\\').append(e)
                        // Any other escaped character is a literal in Java and an error under `u`. Written as a
                        // `\u` escape rather than bare, so `\&\&` inside a class does not become Java's `&&`.
                        e.isSurrogate() -> out.append(e)
                        else -> out.append("\\u").append(e.code.toString(16).padStart(4, '0'))
                    }
                    continue
                }
                if (inClass) {
                    when (c) {
                        ']' -> {
                            if (i == classStart) refuse("has an empty class, or one that opens with ']'; escape it as '\\]'.")
                            inClass = false
                        }

                        '[' -> refuse("has '[' inside a class, which Java reads as a union; escape it as '\\['.")
                        '&' if i + 1 < n && source[i + 1] == '&' ->
                            refuse("has '&&' inside a class, which Java reads as an intersection.")
                    }
                    out.append(c)
                    i++
                    continue
                }
                i++
                when (c) {
                    '[' -> {
                        out.append('[')
                        if (i < n && source[i] == '^') {
                            out.append('^')
                            i++
                        }
                        inClass = true
                        classStart = i
                    }
                    '(' -> {
                        out.append('(')
                        if (i < n && source[i] == '?') {
                            val rest = source.substring(i)
                            val prefix = listOf("?:", "?=", "?!", "?<=", "?<!").firstOrNull { rest.startsWith(it) }
                            when {
                                prefix != null -> {
                                    out.append(prefix)
                                    i += prefix.length
                                }
                                // A named group, `(?<name>`: copied through its closing `>`.
                                rest.length > 2 && rest[1] == '<' && rest[2].isLetter() && '>' in rest -> {
                                    val close = source.indexOf('>', i)
                                    out.appendRange(source, i, close + 1)
                                    i = close + 1
                                }
                                else -> refuse(
                                    "opens a '(?' group that only Java has -- an inline flag such as '(?i)', or an " +
                                        "atomic group.",
                                )
                            }
                        }
                    }
                    '$' -> out.append(endOfValue)
                    '.' -> out.append(anyButLineTerminator)
                    '*', '+', '?' -> {
                        out.append(c)
                        afterQuantifier()
                    }
                    '{' -> {
                        val close = source.indexOf('}', i)
                        val body = if (close > 0) source.substring(i, close) else ""
                        if (!quantifierBody.matches(body)) {
                            refuse("has a '{' that is not a quantifier such as '{2}' or '{1,3}'; escape it as '\\{'.")
                        }
                        out.append('{').append(body).append('}')
                        i = close + 1
                        afterQuantifier()
                    }
                    '}', ']' -> out.append('\\').append(c)
                    else -> out.append(c)
                }
            }
            if (inClass) refuse("has a class that is never closed.")
            return out.toString()
        }

        private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    }
}
