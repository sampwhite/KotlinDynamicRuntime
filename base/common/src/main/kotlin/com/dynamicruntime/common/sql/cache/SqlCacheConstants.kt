package com.dynamicruntime.common.sql.cache

/**
 * The SQL topic holding the cross-node cache-state row. Its own topic (rather than sharing one with a
 * cached table's) because every cache writes to it: a topic is a grouping of tables, and this one belongs to
 * the caching machinery, not to whichever subsystem happens to have adopted a cache first.
 */
const val cacheTopic = "cache"

/** `KdrCacheState` table and column names. Each name matches its value. */
@Suppress("ConstPropertyName")
object CST {
    /** The table recording, per cached table, when it was last changed by any node. */
    const val kdrCacheState = "KdrCacheState"

    /** Primary key: the id of a *collection* of caches (there is one collection today). */
    const val cacheId = "cacheId"

    /** Map of cached table name -> the last-changed date, formatted by `Instant.formatDate`. */
    const val cacheState = "cacheState"

    /** The single collection every cache registers into. */
    const val defaultCacheId = "StdTableCaches"
}

/**
 * Tuning constants and lookup keys for the table-cache subsystem, plus the two environment variables that
 * configure it (documented in `environment-variables.md`).
 */
@Suppress("ConstPropertyName")
object TCH {
    /**
     * The maximum expected difference, in milliseconds, between a row being stamped with its `updatedAt` and
     * that row becoming visible to a query issued by another thread or node. The reload query starts this far
     * *behind* the point it previously reached, so a row committed just after the last pass is not skipped.
     * Smaller means less needless re-reading; larger means less chance of missing a row when the database is
     * under pressure.
     */
    const val expectedMaxDriftMs = 200L

    /**
     * The floor the reload query never starts later than: two minutes behind now. This covers a node whose
     * clock is behind the one that wrote the row -- during a blue/green switch, say -- where a query starting
     * from this node's idea of "just now" would silently step over a row the other node stamped in what this
     * node considers the future.
     */
    const val potentialLagMs = 2L * 60 * 1000

    /**
     * Default for [minRecheckMsEnv]: how far back a cache is asked to reconsider when the state row reports no
     * change at all. It bounds staleness for a change this node never hears about (a row written by a process
     * that does not participate in the state table -- a migration script, a DBA).
     */
    const val defaultMinRecheckMs = 5_000L

    /**
     * How often, at most, one node reads the shared state row -- node-global, not per context. Without a
     * throttle, every request's first cached lookup queried the row, which merely traded the query the cache
     * saved for a different one. Cross-node pickup waits at most this long extra, well inside the
     * [defaultMinRecheckMs] promise; local writes are unaffected (they mark the cache directly).
     */
    const val stateReadThrottleMs = 250L

    /**
     * How far the reload point may legitimately sit ahead of the date a caller is asking about before the
     * clock, rather than the data, is taken to be the explanation. Beyond this the cache resets its reload
     * point backwards and logs it, rather than sitting out every future refresh.
     */
    const val timeJumpToleranceMs = 60_000L

    /** A single load returning more rows than this is logged as a warning; a cache is meant for a table that
     *  fits comfortably in memory, and the warning is how an unsuitable one announces itself. */
    const val largeLoadWarning = 50_000

    /**
     * Separator joining a multi-column primary key into a [SqlCacheRow] id. A NUL is used because no column
     * value coming back from the database can contain one, so two different keys cannot collide by carrying
     * the separator inside themselves.
     */
    const val idSeparator = "\u0000"

    /** [com.dynamicruntime.common.context.KdrCxt.locals] key holding the context's refresh memo (a
     *  `SqlTableCacheService.RefreshMemo`: the change generation and time it refreshed at), so repeated reads
     *  in one context cost nothing while the world stands still. */
    const val refreshedKey = "kdrTableCachesRefreshed"

    /** [com.dynamicruntime.common.context.KdrCxt.locals] key holding the change monitor for the current
     *  request (or [SqlTableCacheService.withMonitoring] block). */
    const val monitorKey = "kdrTableCacheMonitor"

    /** Turns every registered table cache off; reads fall back to their SQL path. */
    const val disabledEnv = "KDR_TABLE_CACHE_DISABLED"

    /** Overrides [defaultMinRecheckMs]. */
    const val minRecheckMsEnv = "KDR_TABLE_CACHE_MIN_RECHECK_MS"
}
