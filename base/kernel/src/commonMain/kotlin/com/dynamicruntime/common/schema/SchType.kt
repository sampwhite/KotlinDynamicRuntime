package com.dynamicruntime.common.schema

/**
 * A parsed, resolved JSON Schema type — the consumption form used for validation,
 * as opposed to the Map-backed builders. Everything the validator needs is a
 * declared attribute here, so validation never reaches into a raw schema Map.
 *
 * Only the constructs we support so far are modeled; `anyOf`/`allOf`/`if`/`then`/
 * `else` are intentionally not represented yet.
 */
class SchType(
    /** Fully qualified name for a `$defs` type; null for an anonymous/inline schema. */
    val name: String?,
    /** The JSON Schema `type` value (object/string/integer/array/...), or null if unconstrained. */
    val jsonType: String?,
    /**
     * Custom `allowCoerce` keyword (resolved): whether a value that doesn't match
     * [jsonType] may be coerced to it during validation. Defaults to true for
     * numeric types (integer/number), false otherwise.
     */
    val allowCoerce: Boolean,
    /**
     * Custom `emptyIsAbsent` keyword (resolved): whether an empty value for a property means the property was
     * not supplied at all — so it is dropped from the coerced output and counts as missing for `required`.
     * Empty means **zero-length** (a blank string, an empty list, an empty map) and, wherever this is on, also
     * `null`; it never means zero-*valued*, so `0` and `false` are values like any other.
     *
     * Defaults to true for scalars (string / integer / number / boolean, including date-format strings) and
     * false for arrays, objects, and untyped fields. Arrays and objects are opt-in because an empty one is
     * often meaningful — on an update endpoint `[]` says "clear the list" where an absent field says "leave it
     * alone" — and an untyped field constrains nothing, so there is no basis for reading its emptiness.
     */
    val emptyIsAbsent: Boolean,
    /**
     * The JSON Schema `format` value (e.g. [SFMT.date] / [SFMT.dateTime]) for a string type, or null.
     * A recognized date format makes a string field validate as a date and default [allowCoerce] to true.
     */
    val format: String?,
    @Suppress("unused")
    val description: String?,
    /** Fields, for an object type (empty otherwise). */
    val properties: Map<String, SchProperty>,
    /** Required field names, for an object type. */
    val required: Set<String>,
    /**
     * Whether undeclared properties are allowed on an object. kd2 defaults this to **false when the type
     * declares any properties** and **true when it declares none** (so a property-less generic map still
     * accepts anything). Keys prefixed with `_` or a non-keyword `$` are exempt from this check regardless
     * (see the validator).
     */
    val additionalProperties: Boolean,
    /**
     * Element schema, for an array type (null otherwise). Usually set at construction, but — like
     * [SchProperty.valueType] — an array whose `items` is a `$ref` has it bound in the reference-resolution
     * pass once all types are parsed (see [parseSchemaTypes]).
     */
    var itemType: SchType?,
    /** Choice list for the custom `options` construct; null if not an options field. */
    val options: List<SchOption>?,
    /**
     * The JSON Schema `default` value, or null if none. A non-null default lets a
     * missing required property pass validation and is injected (cloned) when
     * coercing. (An explicit `default: null` is treated as no default.)
     */
    val default: Any?,
    /**
     * Custom `g-errors` keyword (resolved): what a failure against this field should say, keyed by
     * [SchFailCode] name with [SCH.errorDefault] as the fallback. Empty when the schema declares none, which
     * leaves the validator's own wording in place.
     *
     * Copy, in a model that is otherwise about validity — but it is the *validator's* output, not a
     * renderer's decoration, which is the line that keeps this type honest: it carries nothing that varies by
     * surface. Two surfaces showing the same failure differently choose between this and
     * [SchFailure.message]; they do not each need their own version of this map.
     */
    val errorMessages: Map<String, String>,
    /**
     * The declared lower bound, or null. **One field for all four of JSON Schema's min/max pairs**, because a
     * type declares at most one of them: `minimum` for a number, `minLength` for a string, `minItems` for an
     * array, `minProperties` for an object. Which keyword it was read from follows from [jsonType], and what
     * it is compared against follows with it — the value itself, its length, its size.
     *
     * Held as a Double, so one field serves a fractional `minimum` and an integral `minLength` alike; there is
     * no BigDecimal in a KMP common source set, so a bound beyond 2^53 loses precision. Not a real constraint
     * on a length or a count, and a `minimum` out there is not a bound anyone is enforcing meaningfully.
     */
    val minBound: Double?,
    /** The declared upper bound, or null; the counterpart of [minBound] and read the same way. */
    val maxBound: Double?,
)
