package com.dynamicruntime.common.sql.cache

import com.dynamicruntime.common.context.KdrCxt

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
 * **Not thread safe.** One cursor belongs to one consumer; the cache behind it is shared and thread safe,
 * the position is not.
 */
class SqlCacheCursor<T : Any>(val cache: SqlTableCache<T>) {
    /** The highest counter handed out so far. Zero means "nothing consumed yet", i.e. the next read is a
     *  full load of everything the cache holds. */
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
        val changes = cache.snapshot.changesSince(lastCounter)
        if (changes.isNotEmpty()) {
            lastCounter = changes.last().counter
        }
        return changes
    }
}
