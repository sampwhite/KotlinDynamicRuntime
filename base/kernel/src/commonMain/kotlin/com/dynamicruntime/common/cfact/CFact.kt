package com.dynamicruntime.common.cfact

/**
 * A **cfact** is a named boolean fact about the current scope: present or absent, never a value (issue #454).
 *
 * The name is deliberately not "tag", which already means three unrelated things here -- the capability tags
 * that decide what a node carries, the publication mark on an endpoint, and the free-form search tags that
 * slice a catalog. A cfact is none of those: it is what a *display* decision is made against.
 *
 * **Presence only, and that is the rule that keeps this small.** A new condition is a new cfact computed in
 * Kotlin, never a richer operator here. "Has more than three forms" is a cfact called something like
 * `hasForms`, decided where the data is; it is not `count > 3` in an expression. Hold that line and the
 * evaluator stays a few dozen lines forever, instead of becoming a language nobody meant to write.
 *
 * **A cfact decides presentation, never permission.** Hiding a button must not be what stops the action --
 * the endpoint behind it refuses on its own, and a caller who calls it anyway is refused there. The same
 * distinction `publicApi` draws between advertising an endpoint and admitting a caller.
 */
interface CFactPredicate {
    /** Whether [cfacts] satisfies this. */
    fun matches(cfacts: Set<String>): Boolean

    /** The expression this came from, near enough to round-trip; for diagnostics and tests. */
    fun render(): String
}

/** One cfact by name: true when it is present. */
class CFactAtom(val name: String) : CFactPredicate {
    override fun matches(cfacts: Set<String>): Boolean = name in cfacts
    override fun render(): String = name
}

/**
 * The absence of [inner].
 *
 * **Prefer a positive expression wherever one exists.** `app` on the items an application shows says what is
 * meant; `~edge` says it by exclusion and quietly admits every role invented later. Negation is a last resort
 * rather than a peer of the others -- which is also why an unregistered name is refused at parse time: a
 * mistyped `~admn` names a cfact that is never present, so it is *always true*, and the guarded item shows to
 * everyone. The positive form of the same typo fails closed and is noticed at once.
 */
class CFactNot(val inner: CFactPredicate) : CFactPredicate {
    override fun matches(cfacts: Set<String>): Boolean = !inner.matches(cfacts)
    override fun render(): String = "~" + inner.render()
}

/** Every part must match. Written with `,`. */
class CFactAll(val parts: List<CFactPredicate>) : CFactPredicate {
    override fun matches(cfacts: Set<String>): Boolean = parts.all { it.matches(cfacts) }
    override fun render(): String = parts.joinToString(CFACT.and) { it.renderNested() }
}

/** Any part may match. Written with `|`. */
class CFactAny(val parts: List<CFactPredicate>) : CFactPredicate {
    override fun matches(cfacts: Set<String>): Boolean = parts.any { it.matches(cfacts) }
    override fun render(): String = parts.joinToString(CFACT.or) { it.renderNested() }
}

/** Parenthesizes a compound part when rendering it inside another, mirroring what the parser requires. */
private fun CFactPredicate.renderNested(): String =
    if (this is CFactAll || this is CFactAny) "(" + render() + ")" else render()

/** The expression syntax. Values are the characters themselves, so a message can quote them. */
@Suppress("ConstPropertyName")
object CFACT {
    /** Joins operands that must all match. */
    const val and = ","

    /** Joins operands of which any may match. */
    const val or = "|"

    /** Negates the operand that follows. */
    const val not = "~"

    const val open = "("
    const val close = ")"

    /**
     * Marks a **literal** rather than a cfact name.
     *
     * The same instinct as `SCH.gPrefix` marking kd2's own JSON Schema keywords: a reader can see at a glance
     * that `#never` is not something a component registered. It also keeps the cfact namespace free of
     * reserved words -- a real cfact may still be called `never` -- and lets the parser tell the two apart
     * *lexically*, so a mistyped literal reports itself as one instead of sending the reader hunting for a
     * registration that was never the problem.
     */
    const val literal = "#"

    /** Literal: matches nothing, whatever is present. */
    const val neverName = "#never"

    /** Literal: matches everything. */
    const val alwaysName = "#always"

    /**
     * Matches everything. Written [alwaysName], or by omitting the expression entirely.
     *
     * Spelled explicitly as well as omittable because that is how an overlay **re-enables** something a layer
     * beneath it disabled: a base that says [neverName] is turned back on by an overlay saying [alwaysName].
     * With only the omitted form, the same intent would have to be written as a tautology.
     */
    val always: CFactPredicate = object : CFactPredicate {
        override fun matches(cfacts: Set<String>): Boolean = true
        override fun render(): String = alwaysName
    }

    /**
     * Matches nothing.
     *
     * Its real use is **removal through an overlay**. A client or boot-role overlay sets an item's expression
     * to this and the item stops appearing -- so merging never needs to learn to delete an array element. The
     * base stays one readable list of everything that exists, "why is this gone?" is answered by reading the
     * item rather than diffing overlays, and no overlay can delete something a later one expected to find.
     */
    val never: CFactPredicate = object : CFactPredicate {
        override fun matches(cfacts: Set<String>): Boolean = false
        override fun render(): String = neverName
    }
}
