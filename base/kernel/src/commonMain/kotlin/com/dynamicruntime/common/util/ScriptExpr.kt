package com.dynamicruntime.common.util

import com.dynamicruntime.common.annotation.KdrPrivate

/**
 * The expression grammar inside a `${...}` block -- literals, operators, a conditional, and a default -- sitting
 * behind [evalTemplate]'s substitution engine (see `ScriptUtil.kt`, which owns the surrounding text scan).
 *
 * Kept in its own file because the two jobs are genuinely different: `ScriptUtil` walks characters of a
 * document deciding what is text and what is a block, while this parses one small language. Both are pure,
 * KMP-safe Kotlin, so the browser evaluates a template exactly as the backend does.
 *
 * Grammar, loosest binding first (each line binds tighter than the one above):
 *
 * ```
 * expression     := ternary
 * ternary        := elvis [ '?' expression ':' expression ]     // right-associative
 * elvis          := or [ '?:' elvis ]                           // right-associative
 * or             := and { '||' and }
 * and            := equality { '&&' equality }
 * equality       := comparison { ('==' | '!=') comparison }
 * comparison     := concat { ('<' | '>' | '<=' | '>=') concat }
 * concat         := additive { '~' additive }                   // text join, looser than arithmetic
 * additive       := multiplicative { ('+' | '-') multiplicative }
 * multiplicative := unary { ('*' | '/' | '%') unary }
 * unary          := ('!' | '-') unary | primary
 * primary        := number | string | 'true' | 'false' | 'null' | path | '(' expression ')'
 * path           := ident { '.' ident }
 * ```
 *
 * **Where a missing value is tolerated.** A bare path that is absent or null is still an error, exactly as
 * before this grammar existed -- that is what makes a typo in a fragment loud. Three positions deliberately
 * tolerate it instead, because in each the author has already said what should happen when the value is not
 * there: the **left side of `?:`**, the **condition of `? :`** (an absent flag is simply falsy), and either
 * side of a **comparison against `null`** (which is the presence test itself). Nowhere else, so `${a + 1}`
 * with no `a` still reports [ScriptError.missingKey] against the block's position.
 */
@Suppress("ConstPropertyName")
object SEXP {
    /**
     * Nesting cap for the recursive descent below, per the house rule that recursion over external data
     * carries a depth. Templates are authored content and are meant to become browser-editable, so
     * `${((((...))))}` must report an error rather than exhaust the stack.
     */
    const val maxDepth = 32
}

// --- tokens -----------------------------------------------------------------

/** What a lexed [Token] is. A closed operational set, so an enum rather than string constants. */
@Suppress("EnumEntryName")
enum class TokenKind { number, text, ident, op, end }

/** One lexed token: its [kind], the source [text], a literal [value] for numbers/strings, and its position. */
@KdrPrivate
class Token(val kind: TokenKind, val text: String, val value: Any?, val pos: Int)

/** The multi-character operators, longest first so `<=` is never read as `<` then `=`. */
private val multiCharOps = listOf("?:", "==", "!=", "<=", ">=", "&&", "||")

private val singleCharOps = "?:+-*/%<>!().~".toSet()

/**
 * Splits an expression into tokens. Errors carry [ScriptError.syntaxError] and, like every template error,
 * point at the start of the enclosing `${` block rather than into the expression -- the block is what an
 * author sees in their document.
 */
