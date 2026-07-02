package com.dynamicruntime.common.schema

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.deepClone
import com.dynamicruntime.common.util.jsonArray
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.splitComma
import com.dynamicruntime.common.util.toOptBool
import kotlin.time.Instant

/**
 * Why a value failed schema validation. Internal and not serialized (so the lower
 * camelCase entry names are fine); grows as more JSON Schema constructs are
 * supported.
 */
@Suppress("EnumEntryName")
enum class SchFailCode {
    missingRequired,

    /** A plain type check rejected the value before its content was ever inspected. */
    wrongType,

    /** The value's content was inspected (parsed/coerced) and found invalid -- e.g., a string that is
     *  not a recognizable date, boolean, number, or JSON structure. */
    badValue,

    invalidOption,
}

/**
 * A single validation failure: the [path] through the data to the offending value
 * (e.g. "address.street" or "tags[1]"), a [code] for why, and a human [message].
 * For an [SchFailCode.invalidOption] failure, [options] carries the full list of
 * currently valid choices, given first-class visibility to the caller. When a
 * [SchFailCode.badValue] came from a failed parse, [cause] carries that exception
 * (which itself holds the offset/line of the parse error).
 */
data class SchFailure(
    val path: String,
    val code: SchFailCode,
    val message: String,
    val options: List<SchOption>? = null,
    val cause: KdrException? = null,
)

/** Result of a coercing validation: the (possibly transformed) [value] and the [failures]. */
data class SchResult(val value: Any?, val failures: List<SchFailure>)

/**
 * Validates [data] against the parsed [type], collecting EVERY failure (not
 * fail-fast). Does not alter the data. Reads only declared attributes on [SchType]
 * / [SchProperty] — it never looks a value up in a raw schema map.
 *
 * Even in validate-only mode, `allowCoerce` still governs validation: a coercible field is checked with
 * its coercion rules (e.g., a boolean field with `allowCoerce` accepts "yes"), the transformed value is
 * simply not returned.
 */
fun validate(type: SchType, data: Any?): List<SchFailure> {
    val failures = mutableListOf<SchFailure>()
    validateValue(type, data, "", coerce = false, failures)
    return failures
}

/**
 * Validates AND coerces [data]: applies `allowCoerce` conversions (string -> Long / Double / Boolean /
 * Date / list / map) and injects `default` values for missing properties, returning the transformed
 * value plus the failures. The input is never mutated — new maps/lists are built where anything changes.
 *
 * Validation and coercion share one pass: a value is parsed at most once, its failures recorded, and the
 * parsed result reused for the output (kept only when [coerceAndValidate] asked for it).
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

    // A string field carrying a date format is validated by parsing (and optionally becomes a Date).
    if (jsonType == SCT.string && isDateFormat(type.format)) {
        return validateDate(type, value, path, coerce, failures)
    }

    if (!matchesType(jsonType, value)) {
        return coerceMismatch(type, value, path, coerce, failures)
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
 * Single-pass coercion for a [value] whose type did not match [type]. Honors the custom `allowCoerce`
 * keyword: when it is off, the mismatch is a plain [SchFailCode.wrongType]; when it is on, the value's
 * content is inspected and either coerced or reported as a [SchFailCode.badValue].
 *
 * The returned value is the coerced form; callers keep it only when [coerce] is set (validate-only mode
 * discards it), so the same inspection serves both validation and output without parsing twice. For a
 * string coerced into a list or map, the parsed structure is fed back through [validateValue] so the
 * element / property schema is applied.
 */
@KdrPrivate
fun coerceMismatch(type: SchType, value: Any?, path: String, coerce: Boolean, failures: MutableList<SchFailure>): Any? {
    if (!type.allowCoerce) {
        // A plain type check decided the value is wrong; its content was never inspected.
        failures.add(SchFailure(path, SchFailCode.wrongType, wrongTypeMsg(type)))
        return value
    }
    return when (type.jsonType) {
        SCT.integer -> coerceNumericString(type, value, path, failures) { it.trim().toLongOrNull() }
        SCT.number -> coerceNumericString(type, value, path, failures) { it.trim().toDoubleOrNull() }
        SCT.boolean -> coerceStringToBool(type, value, path, coerce, failures)
        SCT.string -> {
            if (value == null) {
                // Nothing to render; a plain null-vs-string type check.
                failures.add(SchFailure(path, SchFailCode.wrongType, wrongTypeMsg(type)))
                value
            } else {
                value.toString()
            }
        }
        SCT.array -> coerceStringToArray(type, value, path, coerce, failures)
        SCT.kObject -> coerceStringToObject(type, value, path, coerce, failures)
        else -> {
            failures.add(SchFailure(path, SchFailCode.wrongType, wrongTypeMsg(type)))
            value
        }
    }
}

/** Parses a numeric string with [parse]; a non-string is [SchFailCode.wrongType], an unparseable string
 *  [SchFailCode.badValue]. */
