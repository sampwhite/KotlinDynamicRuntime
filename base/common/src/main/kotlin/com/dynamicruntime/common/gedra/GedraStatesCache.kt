package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.cache.SqlCacheIndex
import com.dynamicruntime.common.sql.cache.SqlCacheParams
import com.dynamicruntime.common.sql.cache.SqlCacheRow
import com.dynamicruntime.common.sql.cache.SqlTableCache
import com.dynamicruntime.common.sql.cache.SqlTableCacheService
import com.dynamicruntime.common.util.toOptStr

/** Index names on the `GedraDataStates` cache. Each name matches its value. */
@Suppress("ConstPropertyName")
object GSX {
    /** Every state row of one client. */
    const val client = "client"
}

/**
 * The always-resident in-memory cache of the `GedraDataStates` table (issue #598), the state companion to
 * [GedraDataCache].
 *
 * A **second** cache rather than folding state into [GedraDataCache], for the reason #596 split the tables:
 * state rows stay small and change in batches, where a data row can grow large and is pruned. So the state
 * cache can stay **whole and resident** even when the data cache has had to shed rows to fit heap -- which is
 * what turns "what state is this gedra in?" into a memory hit rather than a query, and is the substrate the
 * `withStates` read (issue #600) and the deferred batch recompute both build on.
 *
 * Read the same way [GedraDataCache] is: the payload is the **raw stored row**, not a [GedraDataRow]. A
 * `GedraDataRow` is mutable, so a shared instance would let one caller's edit become everyone's, and extracting
 * one needs a [GedraService] the cache has no business holding -- so extracting per read *is* the defensive
 * copy, exactly as it is there.
 *
 * Keyed by [GD.gedraId] (the primary-key map the snapshot serves for free -- one state row per gedra), with a
 * single non-unique [GSX.client] index, the shape a state listing and the batch recompute take. There is no
 * `kind` index: the state table carries no `gedraKind` column (the id carries the kind), and no state listing
 * filters by kind.
 */
@Suppress("ConstPropertyName")
object GedraStatesCache {
    /**
     * The row count past which a load warns the states table has outgrown caching. There is at most one state
     * row per gedra, so the ceiling matches [GedraDataCache.largeLoadWarning] rather than the smaller default
     * [com.dynamicruntime.common.sql.cache.TCH.defaultLargeLoadWarning] sized for `AuthUsers`.
     */
    const val largeLoadWarning = 100_000

    fun params(): SqlCacheParams<Map<String, Any?>> = SqlCacheParams(
        topic = gedraDataTopic,
        tableName = GDT.gedraDataStates,
        extract = { _, data -> data },
        indexes = listOf(
            SqlCacheIndex(GSX.client) { it[PF.client].toOptStr() },
        ),
        largeLoadWarning = largeLoadWarning,
    )

    /** Registers the cache with the running service, or returns null when there is none. */
    fun register(cxt: KdrCxt): SqlTableCache<Map<String, Any?>>? = SqlTableCacheService.registerCache(cxt, params())

    /** Every cached state row of [client], in cache load order -- for a state listing or the batch recompute. */
    fun rowsForClient(
        cache: SqlTableCache<Map<String, Any?>>,
        client: String,
    ): List<SqlCacheRow<Map<String, Any?>>> = cache.snapshot.allByIndex(GSX.client, client)
}
