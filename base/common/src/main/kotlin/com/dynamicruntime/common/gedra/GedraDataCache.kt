package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.cache.SqlCacheIndex
import com.dynamicruntime.common.sql.cache.SqlCacheParams
import com.dynamicruntime.common.sql.cache.SqlCacheRow
import com.dynamicruntime.common.sql.cache.SqlTableCache
import com.dynamicruntime.common.sql.cache.SqlTableCacheService
import com.dynamicruntime.common.util.toOptStr

/** Index names on the `GedraData` cache. Each name matches its value. */
@Suppress("ConstPropertyName")
object GDX {
    /** Every gedra of one client, whatever its kind. */
    const val client = "client"

    /** Every gedra of one kind within one client; key built by [GedraDataCache.clientKindKey]. */
    const val clientKind = "clientKind"
}

/**
 * The in-memory cache of the `GedraData` table.
 *
 * **There is deliberately no index on `userId`**, even though the table carries the column and SQL indexes it.
 * A gedra id is *derived*, not assigned: `ownGedraId` and `oneOfGedraId` produce the same id every time for
 * the same user, client and key, so "the gedra this user owns" is answered by **building the id and looking it
 * up** -- a primary-key hit, which the snapshot serves for free. SQL needs that index because it cannot
 * reconstruct an id from its parts; the cache does not, because the caller already holds them. An index here
 * would spend memory duplicating what the id encoding gives away.
 *
 * What is left is the *listing* shape, and it is organized the way the application is: by client, and by kind
 * within a client.
 *
 * The payload is the **raw stored row**, not a [GedraDataRow]. Two reasons, both the same as `AuthUserCache`'s:
 * a `GedraDataRow` is mutable, so a shared instance would let one caller's edit become everyone's; and
 * extracting one needs a [GedraService], which the cache has no business holding. Extracting per read *is* the
 * defensive copy.
 */
object GedraDataCache {
    /**
     * The [GDX.clientKind] key. A NUL joins the parts because no column value coming back from the database
     * can contain one, so no pair of (client, kind) can collide with another by carrying the separator.
     */
    fun clientKindKey(client: String, kind: String): String = "$client\u0000$kind"

    /**
     * The row count past which a load warns this table has outgrown caching. The stated operational ceiling
     * for gedras is 50,000; the warning sits at **double** that, so a deployment at its expected maximum loads
     * in silence and the warning means what it says -- volume beyond what the cache was planned for -- rather
     * than firing the moment capacity is reached. It deliberately does not inherit
     * [TCH.defaultLargeLoadWarning], which is 50,000 and was sized for `AuthUsers`: that would put the warning
     * line exactly on the ceiling.
     */
    const val largeLoadWarning = 100_000

    fun params(): SqlCacheParams<Map<String, Any?>> = SqlCacheParams(
        topic = gedraDataTopic,
        tableName = GDT.gedraData,
        extract = { _, data -> data },
        indexes = listOf(
            SqlCacheIndex(GDX.client) { it[PF.client].toOptStr() },
            SqlCacheIndex(GDX.clientKind) { row ->
                val client = row[PF.client].toOptStr()
                val kind = row[GD.gedraKind].toOptStr()
                if (client == null || kind == null) null else clientKindKey(client, kind)
            },
        ),
        largeLoadWarning = largeLoadWarning,
    )

    /** Registers the cache with the running service, or returns null when there is none. */
    fun register(cxt: KdrCxt): SqlTableCache<Map<String, Any?>>? = SqlTableCacheService.registerCache(cxt, params())

    /** Every cached gedra of [client], whatever its kind, in cache load order. */
    fun rowsForClient(
        cache: SqlTableCache<Map<String, Any?>>,
        client: String,
    ): List<SqlCacheRow<Map<String, Any?>>> = cache.snapshot.allByIndex(GDX.client, client)

    /** Every cached gedra of one [kind] within [client], in cache load order. */
    fun rowsForClientKind(
        cache: SqlTableCache<Map<String, Any?>>,
        client: String,
        kind: String,
    ): List<SqlCacheRow<Map<String, Any?>>> =
        cache.snapshot.allByIndex(GDX.clientKind, clientKindKey(client, kind))
}
