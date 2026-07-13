package com.dynamicruntime.common.util

import com.dynamicruntime.common.annotation.KdrPrivate

// Extension methods for Lists and Maps.

/**
 * Returns a deep copy of this map. Nested maps and lists are copied recursively
 * down to [maxDepth] container levels (default 5, the top map counting as the
 * first); anything deeper is shared by reference, as are scalar values (assumed
 * immutable).
 *
 * The depth cap is deliberate: it bounds runaway recursion on cyclically defined
 * data, and matches practice -- e.g. schema data is never mutated many levels
 * deep, so cloning that far is unnecessary.
 */
fun <K, V> Map<K, V>.deepClone(maxDepth: Int = 5): MutableMap<K, V> {
    val out = LinkedHashMap<Any?, Any?>(size)
    for ((k, v) in this) out[k] = deepCloneValue(v, maxDepth - 1)
    return out.toT()
}

/** Deep copy of this list, with the same [maxDepth] semantics as [Map.deepClone]. */
fun <T> List<T>.deepClone(maxDepth: Int = 5): MutableList<T> {
    val out = ArrayList<Any?>(size)
    for (e in this) out.add(deepCloneValue(e, maxDepth - 1))
    return out.toT()
}

// Recursively copies maps and lists while depth remains; shares everything else.
@KdrPrivate
fun deepCloneValue(value: Any?, depth: Int): Any? = when (value) {
    is Map<*, *> if depth > 0 -> LinkedHashMap<Any?, Any?>(value.size).also { out ->
        for ((k, v) in value) out[k] = deepCloneValue(v, depth - 1)
    }

    is List<*> if depth > 0 -> value.mapTo(ArrayList(value.size)) { deepCloneValue(it, depth - 1) }
    else -> value
}
