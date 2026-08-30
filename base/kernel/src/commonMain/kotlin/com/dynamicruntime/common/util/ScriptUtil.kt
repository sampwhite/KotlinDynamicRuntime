package com.dynamicruntime.common.util

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC

/**
 * A home-grown template/script evaluator. Given a template string and a map of data, it substitutes
 * `${name}` expressions with the mapped value -- e.g. `"Your code is ${verifyCode}."` becomes
 * `"Your code is 1234."`. It replaces the third-party templating (FreeMarker) the prior-art `dn` used, so we
 * own the behavior end to end -- most importantly the error reporting.
 *
 * Design mirrors [String.json]: a single-pass, character-by-character state engine ([ScriptState]) building
 * its output in a [StringBuilder]. This is the harder approach, but it is fast (one pass), fully under our
 * control, and -- crucially -- it is where we grow a small script language over time.
 *
 * This file owns the *document* scan: what is literal text, what opens a block, and where a block ends. The
 * grammar **inside** a block -- literals, arithmetic, comparison, `&&`/`||`, `cond ? a : b`, `a ?: b`, and
 * calls to the built-in functions -- lives in `ScriptExpr.kt` (parse), `ScriptEval.kt` (value rules) and
 * `ScriptFunc.kt` (the functions). Letting a *deployment* register its own functions is the next step, and a
 * larger decision than adding to the table: it is about what content is allowed to do.
 *
 * Two deliberate properties:
 *  - **Transpile-compatible.** Written in pure, KMP-safe Kotlin (no `java.*`, no reflection), so the same
 *    logic can eventually run in the frontend via Kotlin/JS. The idea is one shared implementation for both
 *    backend and frontend; a user editing a script in the browser can get the exact same evaluation and
 *    error reporting without a round trip to the server.
 *  - **Precise, structured errors.** Every failure carries a [ScriptError] code (under
 *    [KdrException.errorCodeKey]) plus the originating position ([KdrException.offsetKey]/[KdrException.lineKey]/
 *    [KdrException.lineColKey]), pointing at the start of the offending `${...}` block, so a UI can explain
 *    and highlight it.
 *
 * The `$` prefix is a parameter (default `'$'`). Passing a different character (e.g. `'#'`) makes `#{...}` the
 * substitution block and leaves any `${...}` untouched -- which enables multi-pass evaluation, where the
 * backend resolves one prefix and forwards the rest for the frontend to resolve later.
 */
fun String.evalTemplate(data: Map<String, Any?>, prefix: Char = '$', resolver: FragmentResolver? = null): String {
    val state = ScriptState(this, prefix, resolver)
    runTemplate(state, data)
    return state.sb.toString()
}

/**
 * The one capability the `@t` construct needs from outside the pure grammar (issue #505): given a fragment
 * key, return its **raw template text**, or null when this node has no such fragment.
 *
 * It returns *unevaluated* text on purpose. `@t` evaluates what it pulls, with either the caller's scope or the
 * hermetic bindings, so the resolver stays a dumb lookup and all the scope/evaluation rules live here in the
 * kernel. It is the "two-way" seam: the same kernel evaluation runs on the backend (where a resolver reads the
 * merged fragment registry) and the frontend (where one reads the delivered copy), so both resolve a template
 * identically. Synchronous, because evaluation is -- a resolver that had to *fetch* could not be called from
 * here, which is why a frontend one resolves only already-delivered content.
 */
fun interface FragmentResolver {
    fun resolve(key: String): String?
}

/**
 * Evaluates [text] -- the fragment [key] pulled by `@t` -- as its own template, one include level deeper than
 * [parent] (issue #505). Carries [parent]'s prefix and resolver so a pulled fragment can pull further.
 *
 * **A cycle is ancestry, not a visited set** ([ScriptState.includeChain]): a key inside its own chain is a
 * cycle, while one pulled from two different branches is reuse, and a set would refuse that second, correct
 * case. [SEXP.maxIncludeDepth] then bounds a chain that is long without repeating. The two report separately,
 * because "pulls itself" and "nests too far" call for different fixes.
 *
 * Errors from inside are re-reported by [mkFragmentContext].
 */
@KdrPrivate
fun evalFragmentText(parent: ScriptState, key: String, text: String, data: Map<String, Any?>): String {
    if (parent.includeChain.contains(key)) {
        val path = (parent.includeChain + key).joinToString(" -> ")
        throw mkScriptException(
            parent, ScriptError.fragmentCycle,
            "Fragment '$key' pulls itself in a cycle: $path.",
        )
    }
    if (parent.includeChain.size + 1 > SEXP.maxIncludeDepth) {
        throw mkScriptException(
            parent, ScriptError.fragmentIncludeTooDeep,
            "Fragment includes nest deeper than ${SEXP.maxIncludeDepth} levels, starting at " +
                "'${parent.includeChain.first()}'. No fragment repeats, so this is depth rather than a cycle.",
        )
    }
    val sub = ScriptState(text, parent.prefix, parent.resolver, parent.includeChain + key)
    try {
        runTemplate(sub, data)
    } catch (e: KdrException) {
        throw mkFragmentContext(key, e)
    }
    return sub.sb.toString()
}

