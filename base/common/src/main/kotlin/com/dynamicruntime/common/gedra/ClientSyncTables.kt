package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.tableModule

/**
 * The topic the client-sync tracking row belongs to (issue #618): its **own**, apart from `gedraConfig`. A
 * config write takes a lock on the config transaction root; the sync row takes its own lock on every announce,
 * and two transactional tables in one topic would force every config write to name the lock it takes (issue
 * #435). Keeping them apart is simpler and keeps a frequent config write from ever contending with a sync
 * announce.
 */
const val clientSyncTopic = "clientSync"

/** The client-sync tracking table and its columns (issue #618). Each name matches its value. */
@Suppress("ConstPropertyName")
object CSY {
    const val clientSyncTracking = "ClientSyncTracking"

    /** The id of the one row this node's deployment shares; there is exactly one, like the cache-state row. */
    const val syncId = "syncId"

    /** The single shared row's id. */
    const val defaultSyncId = "clientSync"

    /**
     * A JSON map, client id -> the date of the newest configuration any node has settled on for that client.
     * A node compares its own last-synced marker per client against this and reloads (#616) when it is behind;
     * a reload or a restart that loaded newer configuration advances it. The same shape as the cache-state row.
     */
    const val syncState = "syncState"
}

/**
 * The client-sync tracking table (issue #618): one deployment-shared row of client id -> newest-config date,
 * the multi-node fallback to the reload endpoint (#616). Modeled on `KdrCacheState`: a single row holding a
 * map, advanced under its own lock, read on a throttle. In its own topic, so it never shares a transaction
 * with a config write; see [clientSyncTopic].
 */
fun clientSyncTables(cxt: KdrCxt): List<KdrTable> =
    tableModule(cxt, namespace = "clientSync", topic = clientSyncTopic) {
        table(CSY.clientSyncTracking, "When each client's configuration last changed, by any node (#618).") {
            column(CSY.syncId, "Id of the collection this row describes.", required = true)
            column(CSY.syncState, "Client id -> the date its configuration last changed.") { type = SCT.kObject }
            primaryKey(CSY.syncId)
            withTransactions()
        }
    }
