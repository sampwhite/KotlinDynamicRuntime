package com.dynamicruntime.common.schema

// String constants for the standard JSON Schema (draft 2020-12) keywords and the
// `type` keyword's values. Per the code guide: lowerCamelCase const vals in
// upper-cased acronym objects, referenced qualified, with the const-naming
// inspection suppressed at the object level. Each plain keyword's NAME matches
// its VALUE. Two variant rules (issue #2):
//   * a leading `$` becomes a `d` prefix with the next letter capitalized
//     (`$ref` -> dRef);
//   * a name colliding with a Kotlin hard keyword takes a `k` prefix with the
//     next letter capitalized (`if` -> kIf; `then` included for consistency).

/** JSON Schema keywords (the object keys). */
@Suppress("ConstPropertyName", "unused")
object SCH {
    // Core / identifier ($-keywords -> d prefix).
    const val dSchema = $$"$schema"
    const val dId = $$"$id"
    const val dRef = $$"$ref"
    const val dDefs = $$"$defs"
    const val dAnchor = $$"$anchor"
    const val dDynamicRef = $$"$dynamicRef"
    const val dDynamicAnchor = $$"$dynamicAnchor"
    const val dVocabulary = $$"$vocabulary"
    const val dComment = $$"$comment"

    // Annotations / metadata.
    const val title = "title"
    const val description = "description"
    const val default = "default"
    const val examples = "examples"
    const val deprecated = "deprecated"
    const val readOnly = "readOnly"
    const val writeOnly = "writeOnly"

    // Generic validation.
    const val type = "type"
    const val enum = "enum"
    const val const = "const"

    // Objects.
    const val properties = "properties"
    const val patternProperties = "patternProperties"
    const val additionalProperties = "additionalProperties"
    const val unevaluatedProperties = "unevaluatedProperties"
    const val required = "required"
    const val propertyNames = "propertyNames"
    const val minProperties = "minProperties"
    const val maxProperties = "maxProperties"
    const val dependentRequired = "dependentRequired"
    const val dependentSchemas = "dependentSchemas"

    // Arrays.
    const val prefixItems = "prefixItems"
    const val items = "items"
    const val unevaluatedItems = "unevaluatedItems"
    const val contains = "contains"
    const val minContains = "minContains"
    const val maxContains = "maxContains"
    const val minItems = "minItems"
    const val maxItems = "maxItems"
    const val uniqueItems = "uniqueItems"

    // Strings.
    const val minLength = "minLength"
    const val maxLength = "maxLength"
    const val pattern = "pattern"
    const val format = "format"

    // Numbers.
    const val minimum = "minimum"
    const val maximum = "maximum"
    const val exclusiveMinimum = "exclusiveMinimum"
    const val exclusiveMaximum = "exclusiveMaximum"
    const val multipleOf = "multipleOf"

    // Combinators / conditionals (if/then/else collide with Kotlin keywords -> k prefix).
    const val allOf = "allOf"
    const val anyOf = "anyOf"
    const val oneOf = "oneOf"
    const val not = "not"
    const val kIf = "if"
    const val kThen = "then"
    const val kElse = "else"

    // Content.
    const val contentEncoding = "contentEncoding"
    const val contentMediaType = "contentMediaType"
    const val contentSchema = "contentSchema"

    // Custom (kd2) keywords — not part of standard JSON Schema.
    /** Whether a value may be coerced to the property's type during validation. */
    const val allowCoerce = "allowCoerce"

    /** A labeled choice list on a property (array of `{label, value}` entries). */
    const val options = "options"
    /** Display label of an `options` entry. */
    const val label = "label"
    /** Stored value of an `options` entry. */
    const val value = "value"
}

/** Values of the JSON Schema `type` keyword (object/null collide with Kotlin
 *  hard keywords -> k prefix). */
@Suppress("ConstPropertyName", "unused")
object SCT {
    const val string = "string"
    const val number = "number"
    const val integer = "integer"
    const val boolean = "boolean"
    const val array = "array"
    const val kObject = "object"
    const val kNull = "null"
}

/** Values of the JSON Schema `format` keyword that we act on. The `dateTime` name does not match its
 *  hyphenated value, which is the standard JSON Schema spelling. */
@Suppress("ConstPropertyName", "unused")
object SFMT {
    /** A day-only date, e.g. `2021-06-01`. */
    const val date = "date"

    /** A full timestamp, e.g. `2021-06-01T08:00:00.000Z`. */
    const val dateTime = "date-time"

    /**
     * File content: `{"type": "string", "format": "binary"}`. This is OpenAPI's own way of saying "this field
     * is a file" — the same spelling an OpenAPI 3.0 document uses for an upload part or a downloaded body — so
     * a schema declaring one reads as a standard document rather than a house invention.
     *
     * It is a *string* type carrying a format for the same reason OpenAPI does it: JSON Schema has no binary
     * type, and a file is only ever a string in the sense that the wire is bytes. Nothing decodes it as text —
     * the value at runtime is a `ContentData`, and [isBinaryFormat] is what tells the validator to leave it
     * alone rather than coerce it (see the validator's note).
     *
     * OpenAPI 3.1 instead layers `contentMediaType`/`contentEncoding` onto JSON Schema proper. We use the 3.0
     * spelling because it is the one still universally understood by tooling, and because a `format` is
     * something this schema layer already models.
     */
    const val binary = "binary"
}
