package com.dynamicruntime.common.schema

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonMap

/**
 * Choice lists assembled when the schema is rendered rather than written into the document (issue #413).
 *
 * A property declares [SCH.optionsSource] naming a callback registered at startup; the callback is handed the
 * request's [KdrCxt] and the property's name, and answers with the choices *this caller* should see. The
 * rendering pass then writes those into [SCH.options] and drops the source key, so what leaves the node is an
 * ordinary choice list. Nothing downstream -- the form engine, the read-only outline, a future export -- needs
 * a second way to have options, which is the point of resolving here rather than shipping the id.
 *
 * The first of these is the client list, whose contents depend on whether the caller holds `allClients`. Its
 * shape generalizes: a list drawn from a table, or from what this user has permission to name, is the same
 * mechanism with a different callback.
 *
 * ### In `base/common`, not the kernel
 *
 * A provider takes a [KdrCxt], which is a `base/common` type, so this cannot live beside `collectDefs` in the
 * kernel however much it looks like it belongs there. That boundary is a feature: a callback runs against a
 * request, and the frontend -- which shares the kernel -- has no business resolving one. It receives the
 * answer.
 */
typealias SchOptionsProvider = (cxt: KdrCxt, propertyName: String) -> List<SchOption>

/**
 * Returns [node] with every [SCH.optionsSource] replaced by the choices its provider gives for [cxt].
 *
 * **Copy-on-write, and that is the whole safety argument.** The catalog's renderings and its `$defs` bag share
 * node objects with the compiled store -- `renderEndpoint` hands back `endpoint.outputSchema` itself, and
 * `collectDefs` inserts the store's own def maps -- so resolving by mutation would write one caller's answer
 * into the schema every later caller is served, across clients. Instead, each node that changes is copied,
 * along with its ancestors on the path to it, and everything else is shared by reference. A document with no
 * sourced options comes back as the identical object.
 *
 * The replacement keeps the **position** of the key it replaces, so two callers receive the same document with
 * different values rather than differently ordered documents.
 */
fun resolveOptionsSources(
    cxt: KdrCxt,
    node: Map<String, Any?>,
    providers: Map<String, SchOptionsProvider>,
): Map<String, Any?> = copyOnWriteSchema(node) { n, name -> resolveOptionsOnNode(cxt, n, providers, name) }

/**
 * The per-node transform: when [node] declares [SCH.optionsSource], replace it -- in place, keeping its
 * position -- with the [SCH.options] its provider gives for [cxt] under the property [name]. A node without the
 * keyword comes back unchanged (the same instance). [copyOnWriteSchema] supplies the traversal and the name.
 */
private fun resolveOptionsOnNode(
    cxt: KdrCxt,
    node: Map<String, Any?>,
    providers: Map<String, SchOptionsProvider>,
    name: String,
): Map<String, Any?> {
    val source = node[SCH.optionsSource] as? String ?: return node
    val provider = providers[source]
        // Unreachable on a booted node -- checkInit refuses an unregistered id before serving anything -- but
        // a store assembled by hand in a test has no such pass, and a silent empty choice list is exactly the
        // failure this whole feature makes hard to see.
        ?: throw KdrException("No options provider is registered under '$source' (for property '$name').")
    val choices = provider(cxt, name).map { linkedMapOf<String, Any?>(SCH.label to it.label, SCH.value to it.value) }
    val result = LinkedHashMap<String, Any?>()
    for ((key, value) in node) {
        if (key == SCH.optionsSource) result[SCH.options] = choices else result[key] = value
    }
    return result
}

/**
 * Every problem with the [SCH.optionsSource] declarations under [node], reported against [where] so a boot
 * failure names the type or endpoint at fault.
 *
 * Both checks are here rather than in the builder because either half can be written last: `option(...)`
 * followed by `optionsSource(...)` and the reverse produce the same node, so a check at declaration time
 * catches one order and misses the other. Boot sees the finished document.
 */
fun optionsSourceProblems(where: String, node: Any?, providers: Map<String, SchOptionsProvider>): List<String> {
    val problems = mutableListOf<String>()
    collectProblems(where, node, providers, "", problems)
    return problems
}

private fun collectProblems(
    where: String,
    node: Any?,
    providers: Map<String, SchOptionsProvider>,
    name: String,
    out: MutableList<String>,
) {
    when (node) {
        is Map<*, *> -> {
            val json = node.toJsonMap()
            val at = if (name.isEmpty()) where else "$where property '$name'"
            when (val source = json[SCH.optionsSource]) {
                null -> {}
                !is String -> out.add("$at declares '${SCH.optionsSource}' as ${source}, which is not an id.")
                else -> {
                    if (source !in providers) {
                        out.add(
                            "$at sources its options from '$source', which no component registered. Register a " +
                                "provider under that id, or correct the name.",
                        )
                    }
                    if (json[SCH.options] != null) {
                        out.add(
                            "$at declares both '${SCH.options}' and '${SCH.optionsSource}'. A choice list comes " +
                                "from one place or the other -- drop whichever is not wanted.",
                        )
                    }
                }
            }
            for ((key, value) in json) {
                if (key == SCH.properties && value is Map<*, *>) {
                    for ((child, body) in value.toJsonMap()) collectProblems(where, body, providers, child, out)
                } else {
                    collectProblems(where, value, providers, name, out)
                }
            }
        }
        is List<*> -> for (element in node) collectProblems(where, element, providers, name, out)
    }
}
