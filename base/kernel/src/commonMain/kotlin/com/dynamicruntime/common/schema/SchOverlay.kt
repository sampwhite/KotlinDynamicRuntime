package com.dynamicruntime.common.schema

import com.dynamicruntime.common.util.toJsonMap

/**
 * Applies a client's overlays to a `$defs` document, producing the document that client's schema is parsed
 * from (issue #356).
 *
 * ### Why this works on the raw maps rather than on parsed types
 *
 * A `$ref` is bound to an **object pointer** during parsing -- `SchProperty.valueType`, `SchType.itemType`,
 * `SchVariants.branches` all hold resolved [SchType]s. So narrowing a type by editing the parsed graph would
 * mean rebuilding every type that reaches it, transitively, and a variant that missed one would silently keep
 * pointing at the global form in the one place nobody looked.
 *
 * Overlaying the **document** and re-parsing removes that problem rather than solving it: `$ref` resolution
 * binds to whatever is in the map it was handed, so every reference to an altered type -- nested, inside a
 * union branch, through an array's `items` -- lands on the altered version with no traversal written and none
 * to get wrong. That is the case `client-definition.md` says cannot be faked with namespacing, and it is why
 * this layer exists at all.
 *
 * ### What it costs, and why that was already accepted
 *
 * The whole document is parsed again for each client that varies something. `client-definition.md` settled
 * this: *"Whatever the code is simplest carrying... the CPU and memory involved are small enough to ignore, and
 * simplicity of code is the thing actually being bought."* A client that overlays nothing gets the global
 * document back **by identity**, so it builds no variant at all.
 */
fun overlayDefs(defs: Map<String, Any?>, overlays: Map<String, Any?>): Map<String, Any?> {
    if (overlays.isEmpty()) {
        // The same object, not a copy: a client that varies nothing shares the global document, which is what
        // lets the caller skip building a variant by comparing identity.
        return defs
    }
    val out = LinkedHashMap<String, Any?>(defs.size + overlays.size)
    for ((name, body) in defs) {
        val overlay = overlays[name]
        out[name] = if (overlay is Map<*, *> && body is Map<*, *>) {
            overlayType(body.toJsonMap(), overlay.toJsonMap())
        } else {
            // Shared by reference, never written to. A type this client did not mention is the global one.
            body
        }
    }
    // Whatever the overlays declared that the base does not have: a wholly new type, or an extension, both of
    // which are ordinary entries under a name of their own.
    for ((name, body) in overlays) {
        if (name !in defs) {
            out[name] = body
        }
    }
    return out
}

/**
 * One type body with [overlay] applied over it -- **at two levels only**.
 *
 * Public because the narrowing check needs it: what a client may or may not do is a question about the
 * **result**, not about the fragment they wrote, since a property body they declare replaces rather than
 * merges. See `narrowingProblems`.
 *
 * A key the overlay does not mention is carried across untouched; a key it does mention **replaces**. There is
 * no deep merging, and that is the design rather than a simplification: once an overlay starts defining
 * something, that definition wins completely, so what a client wrote is what a client gets. The one exception
 * is [SCH.properties], which has its own rule -- see [mergeProperties].
 *
 * The consequence is worth stating, because it is what shapes how schema gets authored: **there is no way to
 * address just a nested part of a type**. An interior structure a client may want to narrow is therefore
 * pulled out as a named type and referenced by `$ref`, so that it can be altered directly, as a type in its
 * own right. That is a reason to name interior types, not merely a style preference.
 *
 * Nothing is ever written into [base] or anything it holds: unmentioned values are shared by reference and
 * never mutated. That is `client-definition.md`'s sharing invariant -- *"a variant may create new nodes and
 * point at old ones; it must not write into old ones"* -- as a property of how this is written rather than a
 * discipline somebody has to keep.
 */
fun overlayType(base: Map<String, Any?>, overlay: Map<String, Any?>): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>(base.size + overlay.size)
    for ((key, value) in base) {
        val over = overlay[key]
        out[key] = when {
            key !in overlay -> value
            key == SCH.properties && over is Map<*, *> && value is Map<*, *> ->
                mergeProperties(value.toJsonMap(), over.toJsonMap())

            else -> over
        }
    }
    for ((key, value) in overlay) {
        if (key !in base) {
            out[key] = value
        }
    }
    return out
}

/**
 * The properties an altered type has: **exactly the ones the overlay mentions**.
 *
 * Two rules, and they are the whole authoring model:
 *
 *  - **Mentioning keys is how the set is reduced.** A property the overlay does not name is gone. That forces
 *    a client altering a type to state the complete set it offers, which was found in practice to be the right
 *    thing -- somebody reading a client's definition sees every property their users will see, rather than a
 *    fragment plus whatever the base happened to hold. The cost, accepted: a property cannot be slipped into
 *    every client at once by adding it to the underlying type.
 *  - **An empty body inherits; a non-empty one replaces.** `{"name": {}}` keeps the global definition of
 *    `name`, and anything else is this client's definition of it, entire.
 *
 * **The order is the client's.** A [LinkedHashMap] built in the overlay's own order, because for `properties`
 * order is meaning rather than presentation -- it is the order a form shows its fields in. A client that
 * reorders the set has reordered the form, which is a thing they should be able to do while narrowing it.
 * (`JsonUtil` keeps the order of a parsed object for the same reason; see `PState.preserveOrder`.)
 */
private fun mergeProperties(base: Map<String, Any?>, overlay: Map<String, Any?>): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>(overlay.size)
    for ((name, over) in overlay) {
        val declared = (over as? Map<*, *>)?.toJsonMap()
        // Empty means "as it already is", which is how a client keeps a property while reducing the set
        // around it -- by far the common case, since most alterations change one property and keep the rest.
        out[name] = if (declared.isNullOrEmpty()) base[name] ?: over else over
    }
    return out
}
