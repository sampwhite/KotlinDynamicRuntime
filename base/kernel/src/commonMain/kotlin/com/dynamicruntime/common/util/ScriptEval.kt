package com.dynamicruntime.common.util

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.KdrException

/**
 * Evaluation and the value rules for the expression grammar in `ScriptExpr.kt`.
 *
 * Values are whatever the data map holds -- what `JsonUtil` produces, so `String`, `Long`, `Double`,
 * `Boolean`, `Map`, `List`, or null -- and the operators have to say what they mean across those. The rules
 * below are deliberately *stated* rather than emergent, because this is the part of a small dynamic language
 * that otherwise turns into a lookup table nobody can predict:
 *
 *  - **A numeric operator reads a numeric string as a number; nothing else does** (issue #608). The operator
 *    has declared it wants a number, so `${count + 1}` on a `"3"` is `4` -- a cleanly-numeric string is coerced
 *    (an exact, full-string parse). `"3abc"` is still a type error, which is the useful outcome: it names a
 *    value that arrived as text and is not a number. Everywhere an operator is *not* involved a string stays
 *    text: equality is same-kind (`"1" == 1` is a mismatch) and a function names its kinds (`abs("3")` is a
 *    mismatch), so coercion is the operators' rule, not a global one.
 *  - **Truth**: null is false; a boolean is itself; a number is true when non-zero; a string is true when
 *    non-empty; a map or list is true when non-empty. Anything else is true.
 *  - **`+ - * / %` are arithmetic.** Each operand is a number or a cleanly-numeric string; anything else is
 *    [ScriptError.typeMismatch]; dividing by zero is [ScriptError.divideByZero] rather than an infinity that
 *    would print.
 *  - **`~` joins text**, formatting each side with [fmt]. Separate from `+` so neither operator is ever
 *    ambiguous about what it is doing. Most templates need no operator at all -- `n=${count}` already
 *    concatenates by juxtaposition; `~` is for composing inside an expression, e.g., a ternary branch. It never
 *    coerces -- it always wants text -- which is the mirror of the arithmetic rule.
 *  - **Numbers stay integral where they start.** Two `Long`s divide as integers (`7 / 2` is 3); one `Double`
 *    makes the result a `Double`. A coerced integer string is a `Long`, so `"7" / "2"` is 3 too.
 *  - **`< > <= >=` compare numerically**, each side a number or a cleanly-numeric string. Two plain strings are
 *    a type mismatch, not a lexicographic answer -- ordering means magnitude, and text ordering is a function
 *    (to be added when a case needs one), not an operator that guesses.
 *  - **`==` / `!=`** compare within a kind -- numbers with numbers, text with text, booleans with booleans.
 *    Comparing against `null` is always allowed (that is the presence test); mixing other kinds is a type
 *    mismatch, so `${flag == "true"}` on a real boolean tells you to write `${flag == true}`.
 *  - **null is never silently printed.** `${a}` with `a` absent or null still throws, exactly as before this
 *    grammar existed. Absence is tolerated in exactly the three places where the author has already said what
 *    it means: the left of `?:`, a ternary condition, and either side of a comparison against the literal
 *    `null`. Tolerance flows down the whole subtree there, which is what makes `${a.b ?: "none"}` and
 *    `${a.b == null ? "none" : a.b}` work when `a` itself is missing.
 */
@KdrPrivate
fun evalNode(state: ScriptState, data: Map<String, Any?>, node: ScriptNode, tolerant: Boolean, depth: Int): Any? {
    if (depth >= SEXP.maxDepth) {
        throw mkScriptException(
            state, ScriptError.expressionTooDeep,
            "Template expression nests deeper than ${SEXP.maxDepth} levels.",
        )
    }
    val next = depth + 1
    return when (node) {
        is LiteralNode -> node.value
        is PathNode -> resolvePath(state, data, node, tolerant)
        is CallNode -> {
            val args = node.args.map { evalNode(state, data, it, tolerant, next) }
            // In a tolerant position an absent argument makes the whole call absent, rather than a type error
            // about the null it produced. `${upper(user.name) ?: "anon"}` is the natural way to write that, and
            // the alternative -- erroring so the author moves the default inside the call -- would make `?:`
            // stop working the moment a function appeared to its left. Outside a tolerant position a null
            // argument can only be a literal `null`, which is a real mistake and still reports.
            if (tolerant && args.any { it == null }) null else node.fn.invoke(state, args)
        }
        is UnaryNode -> evalUnary(state, data, node, tolerant, next)
        is BinaryNode -> evalBinary(state, data, node, tolerant, next)
        // The guarded side is evaluated tolerantly: the whole point of `?:` is that the left may not be there.
        is ElvisNode -> evalNode(state, data, node.left, tolerant = true, depth = next)
            ?: evalNode(state, data, node.right, tolerant, next)
        // Likewise a condition: an absent flag is falsy, not an error. Only the taken branch is evaluated.
        is TernaryNode -> {
            val cond = truthy(evalNode(state, data, node.cond, tolerant = true, depth = next))
            evalNode(state, data, if (cond) node.whenTrue else node.whenFalse, tolerant, next)
        }
        is FragmentNode -> evalFragment(state, data, node, tolerant, next)
    }
}

