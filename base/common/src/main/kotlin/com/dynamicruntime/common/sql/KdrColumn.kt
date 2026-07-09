package com.dynamicruntime.common.sql

/**
 * A single column of a [KdrTable]: its [name], the per-column JSON Schema [schema] describing the column's
 * value (built with the schema property DSL), and the SQL-relevant facts derived from that schema plus
 * builder options.
 *
 * Unlike an endpoint's input/output fields, a table's columns are **not** assembled into a formal named
 * JSON Schema type in the schema store. The [schema] here documents and types the single column only — a
 * `KdrTable` is, in the words of issue #33, "a variant of the SchType where a list of schema fields is given
 * to it explicitly and no actual JSON schema type is formally constructed."
 */
class KdrColumn(
    val name: String,
    val schema: Map<String, Any?>,
    /** Storage encoding of the value; for a list column, the encoding of each element. */
    val storeType: StoreType,
    /** Whether the column holds a list (JSON Schema `array`), stored as an encoded string. */
    val isList: Boolean,
    /** Whether the column is NOT NULL. Required is tracked on the side (see [KdrTable.required]). */
    val required: Boolean,
    /** Whether the database auto-generates the value (a counter/serial primary key). */
    val autoIncrement: Boolean,
) {
    /**
     * For a list column, whether individual elements may contain commas and so must be JSON-encoded rather
     * than stored as a simple comma-separated list. Scalar numeric/boolean/date elements never can, so they
     * use the cheaper comma-separated form.
     */
    fun listElementsCanHaveCommas(): Boolean = when (storeType) {
        StoreType.integer, StoreType.float, StoreType.boolean, StoreType.date -> false
        else -> true
    }
}
