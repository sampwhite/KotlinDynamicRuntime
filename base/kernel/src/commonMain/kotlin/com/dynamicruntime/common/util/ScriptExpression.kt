package com.dynamicruntime.common.util

import com.dynamicruntime.common.exception.KdrException

/**
 * One script expression, written bare -- `perItemAmount * itemCount`, the inside of a `${...}` block without the
 * braces -- parsed once and evaluated to a **value** (issue #1501), where [evalTemplate] always renders text. The
 * grammar is the template's own (literals, arithmetic, `~`, comparison, `&& || !`, `?:`, the ternary, and the
 * built-in functions), so an expression a template accepts here means the same thing.
 *
 * Built for a value computed from data and stored, such as a schema function's: the paths it reads ([paths]) let a
 * definition be checked against the type it reads before anything is evaluated, and [pullsFragments] lets a caller
 * refuse `@t`, which has no copy to pull from when an expression is evaluated over data.
 *
 * Strict, as a template is: a bare path that is absent or null throws ([evaluate]); `?:` says what to use instead.
 */
class ScriptExpression private constructor(
    /** The expression as written. */
    val text: String,
    private val node: ScriptNode,
) {
    /** The data paths the expression reads: required where read bare, optional where read under `?:` or a test. */
    val paths: TemplatePaths by lazy {
        val required = LinkedHashSet<String>()
        val optional = LinkedHashSet<String>()
        collectPaths(node, tolerant = false, required = required, optional = optional, depth = 0)
        TemplatePaths(required, optional - required)
    }

    /** Whether the expression pulls fragment copy with `@t`, literal key or computed. */
    val pullsFragments: Boolean by lazy { pulls(node, 0) }

    /**
     * The value of the expression over [data] -- a number, text, a boolean, a list, a map, or null -- or a thrown
     * [KdrException] carrying its `ScriptError` code, as a template's failure does. [env] is what the date functions
     * read (the zone and now); none reads the deployment's.
     */
    fun evaluate(data: Map<String, Any?>, env: ScriptEnv? = null): Any? =
        evalNode(ScriptState(text, '$', env = env), data, node, tolerant = false, depth = 0)

    private fun pulls(n: ScriptNode, depth: Int): Boolean {
        if (depth >= SEXP.maxDepth) return false
        val next = depth + 1
        return when (n) {
            is LiteralNode, is PathNode -> false
            is FragmentNode -> true
            is CallNode -> n.args.any { pulls(it, next) }
            is UnaryNode -> pulls(n.operand, next)
            is BinaryNode -> pulls(n.left, next) || pulls(n.right, next)
            is ElvisNode -> pulls(n.left, next) || pulls(n.right, next)
            is TernaryNode -> pulls(n.cond, next) || pulls(n.whenTrue, next) || pulls(n.whenFalse, next)
        }
    }

    companion object {
        /**
         * [text] parsed as one expression, or a thrown [KdrException] carrying the `ScriptError` code and where in
         * [text] the fault is: an empty expression, a syntax error, or trailing input.
         */
        fun parse(text: String): ScriptExpression {
            val state = ScriptState(text, '$')
            if (text.isBlank()) {
                throw mkScriptException(state, ScriptError.emptyExpression, "The script expression is empty.")
            }
            return ScriptExpression(text, ScriptParser(state, tokenize(state, text)).parseAll())
        }
    }
}
