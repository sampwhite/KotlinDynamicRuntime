package com.dynamicruntime.common.util

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.JsonMappable

/**
 * One problem found in a template, positioned in the source. Plain data over primitives, so it crosses the
 * wire as a map and reads the same in a browser as on the server.
 */
class TemplateIssue(
    val code: ScriptError,
    val message: String,
    val offset: Int,
    val line: Int,
    val col: Int,
) : JsonMappable {
    override fun toJsonMap(): Map<String, Any?> = mapOf(
        TISS.code to code.name,
        TISS.message to message,
        TISS.offset to offset,
        TISS.line to line,
        TISS.col to col,
    )
}

/** Field names for [TemplateIssue.toJsonMap]; each name matches its value. */
@Suppress("ConstPropertyName")
object TISS {
    const val code = "code"
    const val message = "message"
    const val offset = "offset"
    const val line = "line"
    const val col = "col"
}

/**
 * Checks every `${...}` in a template **without evaluating it**, returning what is wrong rather than throwing
 * at the first problem -- a check that stops at the first bad block would have an author fixing a file one
 * error per run.
 *
 * Parse-only, and that bounds what it can find. It catches an unterminated block, an unterminated string, a
 * stray character, a malformed expression, one nested past the depth cap: everything that is wrong about the
 * *template*. It cannot catch a missing key or a type mismatch, because those are facts about the data a
 * caller will supply, not about the text. A missing key is answerable without evaluating -- see
 * [TemplatePaths.missingFrom], which checks required paths against a map -- but a *type* mismatch is not, and
 * is deferred: see `deferred-work.md#when-fragment-copy-computes-with-its-data`.
 *
 * Recovery after a bad block is deliberately coarse. When the *expression* fails to parse, the block's extent
 * is still known, so scanning resumes after it and later blocks are reported too. When the block itself never
 * closes, there is nothing trustworthy left to resume from, so that issue is the last one reported.
 */
fun String.checkTemplateSyntax(prefix: Char = '$'): List<TemplateIssue> = analyzeTemplate(prefix).issues

/**
 * What a template asks of its data: the paths it will read, split by whether an absent value is an **error**
 * or something the template already handles.
 *
 * "Required" means *referenced somewhere that would throw*, which deliberately over-approximates. Both arms of
 * `${c ? a : b}` are required though only one is evaluated, and the right of `&&` is required though it may be
 * short-circuited away -- because either could run, and a caller that cannot supply one of them has a latent
 * failure either way. Reading "required" as "referenced" is the honest description.
 */
class TemplatePaths(val required: Set<String>, val optional: Set<String>)

/** A template's problems and its data requirements, from one parse. */
class TemplateAnalysis(val issues: List<TemplateIssue>, val paths: TemplatePaths)

/**
 * Parses every block once, collecting both what is wrong with the template and what it asks of its data.
 * Single-pass because the two answers come from the same syntax tree, and parsing twice would let them
 * disagree about a template that only half-parses.
 */
fun String.analyzeTemplate(prefix: Char = '$'): TemplateAnalysis {
    val issues = mutableListOf<TemplateIssue>()
    val required = mutableSetOf<String>()
    val optional = mutableSetOf<String>()
    val state = ScriptState(this, prefix)
    while (state.offset < state.end) {
        val ch = this[state.offset]
        if (ch != prefix) {
            state.advance(ch)
            continue
        }
        val next = if (state.offset + 1 < state.end) this[state.offset + 1] else ' '
        when (next) {
            '{' -> {
                state.captureBlockStart()
                state.advance(ch)
                state.advance(this[state.offset])
                val expr = try {
                    readExpression(state)
                } catch (e: KdrException) {
                    // The block never closed: the rest of the document cannot be trusted to be text.
                    issues.add(issueOf(e, state))
                    return TemplateAnalysis(issues, mkPaths(required, optional))
                }
                if (expr.isBlank()) {
                    issues.add(
                        TemplateIssue(
                            ScriptError.emptyExpression, "Empty '$prefix{}' expression.",
                            state.blockOffset, state.blockLine + 1, state.blockCol + 1,
                        ),
                    )
                    continue
                }
                try {
                    val node = ScriptParser(state, tokenize(state, expr)).parseAll()
                    collectPaths(node, tolerant = false, required = required, optional = optional, depth = 0)
                } catch (e: KdrException) {
                    issues.add(issueOf(e, state))
                }
            }
            prefix -> {
                state.advance(ch)
                state.advance(this[state.offset])
            }
            else -> state.advance(ch)
        }
    }
    return TemplateAnalysis(issues, mkPaths(required, optional))
}

/** A path read in both a guarded and an unguarded place is required: the unguarded read is what decides. */
private fun mkPaths(required: Set<String>, optional: Set<String>) =
    TemplatePaths(required, optional - required)