@KdrPrivate
fun tokenize(state: ScriptState, expr: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var i = 0
    while (i < expr.length) {
        val ch = expr[i]
        when {
            ch.isWhitespace() -> i++
            ch.isDigit() -> {
                val start = i
                while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                val text = expr.substring(start, i)
                // Long when integral and Double otherwise, matching how JsonUtil reads numbers -- so a literal
                // and a value that arrived through JSON behave identically in arithmetic.
                val value: Any = if (text.contains('.')) {
                    text.toDoubleOrNull() ?: throw mkScriptException(
                        state, ScriptError.syntaxError, "Template expression has a malformed number '$text'.",
                    )
                } else {
                    text.toLongOrNull() ?: throw mkScriptException(
                        state, ScriptError.syntaxError, "Template expression has a number too large: '$text'.",
                    )
                }
                tokens.add(Token(TokenKind.number, text, value, start))
            }
            ch == '"' || ch == '\'' -> {
                val start = i
                i++ // opening quote
                val sb = StringBuilder()
                var closed = false
                while (i < expr.length) {
                    val c = expr[i]
                    if (c == '\\' && i + 1 < expr.length) {
                        sb.append(unescape(expr[i + 1]))
                        i += 2
                        continue
                    }
                    if (c == ch) {
                        i++
                        closed = true
                        break
                    }
                    sb.append(c)
                    i++
                }
                if (!closed) {
                    throw mkScriptException(
                        state, ScriptError.syntaxError, "Template expression has an unterminated string literal.",
                    )
                }
                tokens.add(Token(TokenKind.text, sb.toString(), sb.toString(), start))
            }
            ch.isLetter() || ch == '_' -> {
                val start = i
                while (i < expr.length && (expr[i].isLetterOrDigit() || expr[i] == '_')) i++
                tokens.add(Token(TokenKind.ident, expr.substring(start, i), null, start))
            }
            else -> {
                val two = if (i + 1 < expr.length) expr.substring(i, i + 2) else ""
                val op = multiCharOps.firstOrNull { it == two }
                if (op != null) {
                    tokens.add(Token(TokenKind.op, op, null, i))
                    i += 2
                } else if (ch in singleCharOps) {
                    tokens.add(Token(TokenKind.op, ch.toString(), null, i))
                    i++
                } else {
                    throw mkScriptException(
                        state, ScriptError.syntaxError, "Template expression has an unexpected character '$ch'.",
                    )
                }
            }
        }
    }
    tokens.add(Token(TokenKind.end, "", null, expr.length))
    return tokens
}

private fun unescape(ch: Char): Char = when (ch) {
    'n' -> '\n'
    't' -> '\t'
    'r' -> '\r'
    else -> ch // covers \" \' \\ and anything else, which stands for itself
}

// --- syntax tree ------------------------------------------------------------

/**
 * A parsed expression. Built before evaluation rather than interpreted while parsing, because `?:` and `? :`
 * must *not* evaluate the branch they do not take -- and a side of `?:` that would throw is exactly the side
 * being guarded against.
 */
@KdrPrivate
sealed interface ScriptNode

@KdrPrivate
class LiteralNode(val value: Any?) : ScriptNode

@KdrPrivate
class PathNode(val segments: List<String>, val text: String) : ScriptNode

@KdrPrivate
class UnaryNode(val op: String, val operand: ScriptNode) : ScriptNode

@KdrPrivate
class BinaryNode(val op: String, val left: ScriptNode, val right: ScriptNode) : ScriptNode

@KdrPrivate
class ElvisNode(val left: ScriptNode, val right: ScriptNode) : ScriptNode

@KdrPrivate
class TernaryNode(val cond: ScriptNode, val whenTrue: ScriptNode, val whenFalse: ScriptNode) : ScriptNode

// --- parser -----------------------------------------------------------------

/** Recursive-descent parser over [tokenize]'s output. One instance per expression; [pos] is the read cursor. */
@KdrPrivate
class ScriptParser(val state: ScriptState, val tokens: List<Token>) {
    var pos: Int = 0

    fun peek(): Token = tokens[pos]

    fun atOp(vararg ops: String): String? {
        val t = peek()
        return if (t.kind == TokenKind.op && ops.contains(t.text)) t.text else null
    }

    fun take(): Token = tokens[pos++]

    fun expectOp(op: String) {
        if (atOp(op) == null) {
            throw mkScriptException(
                state, ScriptError.syntaxError,
                "Template expression expected '$op' but found '${peek().text.ifEmpty { "end of expression" }}'.",
            )
        }
        pos++
    }

