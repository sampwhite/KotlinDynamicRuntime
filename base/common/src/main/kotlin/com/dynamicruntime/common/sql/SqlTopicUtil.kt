package com.dynamicruntime.common.sql

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toOptInstant
import kotlin.time.Instant

/**
 * Builds the standard per-table statements (insert/select/update and the transaction-lock update) and
 * populates protocol columns from the [KdrCxt] before a write.
 *
 * Protocol population follows issue #33's owner-vs-actor split (see [KdrCxt]): `createdBy`/`updatedBy` come
 * from the acting user ([KdrCxt.userProfile]), while the ownership columns added by the account/user table
 * features come from the bound owner ([KdrCxt.account] / [KdrCxt.userId]). Which columns to fill is driven
 * by the table's declared columns and [TableFeature]s, not hard-coded — so this is not a blind fallback.
 * Rewritten from the prior-art `SqlTopicUtil`.
 */
@Suppress("ConstPropertyName")
object SqlTopicUtil {
    /** Marker written into [PF.lastTranId] when the placeholder lock row is first inserted. */
    const val initialInsertTranId = "INITIAL_INSERT"

    // --- statement builders -------------------------------------------------

    fun mkTableInsertStmt(sqlCxt: SqlCxt, table: KdrTable): SqlStatement {
        val hasAutoIncrement = booleanArrayOf(false)
        val query = SqlStmtUtil.mkInsertQuery(table.tableName, table.columns, hasAutoIncrement)
        val stmt = SqlStmtUtil.prepareSql(sqlCxt, "i${table.tableName}", table.columns, query)
        if (hasAutoIncrement[0]) {
            stmt.returnGeneratedKeys = true
        }
        return stmt
    }

    fun mkTableSelectStmt(sqlCxt: SqlCxt, table: KdrTable): SqlStatement =
        mkNamedTableSelectStmt(sqlCxt, "q${table.tableName}", table, table.primaryKey)

    fun mkNamedTableSelectStmt(
        sqlCxt: SqlCxt,
        qName: String,
        table: KdrTable,
        andFields: List<String>,
    ): SqlStatement {
        val query = SqlStmtUtil.mkSelectQuery(table.tableName, andFields)
        return SqlStmtUtil.prepareSql(sqlCxt, qName, table.columns, query)
    }

    /** Update-by-primary-key statement; never updates the creation-audit, auto-increment, or lock columns. */
    fun mkTableUpdateStmt(sqlCxt: SqlCxt, table: KdrTable): SqlStatement {
        val relevantColumns = table.columns.filter { col ->
            col.name != PF.touchedAt && col.name != PF.createdAt && col.name != PF.createdBy && !col.autoIncrement
        }
        val query = SqlStmtUtil.mkUpdateQuery(table.tableName, relevantColumns, table.primaryKey)
        return SqlStmtUtil.prepareSql(sqlCxt, "u${table.tableName}", table.columns, query)
    }

    /** Takes the transaction lock by updating [PF.touchedAt] on the primary-key row. */
    fun mkTableTranLockStmt(sqlCxt: SqlCxt, table: KdrTable): SqlStatement {
        if (!table.columnsByName.containsKey(PF.touchedAt)) {
            throw KdrException(
                "Table ${table.tableName} cannot have a lock query created for it because it does not have " +
                    "the column ${PF.touchedAt}.",
            )
        }
        val relevantColumns = table.columns.filter { it.name in table.primaryKey || it.name == PF.touchedAt }
        val query = SqlStmtUtil.mkUpdateQuery(table.tableName, relevantColumns, table.primaryKey)
        return SqlStmtUtil.prepareSql(sqlCxt, "uTran${table.tableName}", table.columns, query)
    }

    // --- protocol-field population ------------------------------------------

    /**
     * Stamps the audit and ownership columns onto [data] before writing to the row. `createdBy`/`createdAt` are filled
     * only if absent (so an update preserves the original creator/creation time); `updatedBy`/`updatedAt`
     * are always set (`updatedAt` advancing monotonically). Ownership columns are filled from the context's
     * bound owner only when the table declares the matching feature.
     *
     * A standard "execute" also marks the row [PF.enabled] (issue #48). This is unconditional -- not
     * put-if-absent -- so "creating" over an existing disabled row re-enables it, which is the intended
     * behavior for a transaction table's "create" path.
     */
    fun prepForStdExecute(cxt: KdrCxt, table: KdrTable, data: MutableMap<String, Any?>) {
        val actor = cxt.userProfile.userId
        data.putIfAbsent(PF.createdBy, actor)
        data[PF.updatedBy] = actor
        prepDates(cxt, data)
        if (table.columnsByName.containsKey(PF.enabled)) {
            data[PF.enabled] = true
        }
        if (TableFeature.account in table.features) {
            data.putIfAbsent(PF.account, cxt.account)
        }
        if (TableFeature.user in table.features) {
            data.putIfAbsent(PF.userId, cxt.userId)
        }
    }

    /** Sets [PF.createdAt] (if absent) and [PF.updatedAt] (always), forcing updatedAt to advance. */
    fun prepDates(cxt: KdrCxt, data: MutableMap<String, Any?>) {
        // Persisted protocol dates use the instance clock, not the per-context one (issue #160): updatedAt is a
        // queuing date and must be monotonic and consistent across concurrent requests.
        var now = cxt.instanceNow()
        if (data[PF.createdAt].toOptInstant() == null) {
            data[PF.createdAt] = now
        }
        val lastUpdated = data[PF.updatedAt].toOptInstant()
        if (lastUpdated != null) {
            // Advance by at least a millisecond when the clock did not move (or barely did, within 2s),
            // which can happen when queries run very fast or node clocks are slightly out of sync.
            val l = lastUpdated.toEpochMilliseconds()
            val n = now.toEpochMilliseconds()
            if (n in (l - 2000)..l) {
                now = Instant.fromEpochMilliseconds(l + 1)
            }
        }
        data[PF.updatedAt] = now
    }

    /** Sets the transaction-lock bookkeeping for the initial placeholder-row insert. */
    fun prepForTranInsert(cxt: KdrCxt, data: MutableMap<String, Any?>) {
        data[PF.touchedAt] = cxt.instanceNow() // a persisted queuing date (issue #160)
        data[PF.lastTranId] = initialInsertTranId
    }
}
