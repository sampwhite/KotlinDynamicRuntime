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
    @Suppress("unused")
    val description: String?,
    /** Fields, for an object type (empty otherwise). */
    val properties: Map<String, SchProperty>,
    /** Required field names, for an object type. */
    val required: Set<String>,
    /** Element schema, for an array type (null otherwise). */
    val itemType: SchType?,
    /** Choice list for the custom `options` construct; null if not an options field. */
    val options: List<SchOption>?,
)