    /** Parses a whole expression and requires that nothing is left over. */
    fun parseAll(): ScriptNode {
        val node = parseExpr(0)
        if (peek().kind != TokenKind.end) {
            throw mkScriptException(
                state, ScriptError.syntaxError,
                "Template expression has unexpected trailing input at '${peek().text}'.",
            )
        }
        return node
    }

    fun parseExpr(depth: Int): ScriptNode = parseTernary(guard(depth))

    /** Throws past [SEXP.maxDepth]; every descent step passes `guard(depth)` so the count is real. */
    fun guard(depth: Int): Int {
        if (depth >= SEXP.maxDepth) {
            throw mkScriptException(
                state, ScriptError.expressionTooDeep,
                "Template expression nests deeper than ${SEXP.maxDepth} levels.",
            )
        }
        return depth + 1
    }

    fun parseTernary(depth: Int): ScriptNode {
        val cond = parseElvis(guard(depth))
        if (atOp("?") == null) return cond
        take() // '?'
        val whenTrue = parseExpr(guard(depth))
        expectOp(":")
        val whenFalse = parseExpr(guard(depth)) // right-associative: `a ? b : c ? d : e` chains to the right
        return TernaryNode(cond, whenTrue, whenFalse)
    }

    fun parseElvis(depth: Int): ScriptNode {
        val left = parseBinary(guard(depth), 0)
        if (atOp("?:") == null) return left
        take() // '?:'
        return ElvisNode(left, parseElvis(guard(depth)))
    }

    /**
     * The binary levels, driven by a precedence table rather than one function per level -- six near-identical
     * functions is the shape that drifts when an operator is added.
     */
    fun parseBinary(depth: Int, level: Int): ScriptNode {
        if (level >= binaryLevels.size) return parseUnary(guard(depth))
        var left = parseBinary(guard(depth), level + 1)
        while (true) {
            val op = atOp(*binaryLevels[level]) ?: return left
            take()
            val right = parseBinary(guard(depth), level + 1)
            left = BinaryNode(op, left, right)
        }
    }

    fun parseUnary(depth: Int): ScriptNode {
        val op = atOp("!", "-")
        if (op != null) {
            take()
            return UnaryNode(op, parseUnary(guard(depth)))
        }
        return parsePrimary(guard(depth))
    }

    fun parsePrimary(depth: Int): ScriptNode {
        val t = peek()
        when {
            t.kind == TokenKind.number || t.kind == TokenKind.text -> {
                take()
                return LiteralNode(t.value)
            }
            t.kind == TokenKind.ident -> {
                // The three word-literals are reserved; anything else starts a path.
                when (t.text) {
                    "true" -> { take(); return LiteralNode(true) }
                    "false" -> { take(); return LiteralNode(false) }
                    "null" -> { take(); return LiteralNode(null) }
                }
                val segments = mutableListOf(take().text)
                while (atOp(".") != null) {
                    take()
                    val seg = peek()
                    if (seg.kind != TokenKind.ident) {
                        throw mkScriptException(
                            state, ScriptError.syntaxError,
                            "Template expression expected a name after '.' but found '${seg.text}'.",
                        )
                    }
                    segments.add(take().text)
                }
                return PathNode(segments, segments.joinToString("."))
            }
            atOp("(") != null -> {
                take()
                val inner = parseExpr(guard(depth))
                expectOp(")")
                return inner
            }
            else -> throw mkScriptException(
                state, ScriptError.syntaxError,
                "Template expression has nothing to evaluate at '${t.text.ifEmpty { "end of expression" }}'.",
            )
        }
    }
}

/** Binary operators by precedence, loosest first. Mirrors the grammar in this file's header. */
private val binaryLevels: List<Array<String>> = listOf(
    arrayOf("||"),
    arrayOf("&&"),
    arrayOf("==", "!="),
    arrayOf("<", ">", "<=", ">="),
    // `~` sits between comparison and arithmetic, so `"n=" ~ count + 1` joins the *sum* rather than
    // concatenating first and then failing to add to text.
    arrayOf("~"),
    arrayOf("+", "-"),
    arrayOf("*", "/", "%"),
)
