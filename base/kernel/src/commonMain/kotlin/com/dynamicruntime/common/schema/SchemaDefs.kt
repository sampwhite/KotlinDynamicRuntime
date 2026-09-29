package com.dynamicruntime.common.schema

/**
 * Collects the **closure** of `$defs` reachable from [seeds] -- every type they name, every type those name,
 * and so on -- out of a larger [defs] bag (issue #534).
 *
 * The point is a *self-contained* subset: hand a consumer the handful of types some structure references,
 * plus their transitive dependencies, rather than the whole document. A workflow view uses it so its trait
 * `$ref`s resolve against a `$defs` of its own -- which is what a page needs when a workflow **narrows** a
 * type (the reachable body is then the workflow's, not the client catalog's), and what spares it fetching a
 * catalog of hundreds of unrelated endpoints to resolve a few fields.
 *
 * [seeds] are **qualified type names** (`globalconfig.NameData`), not `$ref` strings; use [refName] to turn a
 * `#/$defs/x` pointer into one. A seed absent from [defs] is skipped rather than faulted -- a dangling `$ref`
 * is a boot-time concern the schema build already owns, not this walk's to relitigate. The result is keyed
 * the same way [defs] is, so it drops under a [SCH.dDefs] key unchanged.
 *
 * The walk is the endpoint catalog's own ([collectRefsInto], issue #813), started from names rather than from
 * rendered nodes -- so it follows everything that one does, a discriminator's `defaultMapping` included, and
 * resolves a reference by the same rule. A second walk that followed only `$ref` once shipped a view whose union
 * the frontend could not parse, for want of its default branch.
 */
fun collectDefClosure(seeds: Collection<String>, defs: Map<String, Any?>): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for (seed in seeds) {
        includeDef(seed, defs, out)
    }
    return out
}

/**
 * The type name a `#/$defs/x` pointer names, or null when [ref] is not a local `$defs` pointer -- for a caller that
 * must refuse anything else. Resolving a reference, as the closure and the parser do, is [refTargetName]'s job.
 */
fun refName(ref: String): String? {
    val prefix = "#/${SCH.dDefs}/"
    return if (ref.startsWith(prefix)) ref.substring(prefix.length) else null
}