/**
 * Walks a parsed expression recording which paths it reads, and whether each read is in a position that
 * tolerates absence.
 *
 * The tolerant positions are **exactly** the ones `evalNode` honors -- the left of `?:`, a ternary condition,
 * and either side of a comparison against the literal `null` -- and they must stay exactly the same. If this
 * walk were stricter than the evaluator, every `${a ?: "x"}` would be reported as a missing key; if it were
 * more lenient, a genuine break would go unreported. That is why the null-test rule is shared rather than
 * restated ([isNullLiteral]).
 *
 * Carries a depth for the house rule on recursion over external data. The tree cannot exceed the parser's own
 * cap, so this is belt and braces rather than the real defense.
 */
@KdrPrivate
fun collectPaths(
    node: ScriptNode,
    tolerant: Boolean,
    required: MutableSet<String>,
    optional: MutableSet<String>,
    depth: Int,
) {
    if (depth >= SEXP.maxDepth) return
    val next = depth + 1
    when (node) {
        is LiteralNode -> {}
        is PathNode -> (if (tolerant) optional else required).add(node.text)
        is CallNode -> node.args.forEach { collectPaths(it, tolerant, required, optional, next) }
        is UnaryNode -> collectPaths(node.operand, tolerant, required, optional, next)
        is BinaryNode -> {
            val nullTest = (node.op == "==" || node.op == "!=") &&
                (isNullLiteral(node.left) || isNullLiteral(node.right))
            val t = tolerant || nullTest
            collectPaths(node.left, t, required, optional, next)
            collectPaths(node.right, t, required, optional, next)
        }
        is ElvisNode -> {
            collectPaths(node.left, tolerant = true, required = required, optional = optional, depth = next)
            collectPaths(node.right, tolerant, required, optional, next)
        }
        is TernaryNode -> {
            collectPaths(node.cond, tolerant = true, required = required, optional = optional, depth = next)
            // Both arms, though only one will run: either could, so a caller must be able to supply either.
            collectPaths(node.whenTrue, tolerant, required, optional, next)
            collectPaths(node.whenFalse, tolerant, required, optional, next)
        }
    }
}

/**
 * The required paths [data] would fail to supply -- the missing-key half of a render failure, answered without
 * evaluating anything. A path counts as unsupplied when a segment is absent, when something before the end is
 * not an object to drill into, or when the final value is null: each is what `resolvePath` throws on.
 */
fun TemplatePaths.missingFrom(data: Map<String, Any?>): List<String> = required.filter { path ->
    var current: Any? = data
    for (segment in path.split('.')) {
        val map = current as? Map<*, *> ?: return@filter true
        if (!map.containsKey(segment)) return@filter true
        current = map[segment]
    }
    current == null
}.sorted()

/** Reads a thrown template error back into a [TemplateIssue], keeping its code and position. */
private fun issueOf(e: KdrException, state: ScriptState): TemplateIssue = TemplateIssue(
    e.extraData[KdrException.errorCodeKey] as? ScriptError ?: ScriptError.syntaxError,
    e.message ?: "Template expression is not valid.",
    e.extraData[KdrException.offsetKey] as? Int ?: state.blockOffset,
    e.extraData[KdrException.lineKey] as? Int ?: (state.blockLine + 1),
    e.extraData[KdrException.lineColKey] as? Int ?: (state.blockCol + 1),
)

/**
 * Checks every value of a parsed fragment file (`namespace -> key -> value`), returning the issues found with
 * each one's `namespace.key` prefixed onto the message -- a file-level check is only useful if it says *which*
 * entry is broken.
 */
/** One fragment entry's data requirements, named by its `namespace.key` so a report can point at it. */
class FragmentEntryPaths(val entry: String, val paths: TemplatePaths)

/**
 * The data requirements of every entry in a parsed fragment file. Entries that read nothing are dropped: a
 * report listing every piece of static copy would bury the handful that actually depend on a caller.
 */
fun Map<String, Map<String, String>>.fragmentPaths(prefix: Char = '$'): List<FragmentEntryPaths> {
    val out = mutableListOf<FragmentEntryPaths>()
    for ((namespace, entries) in this) {
        for ((key, value) in entries) {
            val paths = value.analyzeTemplate(prefix).paths
            if (paths.required.isNotEmpty() || paths.optional.isNotEmpty()) {
                out.add(FragmentEntryPaths("$namespace.$key", paths))
            }
        }
    }
    return out
}

fun Map<String, Map<String, String>>.checkFragmentSyntax(prefix: Char = '$'): List<TemplateIssue> {
    val issues = mutableListOf<TemplateIssue>()
    for ((namespace, entries) in this) {
        for ((key, value) in entries) {
            for (issue in value.checkTemplateSyntax(prefix)) {
                issues.add(
                    TemplateIssue(
                        issue.code, "$namespace.$key: ${issue.message}", issue.offset, issue.line, issue.col,
                    ),
                )
            }
        }
    }
    return issues
}
