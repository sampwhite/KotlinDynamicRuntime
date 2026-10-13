package com.dynamicruntime.common.http.request

/**
 * A request's arguments -- its query parameters, or its body's fields, multipart included -- as a **bounded, redacted
 * log string** (issue #1168). Every place that writes a request's arguments to the log goes through [format]; nothing
 * logs them raw.
 *
 * What it does to the arguments, in the shape Cedar's request logging had:
 *
 * - A **sensitive** value is replaced by [redacted], keeping its key, so the line still says the field was sent. A
 *   field is sensitive when its endpoint declares it so (`g-sensitive`, passed as `declared`) or its name says so
 *   ([isSensitiveName]), at any depth.
 * - An **email address**, wherever it appears in a value, keeps its first character and its domain
 *   (`p***@acme.test`): the search text and the user filters a page sends are the caller's to see, not the log's,
 *   and the domain is usually what a diagnosis needs.
 * - A **long string** is cut to its start and `...`.
 * - A **nested** map or list is followed to at most [maxDepth] levels, as a simplified `toString`; deeper, it reads
 *   [nested]. A file part, or any bytes, reads [binary].
 * - A **budget**: once the string passes [budget] characters, nothing more is followed -- the remaining entries are
 *   keys with abbreviated values -- and past [keysOnlyAt], keys alone, until [cutAt] ends it with `...`. One large
 *   body cannot flood a log line.
 * - Every character below a space becomes a space, so a value cannot forge a log line.
 *
 * Pure, and tested directly (`RequestArgsLogTest`).
 */
@Suppress("ConstPropertyName")
object RequestArgsLog {
    /** Past this many characters nothing more is followed, and values are abbreviated. */
    const val budget = 150

    /** Past this many characters, only keys are written. */
    const val keysOnlyAt = 300

    /** Past this many characters the string ends, with `...`. */
    const val cutAt = 400

    /** How many levels of nesting are followed; a value deeper reads [nested]. */
    const val maxDepth = 3

    /** The longest string written whole, within the [budget]. */
    const val longValue = 40

    /** How much of a string is kept once past the [budget]. */
    const val shortValue = 6

    /** What a sensitive value is written as. */
    const val redacted = "<redacted>"

    /** What a value too deep to follow, or past the budget, is written as. */
    const val nested = "obj"

    /** What a file part, or any bytes, is written as. */
    const val binary = "file"

    const val ellipsis = "..."

    /** Names that are secrets wherever they appear, compared lower-cased. */
    private val sensitiveNames = setOf(
        "password", "verifycode", "formauthtoken", "invitationtoken", "googlecredential", "apikey", "secret",
    )

    /**
     * Endings that make a name a secret, compared lower-cased. Not `key`, which would take every copy key and sort key
     * with it; `apikey` is named instead, and anything else is declared `g-sensitive` where it is defined.
     */
    private val sensitiveSuffixes = listOf("password", "token", "secret", "credential")

    /** Whether a field called [name] is a secret by its name alone: the backstop for input nobody declared. */
    fun isSensitiveName(name: String): Boolean {
        val lower = name.lowercase()
        return lower in sensitiveNames || sensitiveSuffixes.any { lower.endsWith(it) }
    }

    /** [args] as a log string; [declared] names the top-level fields the endpoint declares `g-sensitive`. */
    fun format(args: Map<String, Any?>, declared: Set<String> = emptySet()): String {
        val sb = StringBuilder()
        appendMap(sb, args, declared, depth = 1)
        return sb.toString()
    }

    private fun appendMap(sb: StringBuilder, map: Map<*, *>, declared: Set<String>, depth: Int) {
        sb.append('{')
        var first = true
        for ((rawKey, value) in map) {
            if (!first) sb.append(", ")
            first = false
            if (sb.length > cutAt) {
                sb.append(ellipsis)
                break
            }
            val key = clean(rawKey.toString())
            sb.append(key)
            if (sb.length > keysOnlyAt) continue
            sb.append('=')
            if (key in declared || isSensitiveName(key)) sb.append(redacted) else appendValue(sb, value, depth)
        }
        sb.append('}')
    }

    private fun appendList(sb: StringBuilder, list: List<*>, depth: Int) {
        sb.append('[')
        for ((i, value) in list.withIndex()) {
            if (i > 0) sb.append(", ")
            if (sb.length > keysOnlyAt) {
                sb.append("${list.size - i} more")
                break
            }
            appendValue(sb, value, depth)
        }
        sb.append(']')
    }

    private fun appendValue(sb: StringBuilder, value: Any?, depth: Int) {
        val pastBudget = sb.length > budget
        when (value) {
            null, is Number, is Boolean -> sb.append(value)
            is ContentData, is ByteArray -> sb.append(binary)
            is Map<*, *> -> if (pastBudget || depth >= maxDepth) sb.append(nested) else appendMap(sb, value, emptySet(), depth + 1)
            is List<*> -> if (pastBudget || depth >= maxDepth) sb.append(nested) else appendList(sb, value, depth + 1)
            else -> sb.append(abbreviate(masked(clean(value.toString())), if (pastBudget) shortValue else longValue))
        }
    }

    /** Something shaped like an email address, anywhere in a value: search text carries them among other words. */
    private val emailLike = Regex("""[^\s@,;<>"']+@[^\s@,;<>"']+\.[^\s@,;<>"']+""")

    /** [s] with every email address in it reduced to its first character and its domain: `p***@acme.test`. */
    private fun masked(s: String): String =
        if ('@' !in s) s else emailLike.replace(s) { m -> m.value.take(1) + "***" + m.value.substring(m.value.lastIndexOf('@')) }

    private fun abbreviate(s: String, max: Int): String = if (s.length <= max) s else s.take(max) + ellipsis

    /** Every character below a space as a space, so nothing in a value can start a log line of its own. */
    private fun clean(s: String): String = if (s.none { it < ' ' }) s else s.map { if (it < ' ') ' ' else it }.joinToString("")
}