/**
 * Re-reports [cause], thrown while evaluating fragment [key], so its position is readable.
 *
 * A template error carries an offset into the text being evaluated. For a pulled fragment that text is the
 * *fragment*, not the document the author is looking at, so an unqualified "line 1, column 4" points into a
 * caller that may be four characters long. The code and the position are kept -- they are right about the
 * fragment -- and the message and [KdrException.fragmentKey] say which text they are right *about*.
 */
@KdrPrivate
fun mkFragmentContext(key: String, cause: KdrException): KdrException {
    val ke = KdrException(
        "In fragment '$key': ${cause.message}", cause, cause.code, cause.source, cause.activity,
    )
    ke.extraData.putAll(cause.extraData)
    // The innermost fragment wins: a nested pull has already labeled itself, and that is the one to fix.
    if (!ke.extraData.containsKey(KdrException.fragmentKey)) {
        ke.extraData[KdrException.fragmentKey] = key
    }
    return ke
}

/** Error codes reported by [evalTemplate], carried in [KdrException.extraData] under [KdrException.errorCodeKey]. */
@Suppress("EnumEntryName")
enum class ScriptError {
    /** A `${` (or the configured prefix) opened an expression that never reached a closing `}`. */
    unterminatedExpression,

    /** A `${}` expression with no content between the braces. */
    emptyExpression,

    /** The expression named a key that is not present in the data map. */
    missingKey,

    /** The expression named a key that is present but whose value is null. */
    nullValue,

    /** A dotted path (`${x.y}`) tried to drill into a segment whose value is not an object/map. */
    notAnObject,

    /** The expression could not be parsed: a stray character, an unterminated string, a missing operand. */
    syntaxError,

    /** An operator was applied to a value it has no meaning for, e.g., multiplying an object. */
    typeMismatch,

    /** A `/` or `%` had a zero right-hand side. Reported rather than yielding an infinity that would print. */
    divideByZero,

    /** The expression nested deeper than [SEXP.maxDepth], the cap on the recursive-descent parse. */
    expressionTooDeep,

    /** `@t(key)` named a fragment this node has no content for, in a position that does not tolerate absence. */
    fragmentNotFound,

    /** A fragment pulled itself, directly or through a chain: `a` -> `b` -> `a`. Reported with the path. */
    fragmentCycle,

    /**
     * `@t` pulls nested past [SEXP.maxIncludeDepth] without any fragment repeating -- depth rather than a
     * cycle, which [fragmentCycle] reports precisely. Reachable when computed keys generate a long chain of
     * distinct fragments, so ancestry alone cannot bound it.
     */
    fragmentIncludeTooDeep,

    /** A template used `@t` but was evaluated with no [FragmentResolver] to resolve it against. */
    noResolver,
}

/**
 * The single-pass parse state, analogous to JSON's `PState`: the input, the running position (character
 * [offset], and [line]/[lineOffset] for line/column reporting), and the [sb] output being built. It also
 * remembers where the current `${...}` block began ([blockOffset]/[blockLine]/[blockCol]) so an error points
 * at the block's start rather than wherever parsing happened to stop.
 */
@KdrPrivate
class ScriptState(
    val str: String,
    val prefix: Char,
    /** Resolves a `@t` fragment key to raw text; null when this evaluation supplies none (issue #505). */
    val resolver: FragmentResolver? = null,
    /**
     * The fragment keys currently being evaluated, outermost first -- this evaluation's ancestry (issue #505).
     * Empty for a document a caller evaluates directly. A key already in here is a cycle; its length is the
     * include depth. Ancestry rather than a visited *set*, so one fragment pulled from two branches stays
     * legal.
     */
    val includeChain: List<String> = emptyList(),
) {
    val end: Int = str.length
    var offset: Int = 0
    var line: Int = 0
    var lineOffset: Int = 0
    val sb: StringBuilder = StringBuilder()

    /**
     * When true, a missing key or null value resolves to an empty string instead of throwing, for every block
     * in the document. Off by default: a bare `${a}` that is not there stays loud, which is what catches a
     * typo in a fragment.
     *
     * This is the blunt, whole-document switch. Per-expression, an author says it themselves with `?:`
     * (`${a ?: ""}`), which is why that operator exists; nothing sets this flag yet.
     */
    var allowMissingOrNull: Boolean = false

    // Origin of the block currently being parsed, captured at the prefix character.
    var blockOffset: Int = 0
    var blockLine: Int = 0
    var blockCol: Int = 0

    /** Advances one character, tracking line/column ([ch] is the character being consumed). */
    fun advance(ch: Char) {
        offset++
        if (ch == '\n') {
            line++
            lineOffset = 0
        } else {
            lineOffset++
        }
    }

    /** Freezes the current position as the start of a `${...}` block, for error reporting. */
    fun captureBlockStart() {
        blockOffset = offset
        blockLine = line
        blockCol = lineOffset
    }
}

