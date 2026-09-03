package com.dynamicruntime.common.schema

/**
 * A parsed, resolved JSON Schema type — the consumption form used for validation,
 * as opposed to the Map-backed builders. Everything the validator needs is a
 * declared attribute here, so validation never reaches into a raw schema Map.
 *
 * Only the constructs we support so far are modeled; `anyOf` and `allOf` are
 * intentionally not represented yet. A keyword that is not modeled is
 * **ignored** rather than rejected, so a document a stock validator accepts still
 * loads — see [parseSchemaTypes] for what that costs.
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
     * Custom `g-visibleOnly` keyword (resolved): whether every character of a string value must have a visible
     * rendering (issue #543) -- see [SCH.visibleOnly] for what that admits and why. Off unless declared, and
     * only ever true on a plain string type; the parser refuses it elsewhere.
     */
    val visibleOnly: Boolean = false,
    /**
     * Custom `g-outerWhitespace` keyword (resolved): how leading/trailing whitespace on a string value is
     * handled (issue #541) -- see [SCH.outerWhitespace]. Null unless declared (whitespace kept, as today), and
     * only ever set on a plain string type; the parser refuses it elsewhere.
     */
    val outerWhitespace: SchOuterWhitespace? = null,
    /**
     * The JSON Schema `format` value (e.g. [SFMT.date] / [SFMT.dateTime]) for a string type, or null.
     * A recognized date format makes a string field validate as a date and default [allowCoerce] to true.
     */
    val format: String?,
    /**
     * Standard JSON Schema `title`: a short human label for this type or field, as against [description]'s
     * longer explanation (issue #408). Null when none is declared.
     *
     * Carried so a data-entry surface can label a field with words rather than its wire key -- the distinction
     * `SchemaConstants` reserves the keyword for. A surface that documents the wire (the endpoint catalog)
     * ignores it and keeps showing the key; the two read the same schema and choose differently.
     */
    val title: String?,
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
     * Custom `g-openOptions` keyword (resolved): whether [options] are suggestions rather than a bound
     * (issue #418). False, and meaningless, when there are no options.
     *
     * Beside [options] rather than folded into them, because it says something about the list as a whole that
     * no entry could carry -- and because the two readers that act on it, the validator and a form, ask the
     * question in that shape: *may a value outside this list be sent?*
     */
    val openOptions: Boolean,
    /**
     * JSON Schema `const`: the single value this type admits, or null when it admits any.
     *
     * Its own field rather than sugar for a one-entry [options] list, because the two say different things to
     * a reader: `options` is a choice someone makes, `const` is a fact about the shape — most often a branch of
     * a [variants] union stating which branch it is. A form offers the first and does not offer the second.
     */
    val constValue: Any?,
    /**
     * The discriminated "union" this type is, or null for an ordinary type. See [SchVariants].
     *
     * A union carries its branches here rather than in [properties]: the properties a payload must have depend
     * on which branch its discriminator selected, so there is no single property set to put there.
     */
    val variants: SchVariants?,
    /**
     * Custom `g-derived` keyword (resolved): the client does not supply this value; something else produces
     * it (issue #254).
     *
     * Surface-invariant -- a derived field is derived for everyone -- so it belongs here rather than in a
     * per-surface model. What varies is the *direction*: on the way in it is neither asked for nor accepted,
     * on the way out it is an ordinary value. That difference lives in [SchOpts], not in this flag.
     */
    val derived: Boolean,
    /**
     * The `if`/`then`/`else` rule on this type, or null when it declares none. See [SchCondition].
     *
     * Sits beside [required] rather than inside it because it says something [required] cannot: that a
     * property's necessity — or its very admissibility — depends on another property's value. At most one, for
     * the reason [SchCondition] gives.
     */
    val condition: SchCondition?,
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
    /**
     * Custom `g-primaryKey` keyword (resolved): the ordered field names that identify one element of an array
     * of this type (issue #487) -- empty when the type is single-instance.
     *
     * A **type**-level keyword, alongside [required] and read the same way, because a composite key is ordered
     * (a per-property boolean could not say so) and it is how SQL states the same thing. It is what lets a
     * gedra carry several entries of one trait, told apart by this key drawn from their own data; and it is
     * surface-invariant -- a form renders a keyed type as a collection with an add-row for everyone -- so it
     * stays here rather than in a layout vocabulary. Uniqueness across siblings is enforced where they are
     * stored (`checkEntryKeys`), not by this type against one value.
     */
    val primaryKey: List<String> = emptyList(),
    /**
     * Custom `g-presentation` keyword (resolved): how a read-only surface should display this type/field
     * (issue #540) -- a [PRES] value (`status`/`table`/`identifier`), or null for ordinary rendering. Advisory
     * only: it never affects validation, and a renderer that does not recognize the value ignores it. Surfaced
     * here so the frontend reads the hint off the same parsed model it reads every other schema fact from.
     */
    val presentation: String? = null,
)

/**
 * The two modes of the `g-outerWhitespace` keyword (issue #541), resolved from its [SOWS] wire values. An enum
 * rather than a string because it is a genuinely closed operational set the validator switches on -- the kind
 * of use enums are kept for here. Entries are lower-case-first to match the wire spelling ([SOWS.trim] /
 * [SOWS.reject]); absent is modeled as a null [SchType.outerWhitespace], not an entry.
 */
@Suppress("EnumEntryName")
enum class SchOuterWhitespace {
    /** Strip leading/trailing whitespace in coerce mode; validate-only checks the trimmed form. */
    trim,

    /** Fail a value carrying leading/trailing whitespace with `badValue`; alter nothing. */
    reject,
}
