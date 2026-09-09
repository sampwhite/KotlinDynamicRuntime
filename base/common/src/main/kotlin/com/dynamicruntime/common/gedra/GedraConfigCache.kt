package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.cache.SqlCacheIndex
import com.dynamicruntime.common.sql.cache.SqlCacheParams
import com.dynamicruntime.common.sql.cache.SqlTableCache
import com.dynamicruntime.common.sql.cache.SqlTableCacheService
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr

/** Index names on the `GedraConfig` cache. Each name matches its value. */
@Suppress("ConstPropertyName")
object GCX {
    /** Every revision of one config, keyed by its revision class ([GC.configId]). */
    const val configId = "configId"

    /** Every revision of every config one client owns. */
    const val client = "client"
}

/**
 * The two revisions of a class that matter (issue #615): the **latest** revision, and the **latest published**
 * one -- which may be the same row, and which is null while a class has never been published.
 */
class ConfigRevisions(
    /** The most recent enabled revision -- the editable latest, or the published one if nothing is newer. */
    val latest: Map<String, Any?>,
    /** The most recent enabled revision that has been published, or null when none has. */
    val latestPublished: Map<String, Any?>?,
)

/**
 * The in-memory cache of the `GedraConfig` table (issue #615), modeled on [GedraDataCache].
 *
 * ### The two-revision rule lives here, not in the snapshot
 *
 * What matters about a revision class is its **latest** revision and its **latest published** one. The cache
 * keeps every enabled revision and applies that rule at read time ([revisionsOf], over the [GCX.configId]
 * group), rather than holding only two rows per class. That is deliberate: the cache core derives every index
 * from one row at a time and evicts a row only as a disabled tombstone, so keeping two-per-class *inside* the
 * snapshot would mean a new published row evicting a live sibling -- a change to a shared subsystem, for one
 * consumer, that its cursors are not built for. The table is tiny (configs times revisions), so holding the
 * older rows costs nothing, and the policy is still in exactly one place: [latestRevisionRow] and
 * [latestPublishedRow], which the boot loader (#614) reduces with too, so the loader and the cache cannot
 * disagree about what "the current config" is even though the loader reads the table directly (it runs
 * before the cache service exists).
 *
 * ### Not scoped
 *
 * A config row is a client's, never a person's or an organization's, so none of the ownership/scope machinery
 * the data cache carries applies. The reads are index-key lookups by construction -- a class id embeds its
 * client and the listing keys on the client column -- so no scope predicate is ever composed in memory.
 *
 * The payload is the **raw stored row**, as the data cache's is: extracting a [GedraConfigRow] needs a
 * `GedraService` the cache has no business holding, and extracting per read is the defensive copy.
 */
object GedraConfigCache {
    fun params(): SqlCacheParams<Map<String, Any?>> = SqlCacheParams(
        topic = gedraConfigTopic,
        tableName = GCT.gedraConfig,
        extract = { _, data -> data },
        indexes = listOf(
            SqlCacheIndex(GCX.configId) { it[GC.configId].toOptStr() },
            SqlCacheIndex(GCX.client) { it[PF.client].toOptStr() },
        ),
    )

    /** Registers the cache with the running service, or returns null when there is none. */
    fun register(cxt: KdrCxt): SqlTableCache<Map<String, Any?>>? = SqlTableCacheService.registerCache(cxt, params())

    /**
     * The two revisions that matter for the class [configId] names, from the cache, or null when the cache holds
     * no enabled revision of it. Disabled rows are absent from the index, so a soft-deleted revision never wins.
     */
    fun revisionsOf(cache: SqlTableCache<Map<String, Any?>>, configId: String): ConfigRevisions? =
        revisionsOf(cache.snapshot.allByIndex(GCX.configId, configId).map { it.value })

    /** Every cached revision row of every config [client] owns, in cache load order. */
    fun rowsForClient(cache: SqlTableCache<Map<String, Any?>>, client: String): List<Map<String, Any?>> =
        cache.snapshot.allByIndex(GCX.client, client).map { it.value }

    /** [ConfigRevisions] over a class's rows, or null when there are none; the one place the pair is assembled. */
    fun revisionsOf(rows: List<Map<String, Any?>>): ConfigRevisions? {
        val latest = latestRevisionRow(rows) ?: return null
        return ConfigRevisions(latest, latestPublishedRow(rows))
    }
}

/**
 * The **latest** revision among [rows] -- the highest [GC.version] -- or null when there are none (issue
 * #615). The version column is read rather than the id parsed because it is written *from* the id and so
 * agrees with it by construction (see [GC.configId]). The one rule for "which revision is current", shared by
 * the cache and the boot loader.
 */
fun latestRevisionRow(rows: List<Map<String, Any?>>): Map<String, Any?>? =
    rows.maxByOrNull { it[GC.version].toOptLong() ?: Long.MIN_VALUE }

/** The latest **published** revision among [rows] -- the highest version carrying a [GC.publishedAt] -- or null. */
fun latestPublishedRow(rows: List<Map<String, Any?>>): Map<String, Any?>? =
    latestRevisionRow(rows.filter { it[GC.publishedAt].toOptInstant() != null })
