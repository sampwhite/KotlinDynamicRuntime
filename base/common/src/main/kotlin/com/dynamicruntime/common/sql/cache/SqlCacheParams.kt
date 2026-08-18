package com.dynamicruntime.common.sql.cache

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.startup.PRI

/**
 * A secondary index over a cache, computed in memory from the extracted value.
 *
 * One named, repeatable declaration rather than a fixed set of collations plus purpose-built maps: a cache
 * declares as many indexes as it has lookups, and each is named by what it is rather than by its position.
 * [keyOf] returning null means *do not index this row*, which is how a row with no meaningful key for one
 * index stays out of it while remaining in the others.
 *
 * A [unique] index maps a key to at most one row and is the substitute for a unique-index SQL lookup; a
 * non-unique one collects every row sharing a key, in cache load order.
 */
class SqlCacheIndex<T : Any>(
    /** Name the index is looked up by (see [SqlCacheSnapshot.byIndex] / [SqlCacheSnapshot.allByIndex]). */
    val name: String,
    /** Whether a key maps to one row (a lookup) or to every row sharing it. */
    val unique: Boolean = false,
    /** The row's key for this index, or null to leave the row out of it. */
    val keyOf: (T) -> String?,
)

/**
 * Everything needed to cache one table: which table, how to turn a stored row into the payload type, and
 * which secondary indexes to maintain over it.
 *
 * The extractor may return null to *skip* a row, and may keep as much or as little of the stored map as it
 * wants -- the framework does not retain the raw row, so what is dropped is genuinely dropped.
 */
class SqlCacheParams<T : Any>(
    /** The SQL topic the table belongs to. */
    val topic: String,
    /** The table name, as declared in the schema store. Also the cache's identity in the registry. */
    val tableName: String,
    /** Turns a stored row into the payload, or returns null to skip it. */
    val extract: (KdrCxt, Map<String, Any?>) -> T?,
    /** Secondary indexes to maintain; empty for id-only lookups. */
    val indexes: List<SqlCacheIndex<T>> = emptyList(),
    /** Refresh order across caches, lower first -- for a cache whose consumers depend on another being current. */
    val priority: Int = PRI.standard,
)
