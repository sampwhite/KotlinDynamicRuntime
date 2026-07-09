package com.dynamicruntime.common.sql

import java.util.concurrent.ConcurrentHashMap

/**
 * Aliases code-side field names to database column names. Primarily used to translate camelCase field names
 * to lower_snake_case column names when the target database does not preserve the identifier's case, but it can
 * carry any aliasing. Treat [colNameToFieldName] and [fieldNameToColName] as immutable; new versions are
 * produced via [getUpdated] from the [newFieldNameToColNameFeeder].
 */
class SqlColumnAliases(
    /** Whether column names coming back from the database should be lower-cased before lookup. */
    val toLowerCaseColumns: Boolean,
    /**
     * Fields whose names are reserved for common query activities (e.g., paging fields such as `limit`).
     * Consulted when a query references a field the table does not declare.
     */
    val reservedFields: Map<String, KdrColumn>,
    /** Column name -> field name. Immutable. */
    val colNameToFieldName: Map<String, String>,
    /** Field name -> column name. Immutable. */
    val fieldNameToColName: Map<String, String>,
) {
    /** Feeder for producing new versions of this object; not touched by consumers of the field maps. */
    val newFieldNameToColNameFeeder: MutableMap<String, String> = ConcurrentHashMap()

    /** Produces a new aliases object folding in everything accumulated in [newFieldNameToColNameFeeder]. */
    fun getUpdated(): SqlColumnAliases {
        val cf = colNameToFieldName.toMutableMap()
        val fc = fieldNameToColName.toMutableMap()
        for ((k, v) in newFieldNameToColNameFeeder) {
            cf[v] = k
            fc[k] = v
        }
        return SqlColumnAliases(toLowerCaseColumns, reservedFields, cf, fc)
    }

    /** The column name for a field, or the field name unchanged if not aliased. */
    fun getColumnName(fieldName: String): String = fieldNameToColName[fieldName] ?: fieldName

    /** The field name for a column, or the (optionally lower-cased) column name if not aliased. */
    fun getFieldName(columnName: String): String {
        val c = if (toLowerCaseColumns) columnName.lowercase() else columnName
        return colNameToFieldName[c] ?: c
    }
}
