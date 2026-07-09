package com.dynamicruntime.common.sql

import com.dynamicruntime.common.schema.JsonMappable
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypesBuilder

/** Attribute keys for a [KdrTable]'s info dump (see [KdrTable.toJsonMap]). Each name matches its value. */
@Suppress("ConstPropertyName")
object TI {
    const val tableName = "tableName"
    const val namespace = "namespace"
    const val topic = "topic"
    const val description = "description"
    const val features = "features"
    const val primaryKey = "primaryKey"
    const val indexes = "indexes"
    const val columns = "columns"

    // Nested column / index attribute keys.
    const val name = "name"
    const val schema = "schema"
    const val fields = "fields"
    const val unique = "unique"
}

/**
 * Optional table capabilities, opted into via the builder ([TableBuilder.forAccount] /
 * [TableBuilder.forUsers] / [TableBuilder.withTransactions]); each adds its protocol column(s).
 * [transactions] may be combined with either [account] or [user].
 *
 * A closed operational set that gets serialized in the table info dump, so an enum fits (per the code guide).
 */
@Suppress("EnumEntryName")
enum class TableFeature { account, user, transactions }

/** A database index over one or more columns (by field name), optionally named and/or unique. */
class KdrIndex(
    /** Explicit index name, or null to derive one from the column names. */
    val name: String?,
    /** Field names covered by the index, in order. */
    val fieldNames: List<String>,
    /** Whether the index carries a uniqueness constraint. */
    val unique: Boolean,
) : JsonMappable {
    override fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        TI.name to name,
        TI.fields to fieldNames,
        TI.unique to unique,
    )
}

/**
 * A fully realized table definition: its [tableName], the [namespace] it was declared in, a required
 * [description], its [columns] (each carrying its own per-column JSON schema), the [primaryKey] field
 * names, secondary [indexes], and the [features] it opted into.
 *
 * Analogous to `KdrEndpoint`, but with one key difference (issue #33): a table is a variant of a schema type
 * whose fields are given explicitly and which is **never** registered as a named JSON Schema type in the
 * schema store. The list of tables is instead maintained by the topic service. Like `KdrEndpoint`, the table
 * owns its own serialization ([toJsonMap]) and, besides it, the schema of that serialization
 * ([defineInfoType]), so the two cannot drift apart.
 */
class KdrTable(
    val tableName: String,
    val namespace: String,
    /** The topic (database grouping) this table belongs to; used by the topic service to group tables. */
    val topic: String,
    val description: String,
    val columns: List<KdrColumn>,
    val primaryKey: List<String>,
    val indexes: List<KdrIndex>,
    val features: Set<TableFeature>,
) : JsonMappable {
    /** Columns keyed by field name, for a quick lookup during query building. */
    val columnsByName: Map<String, KdrColumn> = columns.associateBy { it.name }

    /** The names of the required (NOT NULL) columns — required is tracked on the side. */
    val required: Set<String> = columns.filter { it.required }.map { it.name }.toSet()

    /** Whether the first column is an auto-incrementing counter (affects insert/generated-key handling). */
    val firstColIsCounter: Boolean = columns.firstOrNull()?.autoIncrement == true

    /** Whether this table participates in topic transactions (i.e., carries the transaction-lock columns). */
    val isTransactional: Boolean = TableFeature.transactions in features

    /**
     * Renders this table's attributes as a JSON map (the form the topic service's list-tables endpoint
     * returns). Serialization lives with the class — beside its schema ([defineInfoType]) — so the two stay
     * aligned in one file. Each column is dumped as its name plus its per-column JSON schema.
     */
    override fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        TI.tableName to tableName,
        TI.namespace to namespace,
        TI.topic to topic,
        TI.description to description,
        TI.features to features.map { it.name },
        TI.primaryKey to primaryKey,
        TI.indexes to indexes.map { it.toJsonMap() },
        TI.columns to columns.map { linkedMapOf(TI.name to it.name, TI.schema to it.schema) },
    )

    @Suppress("ConstPropertyName")
    companion object {
        /** Schema type name for a table's attribute dump (the shape of [toJsonMap]). */
        const val infoTypeName = "TableInfo"

        /**
         * Defines the `TableInfo` schema type (the shape of [toJsonMap]) on [builder]. Kept with the class, so
         * the type and the serialization cannot drift apart, mirroring `KdrEndpoint.defineInfoType`.
         */
        fun defineInfoType(builder: SchTypesBuilder) {
            builder.type(infoTypeName) {
                type = SCT.kObject
                property(TI.tableName, "The database table name.", required = true)
                property(TI.namespace, "The namespace the table was declared in.", required = true)
                property(TI.topic, "The topic (database grouping) the table belongs to.", required = true)
                property(TI.description, "Human description of the table.", required = true)
                property(TI.features, "The features the table opted into.") {
                    type = SCT.array
                    items { type = SCT.string }
                }
                property(TI.primaryKey, "The primary-key field names.", required = true) {
                    type = SCT.array
                    items { type = SCT.string }
                }
                property(TI.indexes, "The secondary indexes.") {
                    type = SCT.array
                    items {
                        type = SCT.kObject
                        property(TI.name, "The index name, if given.")
                        property(TI.fields, "The field names covered by the index.", required = true) {
                            type = SCT.array
                            items { type = SCT.string }
                        }
                        property(TI.unique, "Whether the index is unique.", required = true) { type = SCT.boolean }
                    }
                }
                property(TI.columns, "The table columns and their per-column JSON schema.", required = true) {
                    type = SCT.array
                    items {
                        type = SCT.kObject
                        property(TI.name, "The column (field) name.", required = true)
                        property(TI.schema, "The column's JSON schema.", required = true) { type = SCT.kObject }
                    }
                }
            }
        }
    }
}
