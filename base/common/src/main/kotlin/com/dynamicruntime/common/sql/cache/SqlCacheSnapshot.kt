package com.dynamicruntime.common.sql.cache

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
 * **Disabled rows are absent from every lookup** ([byId], [byIndex], [group]): the soft-delete flag means the
 * row is not there, so a cache lookup answers the way `SqlDatabase.queryOneEnabled` does. They remain in
 * [ordered] as tombstones, which is how a [SqlCacheCursor] finds out a row was removed rather than simply
 * never hearing about it again.
 */
class SqlCacheSnapshot<T : Any>(
    /** Enabled rows by [SqlCacheRow.id]. */
    val byId: Map<String, SqlCacheRow<T>>,
    /** Unique secondary indexes, by index name then key. Enabled rows only. */
    val uniqueIndexes: Map<String, Map<String, SqlCacheRow<T>>>,
    /** Grouped secondary indexes, by index name then key; each group is in load order. Enabled rows only. */
    val groupIndexes: Map<String, Map<String, List<SqlCacheRow<T>>>>,
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
     * The enabled rows a *grouped* index files under [key], in load order; empty when there are none. Throws
     * when [indexName] is not a declared grouped index, for the reason [byIndex] does.
     */
    fun group(indexName: String, key: String): List<SqlCacheRow<T>> =
        (groupIndexes[indexName] ?: throw KdrException("No grouped cache index named '$indexName'."))[key]
            ?: emptyList()

    /**
     * Every row whose counter is greater than [counter], disabled tombstones included -- the change stream a
     * [SqlCacheCursor] walks. [ordered] is sorted by counter, so this is a binary search plus a slice rather
     * than a scan of the whole cache.
     */
    fun changesSince(counter: Long): List<SqlCacheRow<T>> {
        if (counter >= highCounter) return emptyList()
        if (counter <= 0) return ordered
        // Locate the first entry past `counter`. binarySearch returns -(insertionPoint) - 1 on a miss; a hit
        // is impossible to rely on being the *last* equal element in general, but counters are unique here.
        val found = ordered.binarySearch { it.counter.compareTo(counter) }
        val from = if (found >= 0) found + 1 else -(found + 1)
        return if (from >= ordered.size) emptyList() else ordered.subList(from, ordered.size)
    }

    companion object {
        /** The starting (empty) snapshot, before a cache has loaded anything. */
        fun <T : Any> empty(indexes: List<SqlCacheIndex<T>> = emptyList()): SqlCacheSnapshot<T> = SqlCacheSnapshot(
            byId = emptyMap(),
            uniqueIndexes = indexes.filter { it.unique }.associate { it.name to emptyMap() },
            groupIndexes = indexes.filter { !it.unique }.associate { it.name to emptyMap() },
            ordered = emptyList(),
        )
    }
}
