package com.dynamicruntime.common.util

import com.dynamicruntime.common.annotation.KdrPrivate
import kotlin.math.abs
import kotlin.time.Instant

/** Built-in function names for the `${...}` grammar; each name matches its value. */
@Suppress("ConstPropertyName")
object SFN {
    const val upper = "upper"
    const val lower = "lower"
    const val trim = "trim"
    const val count = "count"
    const val abs = "abs"
    const val formatDate = "formatDate"
    const val formatDay = "formatDay"
}

/**
 * The styles `formatDate(x, style)` takes (issue #1321), each read against the evaluation's [ScriptEnv]. With no style
 * it writes the UTC system timestamp, as it always has.
 */
@Suppress("ConstPropertyName")
object SDSTY {
    /** `12m ago`, `in 3h`, the day alone from 48 hours on: the table's rule ([relativeTimeText]). */
    const val relative = "relative"

    /** The date and time in the zone, labeled: `Oct 17, 2026, 5:00 PM CT` ([momentText]). */
    const val moment = "moment"

    /** The day alone in the zone, `2026-10-17`, as `formatDay` writes it ([zonedDayText]). */
    const val day = "day"

    /** Every style, in the order a refusal lists them. */
    val all: List<String> = listOf(relative, moment, day)
}

/**
 * One callable built-in: how many arguments it takes, and what it does with them.
 *
 * The arity is data rather than a check inside [invoke] so the **parser** can enforce it -- which is what lets
 * `/operator/fragments/check` reject `upper()` or a misspelled `uppr(x)` with no data at hand. A wrong-arity
 * call caught at render time would be a 500 in front of a user; caught at parse time it is a boot failure in
 * dev and a line in a check report everywhere else.
 */
@KdrPrivate
class ScriptFunction(
    val name: String,
    val minArgs: Int,
    val maxArgs: Int,
    /**
     * Checks the call's arguments as parsed, before any data (issue #1321) -- for an argument whose literal value can
     * be wrong in itself, such as `formatDate`'s style. Throws, as the arity check does; null checks nothing.
     */
    val checkArgs: ((ScriptState, List<ScriptNode>) -> Unit)? = null,
    val invoke: (ScriptState, List<Any?>) -> Any?,
)

/**
 * The functions a template may call. A **fixed table**, deliberately not a registry a deployment can extend:
 * a template is content, and letting content call arbitrary code is a much larger decision than adding a way
 * to upper-case a name. A registry is the obvious follow-on once this set proves too small, and nothing here
 * forecloses it.
 *
 * Every one of these is **pure** -- it transforms its arguments and returns a value, with no I/O and no side
 * effects -- and every one behaves identically on JVM and Kotlin/JS, which is the standing requirement for kernel
 * code and the reason the date functions format in the kernel rather than through any platform formatter. The date
 * functions also read the evaluation's [ScriptEnv] (issue #1321), its zone and now, which the caller hands in: pure
 * over their arguments and that.
 *
 * Argument types follow the rules the operators settled: a kind is a kind. `upper(42)` is a type mismatch
 * rather than `"42"`, for the same reason `"3" * 2` is -- the error names the value and says where it came
 * from, instead of quietly making the template look like it worked.
 */
@KdrPrivate
val scriptFunctions: Map<String, ScriptFunction> = listOf(
    ScriptFunction(SFN.upper, 1, 1) { state, args -> textArg(state, SFN.upper, args[0]).uppercase() },
    ScriptFunction(SFN.lower, 1, 1) { state, args -> textArg(state, SFN.lower, args[0]).lowercase() },
    ScriptFunction(SFN.trim, 1, 1) { state, args -> textArg(state, SFN.trim, args[0]).trim() },
    // Counts what has a size: the characters of a string, the entries of a list or an object. Returns a Long
    // so it lands in the same numeric world as a literal and can be compared and pluralized against.
    ScriptFunction(SFN.count, 1, 1) { state, args ->
        when (val v = args[0]) {
            is String -> v.length.toLong()
            is Collection<*> -> v.size.toLong()
            is Map<*, *> -> v.size.toLong()
            else -> throw mkFuncTypeError(state, SFN.count, v, "text, a list or an object")
        }
    },
    ScriptFunction(SFN.abs, 1, 1) { state, args ->
        when (val n = numOf(args[0])) {
            is Long -> abs(n)
            is Double -> abs(n)
            else -> throw mkFuncTypeError(state, SFN.abs, args[0], "a number")
        }
    },
    // Dates arrive as an Instant from Kotlin or as an ISO string from JSON, and both are legitimate -- so both
    // are accepted, and anything else is named as the mismatch it is.
    ScriptFunction(SFN.formatDate, 1, 2, checkArgs = ::checkDateStyleLiteral) { state, args ->
        val at = instantArg(state, SFN.formatDate, args[0])
        if (args.size < 2) return@ScriptFunction at.formatDate()
        val env = state.envOrDefault
        when (val style = args[1]) {
            SDSTY.relative -> relativeTimeText(at, env.now, env.zone)
            SDSTY.moment -> momentText(at, env.zone)
            SDSTY.day -> zonedDayText(at, env.zone)
            // A computed style that is not one: a literal one was refused at the parse.
            else -> throw mkStyleError(state, style)
        }
    },
    ScriptFunction(SFN.formatDay, 1, 1) { state, args ->
        zonedDayText(instantArg(state, SFN.formatDay, args[0]), state.envOrDefault.zone)
    },
).associateBy { it.name }

/** Refuses a literal `formatDate` style that is not one of [SDSTY.all] when the template is parsed (issue #1321). */
private fun checkDateStyleLiteral(state: ScriptState, args: List<ScriptNode>) {
    val style = (args.getOrNull(1) as? LiteralNode ?: return).value
    if (style !in SDSTY.all) throw mkStyleError(state, style, ScriptError.syntaxError)
}

private fun mkStyleError(state: ScriptState, style: Any?, code: ScriptError = ScriptError.typeMismatch) =
    mkScriptException(
        state, code,
        "Template function '${SFN.formatDate}' takes a style of ${SDSTY.all.joinToString(", ") { "\"$it\"" }} " +
            "but was given ${describeValue(style)}.",
    )

private fun textArg(state: ScriptState, fn: String, v: Any?): String =
    v as? String ?: throw mkFuncTypeError(state, fn, v, "text")

private fun instantArg(state: ScriptState, fn: String, v: Any?): Instant = when (v) {
    is Instant -> v
    // A malformed date is reported as a function type error, so the message names the function the author wrote.
    is String -> v.parseDateOrNull() ?: throw mkFuncTypeError(state, fn, v, "a date")
    else -> throw mkFuncTypeError(state, fn, v, "a date")
}

private fun mkFuncTypeError(state: ScriptState, fn: String, v: Any?, expected: String) = mkScriptException(
    state, ScriptError.typeMismatch,
    "Template function '$fn' expects $expected but was given ${describeValue(v)}.",
)

/** Names a value's kind for an error message, without printing a whole map or list into it. */
@KdrPrivate
fun describeValue(v: Any?): String = when (v) {
    null -> "null"
    is String -> "the text '${v.take(30)}'"
    is Boolean -> "the boolean $v"
    is Map<*, *> -> "an object"
    is Collection<*> -> "a list"
    else -> "the value ${v.fmt()}"
}
