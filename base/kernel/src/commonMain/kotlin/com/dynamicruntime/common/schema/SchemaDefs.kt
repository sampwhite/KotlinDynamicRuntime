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
 * Pure and transpile-safe: it walks raw maps and lists, so backend and (later) frontend share one hunt.
 */
fun collectDefClosure(seeds: Collection<String>, defs: Map<String, Any?>): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    val queue = ArrayDeque(seeds.toList())
    while (queue.isNotEmpty()) {
        val name = queue.removeFirst()
        if (name in out) continue
        val body = defs[name] ?: continue
        out[name] = body
        for (ref in refsIn(body)) {
            if (ref !in out) queue.addLast(ref)
        }
    }
    return out
}

/** The type name a `#/$defs/x` pointer names, or null when [ref] is not a local `$defs` pointer. */
fun refName(ref: String): String? {
    val prefix = "#/${SCH.dDefs}/"
    return if (ref.startsWith(prefix)) ref.substring(prefix.length) else null
}

/** Every local `$defs` type name a raw schema [node] references, at any depth (following `$ref` values). */
private fun refsIn(node: Any?): List<String> {
    val found = mutableListOf<String>()
    fun walk(n: Any?) {
        when (n) {
            is Map<*, *> -> for ((k, v) in n) {
                if (k == SCH.dRef && v is String) refName(v)?.let { found.add(it) } else walk(v)
            }
            is List<*> -> n.forEach { walk(it) }
        }
    }
    walk(node)
    return found
}
