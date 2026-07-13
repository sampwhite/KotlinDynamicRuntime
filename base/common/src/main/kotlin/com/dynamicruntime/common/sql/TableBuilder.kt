package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypeBuilder

/**
 * Builds a namespace's tables with a Kotlin DSL, mirroring `schemaModule`/`SchModuleBuilder` for endpoints.
 *
 * ```
 * val tables = tableModule(cxt, "node") {
 *     table("InstanceConfig", "Stores private instance data") {
 *         column("instanceName", "Unique identifier of instance of application.")
 *         column("configName", "The name of the configuration data.")
 *         column("configData", "The configuration data for this entry row.") { type = SCT.kObject }
 *         primaryKey("instanceName", "configName")
 *     }
 * }
 * ```
 */
fun tableModule(
    cxt: KdrCxt,
    namespace: String,
    topic: String = namespace,
    build: TableModuleBuilder.() -> Unit,
): List<KdrTable> = TableModuleBuilder(cxt, namespace, topic).apply(build).tables

/** Accumulates the [KdrTable]s declared for one namespace/topic. */
class TableModuleBuilder(val cxt: KdrCxt, val namespace: String, val topic: String) {
    val tables: MutableList<KdrTable> = mutableListOf()

    /** Declares a table; see [TableBuilder] for the column/key/feature DSL. */
    fun table(tableName: String, description: String, build: TableBuilder.() -> Unit) {
        tables.add(TableBuilder(cxt, namespace, topic, tableName, description).apply(build).build())
    }
}

/**
 * Builds a single [KdrTable]. Columns are declared with [column]; the primary key with [primaryKey];
 * secondary indexes with [index]; and account/user ownership with [forAccount]/[forUsers]. On [build] the
 * table's protocol columns are injected: the [PF.enabled] soft-delete flag and the audit columns
 * ([PF.createdBy]/[PF.updatedBy]/[PF.createdAt]/[PF.updatedAt]) on every table (unless suppressed via
 * [withoutEnabled]), plus the feature columns for any opted-in [TableFeature].
 */
