package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException

/**
 * Limits of the `g-visibleWhen` rules over a value (issue #830): the write's (`keepGatedFields`, on the backend)
 * and the view's ([hideGatedFields]). In the kernel so the two walk to the same depth.
 */
@Suppress("ConstPropertyName")
object SGATE {
    /** How deep a value's nested objects, lists and map entries are followed before the value is refused. */
    const val maxDepth = 30

    /** Below this a double holds every whole number exactly, so the write's comparison may read `2.0` as `2`. */
    const val exactWholeLimit = 9.0e15
}

/**
 * [value] (of [type]) as a caller may be **shown** it for editing: without the fields, at any depth, whose
 * `g-visibleWhen` gate the caller fails ([allows] false) -- in an object's own fields, in a list's elements, and in
 * a map's entries (issue #1055).
 *
 * For a control that draws a whole object as one thing -- the JSON editor an object with no declared fields gets,
 * which is how a map is drawn. A form hides a gated field by not drawing its box; a control with no box per field
 * has to be handed a value the field is not in, or the gate hides nothing there. Leaving the field out is also the
 * round trip a write expects: a gated field absent from what is sent keeps its stored value (`keepGatedFields`).
 *
 * Returns [value] itself when nothing in it is hidden, so a caller can tell by identity that nothing was -- and
 * text someone is part way through typing, which is no map yet, passes through as it is.
 */
fun hideGatedFields(type: SchType, value: Any?, allows: (expression: String) -> Boolean, depth: Int = 0): Any? {
    if (depth > SGATE.maxDepth) {
        throw KdrException("A value nests deeper than ${SGATE.maxDepth} levels; the gated view stops there.")
    }
    return when (value) {
        is Map<*, *> -> {
            var changed = false
            val out = LinkedHashMap<Any?, Any?>(value.size)
            for ((key, child) in value) {
                val prop = type.properties[key]
                val childType = prop?.valueType ?: type.additionalValueType
                val gate = prop?.visibleWhen
                if (gate != null && !allows(gate)) {
                    changed = true
                    continue
                }
                val shown = if (childType == null) child else hideGatedFields(childType, child, allows, depth + 1)
                if (shown !== child) changed = true
                out[key] = shown
            }
            if (changed) out else value
        }
        is List<*> -> {
            val itemType = type.itemType ?: return value
            var changed = false
            val out = value.map { element ->
                hideGatedFields(itemType, element, allows, depth + 1).also { if (it !== element) changed = true }
            }
            if (changed) out else value
        }
        else -> value
    }
}
