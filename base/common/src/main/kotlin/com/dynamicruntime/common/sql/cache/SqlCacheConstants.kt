package com.dynamicruntime.common.sql.cache

import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef

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
 * configure it ([disabledEnv] and [minRecheckMsEnv], each an `EnvVarDef` carrying its own documentation).
 */
@Suppress("ConstPropertyName")
object TCH {
    /**
     * The maximum expected difference, in milliseconds, between a row being stamped with its `updatedAt` and
     * that row becoming visible to a query issued by another thread or node. The reload query starts this far
     * *behind* the point it previously reached, so a row committed just after the last pass is not skipped.
     *
     * It is a **budget with two components plus margin**: the clock skew between the stamping node and the
     * reading node (~200ms is the deployment assumption; AWS time sync typically holds well under that), and
     * the time between the stamp being applied and the transaction becoming visible (~50ms typically -- but a
     * slow statement or a GC pause between stamp and commit has no hard bound). The margin matters because a
     * row that falls outside the budget on a busy table is not late, it is **missed until its next genuine
     * write**: the query start never retreats past the rows already seen. Overshooting merely re-reads the
     * extra window's rows each pass, so the trade is priced steeply toward not missing.
     */
    const val expectedMaxDriftMs = 500L

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
     * that does not participate in the state table -- a migration script, a DBA), and for a change whose
     * request-end announcement failed.
     *
     * It is deliberately a **backstop, not the promise**. Every write made through the application announces
     * itself (the write listener sits at the one place all statements pass through), and an announced change
     * is picked up within [stateReadThrottleMs] of the reader's next request -- none of that waits on this
     * floor. What the floor buys is a periodic reload query per cache per node even when the state row says
     * nothing changed, so it is sized in tens of seconds: tight enough that an out-of-band edit is a
     * coffee-sip late, loose enough that the paranoia query stays rare.
     */
    const val defaultMinRecheckMs = 30_000L

    /**
     * How often, at most, one node reads the shared state row -- node-global, not per context. Without a
     * throttle, every request's first cached lookup queried the row, which merely traded the query the cache
     * saved for a different one. Announced changes reach other nodes within this window plus their next
     * request -- this throttle, not the [defaultMinRecheckMs] backstop, is what sets cross-node promptness;
     * local writes are unaffected (they mark the cache directly).
     */
    const val stateReadThrottleMs = 250L

    /**
     * How far the reload point may legitimately sit ahead of the date a caller is asking about before the
     * clock, rather than the data, is taken to be the explanation. Beyond this the cache resets its reload
     * point backwards and logs it, rather than sitting out every future refresh.
     */
    const val timeJumpToleranceMs = 60_000L

    /**
     * The **default** ceiling above which a single load is logged as a warning: a cache is meant for a table
     * that fits comfortably in memory, and the warning is how an unsuitable one announces itself. It is the
     * fallback for [SqlCacheParams.largeLoadWarning], which a cache raises when it genuinely expects more --
     * so this number sizes the caches that say nothing, and each cache that knows better carries its own.
     *
     * The value was chosen for `AuthUsers`. A cache whose expected maximum is near or above it should not
     * inherit it: sitting a real ceiling exactly on the warning line makes a healthy at-capacity table warn on
     * every load, which trains the warning to be ignored.
     */
    const val defaultLargeLoadWarning = 50_000

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

    val disabledEnv = EnvVarDef(
        "KDR_TABLE_CACHE_DISABLED", group = ENVGRP.caching, defaultDoc = "off (caches on)",
        description = "Turns every registered table cache off; reads fall back to their SQL path.",
    )

    val minRecheckMsEnv = EnvVarDef(
        "KDR_TABLE_CACHE_MIN_RECHECK_MS", group = ENVGRP.caching, defaultDoc = "$defaultMinRecheckMs",
        description = "Overrides the backstop interval (ms) at which a cache reconsiders changes made outside " +
            "the application, which nothing announces. Cross-node promptness for an announced change is set by " +
            "the state-read throttle, not this floor.",
    )
}
