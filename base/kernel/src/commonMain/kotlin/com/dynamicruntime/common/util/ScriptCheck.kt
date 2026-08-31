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
 *
 * What a `@t` pull's fragment reads is **not** included: seeing it means resolving the key against the
 * fragment registry, which this text-only walk has no access to (Phase 2 of issue #505). So [missingFrom] can
 * answer "nothing missing" for a template that still fails at render.
 */
class TemplatePaths(val required: Set<String>, val optional: Set<String>)

/**
 * One `@t` fragment reference with a **literal** key, found in a template, positioned at its block (issue #505).
 * A computed key (`@t(chosenKey)`) is not collected: it names a fragment only at evaluation time, so no static
 * check can resolve it -- the same over-approximation "required means referenced" already lives by.
 */
class TemplateRef(
    val key: String,
    /**
     * Whether the reference sits in a position that already handles absence -- the left of `?:`, a ternary
     * condition, a null test. Such a reference is **not** reported when it resolves to nothing: the author said
     * what happens instead. It still counts as an edge for cycle detection when it *does* resolve, because at
     * render time it is followed like any other.
     */
    val tolerant: Boolean,
    val offset: Int,
    val line: Int,
    val col: Int,
)

/** A template's problems, its data requirements, and its literal `@t` references, from one parse. */
class TemplateAnalysis(
    val issues: List<TemplateIssue>,
    val paths: TemplatePaths,
    val refs: List<TemplateRef> = emptyList(),
)

/**
 * Parses every block once, collecting both what is wrong with the template and what it asks of its data.
 * Single-pass because the two answers come from the same syntax tree, and parsing twice would let them
 * disagree about a template that only half-parses.
 */
fun String.analyzeTemplate(prefix: Char = '$'): TemplateAnalysis {
    val issues = mutableListOf<TemplateIssue>()
    val required = mutableSetOf<String>()
    val optional = mutableSetOf<String>()
    val refs = mutableListOf<TemplateRef>()
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
                    return TemplateAnalysis(issues, mkPaths(required, optional), refs)
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
                    collectFragmentRefs(
                        node, refs, tolerant = false,
                        offset = state.blockOffset, line = state.blockLine + 1, col = state.blockCol + 1,
                        depth = 0,
                    )
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
    return TemplateAnalysis(issues, mkPaths(required, optional), refs)
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
        is FragmentNode -> {
            // The key and the binding values read the caller's data, with the pull's own tolerance -- exactly
            // as `evalFragment` evaluates them.
            collectPaths(node.key, tolerant, required, optional, next)
            node.bindings.forEach { collectPaths(it.second, tolerant, required, optional, next) }
        }
    }
}

/**
 * Records every **literal-key** `@t` reference in the tree, positioned at [offset]/[line]/[col] (the block, as
 * every template error is). A separate walk from [collectPaths] rather than a parameter on it, matching how
 * `evalNode` and `collectPaths` each walk the tree for their own concern -- the exhaustive `when` makes a new
 * node type a compile error in all three, so they cannot drift.
 *
 * It carries **tolerance exactly as [collectPaths] does**, and for the same reason: `${@t("a.b") ?: "x"}` says
 * what to do when the fragment is not there, so reporting it as a missing reference would refuse content its
 * author already handled -- and on a strict boot, refuse to start. The flag rides on the ref rather than
 * dropping it here, because a *guarded* reference that does resolve is still a real edge at render time and
 * must still be seen by the cycle walk.
 */
