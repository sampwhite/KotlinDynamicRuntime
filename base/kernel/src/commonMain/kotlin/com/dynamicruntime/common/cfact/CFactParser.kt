package com.dynamicruntime.common.cfact

import com.dynamicruntime.common.exception.KdrException

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
     * Parses [expression] against the cfact names [allowed] permits.
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
    fun parse(expression: String, allowed: Set<String>): CFactPredicate {
        val text = expression.trim()
        if (text.isEmpty()) {
            throw KdrException.mkInput(
                "A cfact expression is blank. Omit it entirely for a condition that always matches, or write " +
                    "'${CFACT.alwaysName}' to say so explicitly.",
            )
        }
        val state = Cursor(text, allowed)
        val result = state.readExpr()
        state.skipSpace()
        if (!state.atEnd()) {
            state.fail("unexpected '${state.peek()}'")
        }
        return result
    }

    /** Position-carrying reader. A class rather than threading an index, so an error can say where it was. */
    private class Cursor(val text: String, val allowed: Set<String>) {
        var at: Int = 0

        fun atEnd(): Boolean = at >= text.length
        fun peek(): Char = text[at]
        fun skipSpace() { while (!atEnd() && peek() == ' ') at++ }

        fun fail(why: String): Nothing = throw KdrException.mkInput(
            "Could not parse the cfact expression '$text' at position $at: $why.",
        )

        /**
         * Operands joined by one operator. Mixing is refused *here*, where both operators have been seen, so
         * the message can name them rather than reporting a generic syntax error somewhere later.
         */
        fun readExpr(): CFactPredicate {
            val parts = mutableListOf(readOperand())
            var op: String? = null
            while (true) {
                skipSpace()
                if (atEnd()) break
                val ch = peek().toString()
                if (ch != CFACT.and && ch != CFACT.or) break
                if (op != null && ch != op) {
                    fail(
                        "'${CFACT.and}' and '${CFACT.or}' are mixed without parentheses -- write " +
                            "'(a${CFACT.and}b)${CFACT.or}c' or 'a${CFACT.and}(b${CFACT.or}c)' to say which is meant",
                    )
                }
                op = ch
                at++
                parts.add(readOperand())
            }
            return when {
                parts.size == 1 -> parts[0]
                op == CFACT.or -> CFactAny(parts)
                else -> CFactAll(parts)
            }
        }

        fun readOperand(): CFactPredicate {
            skipSpace()
            if (atEnd()) fail("an operand is missing")
            if (peek().toString() == CFACT.not) {
                at++
                return CFactNot(readOperand())
            }
            if (peek().toString() == CFACT.open) {
                at++
                val inner = readExpr()
                skipSpace()
                if (atEnd() || peek().toString() != CFACT.close) fail("a '${CFACT.close}' is missing")
                at++
                return inner
            }
            return readAtom()
        }

        /** A literal (`#`-prefixed) or a registered cfact name; the sigil is what tells them apart. */
        fun readAtom(): CFactPredicate {
            if (peek().toString() == CFACT.literal) {
                val start = at
                at++
                while (!atEnd() && peek().isLetterOrDigit()) at++
                return when (val word = text.substring(start, at)) {
                    CFACT.neverName -> CFACT.never
                    CFACT.alwaysName -> CFACT.always
                    // Reported as a bad *literal*, not a missing registration -- the sigil said which it is,
                    // so sending the reader to look for a component that registers it would misdirect them.
                    else -> fail(
                        "'$word' is not a known literal (${CFACT.alwaysName}, ${CFACT.neverName})",
                    )
                }
            }
            return CFactAtom(readName())
        }

        fun readName(): String {
            val start = at
            while (!atEnd() && (peek().isLetterOrDigit() || peek() == '_' || peek() == '.')) at++
            if (at == start) fail("a cfact name was expected")
            val name = text.substring(start, at)
            if (name !in allowed) {
                // Named, and the alternatives listed, because the common cause is a typo and the reader is
                // usually looking at the right word spelled wrong.
                fail("'$name' is not a registered cfact here (registered: ${allowed.sorted()})")
            }
            return name
        }
    }
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
