package com.dynamicruntime.common.cfact

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.Problem
import com.dynamicruntime.common.util.ProblemCode
import com.dynamicruntime.common.util.ProblemLocation

/**
 * Parses a cfact expression into a [CFactPredicate] tree (issue #454).
 *
 * ```
 * expr    := operand (op operand)*     -- every op at one level must be the same
 * operand := '~'? atom
 * atom    := NAME | '(' expr ')'
 * ```
 *
 * `a,b,c` and `a|b|c` are fine. **`a,b|c` is refused**, because a reader would have to know which operator
 * binds tighter, and precedence that lives only in someone's head is precedence that will be got wrong. The
 * cost is a pair of parentheses in the rare mixed case; the benefit is that every expression means what it
 * looks like.
 *
 * Parsing happens once, against the set of names registered for the scope the expression belongs to, and the
 * tree is evaluated many times -- so the check costs nothing per request and a bad name never reaches one.
 */
object CFactParser {
    /**
     * Parses [expression] against the cfact names [allowed] permits, throwing on the first problem -- the
     * throwing form of [analyze], for a caller that has nothing to do with a malformed expression but fail.
     *
     * An unregistered name is refused here rather than ignored at evaluation, which is the whole reason
     * [allowed] is an argument: an unknown name evaluates to "absent", so a mistyped negation would be
     * silently always true.
     *
     * **Blank is refused, not read as "always".** A condition that is *absent* means always -- see
     * [parseCFactOrAlways], which is what a default case with no expression uses -- but an empty string is a
     * different thing: it is what a missing field, a typo, or an emptied overlay value produces, and reading
     * those as "show this to everyone" fails in the permissive direction. Omission is structural and
     * deliberate; blankness is usually an accident.
     */
    fun parse(expression: String, allowed: Set<String>): CFactPredicate = analyze(expression, allowed).orThrow()

    /**
     * Parses [expression] against [allowed] as a report (issue #909): the [CFactAnalysis.predicate] when it
     * parses, the names it reads, and -- given [evaluateWith], the cfacts present -- whether it matches. A
     * malformed expression is never thrown for; it comes back as [CFactAnalysis.problems], each coded
     * ([CFactError]) and located at its position in [expression].
     *
     * An unregistered name does not stop the parse -- the grammar is intact around it -- so every one is
     * reported, in order; anything else wrong with the syntax is the last problem, since nothing after it can be
     * read with confidence.
     */
    fun analyze(expression: String, allowed: Set<String>, evaluateWith: Set<String>? = null): CFactAnalysis {
        val text = expression.trim()
        if (text.isEmpty()) {
            val problem = Problem(
                CFactError.blank,
                "A cfact expression is blank. Omit it entirely for a condition that always matches, or write " +
                    "'${CFACT.alwaysName}' to say so explicitly.",
                ProblemLocation(offset = 0, line = 1, col = 1),
            )
            return CFactAnalysis(null, emptySet(), listOf(problem), null)
        }
        val state = Cursor(text, allowed, lead = expression.indexOf(text[0]))
        var result = state.readExpr()
        if (result != null) {
            state.skipSpace()
            if (!state.atEnd()) {
                result = state.fail(CFactError.unexpectedCharacter, "unexpected '${state.peek()}'")
            }
        }
        val predicate = result.takeIf { state.problems.isEmpty() }
        val value = if (predicate != null && evaluateWith != null) predicate.matches(evaluateWith) else null
        return CFactAnalysis(predicate, state.names, state.problems, value)
    }

    /**
     * Position-carrying reader. A class rather than threading an index, so a problem can say where it was. A
     * read that fails records its problem and returns null, which every caller passes straight up: the grammar
     * is small enough that unwinding by hand is clearer than a throw caught at the top.
     */
    private class Cursor(val text: String, val allowed: Set<String>, val lead: Int) {
        var at: Int = 0
        val problems = mutableListOf<Problem>()
        val names = LinkedHashSet<String>()

        fun atEnd(): Boolean = at >= text.length
        fun peek(): Char = text[at]
        fun skipSpace() { while (!atEnd() && peek() == ' ') at++ }

        /** Records a problem at the current position; always null, so a failing read can return it. */
        fun fail(code: CFactError, why: String): CFactPredicate? {
            problems.add(
                Problem(
                    code,
                    "Could not parse the cfact expression '$text' at position $at: $why.",
                    ProblemLocation(offset = lead + at, line = 1, col = lead + at + 1),
                ),
            )
            return null
        }

        /**
         * Operands joined by one operator. Mixing is refused *here*, where both operators have been seen, so
         * the message can name them rather than reporting a generic syntax error somewhere later.
         */
        fun readExpr(): CFactPredicate? {
            val parts = mutableListOf(readOperand() ?: return null)
            var op: String? = null
            while (true) {
                skipSpace()
                if (atEnd()) break
                val ch = peek().toString()
                if (ch != CFACT.and && ch != CFACT.or) break
                if (op != null && ch != op) {
                    return fail(
                        CFactError.mixedOperators,
                        "'${CFACT.and}' and '${CFACT.or}' are mixed without parentheses -- write " +
                            "'(a${CFACT.and}b)${CFACT.or}c' or 'a${CFACT.and}(b${CFACT.or}c)' to say which is meant",
                    )
                }
                op = ch
                at++
                parts.add(readOperand() ?: return null)
            }
            return when {
                parts.size == 1 -> parts[0]
                op == CFACT.or -> CFactAny(parts)
                else -> CFactAll(parts)
            }
        }

