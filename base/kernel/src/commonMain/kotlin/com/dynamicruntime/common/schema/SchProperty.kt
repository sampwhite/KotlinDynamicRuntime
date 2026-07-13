package com.dynamicruntime.common.schema

/**
 * A parsed field of an object [SchType]. [valueType] is the resolved schema for
 * the field's value — either an inline schema or, for a `$ref` field, the bound
 * target type. It is populated during parsing: inline fields immediately, `$ref`
 * fields in the reference-resolution pass once all types are parsed.
 */
class SchProperty(
    val name: String,
    val description: String?,
    /** Fully-qualified target type name if this field is a `$ref`, else null. */
    val refName: String?,
) {
    /** Resolved value schema. Set once during parsing (see class doc). */
    lateinit var valueType: SchType
}
