package com.dynamicruntime.common.schema

import com.dynamicruntime.common.annotation.KdrPrivate

/**
 * Closing a schema document over the types it references: given a set of schema nodes and the full `$defs`
 * bag they resolve against, the subset actually reachable from them.
 *
 * In the **kernel** rather than beside the endpoint builders that first needed it, because both sides need the
 * same answer and must not each have their own: the backend closes an endpoint's `$defs` before shipping the
 * catalog, and the frontend closes them again to assemble a standalone document from what it received
 * (issue #262). One notorious detail is why sharing matters more than it looks -- see the `defaultMapping`
 * note below.
 */

/**
 * Builds the closed `$defs` bag for a set of endpoint [renderings]: every type reachable by `$ref` from them,
 * resolved against [allDefs] (the store's raw defs) and keyed by qualified name. The walk inserts each target
 * into the result BEFORE recursing into it, so a self- or mutually-referential type terminates instead of
 * looping. The outcome is closed: every `$ref` in the renderings (or in an included def) resolves within it,
 * and each shared type appears exactly once.
 */
fun collectDefs(renderings: List<Map<String, Any?>>, allDefs: Map<String, Any?>): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for (rendering in renderings) {
        collectRefsInto(rendering, allDefs, out)
    }
    return out
}

/** Walks [node] for `$ref`s, adding each referenced def from [allDefs] to [out] (insert-before-recurse). */
@KdrPrivate
fun collectRefsInto(node: Any?, allDefs: Map<String, Any?>, out: MutableMap<String, Any?>) {
    when (node) {
        is Map<*, *> -> {
            includeRef(node[SCH.dRef], allDefs, out)
            // A discriminator's `defaultMapping` is a **bare ref string**, not a `{"$ref": …}` object -- that
            // is OpenAPI's spelling, and it is invisible to a walk that looks for the keyword. Missing it ships
            // a catalog whose union cannot be parsed by the client that receives it, which is how this was
            // found: the frontend's error boundary reporting "$ref to unknown type 'gedra.OpaqueEntry'".
            (node[SCH.discriminator] as? Map<*, *>)?.let { includeRef(it[SCH.defaultMapping], allDefs, out) }
            for (value in node.values) {
                collectRefsInto(value, allDefs, out)
            }
        }
        is List<*> -> for (element in node) collectRefsInto(element, allDefs, out)
    }
}

/** Adds the def [ref] points at (and everything it reaches) to [out], if it is a ref that resolves. */
private fun includeRef(ref: Any?, allDefs: Map<String, Any?>, out: MutableMap<String, Any?>) {
    if (ref !is String) {
        return
    }
    val name = refTargetName(ref)
    if (name in out) {
        return
    }
    val target = allDefs[name] ?: return
    out[name] = target // insert first, so a ref back to this type short-circuits
    collectRefsInto(target, allDefs, out)
}
