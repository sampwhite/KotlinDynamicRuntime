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
    /**
     * The **field's** own `title` -- a short human label, against [description]'s longer explanation; null when
     * none is declared. On the property, not read off [valueType], because a `$ref` field's [valueType] is the
     * *shared* target instance: a title from there would label every field referencing that type identically.
     */
    val title: String? = null,
    /**
     * `g-optionalContents`: this field's object value is a **fragment**, so the validator checks its fields but
     * not its completeness -- neither [SchType.required] nor a conditional's requiredness (issue #487). On the
     * property, not [valueType], because a `$ref` field's value type is shared -- only this use of it is a
     * fragment. See [SCH.optionalContents].
     */
    val optionalContents: Boolean = false,
) {
    /** Resolved value schema. Set once during parsing (see class doc). */
    lateinit var valueType: SchType
}