/**
 * Pulls the fragment `@t(key, ...)` names and evaluates it (issue #505).
 *
 * The key is a full expression, so it may be computed. In a tolerant position (`?:`, a ternary condition, a
 * null test) an absent key, a not-found fragment, or an absence *inside* the fragment all make the pull null,
 * so `@t("x") ?: "default"` uses the grammar's one default mechanism; see [absenceErrors] for where that stops.
 *
 * Scope follows the settled rule: **no bindings inherits** the caller's [data]; **any binding is hermetic** --
 * the pulled fragment runs with only the bound values, so it cannot silently read a variable it was never
 * handed. Bindings are evaluated in the caller's scope, before the fragment runs.
 */
@KdrPrivate
fun evalFragment(state: ScriptState, data: Map<String, Any?>, node: FragmentNode, tolerant: Boolean, depth: Int): Any? {
    val keyValue = evalNode(state, data, node.key, tolerant, depth)
    if (keyValue == null) {
        // Reachable non-null-throwing only under tolerance (an absent path there returns null); a literal
        // `@t(null)` reaches here intolerant and is a real mistake.
        if (tolerant) return null
        throw mkScriptException(state, ScriptError.nullValue, "Fragment reference '@t' has a null key.")
    }
    val key = keyValue as? String ?: throw mkScriptException(
        state, ScriptError.typeMismatch,
        "Fragment reference '@t' needs a text key but was given ${describeValue(keyValue)}.",
    )
    val resolver = state.resolver ?: throw mkScriptException(
        state, ScriptError.noResolver,
        "Fragment reference '@t(\"$key\")' cannot be resolved: this evaluation was given no fragment resolver.",
    )
    val text = resolver.resolve(key)
    if (text == null) {
        if (tolerant) return null
        throw mkScriptException(
            state, ScriptError.fragmentNotFound, "Fragment reference '@t(\"$key\")' names no fragment this node has.",
        )
    }
    val scope = if (node.bindings.isEmpty()) {
        data
    } else {
        // Parsing refuses a duplicate name, so building the map cannot silently drop a binding here.
        node.bindings.associate { (name, expr) -> name to evalNode(state, data, expr, tolerant, depth) }
    }
    if (!tolerant) {
        return evalFragmentText(state, key, text, scope)
    }
    return try {
        evalFragmentText(state, key, text, scope)
    } catch (e: KdrException) {
        if (e.extraData[KdrException.errorCodeKey] in absenceErrors) null else throw e
    }
}

/**
 * The error codes a guarded `@t` absorbs from **inside** the fragment it pulled: the ones meaning "the data was
 * not there", never "the template is wrong". That line is what keeps `?:` a statement about a missing value
 * rather than a blanket catch -- a fragment with a syntax error or a type mismatch still throws, or the defect
 * would be hidden everywhere the fragment is used.
 *
 * [ScriptError.fragmentNotFound] is deliberately **not** here. This pull's *own* key missing is handled before
 * the fragment runs at all (and does yield null under a guard, which is the absence a `?:` is for). So the only
 * way that code reaches this set is a *nested* `@t` inside the pulled fragment naming something that does not
 * exist -- a misspelled reference in the fragment's own text, which is a defect its author must fix, not a
 * value the caller failed to supply.
 */
private val absenceErrors = setOf(
    ScriptError.missingKey,
    ScriptError.nullValue,
    ScriptError.notAnObject,
)

/** Walks a dotted path through nested maps, keeping the pre-grammar error codes exactly as they were. */
@KdrPrivate
fun resolvePath(state: ScriptState, data: Map<String, Any?>, node: PathNode, tolerant: Boolean): Any? {
    var current: Any? = data
    for (segment in node.segments) {
        val map = current as? Map<*, *>
        if (map == null) {
            if (tolerant) return null
            throw mkScriptException(
                state, ScriptError.notAnObject,
                "Template path '${node.text}' cannot drill into '$segment' because the value before it is not " +
                    "an object.",
            )
        }
        if (!map.containsKey(segment)) {
            if (tolerant) return null
            throw mkScriptException(
                state, ScriptError.missingKey,
                "Template references '${node.text}'; segment '$segment' is not present in the provided data.",
            )
        }
        current = map[segment]
    }
    if (current == null && !tolerant) {
        throw mkScriptException(
            state, ScriptError.nullValue, "Template references '${node.text}', whose value is null.",
        )
    }
    return current
}

