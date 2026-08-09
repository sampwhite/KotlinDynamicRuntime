package com.dynamicruntime.common.schema

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.util.toOptDouble

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
    val registry = HashMap(existingTypes)
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
    // ref), so a not-yet-parsed target -- or a self-reference via items -- resolves instead of expanding.
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
        // Scalars read an empty value as "not supplied"; arrays/objects opt in, and an untyped field -- which
        // constrains nothing -- is left alone.
        emptyIsAbsent = (map[SCH.emptyIsAbsent] as? Boolean) ?: isScalarType(jsonType),
        format = format,
        description = map[SCH.description].toOptStr(),
        properties = properties,
        required = parseRequired(map[SCH.required]),
        // Default false when the type declares properties, true when it declares none (generic map).
        additionalProperties = (map[SCH.additionalProperties] as? Boolean) ?: properties.isEmpty(),
        itemType = itemType,
        options = parseOptions(map[SCH.options]),
        default = map[SCH.default],
        errorMessages = parseErrorMessages(map[SCH.errors], name),
        minBound = map[minBoundKeyword(jsonType)].toOptDouble(),
        maxBound = map[maxBoundKeyword(jsonType)].toOptDouble(),
    )
    if (itemRefName != null) {
        pendingItemRefs.add(PendingItemRef(schType, itemRefName))
    }
    return schType
}

/**
 * Which of JSON Schema's four lower-bound keywords applies to [jsonType] — `minimum` for a number,
 * `minLength` for a string, `minItems` for an array, `minProperties` for an object — or null when the type
 * measures nothing (a boolean, or an unconstrained field).
 *
 * A keyword belonging to a *different* type is simply not read, which is what JSON Schema itself does: a
 * validation keyword that does not apply to the instance type is ignored, not an error. Deliberately unlike
 * the check on `g-errors` keys, which is ours to define and so can afford to be strict — being strict here
 * would mean rejecting documents a standard validator accepts.
 */
@KdrPrivate
fun minBoundKeyword(jsonType: String?): String = when {
    isNumericType(jsonType) -> SCH.minimum
    jsonType == SCT.string -> SCH.minLength
    jsonType == SCT.array -> SCH.minItems
    jsonType == SCT.kObject -> SCH.minProperties
    else -> ""
}

/** The upper-bound counterpart of [minBoundKeyword]. */
@KdrPrivate
fun maxBoundKeyword(jsonType: String?): String = when {
    isNumericType(jsonType) -> SCH.maximum
    jsonType == SCT.string -> SCH.maxLength
    jsonType == SCT.array -> SCH.maxItems
    jsonType == SCT.kObject -> SCH.maxProperties
    else -> ""
}

/** Whether a JSON Schema type is one of the numeric types (the [SCH.allowCoerce] default). */
@KdrPrivate
fun isNumericType(jsonType: String?): Boolean = jsonType == SCT.integer || jsonType == SCT.number

/**
 * Whether a JSON Schema type is a single value rather than a container (the [SCH.emptyIsAbsent] default).
 * An unconstrained (null) type is deliberately not scalar: there is no basis for reading its emptiness.
 */
@KdrPrivate
fun isScalarType(jsonType: String?): Boolean =
    jsonType == SCT.string || jsonType == SCT.boolean || isNumericType(jsonType)

/** Whether a `format` value is one of the date formats we validate/coerce ([SFMT.date] / [SFMT.dateTime]). */
@KdrPrivate
fun isDateFormat(format: String?): Boolean = format == SFMT.date || format == SFMT.dateTime

/**
 * Whether [format] marks file content ([SFMT.binary]) — OpenAPI's `type: string, format: binary`.
 *
 * The one question everything else asks: the validator asks it to leave the value alone (it is a `ContentData`,
 * not text), the dispatcher asks it to decide a request is a multipart upload, and the frontend asks it to
 * render a file picker instead of a text box.
 */
fun isBinaryFormat(format: String?): Boolean = format == SFMT.binary

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

/**
 * Parses the custom `g-errors` construct: a map from error key to the message that failure should show.
 *
 * **Every key is checked here, and an unrecognized one fails the parse** rather than being ignored. A message
 * filed under a misspelled code is silent in the worst way — the schema looks like it says something, and the
 * only symptom is the framework's own wording turning up where "custom copy" was expected. The valid keys are a
 * small closed set, so saying which one is wrong costs nothing. This is the same reasoning that puts named
 * functions on the builder: from code the mistake is unwriteable, and this catches the documents that did not
 * come through it.
 *
 * A value that is not a string is skipped rather than rejected: the object form is reserved for a future
 * markdown-fragment reference (`{"fragment": "score.required"}`), so a document using it early degrades to
 * the built-in wording instead of failing to load.
 */
@KdrPrivate
fun parseErrorMessages(raw: Any?, typeName: String?): Map<String, String> {
    if (raw !is Map<*, *>) return emptyMap()
    val out = LinkedHashMap<String, String>(raw.size)
    for ((k, v) in raw) {
        val key = k.toOptStr() ?: continue
        if (key != SCH.errorDefault && SchFailCode.entries.none { it.name == key }) {
            val valid = (SchFailCode.entries.map { it.name } + SCH.errorDefault).joinToString(", ")
            throw KdrException(
                "'${SCH.errors}'${typeName?.let { " on '$it'" } ?: ""} names '$key', which is not a failure " +
                    "code. Valid keys: $valid."
            )
        }
        v.toOptStr()?.let { out[key] = it }
    }
    return out
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
