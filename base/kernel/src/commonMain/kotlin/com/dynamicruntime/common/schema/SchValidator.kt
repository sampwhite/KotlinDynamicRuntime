package com.dynamicruntime.common.schema

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.fmtD
import com.dynamicruntime.common.util.deepClone
import com.dynamicruntime.common.util.jsonArray
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.parseDay
import com.dynamicruntime.common.util.parseDayLenient
import com.dynamicruntime.common.util.splitComma
import com.dynamicruntime.common.util.toDay
import com.dynamicruntime.common.util.toOptBool
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.util.toStartOfDay
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * Why a value failed schema validation; grows as more JSON Schema constructs are supported.
 *
 * These names **are** serialized, as the `code` of a reported failure (issue #198), so they are a wire
 * contract and renaming one is a breaking change. They were internal when this enum was written, and the
 * lower camelCase spelling is a leftover of that — kept because changing it now would be the very break the
 * previous sentence warns about.
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

    /** An undeclared property was present on an object whose schema does not allow additional properties. */
    additionalProperty,

    /**
     * The value fell short of its declared lower bound — whichever bound its type measures: `minimum` for a
     * number, `minLength` for a string, `minItems` for an array, `minProperties` for an object.
     *
     * **One code across all four pairs, rather than one per keyword.** A field has a single type, so on a
     * string this can only mean length and on an array only element count — the same reason [badValue] is not
     * too coarse despite covering dates, booleans and JSON. Eight codes would buy a distinction the field's
     * own type already makes, at the cost of every `g-errors` author having to know which of eight applies.
     * The built-in *messages* still differ per type; only the code is shared.
     *
     * The cost, deliberately accepted: a client branching on the code alone cannot tell "too short" from "too
     * small" without also consulting the field's type. It has the schema, so it can.
     */
    belowMinimum,

    /** The value exceeded its declared upper bound; see [belowMinimum] for why the four pairs share two codes. */
    aboveMaximum,
}

/**
 * The known JSON-Schema `$` keywords, which are treated as normal (non-exempt) keys when they appear as
 * data properties -- so they are subject to the [SchType.additionalProperties] rule and are kept on coercing.
 * Every *other* `$`-prefixed data key (e.g. `$note`) is off-contract: allowed on validating, dropped on coercing.
 */
@KdrPrivate
val reservedSchemaKeys: Set<String> = setOf(
    SCH.dSchema, SCH.dId, SCH.dRef, SCH.dDefs, SCH.dAnchor, SCH.dDynamicRef, SCH.dDynamicAnchor,
    SCH.dVocabulary, SCH.dComment,
)

/**
 * A single validation failure: the [path] through the data to the offending value
 * (e.g. "address.street" or "tags[1]"), a [code] for why, and a human [message].
 * For an [SchFailCode.invalidOption] failure, [options] carries the full list of
 * currently valid choices, given first-class visibility to the caller. When a
 * [SchFailCode.badValue] came from a failed parse, [cause] carries that exception
 * (which itself holds the offset/line of the parse error).
 *
 * **A [message] is a complete sentence, sentence-cased and ending in a period**, as every other message in
 * this codebase is. These were once lowercase unpunctuated fragments in the style errors take when they are
 * only ever wrapped and concatenated — which was right while a failure was read solely as the tail of
 * `path: message`. Since issue #197 a failure has also stood on its own beneath the field that caused it, beside
 * field descriptions written as sentences, and there a fragment reads as truncated. The composed forms lose
 * nothing: `input.score: This must be of type 'integer'.` reads correctly.
 *
 * A message that opens by quoting a supplied value begins with the quote rather than a capital
 * (`'abc' is not a valid number.`). That is deliberate — the value is the caller's, not ours to re-case.
 */
data class SchFailure(
    val path: String,
    val code: SchFailCode,
    val message: String,
    val options: List<SchOption>? = null,
    val cause: KdrException? = null,
    /**
     * The schema's own wording for this failure, from the field's `g-errors` block, or null when it declares
     * none (issue #202). Sits *beside* [message] rather than replacing it, because the two serve different
     * readers: a surface documenting the wire wants to say the value is not an integer, while a form asking
     * someone for a score wants whatever the schema author wrote instead.
     */
    val userMessage: String? = null,
)

