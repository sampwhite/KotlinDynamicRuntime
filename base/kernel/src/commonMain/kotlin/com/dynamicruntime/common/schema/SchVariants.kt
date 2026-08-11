package com.dynamicruntime.common.schema

/**
 * A discriminated union: JSON Schema's `oneOf` plus a declared `discriminator` naming the property whose value
 * selects the branch (issue #252).
 *
 * **Why the discriminator is declared and not deduced.** The `const` values have to be in the branches
 * regardless — that is what lets any validator pick the right one — so naming the property adds no
 * expressiveness. What it adds is a checkable statement of intent: scanning branches for "a property that is
 * `const` and distinct in each" invents a list of edge cases nobody specified (two such properties, a branch
 * missing its `const`, colliding values, a branch added later that silently breaks the inference), each
 * surfacing as confusing runtime behavior. Declared, the whole thing is checked once at boot with a message
 * naming the branch at fault.
 *
 * **Why resolve rather than try every branch.** Plain `oneOf` says only "exactly one subschema validates", so
 * when none does there is no principled way to choose whose failures to report — and a union's error is
 * exactly what a caller needs. Reading the discriminator, selecting one branch and reporting *that* branch's
 * failures is what makes the errors mean anything, and is why OpenAPI layered `discriminator` on top in the
 * first place.
 *
 * Selection changes only which failures are reported, never whether a payload is valid: every branch carries
 * its own `const`, checked at boot, so a stock validator ignoring the keyword reaches the same verdict by
 * trying all of them. That is the property that keeps the document standard-valid.
 */
class SchVariants(
    /** The property whose value selects a branch. Its value is compared as a string. */
    val discriminator: String,
    /**
     * The branches in declaration order.
     *
     * Mutable for the same reason [SchType.itemType] is: a branch is usually a `$ref`, which is bound in the
     * reference-resolution pass once every type is parsed, so a target parsed later — or a branch that refers
     * back to the union — resolves instead of expanding forever.
     */
    val branches: MutableList<SchType>,
    /**
     * Where an unrecognized discriminator value goes, or null to fail instead.
     *
     * OpenAPI 3.2's `defaultMapping`. The stored-entity model needs it: trait definitions are authored at
     * runtime and resolved per caller, so a value this reader has never heard of is an ordinary event rather
     * than a defect — it should stay readable and pass through untouched, not take the whole payload down.
     */
    var defaultBranch: SchType?,
) {
    /**
     * Discriminator value to branch, keyed by each branch's own `const`. Built by the resolution pass, once
     * the branches are bound and can be asked what they claim to be — which is also where a branch that
     * declares no `const` is rejected.
     */
    var byValue: Map<String, SchType> = emptyMap()

    /** The declared discriminator values, in branch order — the choices a caller may send. */
    val values: List<String> get() = byValue.keys.toList()

    /** The branch [value] selects, the default branch when it names none, or null when there is no default. */
    fun select(value: String?): SchType? = (value?.let { byValue[it] }) ?: defaultBranch

    /** Whether [value] names a branch of its own, as against merely landing on the default. */
    fun isKnown(value: String?): Boolean = value != null && byValue.containsKey(value)
}
