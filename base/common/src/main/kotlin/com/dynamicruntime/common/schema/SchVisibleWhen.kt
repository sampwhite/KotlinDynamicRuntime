package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/**
 * Boot-time collection of `g-visibleWhen` problems (issues #545, #564).
 *
 * `g-visibleWhen` is a per-property cfact expression, **evaluated on the frontend** (issue #564): the served
 * schema keeps the keyword, and the client hides a property whose expression the caller's delivered cfacts
 * fail. So there is nothing to resolve in the served schema -- the field is never dropped here, the way [SCH.visibleWhen]
 * describes -- only a boot check that every declared expression is sound.
 *
 * This walks a schema document collecting every declared expression's problems. [check] decides what, if
 * anything, is wrong with one expression: it returns a problem detail, or null when the expression is fine
 * (both a parse and the "names only frontend-delivered cfacts" check live in the caller's [check], since only
 * it holds the registry). [where] names the location so a failure points at the declaration.
 */
fun visibleWhenProblems(where: String, node: Any?, check: (expression: String) -> String?): List<String> {
    val problems = mutableListOf<String>()
    fun walk(n: Any?) {
        when (n) {
            is Map<*, *> -> {
                (n[SCH.visibleWhen] as? String)?.let { expression ->
                    check(expression)?.let { detail ->
                        problems.add("$where: '${SCH.visibleWhen}' expression '$expression' $detail")
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

/**
 * Collects `g-visibleWhen` gates declared on a **required** property (issue #564).
 *
 * A gate hides the field on the frontend, but the served schema still lists it in `required`, so a caller the
 * gate hides could never submit: validation rejects the missing field, pointing at one they cannot see. So
 * `g-visibleWhen` is for optional fields only, and a required one is refused at boot. This walks every object
 * node and matches a gated `properties` child against that node's own `required` array. An endpoint field
 * carries its required-ness on the field rather than in the schema, so its check is separate (see the caller);
 * this covers the type-definition shape. [where] names the location.
 */
fun requiredVisibleWhenProblems(where: String, node: Any?): List<String> {
    val problems = mutableListOf<String>()
    fun walk(n: Any?) {
        when (n) {
            is Map<*, *> -> {
                val props = n[SCH.properties] as? Map<*, *>
                if (props != null) {
                    val required = (n[SCH.required] as? List<*>).orEmpty().filterIsInstance<String>().toSet()
                    for ((child, body) in props) {
                        val name = child as? String ?: continue
                        if (name in required && (body as? Map<*, *>)?.get(SCH.visibleWhen) is String) {
                            problems.add(requiredGateProblem(where, name))
                        }
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

/** The message a required-and-gated property earns (issue #564), shared by the type-def and endpoint checks. */
fun requiredGateProblem(where: String, property: String): String =
    "$where: property '$property' is required but declares '${SCH.visibleWhen}'. The gate hides the field on " +
        "the frontend while the schema still requires it, so a caller it hides could not submit. " +
        "'${SCH.visibleWhen}' is for optional fields only."

/**
 * `g-visibleWhen` applied to a **write** (issue #830): the data to store when a caller writes [incoming] over [stored]
 * (null for a new entry) against [type], and the paths of any gated value the caller tried to change.
 *
 * A field whose gate the caller fails ([allows] false) is not theirs to set, so it keeps its stored value: left out
 * of [incoming] -- a replace from a form that hid it -- or sent back unchanged -- a raw editor's round trip -- it is
 * stored as it was. A different value, or any value on a new entry, is a change, reported in [SchGatedWrite.refused]
 * for the caller to refuse. "Unchanged" is judged as the page judges it ([gateComparable]): null and blank text are
 * absent, and numbers compare by value -- so sending null or "" cannot clear a stored value either.
 *
 * A field whose gate the caller passes, or that has none, is taken as sent; its nested object fields are judged the
 * same way, and a list's elements index by index against the stored list's. Removing a container that still holds a
 * value the caller cannot see -- leaving out a nested object or list, or dropping list elements -- is a change to
 * that value, and refused like one ([holdsGatedValue]). A list reordered by someone who cannot see a gated field in
 * its elements is judged as the positions now stand.
 *
 * Only gates the caller **fails** are ever consulted, and [allows] is asked at most once per declared expression by
 * a caller that memoizes it -- a write touching no gated field costs a walk of the type and nothing more.
 */
fun keepGatedFields(
    type: SchType,
    stored: Map<String, Any?>?,
    incoming: Map<String, Any?>,
    allows: (expression: String) -> Boolean,
    path: String = "",
    depth: Int = 0,
): SchGatedWrite {
    if (depth > SGATE.maxDepth) {
        throw KdrException("A value nests deeper than ${SGATE.maxDepth} levels at '$path'; the gate check stops there.")
    }
    val out = LinkedHashMap(incoming)
    val refused = mutableListOf<String>()
    for ((name, prop) in type.properties) {
        val at = if (path.isEmpty()) name else "$path.$name"
        val storedValue = stored?.get(name)
        val incomingValue = incoming[name]
        val gate = prop.visibleWhen
        if (gate != null && !allows(gate)) {
            val sent = gateComparable(incomingValue, depth + 1)
            when {
                sent == null -> if (storedValue != null) out[name] = storedValue else out.remove(name)
                sent != gateComparable(storedValue, depth + 1) -> refused.add(at)
                // Unchanged: store it exactly as it was, not as this caller happened to spell it.
                else -> out[name] = storedValue
            }
            continue
        }
        val valueType = prop.valueType
        when {
            incomingValue is Map<*, *> && valueType.properties.isNotEmpty() -> {
                val inner = keepGatedFields(
                    valueType, (storedValue as? Map<*, *>)?.toJsonMapOrEmpty(), incomingValue.toJsonMapOrEmpty(),
                    allows, at, depth + 1,
                )
                out[name] = inner.data
                refused.addAll(inner.refused)
            }
            incomingValue is List<*> && valueType.itemType?.properties?.isNotEmpty() == true -> {
                val itemType = valueType.itemType!!
                val storedList = storedValue as? List<*>
                out[name] = incomingValue.mapIndexed { i, element ->
                    val m = element as? Map<*, *> ?: return@mapIndexed element
                    val inner = keepGatedFields(
                        itemType, (storedList?.getOrNull(i) as? Map<*, *>)?.toJsonMapOrEmpty(), m.toJsonMapOrEmpty(),
                        allows, "$at[$i]", depth + 1,
                    )
                    refused.addAll(inner.refused)
                    inner.data
                }
                // Elements dropped off the end take their gated values with them.
                storedList?.drop(incomingValue.size)?.forEachIndexed { j, dropped ->
                    if (holdsGatedValue(itemType, dropped, allows, depth + 1)) refused.add("$at[${incomingValue.size + j}]")
                }
            }
            // The container left out (or emptied) altogether: a hidden value inside it would go with it.
            gateComparable(incomingValue, depth + 1) == null && holdsGatedValue(valueType, storedValue, allows, depth + 1) ->
                refused.add(at)
        }
    }
    return SchGatedWrite(out, refused)
}

/**
 * Whether [value] (of [type]) holds, at any depth, a value in a field whose gate the caller fails (issue #830) --
 * what removing it would take away from a caller who may not change it: a whole entry, a nested object, a list
 * element.
 */
fun holdsGatedValue(type: SchType, value: Any?, allows: (expression: String) -> Boolean, depth: Int = 0): Boolean {
    if (depth > SGATE.maxDepth) {
        throw KdrException("A value nests deeper than ${SGATE.maxDepth} levels; the gate check stops there.")
    }
    return when (value) {
        is Map<*, *> -> type.properties.any { (name, prop) ->
            val child = value[name]
            val gate = prop.visibleWhen
            if (gate != null && !allows(gate)) {
                gateComparable(child, depth + 1) != null
            } else {
                holdsGatedValue(prop.valueType, child, allows, depth + 1)
            }
        }
        is List<*> -> type.itemType?.let { item -> value.any { holdsGatedValue(item, it, allows, depth + 1) } } ?: false
        else -> false
    }
}

/**
 * A value as the gate compares it (issue #830), the way the page decides a field is untouched: null and blank text
 * are absent, a whole number compares equal however it was spelled (`2` and `2.0`), and maps and lists compare
 * element by element with their absent members dropped. Null means absent.
 */
fun gateComparable(value: Any?, depth: Int = 0): Any? {
    if (depth > SGATE.maxDepth) {
        throw KdrException("A value nests deeper than ${SGATE.maxDepth} levels; the gate check stops there.")
    }
    return when (value) {
        null -> null
        is String -> value.takeIf { it.isNotBlank() }
        is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < SGATE.exactWholeLimit) value.toLong() else value
        is Float -> gateComparable(value.toDouble(), depth)
        is Number -> value.toLong()
        is Map<*, *> -> value.entries.mapNotNull { (k, v) -> gateComparable(v, depth + 1)?.let { k.toString() to it } }
            .toMap().ifEmpty { null }
        is List<*> -> value.map { gateComparable(it, depth + 1) }.ifEmpty { null }
        else -> value
    }
}

/** What [keepGatedFields] makes of a write: the [data] to store, and the gated fields the caller tried to change. */
class SchGatedWrite(val data: Map<String, Any?>, val refused: List<String>)

/** Limits of the `g-visibleWhen` write rule (issue #830). */
@Suppress("ConstPropertyName")
object SGATE {
    /** How deep [keepGatedFields] follows nested objects and lists before refusing the value. */
    const val maxDepth = 30

    /** Below this a double holds every whole number exactly, so [gateComparable] may read `2.0` as `2`. */
    const val exactWholeLimit = 9.0e15
}
