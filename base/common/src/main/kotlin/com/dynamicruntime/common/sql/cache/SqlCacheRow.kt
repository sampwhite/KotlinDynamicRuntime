package com.dynamicruntime.common.sql.cache

import com.dynamicruntime.common.sql.PF
import kotlin.time.Instant

/**
 * One cached row: the caller's extracted [value] plus the bookkeeping [SqlTableCache] needs to keep it fresh
 * and replayable.
 *
 * The bookkeeping is a wrapper rather than an interface the payload implements, because [id], [updatedAt] and
 * [enabled] are derivable from any KDR table without the payload's cooperation: the id is the table's
 * declared primary key, and the other two are protocol columns present on every table ([PF.updatedAt],
 * [PF.enabled]). So a cached type implements nothing -- any existing class can be cached unmodified -- and
 * the framework cannot be handed an entity that reports its own dates inconsistently with the row it came
 * from.
 */
class SqlCacheRow<T : Any>(
    /**
     * Load-order counter, assigned by the cache and strictly increasing across the life of the process. A
     * *re-loaded* row gets a **new** counter and its previous entry is dropped, so a consumer walking counters
     * forward ([SqlCacheCursor]) sees each row's latest state once rather than its whole history.
     */
    val counter: Long,
    /** The row's primary-key value(s), rendered by [SqlTableCache.idOf]. */
    val id: String,
    /** The row's [PF.updatedAt]; what the incremental reload query walks forward through. */
    val updatedAt: Instant,
    /**
     * The row's [PF.enabled]. A disabled row is excluded from every lookup on [SqlCacheSnapshot] -- it is a
     * soft delete, and reads treat it as absent -- but is kept in [SqlCacheSnapshot.ordered] so a cursor
     * learns that the row went away rather than merely never hearing about it again.
     */
    val enabled: Boolean,
    /** What the cache's `extract` produced from the stored row. */
    val value: T,
)
