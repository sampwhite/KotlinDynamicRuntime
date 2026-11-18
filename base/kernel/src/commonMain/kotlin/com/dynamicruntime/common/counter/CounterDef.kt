package com.dynamicruntime.common.counter

import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.util.evalTemplate
import com.dynamicruntime.common.util.toOptStr

/**
 * The names a counter is made of (issue #1849): the fields of a `kdr:counterDef` entry, its reset rules, what its
 * prefix template reads, and the fields of the endpoint that issues an id and of the writes that use one. In the
 * kernel so the frontend reads an issued id, and finds the fields a counter fills, by the names the backend writes.
 * Each name matches its value.
 */
@Suppress("ConstPropertyName")
object CTR {
    // --- a counter's entry ---

    /** The counter's id within its client: what a field's `g-counter` names. */
    const val counterId = "counterId"

    /** A string-script template for the text before the count, reading [day] and [compactDay]. */
    const val prefix = "prefix"

    /** How many digits the count is padded to with zeroes, [minDigits] to [maxDigits]. */
    const val digits = "digits"

    /** When the count starts again at 1: [resetNever] or [resetDaily]. */
    const val reset = "reset"

    /** Whether new ids are issued. A counter is disabled, never deleted, so the ids it issued still mean something. */
    const val enabled = "enabled"

    /** What the counter numbers, in the administrator's words. */
    const val description = "description"

    /** The count goes on for good. */
    const val resetNever = "never"

    /** The count starts again at 1 on each of the client's days. */
    const val resetDaily = "daily"

    /** Every [reset] value, in the order a refusal lists them. */
    val resets: List<String> = listOf(resetNever, resetDaily)

    const val minDigits = 2
    const val maxDigits = 8

    /** What [digits] is when an entry leaves it out. */
    const val defaultDigits = 4

    /** What a counter id looks like: the shape a field's `g-counter` and a counter's entry are both held to. */
    const val idPattern = "^[A-Za-z][A-Za-z0-9_]{0,63}$"
    val idRegex: Regex = Regex(idPattern)

    /** [idPattern] in words, for a refusal. */
    const val idRule = "letters, digits, and underscores, starting with a letter, at most 64 characters"

    // --- what a prefix template reads ---

    /** The client's day the id is issued on, `2026-11-17`. */
    const val day = "day"

    /** The same day without its dashes, `20261117`. */
    const val compactDay = "compactDay"

    // --- issuing an id, and using it ---

    /** The issue endpoint's input: the counter to issue the next id of. */
    const val counter = "counter"

    /** The issue endpoint's answer: the id, as a field holds it. */
    const val value = "value"

    /** The issue endpoint's answer: the sealed proof the id was issued to this caller, sent back with the write. */
    const val token = "token"

    /** On a form write: the tokens of the ids it stores for the first time. */
    const val counterTokens = "counterTokens"
}

/**
 * One counter a configuration declares (issue #1849), as its `kdr:counterDef` entry holds it: ids are [prefix] (a
 * template over the day) and then a count padded to [digits], the count starting again at 1 each day when [reset] is
 * [CTR.resetDaily].
 *
 * [reset] is kept as written, so a value a later release adds is told apart rather than mistaken for one of ours; the
 * load refuses one it does not know. [configName] and [stored] are a stamp, not content: which configuration declared
 * it and whether it was read from the database. [extra] is whatever else a stored entry held -- a later release's
 * fields -- written back as it was, as a file ref's is.
 */
class CounterDef(
    val counterId: String,
    val prefix: String,
    val digits: Int = CTR.defaultDigits,
    val reset: String = CTR.resetNever,
    val enabled: Boolean = true,
    val description: String? = null,
    val configName: String? = null,
    val stored: Boolean = false,
    val extra: Map<String, Any?> = emptyMap(),
) {
    /** Whether the count starts again at 1 each day. */
    val resetsDaily: Boolean get() = reset == CTR.resetDaily

    /** This counter as its stored entry: what was authored and what was carried, and none of the stamp. */
    fun toEntry(): Map<String, Any?> = buildMap {
        put(CTR.counterId, counterId)
        put(CTR.prefix, prefix)
        put(CTR.digits, digits)
        put(CTR.reset, reset)
        if (!enabled) put(CTR.enabled, false)
        description?.let { put(CTR.description, it) }
        putAll(extra)
    }

    /** The prefix for an id issued on [day] (`yyyy-MM-dd`), or the template's fault as a `KdrException`. */
    fun prefixFor(day: String): String = counterPrefix(prefix, day)

    /** The id for [count] issued on [day]: the prefix, then the count padded to [digits]. */
    fun valueFor(day: String, count: Long): String = counterValueText(prefixFor(day), count, digits)

    companion object {
        /**
         * The counter [entry] declares. **Forgiving**, as a file ref's is: an entry with a bad field is still a
         * counter -- one the load reports and drops by itself -- since refusing it here would cost its whole
         * configuration. A [CTR.digits] that is not a whole number reads as 0, which the load refuses.
         */
        fun fromEntry(entry: Map<String, Any?>, configName: String?, stored: Boolean): CounterDef = CounterDef(
            counterId = entry[CTR.counterId].toOptStr().orEmpty(),
            prefix = entry[CTR.prefix].toOptStr().orEmpty(),
            digits = when (val d = entry[CTR.digits]) {
                null -> CTR.defaultDigits
                is Number -> d.toDouble().takeIf { it % 1.0 == 0.0 }?.toInt() ?: 0
                else -> d.toString().toIntOrNull() ?: 0
            },
            reset = entry[CTR.reset].toOptStr() ?: CTR.resetNever,
            enabled = entry[CTR.enabled] != false,
            description = entry[CTR.description].toOptStr(),
            configName = configName,
            stored = stored,
            extra = entry.filterKeys { it !in readFields },
        )

        /** The fields of an entry this release reads; anything else is [extra]. */
        private val readFields = setOf(CTR.counterId, CTR.prefix, CTR.digits, CTR.reset, CTR.enabled, CTR.description)
    }
}

/** [prefix] rendered for [day] (`yyyy-MM-dd`): the template reads [CTR.day] and [CTR.compactDay]. */
fun counterPrefix(prefix: String, day: String): String =
    prefix.evalTemplate(mapOf(CTR.day to day, CTR.compactDay to day.replace("-", "")))

/**
 * An id: [prefixText], then [count] padded with zeroes to [digits]. A count that outgrows its digits widens (`99`, then
 * `100`) rather than being refused, so a busy day never stops a store from issuing ids.
 */
fun counterValueText(prefixText: String, count: Long, digits: Int): String = prefixText + count.toString().padStart(digits, '0')

/**
 * The fields of [type] a counter fills, by field name to counter id (issue #1849): its own properties declaring
 * `g-counter`. Only a type's own fields: a counter in a nested object or an array's items is not filled in this release.
 */
fun counterFieldsOf(type: SchType): Map<String, String> =
    type.properties.mapNotNull { (name, prop) -> prop.valueType.counter?.let { name to it } }.toMap()
