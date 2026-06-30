package com.dynamicruntime.common.schema

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.util.deepClone
import com.dynamicruntime.common.util.splitComma

/**
 * Why a value failed schema validation. Internal and not serialized (so the lower
 * camelCase entry names are fine); grows as more JSON Schema constructs are
 * supported.
 */
@Suppress("EnumEntryName")
enum class SchFailCode {
    missingRequired,
    wrongType,
    invalidOption,
}

/**
 * A single validation failure: the [path] through the data to the offending value
 * (e.g. "address.street" or "tags[1]"), a [code] for why, and a human [message].
 * For an [SchFailCode.invalidOption] failure, [options] carries the full list of
 * currently valid choices, given first-class visibility to the caller.
 */
data class SchFailure(
    val path: String,
    val code: SchFailCode,
    val message: String,
    val options: List<SchOption>? = null,
)

/** Result of a coercing validation: the (possibly transformed) [value] and the [failures]. */
data class SchResult(val value: Any?, val failures: List<SchFailure>)

/**
 * Validates [data] against the parsed [type], collecting EVERY failure (not
 * fail-fast). Does not alter the data. Reads only declared attributes on [SchType]
 * / [SchProperty] — it never looks a value up in a raw schema map.
 */
fun validate(type: SchType, data: Any?): List<SchFailure> {
    val failures = mutableListOf<SchFailure>()
    validateValue(type, data, "", coerce = false, failures)
    return failures
}

/**
 * Validates AND coerces [data]: applies `allowCoerce` conversions (string -> Long /
 * Double / list) and injects `default` values for missing properties, returning the
 * transformed value plus the failures. The input is never mutated — new maps/lists
 * are built where anything changes.
 */
fun coerceAndValidate(type: SchType, data: Any?): SchResult {
    val failures = mutableListOf<SchFailure>()
    val value = validateValue(type, data, "", coerce = true, failures)
    return SchResult(value, failures)
}

/** Validates [value] against [type], returning the (possibly coerced) value. */
@KdrPrivate
fun validateValue(type: SchType, value: Any?, path: String, coerce: Boolean, failures: MutableList<SchFailure>): Any? {
    val jsonType = type.jsonType
    if (!matchesType(jsonType, value)) {
        if (canCoerce(type, value)) {
            return if (coerce) coerceValue(type, value) else value
        }
        failures.add(SchFailure(path, SchFailCode.wrongType, "expected type '${jsonType ?: "any"}'"))
        return value
    }
    val options = type.options
    if (options != null) {
        val choice = value as? String
        if (choice == null || options.none { it.value == choice }) {
            failures.add(SchFailure(path, SchFailCode.invalidOption, "'$value' is not a valid option", options))
        }
        return value
    }
    return when (jsonType) {
        SCT.kObject -> validateObject(type, value as Map<*, *>, path, coerce, failures)
        SCT.array -> validateArray(type, value as List<*>, path, coerce, failures)
        else -> value // scalar matched, unchanged
    }
}

@KdrPrivate
fun validateObject(
    type: SchType,
    map: Map<*, *>,
    path: String,
    coerce: Boolean,
    failures: MutableList<SchFailure>,
): Any {
    // Only build a new map when coercing; otherwise the input is returned untouched.
    val out: MutableMap<String, Any?>? = if (coerce) LinkedHashMap(map.size) else null
    for ((k, v) in map) {
        val key = k as? String ?: continue
        val prop = type.properties[key]
        if (prop == null) {
            out?.put(key, v) // unknown property: not checked yet, kept as-is
            continue
        }
        // Call validateValue unconditionally (it collects failures); only store when coercing.
        val coerced = validateValue(prop.valueType, v, childPath(path, key), coerce, failures)
        out?.put(key, coerced)
    }
    for (req in type.required) {
        if (map.containsKey(req)) {
            continue
        }
        val default = type.properties[req]?.valueType?.default
        if (default != null) {
            out?.put(req, cloneForInjection(default)) // a default supplies the value, so no failure
        } else {
            failures.add(
                SchFailure(childPath(path, req), SchFailCode.missingRequired, "required property '$req' is missing"),
            )
        }
    }
    return out ?: map
}

