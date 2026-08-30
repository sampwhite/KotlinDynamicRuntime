package com.dynamicruntime.common.util

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC

/**
 * Extracts named Markdown *fragments* from a fragment file into a two-tier map of
 * `namespace -> (key -> value)`. A fragment file is authored Markdown carrying small, individually
 * addressable snippets that the frontend renders per component (issue #59). The result pairs naturally with
 * [String.evalTemplate]: `${namespace.key}` resolves a fragment against this map.
 *
 * Like the other kernel parsers this is pure, transpile-safe Kotlin (no `java.*`), so backend and frontend
 * share one interpretation. Failures carry a [MarkdownError] code (under [KdrException.errorCodeKey]) so a UI
 * can explain them.
 *
 * ## Format
 * - An **empty line** is one who's every character is `<= ' '` (code point <= 32).
 * - **Comments** `/- ... -/` are stripped first, as if never present (they may span lines; the first `-/`
 *   closes). An unterminated `/-` is an error.
 * - A **namespace** is declared with `# @<namespace>` (`# ` = hash then a single space). It becomes the
 *   parent key for the keys that follow; re-declaring one shifts to a new namespace.
 * - A **key** is declared with `# +<key>`, in one of two variants:
 *   - *inline* -- when non-whitespace follows the key on the same line (`# +k the value`). The value is that
 *     text plus any following lines, terminated by **one** empty line or a `# ` line.
 *   - *next-line* -- when nothing but whitespace follows the key. The value is the following lines,
 *     terminated by **two** consecutive empty lines or a `# ` line (so single blank lines are kept, letting a
 *     value hold multiple Markdown paragraphs).
 * - A `# +key` before any namespace is an error. All values are trimmed. A line starting with `## ` (two or
 *   more hashes) is ordinary content -- only a single `# ` is a fragment delimiter.
 */
fun String.parseMarkdownFragments(): Map<String, Map<String, String>> {
    // Normalize line endings first so a CRLF/CR file does not leave stray carriage returns in values.
    val normalized = this.replace("\r\n", "\n").replace('\r', '\n')
    val cleaned = stripFragmentComments(normalized)
    val lines = cleaned.split('\n')
    val result = LinkedHashMap<String, LinkedHashMap<String, String>>()
    var namespace: String? = null

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            isBlankLine(line) -> i++

            isNamespaceLine(line) -> {
                val name = line.substring(3).trim()
                if (name.isEmpty()) {
                    throw mkMarkdownException(MarkdownError.emptyNamespace, "A '# @' namespace declaration has no name.")
                }
                if (name.contains('.')) {
                    throw mkMarkdownException(
                        MarkdownError.dottedName,
                        "Namespace '$name' contains a '.', which the two-tier format cannot address: a value is " +
                            "reached as 'namespace.key', so a dotted namespace can never be named.",
                    )
                }
                namespace = name
                result.getOrPut(name) { LinkedHashMap() }
                i++
            }

            isKeyLine(line) -> {
                val ns = namespace ?: throw mkMarkdownException(
                    MarkdownError.keyBeforeNamespace,
                    "A '# +' key was declared before any '# @' namespace.",
                )
                val rest = line.substring(3)
                val wsIndex = rest.indexOfFirst { it <= ' ' }
                val key = if (wsIndex < 0) rest else rest.substring(0, wsIndex)
                if (key.isEmpty()) {
                    throw mkMarkdownException(MarkdownError.emptyKey, "A '# +' key declaration has no key name.")
                }
                if (key.contains('.')) {
                    throw mkMarkdownException(
                        MarkdownError.dottedName,
                        "Key '$ns.$key' contains a '.' in its key name, which the two-tier format cannot " +
                            "address: '\${$ns.$key}' would look for a third tier that does not exist.",
                    )
                }
                val inline = if (wsIndex < 0) "" else rest.substring(wsIndex)
                val (value, next) = if (inline.isNotBlank()) {
                    collectInlineValue(lines, i, inline)
                } else {
                    collectNextLineValue(lines, i + 1)
                }
                val bucket = result.getValue(ns)
                if (bucket.containsKey(key)) {
                    throw mkMarkdownException(
                        MarkdownError.duplicateKey, "Key '$key' is declared more than once in namespace '$ns'.",
                    )
                }
                bucket[key] = value.trim()
                i = next
            }

            // A bare `# ` line (not @ or +) or stray content outside a fragment: ignore.
            else -> i++
        }
    }
    return result
}

/** Error codes reported by [parseMarkdownFragments], carried under [KdrException.errorCodeKey]. */
@Suppress("EnumEntryName")
enum class MarkdownError {
    /** A `/-` comment was opened but never closed with `-/`. */
    unterminatedComment,

    /** A `# +key` appeared before any `# @namespace` declaration. */
    keyBeforeNamespace,

    /** The same key was declared twice within one namespace. */
    duplicateKey,

    /** A `# @` declaration had no namespace name. */
    emptyNamespace,

    /** A `# +` declaration had no key name. */
    emptyKey,

    /**
     * A `# @` namespace or `# +` key name contained a `.`, which the two-tier format cannot address. A name is
     * reached as `namespace.key` -- by `${namespace.key}`, which walks dot-separated segments through the map,
     * and by `@t("namespace.key")` -- so a dotted name is unreachable either way: `${ns.a.b}` would look for
     * `map["ns"]["a"]["b"]` and find a String where it needs a map. Refused where it is written rather than
     * left to fail as a puzzling `notAnObject` at render or a dangling reference at boot.
     */
    dottedName,
}