@KdrPrivate
fun runTemplate(state: ScriptState, data: Map<String, Any?>) {
    val str = state.str
    val prefix = state.prefix
    while (state.offset < state.end) {
        val ch = str[state.offset]
        if (ch != prefix) {
            state.sb.append(ch)
            state.advance(ch)
            continue
        }
        // Saw the prefix; decide what it introduces by looking one character ahead.
        val next = if (state.offset + 1 < state.end) str[state.offset + 1] else ' '
        when (next) {
            '{' -> {
                state.captureBlockStart() // Remember the prefix position for error reporting.
                state.advance(ch) // consume prefix
                state.advance(str[state.offset]) // consume '{'
                val expr = readExpression(state)
                appendResolved(state, data, expr)
            }
            prefix -> {
                // A doubled prefix is an escape for a single literal prefix (e.g. "$$" -> "$").
                state.sb.append(prefix)
                state.advance(ch)
                state.advance(str[state.offset]) // consume the second prefix
            }
            else -> {
                // A lone prefix (not opening a block, not escaped) is literal text.
                state.sb.append(ch)
                state.advance(ch)
            }
        }
    }
}

/**
 * Reads the interior of a `${...}` block up to (and consuming) the closing `}`, returning the raw text
 * between the braces for `ScriptExpr.kt` to parse.
 *
 * String-literal aware, and it has to be: once an expression can say `${ok ? "}" : "no"}`, a scan that stopped
 * at the first `}` would end the block inside the quotes and hand the parser a fragment. Quotes are tracked
 * here only well enough to know what is inside one -- the literal's own escapes are decoded by the lexer.
 */
@KdrPrivate
fun readExpression(state: ScriptState): String {
    val str = state.str
    val sb = StringBuilder()
    var quote = ' ' // the open quote character, or a space when outside a string
    var escaped = false
    while (state.offset < state.end) {
        val ch = str[state.offset]
        when {
            escaped -> escaped = false
            quote != ' ' && ch == '\\' -> escaped = true
            quote != ' ' -> if (ch == quote) quote = ' '
            ch == '"' || ch == '\'' -> quote = ch
            ch == '}' -> {
                state.advance(ch) // consume '}'
                return sb.toString()
            }
        }
        sb.append(ch)
        state.advance(ch)
    }
    // Running out of input *inside* a quote means the quote is the mistake, not the brace: the `}` that would
    // have closed the block was almost certainly there, and got eaten as string content. Say the useful thing.
    if (quote != ' ') {
        throw mkScriptException(
            state, ScriptError.syntaxError,
            "Template expression has an unterminated string literal; the $quote was never closed.",
        )
    }
    throw mkScriptException(
        state, ScriptError.unterminatedExpression,
        "Template has an unterminated '${state.prefix}{' expression.",
    )
}

/**
 * Parses [expr] as an expression, evaluates it against [data], and appends the result.
 *
 * The whole-document [ScriptState.allowMissingOrNull] switch is applied here, at the outermost evaluation, so
 * it keeps meaning "an absent value prints as nothing". It is separate from the per-expression tolerance that
 * `?:` and a ternary condition apply to their own operands (see `ScriptEval.kt`).
 */
@KdrPrivate
fun appendResolved(state: ScriptState, data: Map<String, Any?>, expr: String) {
    if (expr.isBlank()) {
        throw mkScriptException(
            state, ScriptError.emptyExpression,
            "Template has an empty '${state.prefix}{}' expression.",
        )
    }
    val tokens = tokenize(state, expr)
    val node = ScriptParser(state, tokens).parseAll()
    val value = if (state.allowMissingOrNull) {
        evalNode(state, data, node, tolerant = true, depth = 0)
    } else {
        evalNode(state, data, node, tolerant = false, depth = 0)
    }
    // A null survives to here only under the tolerant switch, or from an explicit `null` / a `?:` whose right
    // side is null. Printing "null" into a document is never what was meant, so it contributes nothing.
    if (value != null) state.sb.append(value.fmt())
}

/**
 * Builds a [KdrException] for a template error, attaching the [code] and the block's origin position to
 * [KdrException.extraData] under the shared keys. Mirrors JSON's `mkJsonParseException`.
 */
@KdrPrivate
fun mkScriptException(state: ScriptState, code: ScriptError, msg: String): KdrException {
    val errMsg = "$msg Error originates at offset ${state.blockOffset} in input " +
        "(line ${state.blockLine + 1}, column ${state.blockCol + 1})."
    val ke = KdrException(errMsg, null, EXC.badInput, SRC.system, ACT.conversion)
    ke.extraData[KdrException.errorCodeKey] = code
    ke.extraData[KdrException.offsetKey] = state.blockOffset
    ke.extraData[KdrException.lineKey] = state.blockLine + 1
    ke.extraData[KdrException.lineColKey] = state.blockCol + 1
    return ke
}
