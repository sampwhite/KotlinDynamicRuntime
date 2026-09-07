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

    /**
     * A **partial** update statement -- `update <table> set <businessSetSql>, <the update-protocol stamps>
     * where <where>` -- prepared and cached under [name]. The caller owns its own business assignments and the
     * whole `where` clause; this appends the SET assignments for [updateStampColumns] so a scoped or by-id
     * update never names the audit columns itself, and [prepForStdUpdate] fills their bind values. A new
     * protocol update-field is then added in one place (that list plus [prepForStdUpdate]), and every partial
     * update picks it up without being touched.
     *
     * Contrast [mkTableUpdateStmt], which writes a **whole row** by primary key. The protocol fields here are
     * all SET assignments and never conditions, which is why the caller can be handed the whole `where` as a
     * string: nothing this helper adds ever needs to become part of it.
     */
    fun mkPartialUpdateStmt(
        sqlCxt: SqlCxt,
        table: KdrTable,
        name: String,
        businessSetSql: String,
        where: String,
    ): SqlStatement {
        val stampSql = stdUpdateStampSql(table)
        val setSql = if (stampSql.isEmpty()) businessSetSql else "$businessSetSql, $stampSql"
        val query = "update t:${table.tableName} set $setSql where $where"
        return SqlStmtUtil.prepareSql(sqlCxt, name, table.columns, query)
    }

    /** The `SET` assignments (`c:col = :col`, comma-joined) for whichever [updateStampColumns] [table] declares. */
    private fun stdUpdateStampSql(table: KdrTable): String =
        updateStampColumns.filter { table.columnsByName.containsKey(it) }.joinToString(", ") { "c:$it = :$it" }

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
     * The protocol columns a **partial** update stamps on every write: the "updated" audit pair. The single
     * source of truth for both halves of a scoped or by-id update -- [mkPartialUpdateStmt] emits their `SET`
     * assignments from this list, and [prepForStdUpdate] fills their bind values from it -- so a new protocol
     * update-field is added here and given a fill rule in [prepForStdUpdate], and no update *call site* has to
     * learn about it.
     *
     * The creation pair (`createdAt`/`createdBy`) is deliberately **absent**: an update preserves it, exactly
     * as [prepForStdExecute] fills it put-if-absent. Ownership columns are absent for the same reason -- a
     * partial update leaves them as they stand unless it means to change one, and then it says so itself.
     */
    val updateStampColumns: List<String> = listOf(PF.updatedAt, PF.updatedBy)

    /**
     * Stamps the [updateStampColumns] onto [data] for a partial update: `updatedBy` from the acting user, and
     * `updatedAt` from [nextUpdatedAt] against [prior] -- the row's current `updatedAt`, read under the same
     * lock the write takes, so the new stamp is strictly past it and the incremental caches cannot miss the
     * write (see [nextUpdatedAt]). Only columns [table] actually declares are stamped.
     *
     * This is the update-side companion to [prepForStdExecute]. A new protocol update-field is added to
     * [updateStampColumns] and given its fill rule in the `when` below; the `else` throws rather than silently
     * emitting a `SET c:new = :new` with no bound value, so the omission fails loudly at its one site.
     *
     * Returns the `updatedAt` it stamped (null only if [table] declares no such column) -- the monotonic write
     * time, which a caller stamping the same instant elsewhere needs: the gedra patch carries it into each
     * entry's own `updated` so the row column and the in-JSON stamp cannot disagree.
     */
    fun prepForStdUpdate(cxt: KdrCxt, table: KdrTable, data: MutableMap<String, Any?>, prior: Instant?): Instant? {
        for (col in updateStampColumns) {
            if (!table.columnsByName.containsKey(col)) {
                continue
            }
            data[col] = when (col) {
                PF.updatedBy -> cxt.userProfile.userId
                PF.updatedAt -> nextUpdatedAt(cxt, prior)
                else -> throw KdrException(
                    "No fill rule for update-stamp column '$col'; add one in SqlTopicUtil.prepForStdUpdate.",
                )
            }
        }
        return data[PF.updatedAt].toOptInstant()
    }

    /**
     * As [prepForStdUpdate], but reading the prior `updatedAt` from [priorRow] itself -- the row as it stands
     * under the lock, or null when there is none yet. The overload an update path reaches for after reading the
     * current row: it hands the whole row over rather than picking the audit column out of it, so the call site
     * names no protocol field. The value read is the same [PF.updatedAt] the [Instant] overload expects.
     */
    fun prepForStdUpdate(cxt: KdrCxt, table: KdrTable, data: MutableMap<String, Any?>, priorRow: Map<String, Any?>?): Instant? =
        prepForStdUpdate(cxt, table, data, priorRow?.get(PF.updatedAt).toOptInstant())

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
