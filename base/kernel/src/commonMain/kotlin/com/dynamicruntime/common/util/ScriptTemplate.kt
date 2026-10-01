package com.dynamicruntime.common.util

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.KdrException

/**
 * A template parsed **once** into its pieces (issue #909, phase B): literal text, and each `prefix{...}` block
 * as its parsed expression -- or, where the block would not parse, the problem with it. The one scan of the
 * document every template operation now shares: evaluating ([evalTemplate]), analyzing without evaluating
 * ([analyzeTemplate]), and analyzing while evaluating. Before this, evaluation and analysis each walked the
 * text with their own scanner, two walks that had to agree about every escape and quote.
 *
 * A doubled prefix is already resolved to one literal prefix in a text piece; a lone prefix is plain text.
 * [blockCount] counts every block opened, well-formed or not (issue #514).
 */
@KdrPrivate
class ParsedTemplate(val pieces: List<TemplatePiece>, val blockCount: Int)

/** One piece of a [ParsedTemplate]. */
@KdrPrivate
sealed interface TemplatePiece

/** Literal text, copied to the output as it is. */
@KdrPrivate
class TextPiece(val text: String) : TemplatePiece

/**
 * A block, with the position of its prefix character (0-based [offset], [line] and [col]) -- where every
 * problem with it is reported, as before.
 */
@KdrPrivate
sealed interface BlockPiece : TemplatePiece {
    val offset: Int
    val line: Int
    val col: Int
}

/** A block whose expression parsed: [node] is what evaluation walks. */
@KdrPrivate
class ParsedBlock(
    val node: ScriptNode,
    override val offset: Int,
    override val line: Int,
    override val col: Int,
) : BlockPiece

/**
 * A block that would not parse: [error] is the exception the throwing form raises -- the parser's own, built
 * where the fault was found, with its code and position -- and [issue] is the same problem as a report reads it.
 */
@KdrPrivate
class FailedBlock(
    val error: KdrException,
    val issue: TemplateIssue,
    override val offset: Int,
    override val line: Int,
    override val col: Int,
) : BlockPiece

/**
 * Scans [state]'s text once into its pieces. Recovery is the analysis pass's, unchanged: when a block's
 * *expression* fails to parse, the block's extent is still known, so the scan resumes after it and later blocks
 * are parsed too; when the block itself never closes, nothing after it can be trusted to be text, so it is the
 * last piece.
 *
 * The expression parser reports a fault by throwing (deep in a recursive descent, as JSON's reader does); that
 * throw is caught here, once per block, and kept as the block's problem -- never thrown past this module.
 */
@KdrPrivate
fun parseTemplate(state: ScriptState): ParsedTemplate {
    val str = state.str
    val prefix = state.prefix
    val pieces = mutableListOf<TemplatePiece>()
    val text = StringBuilder()
    var blocks = 0
    fun flushText() {
        if (text.isNotEmpty()) {
            pieces.add(TextPiece(text.toString()))
            text.clear()
        }
    }
    fun failed(e: KdrException, issue: TemplateIssue = issueOf(e, state)) =
        FailedBlock(e, issue, state.blockOffset, state.blockLine, state.blockCol)
    while (state.offset < state.end) {
        val ch = str[state.offset]
        if (ch != prefix) {
            text.append(ch)
            state.advance(ch)
            continue
        }
        // Saw the prefix; decide what it introduces by looking one character ahead.
        val next = if (state.offset + 1 < state.end) str[state.offset + 1] else ' '
        when (next) {
            '{' -> {
                blocks++
                flushText()
                state.captureBlockStart() // Remember the prefix position for error reporting.
                state.advance(ch) // consume prefix
                state.advance(str[state.offset]) // consume '{'
                val expr = try {
                    readExpression(state)
                } catch (e: KdrException) {
                    // The block never closed: the rest of the document cannot be trusted to be text.
                    pieces.add(failed(e))
                    return ParsedTemplate(pieces, blocks)
                }
                if (expr.isBlank()) {
                    val e = mkScriptException(
                        state, ScriptError.emptyExpression, "Template has an empty '$prefix{}' expression.",
                    )
                    val issue = TemplateIssue(
                        ScriptError.emptyExpression, "Empty '$prefix{}' expression.",
                        state.blockOffset, state.blockLine + 1, state.blockCol + 1,
                    )
                    pieces.add(failed(e, issue))
                    continue
                }
                pieces.add(
                    try {
                        ParsedBlock(
                            ScriptParser(state, tokenize(state, expr)).parseAll(),
                            state.blockOffset, state.blockLine, state.blockCol,
                        )
                    } catch (e: KdrException) {
                        failed(e)
                    },
                )
            }
            prefix -> {
                // A doubled prefix is an escape for a single literal prefix (e.g. "$$" -> "$").
                text.append(prefix)
                state.advance(ch)
                state.advance(str[state.offset]) // consume the second prefix
            }
            else -> {
                // A lone prefix (not opening a block, not escaped) is literal text.
                text.append(ch)
                state.advance(ch)
            }
        }
    }
    flushText()
    return ParsedTemplate(pieces, blocks)
}

