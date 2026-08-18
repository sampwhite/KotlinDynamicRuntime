package com.dynamicruntime.common.sql.cache

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException

/**
 * A consumer's position in a cache's change stream.
 *
 * A cache holds the *current* state of every row, which is what a lookup wants. A consumer maintaining its
 * own derived structure -- a projection, a denormalized index, an outbound feed -- wants the *changes*
 * instead, and wants them exactly once. That is what this is: it remembers the counter it has consumed
 * through, and each call hands back what has happened since.
 *
 * Rows arrive in load order and include disabled tombstones, so a consumer sees a removal as a row with
 * [SqlCacheRow.enabled] false rather than by noticing an absence. That is what a derived structure needs:
 * keep a map, add on an enabled row, remove on a disabled one.
 *
 * A cursor walks the whole cache by default, or **one key of a non-unique index** when given [indexName] and
 * [indexKey] -- for a consumer that only cares about part of the table. A row leaving the key arrives as a
 * disabled copy, so the same add/remove handling covers departures too.
 *
 * **Not thread safe.** One cursor belongs to one consumer; the cache behind it is shared and thread safe,
 * the position is not.
 */
class SqlCacheCursor<T : Any>(
    val cache: SqlTableCache<T>,
    /**
     * A *non-unique* index to confine this cursor to, or null to walk the whole cache. Given together with
     * [indexKey].
     *
     * Confining here is not the same as filtering the whole stream yourself: a row that *leaves* the key
     * arrives as a disabled copy, whereas a filter would simply stop matching it and the consumer would hold
     * it forever. See [SqlCacheSnapshot.multiStreams].
     */
    val indexName: String? = null,
    /** The key within [indexName]. Required when [indexName] is given, meaningless without it. */
    val indexKey: String? = null,
) {
    init {
        // The two are one setting in two parts, so half of it is always a mistake -- and the mistake would
        // silently widen the cursor to the whole table, handing a consumer rows that are not its business.
        if ((indexName == null) != (indexKey == null)) {
            throw KdrException(
                "A key-scoped cursor needs both an index name and a key; got indexName=$indexName, " +
                    "indexKey=$indexKey.",
            )
        }
    }

    /** The highest counter handed out so far. Zero means "nothing consumed yet", i.e. the next read is a
     *  full load of everything this cursor's scope holds. */
    var lastCounter: Long = 0

    /**
     * Refreshes the cache and returns everything changed since the last call, advancing the position past
     * what is returned. Returns an empty list when nothing has changed.
     *
     * The position advances as the changes are handed over, so a consumer that throws part-way through
     * processing them will not see them again. Variants that defer the advance until the consumer succeeds,
     * or that skip the refresh when draining several cursors at once, are easy to add and deliberately absent
     * until something needs them -- three near-identical advance paths is how the advance rule comes to differ
     * between them.
     */
    fun nextChanges(cxt: KdrCxt): List<SqlCacheRow<T>> {
        cache.checkRefresh(cxt)
        val snapshot = cache.snapshot // one read, so scope and advance describe the same view
        val changes = if (indexName != null && indexKey != null) {
            snapshot.indexChangesSince(indexName, indexKey, lastCounter)
        } else {
            snapshot.changesSince(lastCounter)
        }
        if (changes.isNotEmpty()) {
            lastCounter = changes.last().counter
        }
        return changes
    }
}
