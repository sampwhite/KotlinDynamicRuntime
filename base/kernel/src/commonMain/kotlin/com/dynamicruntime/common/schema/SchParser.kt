package com.dynamicruntime.common.schema

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toOptStr

/**
 * Parses a `$defs`-style map of JSON Schema types (e.g., the output of
 * `schemaDefs { ... }`) into resolved [SchType] / [SchProperty] objects.
 *
 * Every `$ref` is checked: its target must be one of the types in [defs] or in
 * [existingTypes]; otherwise a [KdrException] is thrown. `anyOf`/`allOf`/`if`/
 * `then`/`else` are not handled yet.
 *
 * @return the newly parsed types keyed by fully qualified name.
 */
fun parseSchemaTypes(
    defs: Map<String, Any?>,
    existingTypes: Map<String, SchType> = emptyMap(),
): Map<String, SchType> {
    val pendingRefs = mutableListOf<SchProperty>()
    val pendingItemRefs = mutableListOf<PendingItemRef>()
    val parsed = LinkedHashMap<String, SchType>()
    for ((name, raw) in defs) {
        if (raw is Map<*, *>) {
            parsed[name] = parseNode(name, raw.toJsonMap(), pendingRefs, pendingItemRefs)
        }
    }
    // Resolve $refs against the existing types plus the just-parsed ones.
    val registry = HashMap<String, SchType>(existingTypes)
    registry.putAll(parsed)
    for (prop in pendingRefs) {
        val refName = prop.refName ?: continue
        prop.valueType = registry[refName]
            ?: throw KdrException.mkConv($$"Schema $ref to unknown type '$$refName'.")
    }
    // Bind array element types whose `items` was a $ref (deferred the same way as property refs, so a target
    // parsed later -- or a self-reference via items -- resolves without expanding during parsing).
    for (item in pendingItemRefs) {
        item.array.itemType = registry[item.refName]
            ?: throw KdrException.mkConv($$"Schema $ref to unknown type '$${item.refName}'.")
    }
    return parsed
}

/** An array [SchType] whose `items` is a `$ref` ([refName]), awaiting binding in the resolution pass. */
@KdrPrivate
class PendingItemRef(val array: SchType, val refName: String)

@KdrPrivate
fun parseNode(
    name: String?,
    map: Map<String, Any?>,
    pendingRefs: MutableList<SchProperty>,
    pendingItemRefs: MutableList<PendingItemRef>,
    depth: Int = 0,
): SchType {
    // Guard against runaway recursion -- e.g., a raw schema Map that references itself (see JsonUtil for the
    // same nesting guard on formatting). A legitimate schema never nests anywhere near this deep.
    if (depth > 20) {
        throw KdrException.mkConv("Schema is nested too deeply (over 20 levels); it may contain a self-reference.")
    }
    val properties = LinkedHashMap<String, SchProperty>()
    val rawProps = map[SCH.properties]
    if (rawProps is Map<*, *>) {
        for ((k, v) in rawProps) {
            val pName = k.toOptStr() ?: continue
            if (v is Map<*, *>) {
                properties[pName] = parseProperty(pName, v.toJsonMap(), pendingRefs, pendingItemRefs, depth)
            }
        }
    }
    // The element schema of an array. A `$ref` here is deferred (bound in the resolution pass, like a property
    // ref) so a not-yet-parsed target -- or a self-reference via items -- resolves instead of expanding.
    val rawItems = map[SCH.items]
    var itemType: SchType? = null
    var itemRefName: String? = null
    if (rawItems is Map<*, *>) {
        val itemsMap = rawItems.toJsonMap()
        val itemRef = itemsMap[SCH.dRef].toOptStr()
        if (itemRef != null) {
            itemRefName = refTargetName(itemRef)
        } else {
            itemType = parseNode(null, itemsMap, pendingRefs, pendingItemRefs, depth + 1)
        }
    }
    val jsonType = map[SCH.type].toOptStr()
    val format = map[SCH.format].toOptStr()
    val schType = SchType(
        name = name,
        jsonType = jsonType,
        // Numeric types and recognized date formats are coercible by default; everything else is strict.
        allowCoerce = (map[SCH.allowCoerce] as? Boolean) ?: (isNumericType(jsonType) || isDateFormat(format)),
        format = format,
        description = map[SCH.description].toOptStr(),
        properties = properties,
        required = parseRequired(map[SCH.required]),
        // Default false when the type declares properties, true when it declares none (generic map).
        additionalProperties = (map[SCH.additionalProperties] as? Boolean) ?: properties.isEmpty(),
        itemType = itemType,
        options = parseOptions(map[SCH.options]),
        default = map[SCH.default],
    )
    if (itemRefName != null) {
        pendingItemRefs.add(PendingItemRef(schType, itemRefName))
    }
    return schType
}

/** Whether a JSON Schema type is one of the numeric types (the [SCH.allowCoerce] default). */
@KdrPrivate
fun isNumericType(jsonType: String?): Boolean = jsonType == SCT.integer || jsonType == SCT.number

/** Whether a `format` value is one of the date formats we validate/coerce ([SFMT.date] / [SFMT.dateTime]). */
@KdrPrivate
fun isDateFormat(format: String?): Boolean = format == SFMT.date || format == SFMT.dateTime

/** Parses the custom `options` construct: a list of `{label, value}` entries.
 *  A missing `label` defaults to the `value`. */
@KdrPrivate
fun parseOptions(raw: Any?): List<SchOption>? {
    if (raw !is List<*>) return null
    return raw.mapNotNull { entry ->
        if (entry is Map<*, *>) {
            val value = entry[SCH.value].toOptStr() ?: return@mapNotNull null
            SchOption(value, entry[SCH.label].toOptStr() ?: value)
        } else {
            null
        }
    }
}

@KdrPrivate
fun parseProperty(
    name: String,
    map: Map<String, Any?>,
    pendingRefs: MutableList<SchProperty>,
    pendingItemRefs: MutableList<PendingItemRef>,
    depth: Int,
): SchProperty {
    val description = map[SCH.description].toOptStr()
    val ref = map[SCH.dRef].toOptStr()
    if (ref != null) {
        val prop = SchProperty(name, description, refTargetName(ref))
        pendingRefs.add(prop) // valueType bound in the resolution pass
        return prop
    }
    val prop = SchProperty(name, description, refName = null)
    prop.valueType = parseNode(null, map, pendingRefs, pendingItemRefs, depth + 1)
    return prop
}

@KdrPrivate
fun parseRequired(raw: Any?): Set<String> =
    if (raw is List<*>) raw.mapNotNullTo(LinkedHashSet()) { it.toOptStr() } else emptySet()

/** Extracts the type name from a `$ref` like "#/${'$'}defs/core.Count". */
@KdrPrivate
fun refTargetName(ref: String): String {
    val prefix = "#/${SCH.dDefs}/"
    return if (ref.startsWith(prefix)) ref.substring(prefix.length) else ref
}
