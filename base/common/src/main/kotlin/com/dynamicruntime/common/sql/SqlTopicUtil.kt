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
 * from the acting user ([KdrCxt.userProfile]), while the ownership columns added by the client/org/user table
 * features come from the bound owner ([KdrCxt.client] / [KdrCxt.org] / [KdrCxt.userId]). Which columns to fill is driven
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
        if (TableFeature.client in table.features) {
            data.putIfAbsent(PF.client, cxt.client)
        }
        if (TableFeature.org in table.features) {
            // Only when the context actually has one. A null org is a legitimate final value -- "the client's,
            // not any organization's" -- so there is nothing to stamp and nothing to overwrite: leaving the key
            // absent lets an existing value survive an update, exactly as putIfAbsent does for the others.
            val org = cxt.org
            if (org != null) {
                data.putIfAbsent(PF.org, org)
            }
        }
        if (TableFeature.user in table.features) {
            data.putIfAbsent(PF.userId, cxt.userId)
        }
    }

    /** Sets [PF.createdAt] (if absent) and [PF.updatedAt] (always), forcing updatedAt to advance. */
    fun prepDates(cxt: KdrCxt, data: MutableMap<String, Any?>) {
        // Persisted protocol dates use the instance clock, not the per-context one (issue #160): updatedAt is a
        // queuing date and must be monotonic and consistent across concurrent requests.
        val now = cxt.instanceNow()
        if (data[PF.createdAt].toOptInstant() == null) {
            data[PF.createdAt] = now
        }
        data[PF.updatedAt] = advancePast(now, data[PF.updatedAt].toOptInstant())
    }

    /**
     * The `updatedAt` to stamp on a write, guaranteed **strictly after** [prior] (the row's current value):
     * the instance clock now, bumped a millisecond past [prior] when the clock has not moved beyond it.
     *
     * The invariant matters because the incremental table caches reload by walking `updatedAt` forward and
     * **skip a row stamped at or before the version they already hold** -- so a write that does not advance the
     * date is invisible to every cache until the row's next genuine update. `updatedAt` is stored at
     * millisecond precision, so two writes to one row within a millisecond collide unless something forces the
     * advance; this is that something.
     *
     * [prepDates] applies it for a write assembled from a whole row. A path that stamps `updatedAt` itself --
     * a scoped `update ... set updatedAt = :updatedAt` that cannot hand its row to [prepDates] -- must call
     * this with the row's current value (read under the same lock the write takes), or it reintroduces exactly
     * the gap [prepDates] closes.
     */
    fun nextUpdatedAt(cxt: KdrCxt, prior: Instant?): Instant = advancePast(cxt.instanceNow(), prior)

    /** [now], or one millisecond past [prior] when [now] has not reached beyond it. Shared monotonic bump. */
    private fun advancePast(now: Instant, prior: Instant?): Instant {
        // Advance by at least a millisecond whenever the clock has not moved past the prior stamp --
        // **however far behind it is**. This guard used to apply only within a 2-second window, which let
        // a node whose clock lagged by more stamp a row *earlier* than it already was; a date moving
        // backwards is what the incremental table caches can never see (their reload skips rows at or
        // before the version they hold), so such a write was served stale everywhere until the row's next
        // genuine update. Per-row monotonicity is the invariant, whatever the skew.
        if (prior == null) {
            return now
        }
        val l = prior.toEpochMilliseconds()
        return if (now.toEpochMilliseconds() <= l) Instant.fromEpochMilliseconds(l + 1) else now
    }

    /** Sets the transaction-lock bookkeeping for the initial placeholder-row insert. */
    fun prepForTranInsert(cxt: KdrCxt, data: MutableMap<String, Any?>) {
        data[PF.touchedAt] = cxt.instanceNow() // a persisted queuing date (issue #160)
        data[PF.lastTranId] = initialInsertTranId
    }
}
