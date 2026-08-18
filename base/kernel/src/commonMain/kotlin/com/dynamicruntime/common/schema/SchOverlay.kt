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
            mergeNode(body.toJsonMap(), overlay.toJsonMap())
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
 * [base] with [overlay] merged over it: a **new** map along the merged path, sharing everything untouched.
 *
 * Two maps merge key by key; anything else replaces. **A list replaces rather than merging**, and that is the
 * rule doing the most work here: `required`, `options` and `oneOf` are complete statements about a type, and
 * shortening one is how a client narrows. Merging them element-wise would make the narrowing case --
 * the whole point of an overlay -- impossible to express.
 *
 * Nothing is ever written into [base] or into anything it holds. A key the overlay does not mention is carried
 * across by reference, and a key it does mention gets a freshly built map. That is `client-definition.md`'s
 * sharing invariant -- *"a variant may create new nodes and point at old ones; it must not write into old
 * ones"* -- as a property of how this is written rather than as a discipline somebody has to keep. It also
 * means no depth cap is needed, unlike `deepClone`: nothing deep is copied, so nothing deep can be missed.
 */
private fun mergeNode(base: Map<String, Any?>, overlay: Map<String, Any?>): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>(base.size + overlay.size)
    for ((key, value) in base) {
        val over = overlay[key]
        out[key] = when {
            key !in overlay -> value
            // `properties` is the one map that does not merge key-by-key; see [mergeProperties].
            key == SCH.properties && over is Map<*, *> && value is Map<*, *> ->
                mergeProperties(value.toJsonMap(), over.toJsonMap())

            over is Map<*, *> && value is Map<*, *> -> mergeNode(value.toJsonMap(), over.toJsonMap())
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
 * The properties an altered type has: **exactly the ones the overlay mentions**, each merged over the base's
 * version of it.
 *
 * The one place a map does not merge key by key, and it is deliberate -- reducing the property set is one of
 * the three ways a client may narrow a type, and "mention only the keys you want" is how that is written.
 * Merging here would leave no way to say it at all.
 *
 * The bodies still merge, so narrowing one property is a fragment (`{"name": {"g-options": [...]}}`) rather
 * than a restatement of the property. Which of the two levels merges and which replaces is the whole of the
 * authoring model: *which* properties is a statement, *what each one is* is an edit.
 */
private fun mergeProperties(base: Map<String, Any?>, overlay: Map<String, Any?>): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>(overlay.size)
    for ((name, over) in overlay) {
        val body = base[name]
        out[name] = if (over is Map<*, *> && body is Map<*, *>) {
            mergeNode(body.toJsonMap(), over.toJsonMap())
        } else {
            over
        }
    }
    return out
}
