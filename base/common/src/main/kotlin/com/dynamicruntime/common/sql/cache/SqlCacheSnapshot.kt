package com.dynamicruntime.common.sql.cache

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.KdrException

/**
 * An immutable read view of a cache at one moment. [SqlTableCache] publishes a fresh one after any reload
 * that actually changed something, and readers take it from a `@Volatile` field, so **a read costs no lock at
 * all** -- it is a field read plus a hash lookup.
 *
 * The obvious alternative -- a read lock taken per lookup -- puts the cost on the reads, which is the wrong
 * side: a cache exists because the reads are frequent. Publishing an immutable view instead costs a map
 * rebuild per *reload*, bounded by how often the table changes rather than by how often it is read, and a
 * reload that finds no new rows rebuilds nothing.
 *
 * **Disabled rows are absent from every lookup** ([byId], [byIndex], [allByIndex]): the soft-delete flag means the
 * row is not there, so a cache lookup answers the way `SqlDatabase.queryOneEnabled` does. They remain in
 * [ordered] as tombstones, which is how a [SqlCacheCursor] finds out a row was removed rather than simply
 * never hearing about it again.
 */
class SqlCacheSnapshot<T : Any>(
    /** Enabled rows by [SqlCacheRow.id]. */
    val byId: Map<String, SqlCacheRow<T>>,
    /** Unique secondary indexes, by index name then key. Enabled rows only. */
    val uniqueIndexes: Map<String, Map<String, SqlCacheRow<T>>>,
    /** Non-unique secondary indexes, by index name then key; each key's rows are in load order. Enabled rows only. */
    val multiIndexes: Map<String, Map<String, List<SqlCacheRow<T>>>>,
    /**
     * Per-key **change streams**, by index name then key: the same rows as [multiIndexes], in the same
     * order, but with the tombstones a cursor needs (see [indexChangesSince]).
     *
     * It is a separate structure from [multiIndexes] rather than a filter over it, because it holds something
     * the current membership cannot: a row that *left* the key. Filtering [ordered] by index key could
     * never produce that row for the key it departed -- [ordered] carries only each row's latest state, so
     * a row that moved from one key to another looks, under its old key, exactly like a row that was never
     * there.
     */
    val multiStreams: Map<String, Map<String, List<SqlCacheRow<T>>>>,
    /**
     * Every row the cache holds, disabled ones included, strictly ascending by [SqlCacheRow.counter]. A row
     * appears once, at its latest counter.
     */
    val ordered: List<SqlCacheRow<T>>,
) {
    /** The highest counter in this snapshot; 0 when it is empty. */
    val highCounter: Long = ordered.lastOrNull()?.counter ?: 0L

    /** The number of live (enabled) rows. */
    val size: Int get() = byId.size

    /** The enabled row with this id, or null. */
    fun get(id: String): SqlCacheRow<T>? = byId[id]

    /**
     * The enabled row a *unique* index maps [key] to, or null. Throws when [indexName] is not a declared
     * unique index -- a typo there would otherwise read as "no such row", which is the answer a lookup is
     * least able to question.
     */
    fun byIndex(indexName: String, key: String): SqlCacheRow<T>? =
        (uniqueIndexes[indexName] ?: throw KdrException("No unique cache index named '$indexName'."))[key]

    /**
     * The enabled rows a *non-unique* index files under [key], in load order; empty when there are none. Throws
     * when [indexName] is not a declared non-unique index, for the reason [byIndex] does.
     */
    fun allByIndex(indexName: String, key: String): List<SqlCacheRow<T>> =
        (multiIndexes[indexName] ?: throw KdrException("No non-unique cache index named '$indexName'."))[key]
            ?: emptyList()

    /**
     * Every row whose counter is greater than [counter], disabled tombstones included -- the change stream a
     * whole-cache [SqlCacheCursor] walks.
     */
    fun changesSince(counter: Long): List<SqlCacheRow<T>> = sliceAfter(ordered, counter)

    /**
     * The same, confined to one key of a *non-unique* index -- what a key-scoped [SqlCacheCursor] walks.
     * Empty when the key has never held a row; throws when [indexName] is not a declared non-unique index,
     * for the reason [byIndex] does.
     *
     * A row that **left** this key appears here as a disabled copy, so a consumer maintaining its own
     * structure removes it rather than holding it forever: to a cursor an absence is invisible, and a
     * departure and a soft delete want the same handling anyway.
     */
    fun indexChangesSince(indexName: String, key: String, counter: Long): List<SqlCacheRow<T>> {
        val streams = multiStreams[indexName]
            ?: throw KdrException("No non-unique cache index named '$indexName'.")
        return sliceAfter(streams[key] ?: return emptyList(), counter)
    }

    /**
     * The rows of [rows] past [counter]. Every list here is sorted by counter and holds each counter once, so
     * this is a binary search plus a view rather than a scan. binarySearch returns `-(insertionPoint) - 1` on
     * a miss.
     */
    @KdrPrivate
    fun sliceAfter(rows: List<SqlCacheRow<T>>, counter: Long): List<SqlCacheRow<T>> {
        if (rows.isEmpty() || counter >= rows.last().counter) return emptyList()
        if (counter <= 0) return rows
        val found = rows.binarySearch { it.counter.compareTo(counter) }
        val from = if (found >= 0) found + 1 else -(found + 1)
        return if (from >= rows.size) emptyList() else rows.subList(from, rows.size)
    }

    companion object {
        /** The starting (empty) snapshot, before a cache has loaded anything. */
        fun <T : Any> empty(indexes: List<SqlCacheIndex<T>> = emptyList()): SqlCacheSnapshot<T> = SqlCacheSnapshot(
            byId = emptyMap(),
            uniqueIndexes = indexes.filter { it.unique }.associate { it.name to emptyMap() },
            multiIndexes = indexes.filter { !it.unique }.associate { it.name to emptyMap() },
            multiStreams = indexes.filter { !it.unique }.associate { it.name to emptyMap() },
            ordered = emptyList(),
        )
    }
}
