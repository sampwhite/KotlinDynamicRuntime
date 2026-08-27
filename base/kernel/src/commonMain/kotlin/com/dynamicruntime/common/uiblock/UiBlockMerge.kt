package com.dynamicruntime.common.uiblock

/**
 * Folds every layer that applies to one UiBlock, for [client] (issue #457).
 *
 * ### The order
 *
 * 1. **Base** layers, in declaration order.
 * 2. **Component** overlays (`client == null`), in declaration order.
 * 3. **This client's** overlays, in declaration order.
 *
 * Later wins, and a client gets the last word -- the same precedence fragments use, decided the same way: a
 * client is the most specific thing with an opinion, and a component must not be able to take a customer's
 * choice back by adding an overlay of its own.
 *
 * ### Additive and overriding only -- nothing is ever removed
 *
 * There is no tombstone, no remove marker, and no rule about one overlay deleting what a later one expected to
 * find. An overlay takes an item away by setting its [UIB.cfactExpression] to `#never`, which is resolved later, per
 * request. Three things follow: the base stays one readable list of everything that exists, *"why is this
 * gone?"* is answered by reading the item rather than by diffing overlays in load order, and `#always` puts it
 * back.
 */
fun mergeUiBlock(blockId: String, sources: List<UiBlockSource>, client: String?): MergedUiBlock {
    val applicable = sources.filter { it.blockId == blockId && (it.client == null || it.client == client) }
    val bases = applicable.filter { !it.isOverlay }
    val overlays = applicable.filter { it.isOverlay }.sortedBy { if (it.client == null) 0 else 1 }
    // Declared by the base alone, so several bases (unusual, but not refused) contribute rules in order.
    val arrayKeys = bases.fold(emptyMap<String, String>()) { acc, s -> acc + s.arrayKeys }

    var merged: Map<String, Any?> = emptyMap()
    var found = false
    for (source in bases) {
        found = true
        merged = mergeObject(merged, source.content, "", arrayKeys)
    }
    for (source in overlays) {
        merged = mergeObject(merged, source.content, "", arrayKeys)
    }
    return MergedUiBlock(blockId, client, orderArrays(merged, "", arrayKeys), arrayKeys, found)
}

/** Folds [over] onto [base], key by key, recursing into objects and into arrays that declare a key. */
private fun mergeObject(
    base: Map<String, Any?>,
    over: Map<String, Any?>,
    path: String,
    arrayKeys: Map<String, String>,
): Map<String, Any?> {
    val out = LinkedHashMap(base)
    for ((key, incoming) in over) {
        val at = if (path.isEmpty()) key else "$path.$key"
        val existing = out[key]
        out[key] = when {
            existing is Map<*, *> && incoming is Map<*, *> ->
                mergeObject(asObject(existing), asObject(incoming), at, arrayKeys)
            // An array merges **only** where a primary key says which element is which. Without one, position
            // would have to identify an element, which holds until somebody inserts one -- so a keyless array
            // is replaced wholesale, which is at least a thing an author can predict.
            existing is List<*> && incoming is List<*> && arrayKeys[at] != null ->
                mergeArray(existing, incoming, at, arrayKeys.getValue(at), arrayKeys)
            else -> incoming
        }
    }
    return out
}

/** Merges [over] into [base] by [keyField]: a matching element is folded into, a new one is appended. */
private fun mergeArray(
    base: List<*>,
    over: List<*>,
    path: String,
    keyField: String,
    arrayKeys: Map<String, String>,
): List<Any?> {
    val out = base.toMutableList()
    // The element path, so a nested array inside an element declares its own rule against a stable name --
    // `items.actions` rather than `items[3].actions`, which would depend on where the element landed.
    val elementPath = "$path.*"
    for (incoming in over) {
        val incomingKey = (incoming as? Map<*, *>)?.get(keyField)
        val at = if (incomingKey == null) -1 else out.indexOfFirst { (it as? Map<*, *>)?.get(keyField) == incomingKey }
        if (at >= 0) {
            out[at] = mergeObject(asObject(out[at] as Map<*, *>), asObject(incoming as Map<*, *>), elementPath, arrayKeys)
        } else {
            // Appended rather than refused when it carries no key: an array that declares a rule may still
            // hold something keyless, and dropping it silently would be the worse answer.
            out.add(incoming)
        }
    }
    return out
}

/**
 * Sorts every keyed array by [UIB.displayOrder], then by the element's primary key.
 *
 * **The key is the tie-break, and it is doing real work.** Contribution order is not a usable fallback: a
 * component's `loadPriority` defaults to `PRI.standard` for everybody, so two components at the same priority
 * are ordered by ServiceLoader discovery -- which is jar order, and not the same on every machine. Two items
 * injected at the same `displayOrder` would then appear in an order that differs between environments, which
 * is the kind of intermittent difference that costs an afternoon. Sorting by the key as well makes the answer
 * the same everywhere.
 *
 * An element with no `displayOrder` sorts **after** those that have one. Absence means nobody placed it, and
 * the end is the one position that says so rather than implying a decision.
 *
 * Done at merge rather than per request because it depends only on the layers, which are fixed at boot.
 */
private fun orderArrays(node: Map<String, Any?>, path: String, arrayKeys: Map<String, String>): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for ((key, value) in node) {
        val at = if (path.isEmpty()) key else "$path.$key"
        out[key] = when {
            value is Map<*, *> -> orderArrays(asObject(value), at, arrayKeys)
            value is List<*> && arrayKeys[at] != null -> {
                val keyField = arrayKeys.getValue(at)
                value.map { if (it is Map<*, *>) orderArrays(asObject(it), "$at.*", arrayKeys) else it }
                    .sortedWith(
                        compareBy(
                            { (it as? Map<*, *>)?.get(UIB.displayOrder).asOrder() },
                            { (it as? Map<*, *>)?.get(keyField)?.toString() ?: "" },
                        ),
                    )
            }
            else -> value
        }
    }
    return out
}

/** A `displayOrder` as a sortable number; absent or unreadable sorts last. */
private fun Any?.asOrder(): Int = when (this) {
    is Number -> toInt()
    is String -> toIntOrNull() ?: Int.MAX_VALUE
    else -> Int.MAX_VALUE
}

@Suppress("UNCHECKED_CAST")
private fun asObject(map: Map<*, *>): Map<String, Any?> = map as Map<String, Any?>
