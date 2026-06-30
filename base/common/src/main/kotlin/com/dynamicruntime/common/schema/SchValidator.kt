package com.dynamicruntime.common.schema

import com.dynamicruntime.common.annotation.KdrPrivate
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
}

/**
 * A single validation failure: the [path] through the data to the offending value
 * (e.g. "address.street" or "tags[1]"), a [code] for why, and a human [message].
 */
data class SchFailure(val path: String, val code: SchFailCode, val message: String)

/**
 * Validates [data] against the parsed [type], collecting EVERY failure (not
 * fail-fast) into the returned list. Reads only declared attributes on [SchType] /
 * [SchProperty] — it never looks a value up in a raw schema map.
 */
fun validate(type: SchType, data: Any?): List<SchFailure> {
    val failures = mutableListOf<SchFailure>()
    validateValue(type, data, "", failures)
    return failures
}

@KdrPrivate
fun validateValue(type: SchType, value: Any?, path: String, failures: MutableList<SchFailure>) {
    val jsonType = type.jsonType
    if (!matchesType(jsonType, value)) {
        if (canCoerce(type, value)) {
            return // value can be coerced to the declared type (allowCoerce)
        }
        failures.add(SchFailure(path, SchFailCode.wrongType, "expected type '${jsonType ?: "any"}'"))
        return // can't meaningfully recurse if the container type is wrong
    }
    when (jsonType) {
        SCT.kObject -> {
            val map = value as Map<*, *>
            for (req in type.required) {
                if (!map.containsKey(req)) {
                    failures.add(
                        SchFailure(childPath(path, req), SchFailCode.missingRequired,
                            "required property '$req' is missing"),
                    )
                }
            }
            for ((k, v) in map) {
                val key = k as? String ?: continue
                val prop = type.properties[key] ?: continue // unknown property: not checked yet
                validateValue(prop.valueType, v, childPath(path, key), failures)
            }
        }

        SCT.array -> {
            val list = value as List<*>
            val itemType = type.itemType ?: return
            list.forEachIndexed { i, elem -> validateValue(itemType, elem, indexPath(path, i), failures) }
        }
    }
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