/**
 * The outcome of rendering a [ParsedTemplate]: the text when every block rendered, and the problems in document
 * order otherwise, each with the block it came from ([failures]). [errors] are the exceptions the throwing form
 * raises, so it raises exactly what it always did.
 */
@KdrPrivate
class TemplateRender(val value: String?, val failures: List<BlockFailure>) {
    val errors: List<KdrException> get() = failures.map { it.error }
}

/**
 * One block's problem in a [TemplateRender]: the [block] it belongs to, and the [error] raised for it. The block is
 * kept because the error's own position is not always the block's -- a problem inside a pulled `@t` fragment
 * carries its position in the *fragment's* text.
 */
@KdrPrivate
class BlockFailure(val block: BlockPiece, val error: KdrException)

/**
 * Evaluates [parsed] against [data], in document order. With [collectAll] off it stops at the first problem --
 * which is the one the throwing form raises, the same one it raised when parse and evaluation were interleaved,
 * since a block that failed to parse is met in its place. With it on, every block is attempted and every
 * problem kept, which is what a report wants.
 *
 * Evaluation reports a fault by throwing from deep in the expression walk; it is caught here, once per block.
 * Only a [KdrException] is: anything else is a fault in the evaluator, not in the template or its data, and
 * propagates.
 */
@KdrPrivate
fun renderTemplate(
    state: ScriptState,
    parsed: ParsedTemplate,
    data: Map<String, Any?>,
    collectAll: Boolean,
): TemplateRender {
    val out = StringBuilder()
    val failures = mutableListOf<BlockFailure>()
    for (piece in parsed.pieces) {
        when (piece) {
            is TextPiece -> out.append(piece.text)
            is FailedBlock -> {
                failures.add(BlockFailure(piece, piece.error))
                if (!collectAll) return TemplateRender(null, failures)
            }
            is ParsedBlock -> {
                // Evaluation reports against the block it is in, as when it ran during the scan.
                state.blockOffset = piece.offset
                state.blockLine = piece.line
                state.blockCol = piece.col
                try {
                    // The whole-document switch (`allowMissingOrNull`) applies at the outermost evaluation, so it
                    // keeps meaning "an absent value prints as nothing"; `?:` and a ternary condition apply their
                    // own per-expression tolerance inside (see `ScriptEval.kt`).
                    val value = evalNode(state, data, piece.node, tolerant = state.allowMissingOrNull, depth = 0)
                    // A null survives to here only under the tolerant switch, or from an explicit `null` / a `?:`
                    // whose right side is null. Printing "null" into a document is never what was meant.
                    if (value != null) out.append(value.fmt())
                } catch (e: KdrException) {
                    failures.add(BlockFailure(piece, e))
                    if (!collectAll) return TemplateRender(null, failures)
                }
            }
        }
    }
    return TemplateRender(if (failures.isEmpty()) out.toString() else null, failures)
}

/** Reads a template error back into a [TemplateIssue], keeping its code and position. */
@KdrPrivate
fun issueOf(e: KdrException, state: ScriptState): TemplateIssue = TemplateIssue(
    e.extraData[KdrException.errorCodeKey] as? ScriptError ?: ScriptError.syntaxError,
    e.message ?: "Template expression is not valid.",
    e.extraData[KdrException.offsetKey] as? Int ?: state.blockOffset,
    e.extraData[KdrException.lineKey] as? Int ?: (state.blockLine + 1),
    e.extraData[KdrException.lineColKey] as? Int ?: (state.blockCol + 1),
)