/**
 * The value a **frontend-pass** `@t` fragment reference names in this one file's two-tier map, or null when it
 * names none (issue #505).
 *
 * A frontend reference is `namespace.key` -- the shape `${namespace.key}` resolves, within one file, which is
 * all a frontend has (its delivered copy). The map is exactly two tiers, so a well-formed key is exactly two
 * dot-separated parts; anything else (one part, or three) names no value here and returns null. The boot
 * checker validates a `${@t(...)}` reference with this, and the frontend `FragmentResolver` will resolve one
 * with it at render time, so the two cannot disagree.
 *
 * The **backend pass** is a different rule and not this helper: `%{@t("fileId.namespace.key")}` resolves three
 * parts across the *whole registry*, because the backend has every file where a frontend has only its own. That
 * resolution, and the boot validation of it, arrive with the backend pass (Phase 4 of issue #505); nothing
 * writes it yet.
 */
fun Map<String, Map<String, String>>.resolveFragment(key: String): String? {
    val dot = key.indexOf('.')
    if (dot <= 0 || dot >= key.length - 1) {
        return null
    }
    // Exactly two parts: a second dot would name a third tier this map does not have.
    if (key.indexOf('.', dot + 1) != -1) {
        return null
    }
    return this[key.substring(0, dot)]?.get(key.substring(dot + 1))
}

// --- helpers ----------------------------------------------------------------

/** Whether every character of [line] is whitespace (`<= ' '`), i.e., the line is "empty" for the format. */
@KdrPrivate
fun isBlankLine(line: String): Boolean = line.all { it <= ' ' }

/** Whether [line] is a fragment-level `# ` line (a single hash then a space; `## ` is ordinary content). */
@KdrPrivate
fun isHashLine(line: String): Boolean = line.length >= 2 && line[0] == '#' && line[1] == ' '

@KdrPrivate
fun isNamespaceLine(line: String): Boolean = line.length >= 3 && line[0] == '#' && line[1] == ' ' && line[2] == '@'

@KdrPrivate
fun isKeyLine(line: String): Boolean = line.length >= 3 && line[0] == '#' && line[1] == ' ' && line[2] == '+'

/**
 * Collects an inline value: [inline] (the text after the key on its line) plus the following lines, until the
 * first empty line or `# ` line. Returns the joined value and the next line index to resume at.
 */
@KdrPrivate
fun collectInlineValue(lines: List<String>, keyLine: Int, inline: String): Pair<String, Int> {
    val parts = mutableListOf(inline)
    var j = keyLine + 1
    while (j < lines.size) {
        val l = lines[j]
        if (isBlankLine(l) || isHashLine(l)) break
        parts.add(l)
        j++
    }
    return parts.joinToString("\n") to j
}

/**
 * Collects a next-line value from [start]: following lines until two consecutive empty lines or a `# ` line.
 * Single empty lines are preserved (paragraph breaks). Returns the joined value and the resume index.
 */
@KdrPrivate
fun collectNextLineValue(lines: List<String>, start: Int): Pair<String, Int> {
    val parts = mutableListOf<String>()
    var j = start
    var consecutiveEmpties = 0
    while (j < lines.size) {
        val l = lines[j]
        if (isHashLine(l)) break
        if (isBlankLine(l)) {
            consecutiveEmpties++
            if (consecutiveEmpties >= 2) break
            parts.add("")
        } else {
            consecutiveEmpties = 0
            parts.add(l)
        }
        j++
    }
    return parts.joinToString("\n") to j
}

/**
 * Removes `/- ... -/` comment spans, character by character, as if they were never in the text. Comments may
 * span lines and do not nest (the first `-/` after a `/-` closes). An unterminated `/-` is an error, reported
 * at its opening offset.
 */
@KdrPrivate
fun stripFragmentComments(text: String): String {
    val open = text.indexOf("/-")
    if (open < 0) return text
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        if (i + 1 < text.length && text[i] == '/' && text[i + 1] == '-') {
            val close = text.indexOf("-/", i + 2)
            if (close < 0) {
                // The offset here is accurate (measured on the original text), but kept in the message rather
                // than extraData, so every fragment error exposes a uniform surface: just the error code.
                throw mkMarkdownException(
                    MarkdownError.unterminatedComment,
                    "Markdown has an unterminated '/-' comment starting at offset $i.",
                )
            }
            i = close + 2 // skip past the closing "-/"
        } else {
            sb.append(text[i])
            i++
        }
    }
    return sb.toString()
}

/**
 * Builds a [KdrException] for a fragment-parse error, attaching the [code]. No source position is reported:
 * comment removal shifts line numbers relative to the original file (so a reported line would mislead), and
 * fragment files are small enough that the code plus a message naming the offending key/namespace suffices.
 */
@KdrPrivate
fun mkMarkdownException(code: MarkdownError, message: String): KdrException {
    val ke = KdrException(message, null, EXC.badInput, SRC.system, ACT.conversion)
    ke.extraData[KdrException.errorCodeKey] = code
    return ke
}