class TableBuilder(
    val cxt: KdrCxt,
    val namespace: String,
    val topic: String,
    val tableName: String,
    val description: String,
) {
    // Intermediate mutable form so the primary key can retroactively mark its columns required (NOT NULL).
    private class ColumnSpec(
        val name: String,
        val schema: Map<String, Any?>,
        val storeType: StoreType,
        val isList: Boolean,
        var required: Boolean,
        val autoIncrement: Boolean,
    )

    private val columnSpecs: MutableList<ColumnSpec> = mutableListOf()
    private var primaryKeyFields: List<String> = emptyList()
    private val indexes: MutableList<KdrIndex> = mutableListOf()
    private val features: MutableSet<TableFeature> = mutableSetOf()
    private var addEnabled = true

    /**
     * Declares a column. [description] is mandatory (the column's schema documents the API). The value type
     * defaults to `string` unless [build] sets a `type` (e.g. `{ type = SCT.integer }`) or makes it an
     * array. Set [required] to make the column NOT NULL, or [autoIncrement] for a database-generated counter.
     */
    fun column(
        name: String,
        description: String,
        required: Boolean = false,
        autoIncrement: Boolean = false,
        build: SchTypeBuilder.() -> Unit = {},
    ) {
        val sub = SchTypeBuilder(cxt, namespace)
        sub.description = description
        sub.apply(build)
        // Default the column type to string unless the build block set one (columns are never $refs).
        if (SCH.type !in sub.data) {
            sub.type = SCT.string
        }
        columnSpecs.add(mkSpec(name, sub.data, required, autoIncrement))
    }

    /** Sets the primary-key field names. Primary-key columns are forced NOT NULL on [build]. */
    fun primaryKey(vararg fieldNames: String) {
        primaryKeyFields = fieldNames.toList()
    }

    /** Adds a secondary index over [fieldNames], optionally [unique] and/or explicitly [name]d. */
    fun index(vararg fieldNames: String, unique: Boolean = false, name: String? = null) {
        indexes.add(KdrIndex(name, fieldNames.toList(), unique))
    }

    /** Marks the table as account-scoped: adds an [PF.account] column. */
    fun forAccount() {
        features.add(TableFeature.account)
    }

    /**
     * Marks the table as user-owned: adds a [PF.userId] column and, since user ownership implies an account,
     * also the [PF.account] column (both features are recorded).
     */
    fun forUsers() {
        features.add(TableFeature.user)
        features.add(TableFeature.account)
    }

    /**
     * Marks the table as participating in topic transactions: adds the transaction-lock columns
     * ([PF.touchedAt] and [PF.lastTranId]). May be combined with [forAccount]/[forUsers]. A topic designates
     * one such table as its lock table.
     */
    fun withTransactions() {
        features.add(TableFeature.transactions)
    }

    /**
     * Suppresses the [PF.enabled] soft-delete column otherwise added to every table (mirrors the
     * prior-art `noEnabled` table option). Use only for tables where an enabled/disabled distinction is
     * meaningless and every row is unconditionally live.
     */
    fun withoutEnabled() {
        addEnabled = false
    }

    /** Assembles the [KdrTable], injecting feature and audit protocol columns after the declared columns. */
    fun build(): KdrTable {
        // Primary-key columns are implicitly NOT NULL.
        val pk = primaryKeyFields.toSet()
        for (spec in columnSpecs) {
            if (spec.name in pk) spec.required = true
        }

        val allSpecs = columnSpecs.toMutableList()
        // Feature columns (numeric userId, string account).
        if (TableFeature.user in features) {
            allSpecs.add(mkSpec(PF.userId, integerSchema("Numeric id of the owning user."), required = true))
        }
        if (TableFeature.account in features) {
            allSpecs.add(mkSpec(PF.account, stringSchema("Owning client account."), required = true))
        }
        // Transaction-lock columns (present only on a topic's designated lock table).
        if (TableFeature.transactions in features) {
            allSpecs.add(mkSpec(PF.touchedAt, dateSchema("Transaction-lock bookkeeping date."), required = true))
            allSpecs.add(mkSpec(PF.lastTranId, stringSchema("Id of the last transaction applied to the row.")))
        }
        // The `enabled` soft-delete flag, present on every table unless suppressed. It is nullable on
        // purpose: a null (or absent) value counts as disabled, so the application reads go through
        // SqlDatabase.queryOneEnabled while the standard "write" (SqlTopicUtil.prepForStdExecute) sets it true.
        if (addEnabled) {
            allSpecs.add(mkSpec(PF.enabled, booleanSchema("Whether the row is enabled; a null value counts as disabled.")))
        }
        // Audit columns present on every table.
        allSpecs.add(mkSpec(PF.createdBy, integerSchema("Numeric userId that created the row.")))
        allSpecs.add(mkSpec(PF.updatedBy, integerSchema("Numeric userId that last updated the row.")))
        allSpecs.add(mkSpec(PF.createdAt, dateSchema("Date the row was created.")))
        allSpecs.add(mkSpec(PF.updatedAt, dateSchema("Date the row was last updated.")))

        val columns = allSpecs.map {
            KdrColumn(it.name, it.schema, it.storeType, it.isList, it.required, it.autoIncrement)
        }
        return KdrTable(
            tableName, namespace, topic, description, columns, primaryKeyFields, indexes, features.toSet(),
        )
    }

    // --- helpers ------------------------------------------------------------

    private fun mkSpec(
        name: String,
        schema: Map<String, Any?>,
        required: Boolean = false,
        autoIncrement: Boolean = false,
    ): ColumnSpec {
        val isList = SqlTypeUtil.isListSchema(schema)
        val storeType = SqlTypeUtil.storeTypeForSchema(schema)
        return ColumnSpec(name, schema, storeType, isList, required, autoIncrement)
    }

    private fun stringSchema(description: String): Map<String, Any?> =
        SchTypeBuilder(cxt, namespace).also { it.description = description; it.type = SCT.string }.data

    private fun integerSchema(description: String): Map<String, Any?> =
        SchTypeBuilder(cxt, namespace).also { it.description = description; it.type = SCT.integer }.data

    private fun booleanSchema(description: String): Map<String, Any?> =
        SchTypeBuilder(cxt, namespace).also { it.description = description; it.type = SCT.boolean }.data

    private fun dateSchema(description: String): Map<String, Any?> =
        SchTypeBuilder(cxt, namespace).also { it.description = description; it.dateTime() }.data
}
