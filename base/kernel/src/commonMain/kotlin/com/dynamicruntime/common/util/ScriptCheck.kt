package com.dynamicruntime.common.util

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
 * caller will supply, not about the text. Those remain runtime errors (`ScriptError.missingKey`,
 * `ScriptError.typeMismatch`), which is why the data-aware variant of this check is a separate, later step.
 *
 * Recovery after a bad block is deliberately coarse. When the *expression* fails to parse, the block's extent
 * is still known, so scanning resumes after it and later blocks are reported too. When the block itself never
 * closes, there is nothing trustworthy left to resume from, so that issue is the last one reported.
 */
fun String.checkTemplateSyntax(prefix: Char = '$'): List<TemplateIssue> {
    val issues = mutableListOf<TemplateIssue>()
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
                    return issues
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
                    ScriptParser(state, tokenize(state, expr)).parseAll()
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
    return issues
}

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