@KdrPrivate
fun coerceNumericString(
    type: SchType,
    value: Any?,
    path: String,
    failures: MutableList<SchFailure>,
    parse: (String) -> Any?,
): Any? {
    val s = value as? String
    if (s == null) {
        failures.add(SchFailure(path, SchFailCode.wrongType, wrongTypeMsg(type)))
        return value
    }
    val parsed = parse(s)
    if (parsed == null) {
        failures.add(SchFailure(path, SchFailCode.badValue, "'$s' is not a valid ${type.jsonType}"))
        return value
    }
    return parsed
}

/**
 * Coerces a string to a boolean via [toOptBool]. A recognizable value becomes the Boolean; a
 * non-whitespace string that is unrecognized is a [SchFailCode.badValue]; a pure-whitespace string is
 * treated as no value (null), not a failure. A non-string value is a plain [SchFailCode.wrongType].
 */
@KdrPrivate
fun coerceStringToBool(type: SchType, value: Any?, path: String, coerce: Boolean, failures: MutableList<SchFailure>): Any? {
    val s = value as? String
    if (s == null) {
        failures.add(SchFailure(path, SchFailCode.wrongType, wrongTypeMsg(type)))
        return value
    }
    val b = s.toOptBool()
    if (b != null) {
        return b
    }
    if (s.any { it > ' ' }) {
        failures.add(SchFailure(path, SchFailCode.badValue, "'$s' is not a recognizable boolean"))
        return value
    }
    // Pure whitespace: a blank cell is treated as an absent value.
    return if (coerce) null else value
}

/**
 * Coerces a string to a list. If its first non-whitespace character is '[', it is parsed as JSON;
 * otherwise it is split on commas (see [splitComma]). The resulting list is re-validated against [type]
 * so the element schema (and any element-level coercion) is applied.
 */
@KdrPrivate
fun coerceStringToArray(type: SchType, value: Any?, path: String, coerce: Boolean, failures: MutableList<SchFailure>): Any? {
    val s = value as? String
    if (s == null) {
        failures.add(SchFailure(path, SchFailCode.wrongType, wrongTypeMsg(type)))
        return value
    }
    val list: List<Any?> = if (s.firstOrNull { it > ' ' } == '[') {
        try {
            s.jsonArray() ?: emptyList()
        } catch (e: KdrException) {
            failures.add(SchFailure(path, SchFailCode.badValue, "value is not a valid JSON array", cause = e))
            return value
        }
    } else {
        s.splitComma()
    }
    return validateValue(type, list, path, coerce, failures)
}

/** Coerces a string to a map by parsing it as JSON, then re-validates it against [type] so the object
 *  schema is applied. A parse failure (or a blank string) is a [SchFailCode.badValue]. */
@KdrPrivate
fun coerceStringToObject(type: SchType, value: Any?, path: String, coerce: Boolean, failures: MutableList<SchFailure>): Any? {
    val s = value as? String
    if (s == null) {
        failures.add(SchFailure(path, SchFailCode.wrongType, wrongTypeMsg(type)))
        return value
    }
    val map = try {
        s.jsonMap()
    } catch (e: KdrException) {
        failures.add(SchFailure(path, SchFailCode.badValue, "value is not a valid JSON object", cause = e))
        return value
    }
    if (map == null) {
        failures.add(SchFailure(path, SchFailCode.badValue, "value is not a valid JSON object"))
        return value
    }
    return validateValue(type, map, path, coerce, failures)
}

/**
 * Validates a date-format string field. An already-parsed [Date] passes untouched; a string is validated
 * by [parseDate] (a parse failure is a [SchFailCode.badValue]) and, when [coerce] and `allowCoerce` are
 * set, replaced by the resulting [Instant]. A non-string, non-Instant value is a plain [SchFailCode.wrongType].
 */
@KdrPrivate
fun validateDate(type: SchType, value: Any?, path: String, coerce: Boolean, failures: MutableList<SchFailure>): Any? {
    if (value is Instant) {
        return value
    }
    val s = value as? String
    if (s == null) {
        failures.add(SchFailure(path, SchFailCode.wrongType, "expected a date string"))
        return value
    }
    val date = try {
        s.parseDate()
    } catch (e: KdrException) {
        failures.add(SchFailure(path, SchFailCode.badValue, "'$s' is not a valid date", cause = e))
        return value
    }
    return if (coerce && type.allowCoerce) date else value
}

/** Deep-clones a default before injecting it, so the schema's value is never shared. */
@KdrPrivate
fun cloneForInjection(value: Any?): Any? = when (value) {
    is Map<*, *> -> value.deepClone()
    is List<*> -> value.deepClone()
    else -> value // scalars are immutable
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
fun wrongTypeMsg(type: SchType): String = "expected type '${type.jsonType ?: "any"}'"

@KdrPrivate
fun childPath(parent: String, key: String): String = if (parent.isEmpty()) key else "$parent.$key"

@KdrPrivate
fun indexPath(parent: String, index: Int): String = "$parent[$index]"