        fun readOperand(): CFactPredicate? {
            skipSpace()
            if (atEnd()) return fail(CFactError.missingOperand, "an operand is missing")
            if (peek().toString() == CFACT.not) {
                at++
                return CFactNot(readOperand() ?: return null)
            }
            if (peek().toString() == CFACT.open) {
                at++
                val inner = readExpr() ?: return null
                skipSpace()
                if (atEnd() || peek().toString() != CFACT.close) {
                    return fail(CFactError.missingClose, "a '${CFACT.close}' is missing")
                }
                at++
                return inner
            }
            return readAtom()
        }

        /** A literal (`#`-prefixed) or a registered cfact name; the sigil is what tells them apart. */
        fun readAtom(): CFactPredicate? {
            if (peek().toString() == CFACT.literal) {
                val start = at
                at++
                while (!atEnd() && peek().isLetterOrDigit()) at++
                return when (val word = text.substring(start, at)) {
                    CFACT.neverName -> CFACT.never
                    CFACT.alwaysName -> CFACT.always
                    // Reported as a bad *literal*, not a missing registration -- the sigil said which it is,
                    // so sending the reader to look for a component that registers it would misdirect them.
                    else -> {
                        at = start
                        fail(
                            CFactError.unknownLiteral,
                            "'$word' is not a known literal (${CFACT.alwaysName}, ${CFACT.neverName})",
                        )
                    }
                }
            }
            val start = at
            while (!atEnd() && isCFactNameChar(peek())) at++
            if (at == start) return fail(CFactError.nameExpected, "a cfact name was expected")
            val name = text.substring(start, at)
            names.add(name)
            if (name !in allowed) {
                // Named, and the alternatives listed, because the common cause is a typo and the reader is
                // usually looking at the right word spelled wrong. The grammar around it is intact, so reading
                // goes on: a second unknown name is reported too, rather than found on the next attempt.
                val end = at
                at = start
                fail(CFactError.unknownName, "'$name' is not a registered cfact here (registered: ${allowed.sorted()})")
                at = end
            }
            return CFactAtom(name)
        }
    }
}

/**
 * A cfact expression's report (issue #909): the [predicate] when it parsed cleanly, the cfact [names] it reads
 * (registered or not -- an editor shows both), the [problems] when it did not, and the [value] when it was
 * evaluated ([CFactParser.analyze]'s `evaluateWith`) and parsed.
 */
class CFactAnalysis(
    val predicate: CFactPredicate?,
    val names: Set<String>,
    val problems: List<Problem>,
    val value: Boolean?,
) {
    /** The predicate, or the first problem thrown as the bad input it always was. */
    fun orThrow(): CFactPredicate =
        predicate ?: throw problems.first().toException { KdrException.mkInput(it) }
}

/** What can be wrong with a cfact expression (issue #909); the [CFactAnalysis] problem codes. */
@Suppress("EnumEntryName")
enum class CFactError : ProblemCode {
    /** Empty or only spaces -- an absent condition is written by omitting it, not by leaving it blank. */
    blank,

    /** A name that is not registered in the scope the expression belongs to. */
    unknownName,

    /** A `#` literal that is not `#always` or `#never`. */
    unknownLiteral,

    /** `,` and `|` at one level, which would need a precedence rule nobody remembers. */
    mixedOperators,

    /** An operator, `~` or `(` with nothing after it. */
    missingOperand,

    /** A `(` with no matching `)`. */
    missingClose,

    /** A character where a cfact name had to be. */
    nameExpected,

    /** Something left over once the expression was read. */
    unexpectedCharacter,
}

/**
 * Parses [expression], treating **absence** as "always matches" (issue #454).
 *
 * The form a default case uses: an ordered list of alternatives whose last entry carries no condition. Null
 * is the structural way to say that; a blank string is refused by [CFactParser.parse], because it is what a
 * mistake looks like rather than what an intent looks like.
 */
fun parseCFactOrAlways(expression: String?, allowed: Set<String>): CFactPredicate =
    if (expression == null) CFACT.always else CFactParser.parse(expression, allowed)

/**
 * [CFactParser.analyze], treating **absence** as "always matches", like [parseCFactOrAlways]: a null
 * [expression] is the always predicate, with no names and no problems.
 */
fun analyzeCFactOrAlways(expression: String?, allowed: Set<String>, evaluateWith: Set<String>? = null): CFactAnalysis =
    if (expression == null) {
        CFactAnalysis(CFACT.always, emptySet(), emptyList(), evaluateWith?.let { true })
    } else {
        CFactParser.analyze(expression, allowed, evaluateWith)
    }