@KdrPrivate
fun validateArray(
    type: SchType,
    list: List<*>,
    path: String,
    coerce: Boolean,
    failures: MutableList<SchFailure>,
): Any {
    val itemType = type.itemType
    val out: MutableList<Any?>? = if (coerce) ArrayList(list.size) else null
    list.forEachIndexed { i, elem ->
        val coerced = if (itemType != null) {
            validateValue(itemType, elem, indexPath(path, i), coerce, failures)
        } else {
            elem
        }
        out?.add(coerced)
    }
    return out ?: list
}

/**
 * Produces the coerced value for a [value] that [canCoerce] has accepted. Numeric
 * strings become Long / Double; any value becomes its toString for a string type;
 * a comma-separated string becomes a list (see [splitComma]).
 */
@KdrPrivate
fun coerceValue(type: SchType, value: Any?): Any? = when (type.jsonType) {
    SCT.integer -> (value as String).trim().toLong()
    SCT.number -> (value as String).trim().toDouble()
    SCT.string -> value.toString()
    SCT.array -> coerceStringToList(type.itemType, value as String)
    else -> value
}

@KdrPrivate
fun coerceStringToList(itemType: SchType?, value: String): List<Any?> {
    val parts = value.splitComma()
    return when (itemType?.jsonType) {
        SCT.integer -> parts.map { it.toLong() }
        SCT.number -> parts.map { it.toDouble() }
        else -> parts // strings (or unconstrained)
    }
}

/** Deep-clones a default before injecting it, so the schema's value is never shared. */
@KdrPrivate
fun cloneForInjection(value: Any?): Any? = when (value) {
    is Map<*, *> -> value.deepClone()
    is List<*> -> value.deepClone()
    else -> value // scalars are immutable
}

/**
 * Whether [value] can be coerced to [type] (the custom `allowCoerce` keyword).
 * Numeric: a string that parses to the type. String: any non-null value (rendered
 * via toString). Array: a comma-separated string whose parts fit the element type
 * (see [splitComma]). Anything else is not coercible yet.
 */
@KdrPrivate
fun canCoerce(type: SchType, value: Any?): Boolean {
    if (!type.allowCoerce) {
        return false
    }
    return when (type.jsonType) {
        SCT.integer -> value is String && value.trim().toLongOrNull() != null
        SCT.number -> value is String && value.trim().toDoubleOrNull() != null
        SCT.string -> value != null // toString of any non-null value
        SCT.array -> value is String && canCoerceStringToItems(type.itemType, value)
        else -> false
    }
}

/** Whether the comma-separated [value] splits into parts that fit [itemType]. */
@KdrPrivate
fun canCoerceStringToItems(itemType: SchType?, value: String): Boolean {
    val parts = value.splitComma()
    return when (itemType?.jsonType) {
        null, SCT.string -> true
        SCT.integer -> parts.all { it.toLongOrNull() != null }
        SCT.number -> parts.all { it.toDoubleOrNull() != null }
        else -> false // lists of non-string/non-number not supported yet
    }
}

@KdrPrivate
fun matchesType(jsonType: String?, value: Any?): Boolean = when (jsonType) {
    SCT.string -> value is String
    SCT.integer -> value is Int || value is Long
    SCT.number -> value is Number
    SCT.boolean -> value is Boolean
    SCT.kObject -> value is Map<*, *>
    SCT.array -> value is List<*>
    SCT.kNull -> value == null
    else -> true // null/unknown type => no constraint
}

@KdrPrivate
fun childPath(parent: String, key: String): String = if (parent.isEmpty()) key else "$parent.$key"

@KdrPrivate
fun indexPath(parent: String, index: Int): String = "$parent[$index]"