@KdrPrivate
/** Returns a value or throws, never null -- the null-yielding positions are all in [evalNode]. */
fun evalUnary(state: ScriptState, data: Map<String, Any?>, node: UnaryNode, tolerant: Boolean, depth: Int): Any {
    val v = evalNode(state, data, node.operand, tolerant, depth)
    return when (node.op) {
        "!" -> !truthy(v)
        else -> when (val n = numOperand(v)) { // unary minus; a numeric string coerces, like binary `-`
            is Long -> -n
            is Double -> -n
            else -> throw mkTypeMismatch(state, "-", v, null)
        }
    }
}

@KdrPrivate
/** Returns a value or throws, never null -- as [evalUnary]; an operator has no absent result to express. */
fun evalBinary(state: ScriptState, data: Map<String, Any?>, node: BinaryNode, tolerant: Boolean, depth: Int): Any {
    // `&&` and `||` short-circuit, so the right side is not evaluated when the left decides the answer.
    if (node.op == "&&" || node.op == "||") {
        val left = truthy(evalNode(state, data, node.left, tolerant, depth))
        if (node.op == "&&" && !left) return false
        if (node.op == "||" && left) return true
        return truthy(evalNode(state, data, node.right, tolerant, depth))
    }
    // Comparing against a literal `null` *is* the question "is this there?", so answering it by throwing
    // "it is not there" would be absurd. The operands of such a test are therefore evaluated tolerantly,
    // for the same reason `?:` and a condition are: the author has said what absence means to them.
    val nullTest = (node.op == "==" || node.op == "!=") && (isNullLiteral(node.left) || isNullLiteral(node.right))
    val operandTolerant = tolerant || nullTest
    val l = evalNode(state, data, node.left, operandTolerant, depth)
    val r = evalNode(state, data, node.right, operandTolerant, depth)
    return when (node.op) {
        "~" -> {
            // Joining text says nothing about what an absent side should become, so it is a type error rather
            // than a silent "null" in the output. `${(a ?: "") ~ b}` is how an author says otherwise.
            if (l == null || r == null) throw mkTypeMismatch(state, "~", l, r)
            l.fmt() + r.fmt()
        }
        "+", "-", "*", "/", "%" -> arith(state, node.op, l, r)
        "==" -> valuesEqual(state, l, r)
        "!=" -> !valuesEqual(state, l, r)
        else -> compareOp(state, node.op, l, r)
    }
}

/**
 * Whether [n] is the literal `null`. Not private, because the path analysis in `ScriptCheck.kt` has to decide
 * tolerance by exactly the same rule this evaluator does -- two copies of "what counts as a null test" would
 * drift, and the symptom would be a default reported as a missing key.
 */
@KdrPrivate
fun isNullLiteral(n: ScriptNode): Boolean = n is LiteralNode && n.value == null

/** Arithmetic over numbers only, staying in `Long` while both sides are integral; a numeric string coerces. */
@KdrPrivate
fun arith(state: ScriptState, op: String, l: Any?, r: Any?): Any {
    val ln = numOperand(l) ?: throw mkTypeMismatch(state, op, l, r)
    val rn = numOperand(r) ?: throw mkTypeMismatch(state, op, l, r)
    if (ln is Long && rn is Long) {
        if ((op == "/" || op == "%") && rn == 0L) throw mkDivideByZero(state, op)
        return when (op) {
            "+" -> ln + rn
            "-" -> ln - rn
            "*" -> ln * rn
            "/" -> ln / rn
            else -> ln % rn
        }
    }
    val ld = toD(ln)
    val rd = toD(rn)
    if ((op == "/" || op == "%") && rd == 0.0) throw mkDivideByZero(state, op)
    return when (op) {
        "+" -> ld + rd
        "-" -> ld - rd
        "*" -> ld * rd
        "/" -> ld / rd
        else -> ld % rd
    }
}

/**
 * Ordering comparison, numeric only (issue #608): each side must be a number or a cleanly-numeric string, which
 * [numOperand] coerces. Two plain strings no longer compare lexicographically -- `"apple" < "z"` is now a type
 * mismatch rather than a lexicographic answer, so ordering means one thing (magnitude) and text ordering, when a
 * case needs it, will be a named function rather than an operator that guesses. The one behavior this flips is a
 * numeric-looking pair: `"10" < "9"` was lexicographic `true` and is now numeric `false`, which is the meaning
 * an author comparing those actually wants.
 */