/**
 * This failure as a plain JSON-ready map, for reporting one to a caller (issue #198).
 *
 * Lives here beside [SchFailure] rather than in the HTTP layer so the wire shape is defined **once**: the same
 * kernel both sides already share for validating also says what a reported failure looks like, which is what
 * keeps a client's reading of it from drifting from a server's writing of it.
 *
 * [SchFailure.cause] is not included — see `EP.failures`.
 */
fun SchFailure.toWireMap(): Map<String, Any?> {
    // linkedMapOf, not buildMap: the order the reader wants is path first, and buildMap's builder does not
    // promise to keep insertion order. Nothing depends on it -- JSON objects are unordered -- but a caller
    // reading a list of these by eye finds the field faster when they all start the same way.
    val out = linkedMapOf<String, Any?>(
        EP.failurePath to path,
        EP.failureCode to code.name,
        EP.failureMessage to message,
    )
    userMessage?.let { out[EP.failureUserMessage] = it }
    options?.let { opts ->
        out[EP.failureOptions] = opts.map { linkedMapOf(SCH.value to it.value, SCH.label to it.label) }
    }
    return out
}

/**
 * A one-line summary of [failures] for the envelope's `errorMessage`, naming the fields rather than dumping
 * them. The detail travels structured under `extraData`; this is what a person reads in a log line, and a
 * list of paths is the part that makes such a line worth having.
 */
fun failureSummary(failures: List<SchFailure>): String {
    val paths = failures.map { it.path.ifEmpty { "(root)" } }.distinct()
    val subject = if (paths.size == 1) "field" else "fields"
    return "Validation failed for ${paths.size} $subject: ${paths.joinToString(", ")}."
}

/**
 * The schema's wording for [code] on this field: the specific message, else the field's `default`, else null
 * to leave the validator's own words in place. Three levels deep and deliberately no deeper — the built-in
 * message *is* the global default, so a type-level layer would buy nothing that is not already covered.
 */
@KdrPrivate
fun SchType.userMessage(code: SchFailCode): String? =
    errorMessages[code.name] ?: errorMessages[SCH.errorDefault]

/**
 * A failure against this field, carrying whatever the field's `g-errors` says about [code]. Used in place of
 * constructing [SchFailure] directly so that resolution cannot be forgotten at one site out of nineteen — the
 * kind of omission whose only symptom is one message quietly not being customizable.
 */
@KdrPrivate
fun SchType.failure(
    path: String,
    code: SchFailCode,
    message: String,
    options: List<SchOption>? = null,
    cause: KdrException? = null,
): SchFailure = SchFailure(path, code, message, options, cause, userMessage(code))

/**
 * Knobs that adjust what a validation run *produces*, for a caller whose needs differ from the wire
 * protocol's -- today the interactive endpoint form (issue #191).
 *
 * An option may change the coerced output or how something is reported; it may never change what a schema
 * considers **valid**, so no setting here can turn a failure into a success. This is an object rather than a
 * parameter because the list is expected to grow as the frontend asks for more.
 */
