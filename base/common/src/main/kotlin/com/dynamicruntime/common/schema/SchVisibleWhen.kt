package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonMap

/**
 * Per-caller field visibility, resolved when the endpoint catalog renders (issue #545).
 *
 * A property declares [SCH.visibleWhen] carrying a cfact expression. The rendering pass evaluates it for *this*
 * caller and either keeps the property -- stripping the keyword, so what leaves the node is an ordinary field --
 * or drops it: out of its `properties` map and out of the enclosing `required`, so the served schema never
 * mentions a field the caller may not use. It is the sibling of [resolveOptionsSources]: the two share
 * [copyOnWriteSchema]'s traversal and differ only in the per-node transform, and both decide *presentation* for
 * a caller -- a handler that accepts the field still enforces the condition itself (see [SCH.visibleWhen]).
 *
 * @param isVisible evaluates a [SCH.visibleWhen] expression against the current caller: true keeps the field.
 */
fun resolveVisibleWhen(node: Map<String, Any?>, isVisible: (expression: String) -> Boolean): Map<String, Any?> =
    copyOnWriteSchema(node) { n, _ -> gateProperties(n, isVisible) }

/**
 * The per-node transform: on an object node, drop each `properties` child whose [SCH.visibleWhen] the caller
 * fails (pruning it from the sibling `required`), and strip the now-spent keyword from every survivor. Its
 * children were already walked by [copyOnWriteSchema], so a nested object was gated before this runs. A node
 * with no `properties`, or none of whose children are gated, comes back unchanged (the same instance).
 */
private fun gateProperties(node: Map<String, Any?>, isVisible: (String) -> Boolean): Map<String, Any?> {
    val props = node[SCH.properties] as? Map<*, *> ?: return node
    val json = props.toJsonMap()
    var newProps: MutableMap<String, Any?>? = null
    var dropped: MutableSet<String>? = null
    for ((child, body) in json) {
        val gate = (body as? Map<*, *>)?.get(SCH.visibleWhen) as? String ?: continue
        if (!isVisible(gate)) {
            (newProps ?: LinkedHashMap(json).also { newProps = it }).remove(child)
            (dropped ?: LinkedHashSet<String>().also { dropped = it }).add(child)
        } else {
            // Keep the field, but strip the keyword it has now spent.
            (newProps ?: LinkedHashMap(json).also { newProps = it })[child] = body.toJsonMap() - SCH.visibleWhen
        }
    }
    val resolvedProps = newProps ?: return node // nothing gated here
    val result = LinkedHashMap(node)
    result[SCH.properties] = resolvedProps
    // A gated-out property must also leave `required`: a schema requiring a field it no longer declares would
    // reject every request from the very caller it was hidden from.
    val gone = dropped
    if (gone != null) {
        val required = node[SCH.required] as? List<*>
        if (required != null) {
            val pruned = required.filter { it !is String || it !in gone }
            if (pruned.size != required.size) {
                result[SCH.required] = pruned
            }
        }
    }
    return result
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
