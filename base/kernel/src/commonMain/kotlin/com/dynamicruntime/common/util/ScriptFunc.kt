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
    val invoke: (ScriptState, List<Any?>) -> Any?,
)

/**
 * The functions a template may call. A **fixed table**, deliberately not a registry a deployment can extend:
 * a template is content, and letting content call arbitrary code is a much larger decision than adding a way
 * to upper-case a name. A registry is the obvious follow-on once this set proves too small, and nothing here
 * forecloses it.
 *
 * Every one of these is **pure** -- it transforms its arguments and returns a value, with no context, no I/O
 * and no side effects -- and every one behaves identically on JVM and Kotlin/JS, which is the standing
 * requirement for kernel code and the reason the date functions go through `DateUtil` rather than any
 * platform formatter.
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
    // so it lands in the same numeric world as a literal and can be compared and pluralised against.
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
    ScriptFunction(SFN.formatDate, 1, 1) { state, args -> instantArg(state, SFN.formatDate, args[0]).formatDate() },
    ScriptFunction(SFN.formatDay, 1, 1) { state, args -> instantArg(state, SFN.formatDay, args[0]).formatDayPart() },
).associateBy { it.name }

private fun textArg(state: ScriptState, fn: String, v: Any?): String =
    v as? String ?: throw mkFuncTypeError(state, fn, v, "text")

private fun instantArg(state: ScriptState, fn: String, v: Any?): Instant = when (v) {
    is Instant -> v
    // `parseDate` throws its own KdrException on a malformed date; rethrown here as a function type error so
    // the message names the function the author actually wrote.
    is String -> runCatching { v.parseDate() }.getOrElse { throw mkFuncTypeError(state, fn, v, "a date") }
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
