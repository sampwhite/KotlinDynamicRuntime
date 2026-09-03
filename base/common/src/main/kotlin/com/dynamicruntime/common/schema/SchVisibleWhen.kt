package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonMap

/**
 * Per-caller field visibility, resolved when the endpoint catalog renders (issue #545).
 *
 * A property declares [SCH.visibleWhen] carrying a cfact expression. The rendering pass evaluates it for *this*
 * caller and either keeps the property -- stripping the keyword, so what leaves the node is an ordinary field --
 * or drops it: out of its `properties` map and out of the enclosing `required`, so the served schema never
 * mentions a field the caller may not use. It is the sibling of [resolveOptionsSources]: same place in the
 * pipeline, same copy-on-write discipline, and the same division of labor -- this decides *presentation* for a
 * caller, and a handler that accepts the field still enforces the condition itself (see [SCH.visibleWhen]).
 *
 * ### Copy-on-write, for the same reason
 *
 * The catalog's renderings and `$defs` bag share node objects with the compiled store, so gating by mutation
 * would hide a field from every later caller across clients. Each node that changes is copied along with its
 * ancestors; a document with no gated field, or one whose gates all pass unchanged, comes back as the identical
 * object. Order is preserved, so two callers get the same document with a field present or absent rather than
 * two differently ordered ones.
 *
 * ### In `base/common`, not the kernel
 *
 * The visibility test closes over a request's cfacts, a `base/common` concern, so -- like [resolveOptionsSources]
 * -- this cannot sit beside `collectDefs` in the kernel. The kernel defines the keyword; the answer is computed
 * here and the frontend, which shares the kernel, receives a document already gated.
 *
 * @param isVisible evaluates a [SCH.visibleWhen] expression against the current caller: true keeps the field.
 */
fun resolveVisibleWhen(node: Map<String, Any?>, isVisible: (expression: String) -> Boolean): Map<String, Any?> =
    resolveMap(node, isVisible)

private fun resolveNode(node: Any?, isVisible: (String) -> Boolean): Any? = when (node) {
    is Map<*, *> -> resolveMap(node, isVisible)
    is List<*> -> resolveList(node, isVisible)
    else -> node
}

private fun resolveList(list: List<*>, isVisible: (String) -> Boolean): Any {
    var out: MutableList<Any?>? = null
    for ((index, element) in list.withIndex()) {
        val resolved = resolveNode(element, isVisible)
        if (resolved === element) {
            continue
        }
        (out ?: ArrayList(list).also { out = it })[index] = resolved
    }
    return out ?: list
}

private fun resolveMap(node: Map<*, *>, isVisible: (String) -> Boolean): Map<String, Any?> {
    val json = node.toJsonMap()
    // First recurse into every value copy-on-write, handling a `properties` map specially so a gated child can
    // report itself for removal from the sibling `required`.
    var out: MutableMap<String, Any?>? = null
    var dropped: Set<String> = emptySet()
    for ((key, value) in json) {
        val resolved = if (key == SCH.properties && value is Map<*, *>) {
            val (props, names) = resolveProperties(value, isVisible)
            dropped = names
            props
        } else {
            resolveNode(value, isVisible)
        }
        if (resolved !== value) {
            (out ?: LinkedHashMap(json).also { out = it })[key] = resolved
        }
    }
    if (dropped.isEmpty()) {
        return out ?: json
    }
    // A gated-out property must also leave `required`: a schema requiring a field it no longer declares would
    // reject every request from the very caller it was hidden from.
    val base = out ?: LinkedHashMap(json).also { out = it }
    val required = base[SCH.required] as? List<*>
    if (required != null) {
        val pruned = required.filter { it !is String || it !in dropped }
        if (pruned.size != required.size) {
            base[SCH.required] = pruned
        }
    }
    return base
}

/**
 * Walks a `properties` map, keeping only the children whose [SCH.visibleWhen] the caller satisfies (and every
 * child with no gate), returning the new map paired with the names that were dropped so the caller can prune
 * `required`. The keyword is stripped from every surviving child, since it has done its job by render time.
 */
private fun resolveProperties(
    props: Map<*, *>,
    isVisible: (String) -> Boolean,
): Pair<Map<String, Any?>, Set<String>> {
    val json = props.toJsonMap()
    var out: MutableMap<String, Any?>? = null
    var dropped: MutableSet<String>? = null
    for ((child, body) in json) {
        val recursed = resolveNode(body, isVisible)
        val gate = (recursed as? Map<*, *>)?.get(SCH.visibleWhen) as? String
        when {
            // Gated and the caller fails it: drop the child entirely.
            gate != null && !isVisible(gate) -> {
                val copy = out ?: LinkedHashMap(json).also { out = it }
                copy.remove(child)
                (dropped ?: LinkedHashSet<String>().also { dropped = it }).add(child)
            }
            // Gated and the caller passes it: keep the child, but strip the now-spent keyword.
            gate != null -> {
                (out ?: LinkedHashMap(json).also { out = it })[child] = (recursed as Map<*, *>).toJsonMap() - SCH.visibleWhen
            }
            // No gate: keep whatever the recursion produced (identical object unless a nested gate changed it).
            recursed !== body -> {
                (out ?: LinkedHashMap(json).also { out = it })[child] = recursed
            }
        }
    }
    return (out ?: json) to (dropped ?: emptySet())
}

/**
 * Collects the [SCH.visibleWhen] expressions under [node] that do not parse, for the boot check (issue #545).
 * [parse] is handed each expression and is expected to throw a [KdrException] on bad syntax; [where] names the
 * schema location so a failure points at the declaration. Mirrors `optionsSourceProblems`.
 */
fun visibleWhenProblems(where: String, node: Any?, parse: (String) -> Unit): List<String> {
    val problems = mutableListOf<String>()
    fun walk(n: Any?) {
        when (n) {
            is Map<*, *> -> {
                (n[SCH.visibleWhen] as? String)?.let { expression ->
                    try {
                        parse(expression)
                    } catch (e: KdrException) {
                        problems.add("$where: '${SCH.visibleWhen}' expression '$expression' does not parse: ${e.message}")
                    }
                }
                n.values.forEach { walk(it) }
            }
            is List<*> -> n.forEach { walk(it) }
        }
    }
    walk(node)
    return problems
}