@KdrPrivate
fun collectFragmentRefs(
    node: ScriptNode,
    refs: MutableList<TemplateRef>,
    tolerant: Boolean,
    offset: Int,
    line: Int,
    col: Int,
    depth: Int,
) {
    if (depth >= SEXP.maxDepth) return
    val next = depth + 1
    when (node) {
        is LiteralNode, is PathNode -> {}
        is CallNode -> node.args.forEach { collectFragmentRefs(it, refs, tolerant, offset, line, col, next) }
        is UnaryNode -> collectFragmentRefs(node.operand, refs, tolerant, offset, line, col, next)
        is BinaryNode -> {
            val nullTest = (node.op == "==" || node.op == "!=") &&
                (isNullLiteral(node.left) || isNullLiteral(node.right))
            val t = tolerant || nullTest
            collectFragmentRefs(node.left, refs, t, offset, line, col, next)
            collectFragmentRefs(node.right, refs, t, offset, line, col, next)
        }
        is ElvisNode -> {
            collectFragmentRefs(node.left, refs, tolerant = true, offset = offset, line = line, col = col, depth = next)
            collectFragmentRefs(node.right, refs, tolerant, offset, line, col, next)
        }
        is TernaryNode -> {
            collectFragmentRefs(node.cond, refs, tolerant = true, offset = offset, line = line, col = col, depth = next)
            collectFragmentRefs(node.whenTrue, refs, tolerant, offset, line, col, next)
            collectFragmentRefs(node.whenFalse, refs, tolerant, offset, line, col, next)
        }
        is FragmentNode -> {
            val k = node.key
            // Only a literal key is a static reference; a computed one is a runtime concern.
            if (k is LiteralNode && k.value is String) {
                refs.add(TemplateRef(k.value, tolerant, offset, line, col))
            }
            collectFragmentRefs(node.key, refs, tolerant, offset, line, col, next)
            node.bindings.forEach { collectFragmentRefs(it.second, refs, tolerant, offset, line, col, next) }
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

/** One fragment entry's data requirements, named by its `namespace.key` so a report can point at it. */
class FragmentEntryPaths(val entry: String, val paths: TemplatePaths)

/**
 * Everything a fragment file's check needs, from **one parse of each value** ([analyzeFragmentFile]). The
 * issue lists stay separate because they answer different questions and a caller may want one without the
 * other; they are produced together because [analyzeTemplate] already yields all of it per value, and parsing
 * three times was three chances to disagree about a value that only half-parses.
 */
class FragmentFileAnalysis(
    /** Malformed templates, each message prefixed with the `namespace.key` that carries it. */
    val syntaxIssues: List<TemplateIssue>,
    /** Unresolved `@t` references and reference cycles; see [checkFragmentReferences]. */
    val referenceIssues: List<TemplateIssue>,
    /** Per-entry data requirements; entries that read nothing are dropped. */
    val entryPaths: List<FragmentEntryPaths>,
)

/**
 * Parses every value of a fragment file **once** and derives all three checks from it (issue #505).
 *
 * The reference rules are [checkFragmentReferences]'s; the path rules are [fragmentPaths]'s. Both, plus the
 * syntax issues, come out of the single [analyzeTemplate] per value that already computes them.
 */
fun Map<String, Map<String, String>>.analyzeFragmentFile(prefix: Char = '$'): FragmentFileAnalysis {
    val syntax = mutableListOf<TemplateIssue>()
    val references = mutableListOf<TemplateIssue>()
    val paths = mutableListOf<FragmentEntryPaths>()
    val edges = LinkedHashMap<String, List<String>>()

    for ((namespace, entries) in this) {
        for ((key, value) in entries) {
            val id = "$namespace.$key"
            val analysis = value.analyzeTemplate(prefix)

            for (issue in analysis.issues) {
                syntax.add(TemplateIssue(issue.code, "$id: ${issue.message}", issue.offset, issue.line, issue.col))
            }
            if (analysis.paths.required.isNotEmpty() || analysis.paths.optional.isNotEmpty()) {
                paths.add(FragmentEntryPaths(id, analysis.paths))
            }

            val resolvedTargets = mutableListOf<String>()
            for (ref in analysis.refs) {
                if (resolveFragment(ref.key) != null) {
                    // A resolvable two-part key names exactly the entry `ref.key`, so it is an edge to it --
                    // guarded or not, since at render time a guarded reference that resolves is still followed.
                    resolvedTargets.add(ref.key)
                } else if (!ref.tolerant) {
                    references.add(
                        TemplateIssue(
                            ScriptError.fragmentNotFound,
                            "$id: references fragment '${ref.key}', which is not defined.",
                            ref.offset, ref.line, ref.col,
                        ),
                    )
                }
                // A tolerant reference that resolves to nothing is silent: `?:` already says what happens.
            }
            edges[id] = resolvedTargets
        }
    }
    references.addAll(fragmentReferenceCycles(edges))
    return FragmentFileAnalysis(syntax, references, paths)
}

/**
 * The data requirements of every entry in a parsed fragment file. Entries that read nothing are dropped: a
 * report listing every piece of static copy would bury the handful that actually depend on a caller.
 */
fun Map<String, Map<String, String>>.fragmentPaths(prefix: Char = '$'): List<FragmentEntryPaths> =
    analyzeFragmentFile(prefix).entryPaths

/**
 * Checks every value of a parsed fragment file (`namespace -> key -> value`), returning the issues found with
 * each one's `namespace.key` prefixed onto the message -- a file-level check is only useful if it says *which*
 * entry is broken.
 */
fun Map<String, Map<String, String>>.checkFragmentSyntax(prefix: Char = '$'): List<TemplateIssue> =
    analyzeFragmentFile(prefix).syntaxIssues

/**
 * Validates the **frontend-pass** (`${@t(...)}`) references in a parsed fragment file (issue #505). Two
 * findings, both answerable from the file alone because a frontend reference resolves within the caller's own
 * file ([resolveFragment]):
 *
 *  - a **literal reference that resolves to nothing** -- almost always a renamed or misspelled key, and a
 *    render-time failure a user would hit;
 *  - a **cycle** among entries connected by literal references (`a.x` pulls `b.y` pulls `a.x`), which at render
 *    time would only surface as the include-depth backstop firing.
 *
 * A *computed* key is out of scope here, as everywhere: it names a fragment only when it is evaluated. So is a
 * **guarded** one (`${@t("a.b") ?: "x"}`): its author already said what happens when the fragment is absent, so
 * reporting it would refuse content that is deliberately optional -- and on a strict boot, refuse to start. It
 * still forms an edge when it *does* resolve, because render time follows it like any other.
 *
 * Only references that resolve become edges, so a dangling one is reported once (as a missing reference)
 * rather than twice (again as a broken cycle edge).
 *
 * The **backend pass** (`%{@t("fileId.namespace.key")}`) is not validated here. It uses different addressing --
 * three parts, across the whole registry, not one file -- and it is not built yet (its prefix is not even
 * settled). Its validation lands with the backend pass, Phase 4 of #505. The [prefix] parameter scans a chosen
 * block delimiter, but the resolution is the frontend one, so validating the backend pass needs more than a
 * different prefix here.
 */
fun Map<String, Map<String, String>>.checkFragmentReferences(prefix: Char = '$'): List<TemplateIssue> =
    analyzeFragmentFile(prefix).referenceIssues

/**
 * Every distinct cycle in the reference graph [edges] (`entry -> entries it references`), each named by its
 * path (`a.x -> b.y -> a.x`). Ancestry-based, the same shape as the runtime cycle guard: a back-edge into the
 * path currently being walked is a cycle, while an entry reached twice down different branches is reuse.
 * Deduped by the set of entries involved, so one cycle reachable from several roots is reported once.
 */
private fun fragmentReferenceCycles(edges: Map<String, List<String>>): List<TemplateIssue> {
    val issues = mutableListOf<TemplateIssue>()
    val done = mutableSetOf<String>()
    val onPath = mutableSetOf<String>()
    val path = ArrayDeque<String>()
    val reported = mutableSetOf<Set<String>>()

    fun walk(node: String) {
        onPath.add(node)
        path.addLast(node)
        for (target in edges[node] ?: emptyList()) {
            if (target in onPath) {
                val cycle = path.toList().subList(path.indexOf(target), path.size) + target
                if (reported.add(cycle.dropLast(1).toSet())) {
                    issues.add(
                        TemplateIssue(
                            ScriptError.fragmentCycle,
                            "Fragment reference cycle: ${cycle.joinToString(" -> ")}.",
                            0, 1, 1,
                        ),
                    )
                }
            } else if (target !in done) {
                walk(target)
            }
        }
        path.removeLast()
        onPath.remove(node)
        done.add(node)
    }

    for (node in edges.keys) {
        if (node !in done) {
            walk(node)
        }
    }
    return issues
}
