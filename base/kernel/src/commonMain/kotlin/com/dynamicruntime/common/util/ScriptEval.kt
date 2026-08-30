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
 *  - **A string is never a number.** `"3"` is text everywhere, so `${count + 1}` on a `"3"` is a type error
 *    rather than `4` or `"31"`. That error is the useful outcome: it says the value arrived as text when a
 *    number was expected, which both coercion and concatenation would have hidden. Converting at the boundary
 *    is `ConvertUtil`'s job (and the schema layer's), not an operator's.
 *  - **Truth**: null is false; a boolean is itself; a number is true when non-zero; a string is true when
 *    non-empty; a map or list is true when non-empty. Anything else is true.
 *  - **`+ - * / %` are arithmetic only.** A non-numeric operand is [ScriptError.typeMismatch]; dividing by
 *    zero is [ScriptError.divideByZero] rather than an infinity that would print.
 *  - **`~` joins text**, formatting each side with [fmt]. Separate from `+` so neither operator is ever
 *    ambiguous about what it is doing. Most templates need no operator at all -- `n=${count}` already
 *    concatenates by juxtaposition; `~` is for composing inside an expression, e.g., a ternary branch.
 *  - **Numbers stay integral where they start.** Two `Long`s divide as integers (`7 / 2` is 3); one `Double`
 *    makes the result a `Double`.
 *  - **`< > <= >=`** compare two numbers numerically and two strings lexicographically. Mixed kinds are a type
 *    mismatch rather than a guess.
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
 * The error codes a guarded `@t` absorbs: the ones meaning "the data was not there", never "the template is
 * wrong". That line is what keeps `?:` a statement about a missing value rather than a blanket catch -- a
 * fragment with a syntax error or a type mismatch still throws, or the defect would be hidden everywhere the
 * fragment is used. [ScriptError.fragmentNotFound] counts as absence: a pull naming nothing is a missing value.
 */
private val absenceErrors = setOf(
    ScriptError.missingKey,
    ScriptError.nullValue,
    ScriptError.notAnObject,
    ScriptError.fragmentNotFound,
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
        else -> when (val n = numOf(v)) { // unary minus
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

/** Arithmetic over real numbers only, staying in `Long` while both sides are integral. */
@KdrPrivate
fun arith(state: ScriptState, op: String, l: Any?, r: Any?): Any {
    val ln = numOf(l) ?: throw mkTypeMismatch(state, op, l, r)
    val rn = numOf(r) ?: throw mkTypeMismatch(state, op, l, r)
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

/** Ordering comparison: numeric when both sides are numeric, lexicographic when both are strings. */
@KdrPrivate
fun compareOp(state: ScriptState, op: String, l: Any?, r: Any?): Boolean {
    val ln = numOf(l)
    val rn = numOf(r)
    val cmp = if (ln != null && rn != null) {
        if (ln is Long && rn is Long) ln.compareTo(rn) else toD(ln).compareTo(toD(rn))
    } else if (l is String && r is String) {
        l.compareTo(r)
    } else {
        throw mkTypeMismatch(state, op, l, r)
    }
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
 * [v] as a number, or null when it is not one -- and a **string is never one**, however numeric it looks.
 * `"3" * 2` reports a type mismatch instead of quietly being 6, so a value that reached the template as text
 * gets fixed where it was produced rather than papered over here. Booleans are not numbers either.
 */
@KdrPrivate
fun numOf(v: Any?): Any? = when (v) {
    is Long, is Double -> v
    is Int -> v.toLong()
    is Float -> v.toDouble()
    else -> null
}

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