@KdrPrivate
fun compareOp(state: ScriptState, op: String, l: Any?, r: Any?): Boolean {
    val ln = numOperand(l) ?: throw mkTypeMismatch(state, op, l, r)
    val rn = numOperand(r) ?: throw mkTypeMismatch(state, op, l, r)
    val cmp = if (ln is Long && rn is Long) ln.compareTo(rn) else toD(ln).compareTo(toD(rn))
    return when (op) {
        "<" -> cmp < 0
        ">" -> cmp > 0
        "<=" -> cmp <= 0
        else -> cmp >= 0
    }
}

/**
 * Equality within a kind. Comparing against `null` is always legal -- it is how a template asks whether a value
 * is there -- but a number against a string is a type mismatch, on the same reasoning as everywhere else here:
 * quietly answering `false` would hide the mistake instead of naming it.
 */
@KdrPrivate
fun valuesEqual(state: ScriptState, l: Any?, r: Any?): Boolean {
    if (l == null || r == null) return l == null && r == null
    val ln = numOf(l)
    val rn = numOf(r)
    if (ln != null && rn != null) {
        return if (ln is Long && rn is Long) ln == rn else toD(ln) == toD(rn)
    }
    if (l is String && r is String) return l == r
    if (l is Boolean && r is Boolean) return l == r
    if (l is Map<*, *> && r is Map<*, *>) return l == r
    if (l is Collection<*> && r is Collection<*>) return l == r
    throw mkTypeMismatch(state, "==", l, r)
}

/**
 * Whether [v] counts as true. Stated in the file header; the notable case is that an empty string and an
 * empty collection are false, so `${items ? "some" : "none"}` reads the way an author expects.
 */
@KdrPrivate
fun truthy(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is Long -> v != 0L
    is Int -> v != 0
    is Double -> v != 0.0
    is String -> v.isNotEmpty()
    is Map<*, *> -> v.isNotEmpty()
    is Collection<*> -> v.isNotEmpty()
    else -> true
}

/**
 * [v] as a number, or null when it is not one -- and a **string is never one** here, however numeric it looks.
 * This is the *same-kind* reading: it is what equality ([valuesEqual]) and the functions (`abs`) use, so
 * `"1.0" == "1"` stays a text comparison (false) and `abs("42")` stays a type mismatch, on the rule that a
 * function names its kinds. The numeric *operators* read a numeric string as a number instead -- see
 * [numOperand], which they call. Booleans are not numbers either.
 */
@KdrPrivate
fun numOf(v: Any?): Any? = when (v) {
    is Long, is Double -> v
    is Int -> v.toLong()
    is Float -> v.toDouble()
    else -> null
}

/**
 * [v] as a number for a **numeric operator** (`+ - * / %`, unary `-`, and `< > <= >=`), or null when it is not
 * one (issue #608). Unlike [numOf] this coerces a **cleanly-numeric string**: the whole string, once trimmed,
 * must parse as a number, so `"42"` is `42L` and `"2.9"` is `2.9`, while `"2100abc"` (or `""`) stays null and
 * the operator reports a type mismatch. Integer strings stay `Long` so `"7" / "2"` is `3`, matching a literal
 * `7 / 2`; a fractional string makes it a `Double`.
 *
 * Trimming first is deliberate on two counts: it matches the schema layer's `allowCoerce` (`ConvertUtil`), the
 * coercion this issue is bringing the operators in line with, and it keeps `" 42"` an integer -- without it,
 * `toLongOrNull` rejects the surrounding space while `toDoubleOrNull` tolerates it, so a stray space would
 * silently flip the value from `Long` to `Double` (and inconsistently across JVM and JS).
 *
 * The operators coerce and equality does not, deliberately: an operator has already declared it wants a number
 * (the same reasoning that makes `~` and `+` two operators), and config values often arrive as text -- a quoted
 * JSON number, a fragment-pull result -- so demanding code-level type precision there is the friction issue
 * #608 removes. Equality is an identity check, not an arithmetic one, so `${status == "active"}` stays a text
 * comparison and `${flag == "true"}` on a real boolean still names the mistake.
 */
private fun numOperand(v: Any?): Any? =
    numOf(v) ?: (v as? String)?.trim()?.let { it.toLongOrNull() ?: it.toDoubleOrNull() }

private fun toD(n: Any?): Double = when (n) {
    is Long -> n.toDouble()
    is Double -> n
    else -> 0.0
}

private fun mkTypeMismatch(state: ScriptState, op: String, l: Any?, r: Any?) = mkScriptException(
    state, ScriptError.typeMismatch,
    if (r == null && op == "-") {
        "Template expression cannot apply '$op' to ${describeValue(l)}."
    } else {
        "Template expression cannot apply '$op' to ${describeValue(l)} and ${describeValue(r)}."
    },
)

private fun mkDivideByZero(state: ScriptState, op: String) = mkScriptException(
    state, ScriptError.divideByZero, "Template expression divides by zero with '$op'.",
)
