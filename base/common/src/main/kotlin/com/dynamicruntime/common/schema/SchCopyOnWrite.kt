package com.dynamicruntime.common.schema

import com.dynamicruntime.common.util.toJsonMap

/**
 * A per-node transform applied by [copyOnWriteSchema]: given a schema map node and the **name** it appears
 * under -- the enclosing `properties` key, or `""` at the root and inside `items`/branches -- it returns the
 * node to use, returning the *same instance* when it changes nothing so the walk can share it.
 */
typealias SchNodeTransform = (node: Map<String, Any?>, name: String) -> Map<String, Any?>

/**
 * Walks a schema document copy-on-write, applying [transform] to every map node after its children have been
 * walked (post-order), and returns the rewritten document (issue #545).
 *
 * The catalog's render-time resolutions -- sourcing a choice list ([resolveOptionsSources]) and gating a field
 * ([resolveVisibleWhen]) -- differ only in that transform, not in the traversal, so the traversal lives here
 * once. What it guarantees, and what both resolutions rely on:
 *
 *  - **Copy-on-write identity.** A node the transform leaves unchanged, whose children are unchanged too, comes
 *    back as the identical instance; only nodes on the path to a real change are copied. The catalog's
 *    renderings and `$defs` bag share node objects with the compiled store, so rewriting by mutation would
 *    leak one caller's answer to every later caller across clients -- this is what makes that impossible.
 *  - **`properties` names its children.** Under a `properties` map (and only there) a child's key is its name,
 *    which is what a node's transform is told; everywhere else (`items`, a branch, an `if`/`then`) the enclosing
 *    name carries down unchanged.
 *  - **Order is preserved**, so two callers get the same document with a value changed or a field absent rather
 *    than two differently ordered ones.
 *
 * In `base/common` rather than the kernel because its callers close over a request ([com.dynamicruntime.common.context.KdrCxt]
 * cfacts, an options provider) -- the frontend, which shares the kernel, receives the already-resolved document.
 */
fun copyOnWriteSchema(node: Map<String, Any?>, transform: SchNodeTransform): Map<String, Any?> =
    cowMap(node, "", transform)

private fun cowNode(node: Any?, name: String, transform: SchNodeTransform): Any? = when (node) {
    is Map<*, *> -> cowMap(node, name, transform)
    is List<*> -> cowList(node, name, transform)
    else -> node
}

private fun cowList(list: List<*>, name: String, transform: SchNodeTransform): Any {
    var out: MutableList<Any?>? = null
    for ((index, element) in list.withIndex()) {
        val resolved = cowNode(element, name, transform)
        if (resolved === element) {
            continue
        }
        (out ?: ArrayList(list).also { out = it })[index] = resolved
    }
    return out ?: list
}

private fun cowMap(node: Map<*, *>, name: String, transform: SchNodeTransform): Map<String, Any?> {
    val json = node.toJsonMap()
    var out: MutableMap<String, Any?>? = null
    for ((key, value) in json) {
        val resolved = if (key == SCH.properties && value is Map<*, *>) {
            cowProperties(value, transform)
        } else {
            cowNode(value, name, transform)
        }
        if (resolved !== value) {
            (out ?: LinkedHashMap(json).also { out = it })[key] = resolved
        }
    }
    // Post-order: the node's own transform runs after its children are settled, so a transform that reads its
    // `properties` (field gating) sees children already rewritten, and one that reads the node's own keyword
    // (options sourcing) sees the recursed node.
    return transform(out ?: json, name)
}

private fun cowProperties(props: Map<*, *>, transform: SchNodeTransform): Map<String, Any?> {
    val json = props.toJsonMap()
    var out: MutableMap<String, Any?>? = null
    for ((child, body) in json) {
        val resolved = cowNode(body, child, transform)
        if (resolved !== body) {
            (out ?: LinkedHashMap(json).also { out = it })[child] = resolved
        }
    }
    return out ?: json
}