class SchOpts(
    /**
     * Keep an undeclared property in the coerced output instead of dropping it. It is reported as an
     * [SchFailCode.additionalProperty] failure either way.
     *
     * The wire wants it gone: an endpoint should not be handed keys its schema never declared. An editor wants
     * it kept, because dropping it rewrites the text under the person who typed it -- leaving an error about a
     * key that is no longer on screen, and nothing they can act on.
     */
    val keepAdditionalProperties: Boolean = false,
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
fun validate(type: SchType, data: Any?, opts: SchOpts = SchOpts()): List<SchFailure> {
    val failures = mutableListOf<SchFailure>()
    validateValue(type, data, "", coerce = false, failures, opts)
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
fun coerceAndValidate(type: SchType, data: Any?, opts: SchOpts = SchOpts()): SchResult {
    val failures = mutableListOf<SchFailure>()
    val value = validateValue(type, data, "", coerce = true, failures, opts)
    return SchResult(value, failures)
}

/** Validates [value] against [type], returning the (possibly coerced) value. */
@KdrPrivate
fun validateValue(
    type: SchType, value: Any?, path: String, coerce: Boolean, failures: MutableList<SchFailure>, opts: SchOpts,
): Any? {
    val jsonType = type.jsonType

    // A discriminated union: pick the branch, then validate against that one alone (issue #252).
    type.variants?.let { variants ->
        return validateVariant(type, variants, value, path, coerce, failures, opts)
    }

    // A string field carrying a date format is validated by parsing (and optionally becomes a Date).
    if (jsonType == SCT.string && isDateFormat(type.format)) {
        return validateDate(type, value, path, coerce, failures)
    }

    // File content passes through untouched. It is spelled `type: string` because that is how OpenAPI spells a
    // file (see SFMT.binary), but the value is not text -- it is a ContentData carrying bytes -- so the string
    // checks below would reject it as the wrong type, and coercing it would mean toString() on a file. There is
    // nothing here to validate either: the content's *shape* is the MIME type's business, not JSON Schema's.
    // Presence is still enforced by the required check in the caller.
    if (jsonType == SCT.string && isBinaryFormat(type.format)) {
        return value
    }

    if (!matchesType(jsonType, value)) {
        // Bounds apply to what the value BECAME, not to the string it arrived as: "5" against a minimum of 10
        // has to fail. Coercion runs in both modes (only its result is discarded when validating), so this
        // reports identically whether the caller asked for the coerced value or not.
        val coerced = coerceMismatch(type, value, path, coerce, failures, opts)
        checkBounds(type, coerced, path, failures)
        return coerced
    }

    // `const`: the type admits one value. Checked before options, and separately from them, because it is a
    // statement about the shape rather than a choice being offered -- most often a union branch saying which
    // branch it is. Compared as text, so a discriminator that arrived as a number still matches its declared
    // string; JSON Schema compares by instance equality, and a wire value that reads the same is the same
    // answer to "which branch is this".
    val constValue = type.constValue
    if (constValue != null && value.toOptStr() != constValue.toOptStr()) {
        failures.add(
            type.failure(path, SchFailCode.invalidOption, "'$value' is not '$constValue'.",
                listOf(SchOption(constValue.toOptStr() ?: "", constValue.toOptStr() ?: "")))
        )
        return value
    }

    val options = type.options
    if (options != null) {
        val choice = value as? String
        if (choice == null || options.none { it.value == choice }) {
            failures.add(type.failure(path, SchFailCode.invalidOption, "'$value' is not a valid option.", options))
        }
        return value
    }
    // Measured against the instance as it arrived, which is what the bound is about -- an object's property
    // count before `emptyIsAbsent` drops anything or a default is injected.
    checkBounds(type, value, path, failures)
    return when (jsonType) {
        SCT.kObject -> validateObject(type, value as Map<*, *>, path, coerce, failures, opts)
        SCT.array -> validateArray(type, value as List<*>, path, coerce, failures, opts)
        else -> value // scalar matched, unchanged
    }
}

/**
 * Validates a discriminated union: read the discriminator, select one branch, validate against that branch
 * alone (issue #252).
 *
 * The failures are the selected branch's own, reported at the paths the payload actually has — which is the
 * whole reason the discriminator is declared. Trying every branch and merging what came back produces a list
 * of complaints from shapes the caller never meant to send, and no way to rank them.
 *
 * Three ways this goes wrong, each with its own answer:
 *
 * - **Not an object at all** — a plain [SchFailCode.wrongType] before anything else is attempted.
 * - **No discriminator** — [SchFailCode.missingRequired] against the discriminator's own path, since without
 *   it there is nothing to select with. Reported there rather than against the union, so the message sits on
 *   the field someone has to fill in.
 * - **A value naming no branch** — [SchFailCode.invalidOption] carrying the declared values as its options, so
 *   the existing "Valid options: …" wording lists what this reader knows. Unless a `defaultMapping` exists, in
 *   which case an unrecognized value is not an error at all: it goes to the default branch and passes through.
 */
@KdrPrivate
fun validateVariant(
    type: SchType,
    variants: SchVariants,
    value: Any?,
    path: String,
    coerce: Boolean,
    failures: MutableList<SchFailure>,
    opts: SchOpts,
): Any? {
    if (value !is Map<*, *>) {
        failures.add(type.failure(path, SchFailCode.wrongType, wrongTypeMsg(type)))
        return value
    }
    val discriminatorPath = childPath(path, variants.discriminator)
    val raw = value[variants.discriminator].toOptStr()
    if (raw == null) {
        failures.add(
            type.failure(
                discriminatorPath, SchFailCode.missingRequired,
                "Required property '${variants.discriminator}' is missing.",
            ),
        )
        return value
    }
    val branch = variants.select(raw)
    if (branch == null) {
        failures.add(
            type.failure(
                discriminatorPath, SchFailCode.invalidOption, "'$raw' is not a valid option.",
                variants.values.map { SchOption(it, it) },
            ),
        )
        return value
    }
    return validateValue(branch, value, path, coerce, failures, opts)
}

@KdrPrivate
fun validateObject(
    type: SchType,
    map: Map<*, *>,
    path: String,
    coerce: Boolean,
    failures: MutableList<SchFailure>,
    opts: SchOpts,
): Any {
    // Only build a new map when coercing; otherwise the input is returned untouched.
    val out: MutableMap<String, Any?>? = if (coerce) LinkedHashMap(map.size) else null
    // Keys present in the input but dropped by the emptyIsAbsent rule. Tracked separately rather than read
    // back off `out`, because validate-only mode builds no output map and the two modes have to agree about
    // which required properties were satisfied.
    val dropped = mutableSetOf<String>()

    // Declared properties first, in the order the *schema* declares them rather than the order the data
    // happened to arrive in. That makes the coerced output stable: a field cleared and later refilled reads
    // where it always did instead of moving to the end, and the payload matches the order a form renders in.
    // JSON objects have no meaningful order, so nothing downstream depends on it -- but a person reading the
    // payload does.
    for ((key, prop) in type.properties) {
        if (!map.containsKey(key)) {
            continue
        }
        val v = map[key]
        // Supplied but empty: the field reads as never supplied, so it is neither validated nor written
        // out. Its `required` check is then answered below by `dropped`, not by the input's own key.
        if (isAbsentValue(prop.valueType, v)) {
            dropped.add(key)
            continue
        }
        // Call validateValue unconditionally (it collects failures); only store when coercing. Kept on its
        // own line: folding it into `out?.put(...)` would short-circuit (and skip validation) when out is null.
        val coerced = validateValue(prop.valueType, v, childPath(path, key), coerce, failures, opts)
        out?.put(key, coerced)
    }

    // Then whatever the data carried that the schema does not declare, in the order it arrived.
    for ((k, v) in map) {
        val key = k as? String ?: continue
        if (type.properties.containsKey(key)) {
            continue // handled above
        }
        // Undeclared ("additional") property -- apply the additionalProperties rule with prefix exemptions.
        when {
            key.startsWith("_") -> out?.put(key, v) // off-contract, always allowed and kept
            key.startsWith("$") && key !in reservedSchemaKeys -> {
                // Off-contract annotation (e.g., $note): allowed on validating, dropped on coercing (not written to out).
            }
            type.additionalProperties -> out?.put(key, v) // allowed (incl. reserved $ keys, treated as normal)
            else -> {
                failures.add(
                    SchFailure(childPath(path, key), SchFailCode.additionalProperty, "Additional property '$key' is not allowed."),
                )
                // Reported either way; kept only when the caller asked. See SchOpts.keepAdditionalProperties.
                if (opts.keepAdditionalProperties) out?.put(key, v)
            }
        }
    }
    for (req in type.required) {
        if (map.containsKey(req) && req !in dropped) {
            continue
        }
        val reqType = type.properties[req]?.valueType
        val default = reqType?.default
        if (default != null) {
            out?.put(req, cloneForInjection(default)) // a default supplies the value, so no failure
        } else {
            // Resolved against the missing property's OWN type, not this object's: the failure is reported by
            // the parent's required loop, but the copy belongs to the field it is about.
            failures.add(
                reqType?.failure(childPath(path, req), SchFailCode.missingRequired, "Required property '$req' is missing.")
                    ?: SchFailure(childPath(path, req), SchFailCode.missingRequired, "Required property '$req' is missing."),
            )
        }
    }
    return out ?: map
}

/**
 * Whether [value] counts as "not supplied" for a field of [type] — the custom `emptyIsAbsent` rule.
 *
 * Empty is **zero-length**: a blank (or whitespace-only) string, an empty list, an empty map. It is never
 * zero-*valued*, so `0`, `0.0` and `false` are ordinary values. A `null` counts wherever the rule is on, which
 * also retires an old wart: null matches no `type` and coerces to nothing, so an explicit `null` in a payload
 * used to fail as [SchFailCode.wrongType] rather than reading as "no value".
 *
 * This is asked by the *container* ([validateObject]), because absence is a statement about a key. An empty
 * element inside a list is a value at its position, not a gap, so lists are never silently shortened.
 */
@KdrPrivate
fun isAbsentValue(type: SchType, value: Any?): Boolean {
    if (!type.emptyIsAbsent) {
        return false
    }
    return when (value) {
        null -> true
        is CharSequence -> value.isBlank()
        is List<*> -> value.isEmpty()
        is Map<*, *> -> value.isEmpty()
        else -> false
    }
}

@KdrPrivate
fun validateArray(
    type: SchType,
    list: List<*>,
    path: String,
    coerce: Boolean,
    failures: MutableList<SchFailure>,
    opts: SchOpts,
): Any {
    val itemType = type.itemType
    val out: MutableList<Any?>? = if (coerce) ArrayList(list.size) else null
    list.forEachIndexed { i, elem ->
        val coerced = if (itemType != null) {
            validateValue(itemType, elem, indexPath(path, i), coerce, failures, opts)
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
fun coerceMismatch(
    type: SchType, value: Any?, path: String, coerce: Boolean, failures: MutableList<SchFailure>, opts: SchOpts,
): Any? {
    if (!type.allowCoerce) {
        // A plain type check decided the value is wrong; its content was never inspected.
        failures.add(type.failure(path, SchFailCode.wrongType, wrongTypeMsg(type)))
        return value
    }
    return when (type.jsonType) {
        SCT.integer -> coerceNumericString(type, value, path, failures) { it.trim().toLongOrNull() }
        SCT.number -> coerceNumericString(type, value, path, failures) { it.trim().toDoubleOrNull() }
        SCT.boolean -> coerceStringToBool(type, value, path, coerce, failures)
        SCT.string -> {
            if (value == null) {
                // Nothing to render; a plain null-vs-string type check.
                failures.add(type.failure(path, SchFailCode.wrongType, wrongTypeMsg(type)))
                value
            } else {
                value.toString()
            }
        }
        SCT.array -> coerceStringToArray(type, value, path, coerce, failures, opts)
        SCT.kObject -> coerceStringToObject(type, value, path, coerce, failures, opts)
        else -> {
            failures.add(type.failure(path, SchFailCode.wrongType, wrongTypeMsg(type)))
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
        failures.add(type.failure(path, SchFailCode.wrongType, wrongTypeMsg(type)))
        return value
    }
    val parsed = parse(s)
    if (parsed == null) {
        failures.add(type.failure(path, SchFailCode.badValue, "'$s' is not a valid ${type.jsonType}."))
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
        failures.add(type.failure(path, SchFailCode.wrongType, wrongTypeMsg(type)))
        return value
    }
    val b = s.toOptBool()
    if (b != null) {
        return b
    }
    if (s.any { it > ' ' }) {
        failures.add(type.failure(path, SchFailCode.badValue, "'$s' is not a recognizable boolean."))
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
fun coerceStringToArray(
    type: SchType, value: Any?, path: String, coerce: Boolean, failures: MutableList<SchFailure>, opts: SchOpts,
): Any? {
    val s = value as? String
    if (s == null) {
        failures.add(type.failure(path, SchFailCode.wrongType, wrongTypeMsg(type)))
        return value
    }
    val list: List<Any?> = if (s.firstOrNull { it > ' ' } == '[') {
        try {
            s.jsonArray() ?: emptyList()
        } catch (e: KdrException) {
            failures.add(type.failure(path, SchFailCode.badValue, "The value is not a valid JSON array.", cause = e))
            return value
        }
    } else {
        s.splitComma()
    }
    return validateValue(type, list, path, coerce, failures, opts)
}

/** Coerces a string to a map by parsing it as JSON, then re-validates it against [type] so the object
 *  schema is applied. A parse failure (or a blank string) is a [SchFailCode.badValue]. */
@KdrPrivate
fun coerceStringToObject(
    type: SchType, value: Any?, path: String, coerce: Boolean, failures: MutableList<SchFailure>, opts: SchOpts,
): Any? {
    val s = value as? String
    if (s == null) {
        failures.add(type.failure(path, SchFailCode.wrongType, wrongTypeMsg(type)))
        return value
    }
    val map = try {
        s.jsonMap()
    } catch (e: KdrException) {
        failures.add(type.failure(path, SchFailCode.badValue, "The value is not a valid JSON object.", cause = e))
        return value
    }
    if (map == null) {
        failures.add(type.failure(path, SchFailCode.badValue, "The value is not a valid JSON object."))
        return value
    }
    return validateValue(type, map, path, coerce, failures, opts)
}

/**
 * Validates a date-format string field, honoring **which** date format it declares (issue #189).
 *
 * `format: "date"` coerces to a [LocalDate] and `format: "date-time"` to an [Instant], because the two are
 * genuinely different things and the schema already says which one the field means. Coercing a day to an
 * instant was the old behavior, and it did not survive serialization: the instant landed at midnight in the
 * server time zone and was written back out in UTC, so `2021-06-01` returned as `2021-06-01T08:00:00.000Z` --
 * and on a server *east* of UTC the rendered day was the previous one. A [LocalDate] never acquires a zone,
 * so it round-trips as the day it is.
 *
 * A value that arrived as the *other* kind of date is **reshaped only when `allowCoerce` is on**, because
 * reshaping is itself a coercion and is governed by the same switch as every other one. So a lenient day-only
 * field narrows a timestamp to its day, while a strict one refuses it -- taking only the shape it declares.
 *
 * A parse failure is a [SchFailCode.badValue]; a value that is no kind of date is a plain
 * [SchFailCode.wrongType]. As before, the parsed result replaces the input only when [coerce] and
 * `allowCoerce` are both set.
 */
@KdrPrivate
fun validateDate(type: SchType, value: Any?, path: String, coerce: Boolean, failures: MutableList<SchFailure>): Any? {
    val dayOnly = type.format == SFMT.date
    val lenient = type.allowCoerce

    // Already the shape this field declares: nothing to parse or reshape.
    if (if (dayOnly) value is LocalDate else value is Instant) {
        return value
    }

    val parsed = try {
        when (value) {
            is String ->
                if (!dayOnly) value.parseDate()
                else if (lenient) value.parseDayLenient()
                else value.parseDay()
            is LocalDate -> if (lenient) value.toStartOfDay() else {
                failures.add(type.failure(path, SchFailCode.wrongType, "This must be a timestamp, not a day."))
                return value
            }
            is Instant -> if (lenient) value.toDay() else {
                failures.add(type.failure(path, SchFailCode.wrongType, "This must be a day, not a timestamp."))
                return value
            }
            else -> {
                failures.add(type.failure(path, SchFailCode.wrongType, "This must be a date string."))
                return value
            }
        }
    } catch (e: KdrException) {
        failures.add(type.failure(path, SchFailCode.badValue, "'$value' is not a valid date.", cause = e))
        return value
    }
    return if (coerce && type.allowCoerce) parsed else value
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
fun wrongTypeMsg(type: SchType): String = "This must be of type '${type.jsonType ?: "any"}'."

/**
 * Checks [value] against the field's declared bounds, reporting [SchFailCode.belowMinimum] and
 * [SchFailCode.aboveMaximum] **independently**.
 *
 * Independently rather than as one "out of range": the two ends can come from different places. An overlay
 * that narrows a type may add a maximum a base schema never had, and reporting against the specific end keeps
 * the base schema's own message for the other one intact (issue #203).
 *
 * A value the field cannot measure is left alone — nothing was coerced, so there is no length or size to
 * compare, and a failure has been reported for whatever went wrong instead.
 */
@KdrPrivate
fun checkBounds(type: SchType, value: Any?, path: String, failures: MutableList<SchFailure>) {
    val min = type.minBound
    val max = type.maxBound
    if (min == null && max == null) return
    val measured = measureFor(type.jsonType, value) ?: return
    if (min != null && measured < min) {
        failures.add(type.failure(path, SchFailCode.belowMinimum, boundMsg(type.jsonType, min, atLeast = true)))
    }
    if (max != null && measured > max) {
        failures.add(type.failure(path, SchFailCode.aboveMaximum, boundMsg(type.jsonType, max, atLeast = false)))
    }
}

/**
 * What the bound is compared against, per type: a number is itself, a string is its length, an array its
 * element count, an object its property count. Null when the value is not of the measurable shape.
 */
@KdrPrivate
fun measureFor(jsonType: String?, value: Any?): Double? = when {
    isNumericType(jsonType) -> (value as? Number)?.toDouble()
    jsonType == SCT.string -> (value as? String)?.let { codePointLength(it).toDouble() }
    jsonType == SCT.array -> (value as? List<*>)?.size?.toDouble()
    jsonType == SCT.kObject -> (value as? Map<*, *>)?.size?.toDouble()
    else -> null
}

/**
 * The number of Unicode code points in [s], which is what JSON Schema's `minLength`/`maxLength` count —
 * *characters*, not UTF-16 code units.
 *
 * `String.length` would be wrong for anything outside the basic plane: an emoji is one character and two code
 * units, so a three-emoji name would read as six against a `maxLength` of five and be rejected. A low
 * surrogate is always the second half of a pair, so skipping those counts pairs once.
 */
@KdrPrivate
fun codePointLength(s: String): Int {
    var n = 0
    for (ch in s) {
        if (!ch.isLowSurrogate()) n++
    }
    return n
}

/**
 * The built-in wording for a bound. The code is shared across the four pairs, but the message is not: what the
 * bound means differs by type, and "This must be at least 3." would be a poor way to say a name needs three
 * characters.
 */
@KdrPrivate
fun boundMsg(jsonType: String?, bound: Double, atLeast: Boolean): String {
    val n = bound.fmtD()
    val side = if (atLeast) "at least" else "at most"
    val one = bound == 1.0
    return when (jsonType) {
        SCT.string -> "This must be $side $n character${if (one) "" else "s"}."
        SCT.array -> "This must have $side $n item${if (one) "" else "s"}."
        SCT.kObject -> "This must have $side $n propert${if (one) "y" else "ies"}."
        else -> "This must be $side $n."
    }
}

// These two are deliberately NOT @KdrPrivate. The spelling of a failure's path is a contract, not a detail of
// the validator: a display that wants to show a message beside the field that caused it has to build the same
// path the validator reported, and two implementations of one spelling would eventually disagree — silently,
// since the symptom is a message that quietly matches no field. So the renderer calls these rather than
// growing its own, for the same reason the frontend runs `coerceAndValidate` instead of its own validator.
// See SchFailurePath.kt for reading the paths back.

/** The path to [key] within the object at [parent]; at the root, just the key. */
fun childPath(parent: String, key: String): String = if (parent.isEmpty()) key else "$parent.$key"

/** The path to element [index] of the array at [parent]. */
fun indexPath(parent: String, index: Int): String = "$parent[$index]"
