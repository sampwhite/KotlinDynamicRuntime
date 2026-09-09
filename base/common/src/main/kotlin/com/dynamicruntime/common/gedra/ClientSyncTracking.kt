package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.sql.SqlCxt
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.util.fmt
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import kotlin.time.Instant

/**
 * The shared client-sync row (issue #618), read and written the way `SqlTableCacheService` handles its
 * cache-state row: a node advances a client's marker under the row's lock when it reloads or restarts into
 * newer configuration, and every node reads the row (on a throttle) to learn which clients it is behind on.
 *
 * The marker is the **newest configuration date** the deployment has settled on for a client -- the max
 * `updatedAt` of the revisions a node consumed. Advancing is a monotonic **max**, not a blind bump: a restart
 * that loaded exactly what peers already run must not announce a change, or a rolling restart would make every
 * node reload for nothing. A genuine change bumps `updatedAt` strictly (the #633 clock rule), so a node that
 * loaded it announces a marker strictly greater than the one peers hold, and they catch up.
 *
 * Free functions over the topic's [SqlCxt], because the producers (a reload, a restart) and the consumer (the
 * periodic check) reach the row from different services and must merge it the same way.
 */
object ClientSyncTracking {
    /** The markers as they stand: client id -> newest-config date. */
    fun readMarkers(cxt: KdrCxt, sqlCxt: SqlCxt): Map<String, Instant> {
        val table = cxt.getSchema().tables[CSY.clientSyncTracking]
            ?: throw KdrException("${CSY.clientSyncTracking} table is not registered in the schema store.")
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) {
            row = sqlCxt.sqlDb.queryOneStatement(cxt, SqlTopicUtil.mkTableSelectStmt(sqlCxt, table), mapOf(CSY.syncId to CSY.defaultSyncId))
        }
        return decode(row)
    }

    /**
     * Advances each client's marker to at least [markers] under the row's lock (issue #618) -- a monotonic max,
     * so a marker never goes backward and an announce of what is already recorded changes nothing. A no-op when
     * [markers] is empty.
     */
    fun announce(cxt: KdrCxt, sqlCxt: SqlCxt, markers: Map<String, Instant>) {
        if (markers.isEmpty()) return
        sqlCxt.sqlDb.withSession(cxt) {
            SqlTopicTranProvider.executeTopicTran(
                sqlCxt, "clientSyncAnnounce", null, mapOf(CSY.syncId to CSY.defaultSyncId),
                tranTableName = CSY.clientSyncTracking,
            ) {
                val merged = decode(sqlCxt.tranData).toMutableMap()
                for ((client, date) in markers) {
                    val existing = merged[client]
                    if (existing == null || date > existing) merged[client] = date
                }
                sqlCxt.tranData[CSY.syncState] = merged.mapValues { it.value.fmt() }
            }
        }
    }

    /** Decodes the stored map, skipping any entry that is not a readable date (a future use of the row is not fatal). */
    private fun decode(row: Map<String, Any?>?): Map<String, Instant> {
        val stored = row?.get(CSY.syncState).toJsonMapOrEmpty()
        val out = LinkedHashMap<String, Instant>(stored.size)
        for ((client, value) in stored) {
            val date = runCatching { value.toOptInstant() }.getOrNull() ?: continue
            out[client] = date
        }
        return out
    }
}
