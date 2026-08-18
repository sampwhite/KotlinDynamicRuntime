package com.dynamicruntime.common.sql.cache

import com.dynamicruntime.common.util.formatDate
import kotlin.time.Instant

/**
 * Why the next cached read on a context would, or would not, sweep the caches.
 *
 * An enum rather than string constants because this *is* a closed operational set: the entries are exactly
 * the branches [SqlTableCacheService.refreshNeed] decides between, and a seventh reason would be a change to
 * that decision rather than a new value in a model.
 */
@Suppress("EnumEntryName")
enum class SqlCacheRefreshNeed {
    /** Swept already, nothing has changed since, and the memo has not aged out: the next read costs nothing. */
    current,

    /** This context has not read a cache yet, so it holds no memo and the next read sweeps. */
    neverRefreshed,

    /**
     * A write to some cached table landed on this node after this context swept
     * ([SqlTableCacheService.changeGeneration] moved). The commonest reason during an edit-and-check loop --
     * and the one that makes read-your-writes work.
     */
    changed,

    /**
     * A cache carries a write it has not reloaded yet ([SqlTableCache.forceReload]). Normally momentary; it
     * persists while a transaction is open, since a reload inside one would read uncommitted rows into a
     * snapshot every thread can see.
     */
    reloadPending,

    /**
     * The memo is older than [SqlTableCacheService.minRecheckMs]. Expected on a long-lived context (a script,
     * a background job) rather than on a request.
     */
    aged,

    /** Caching is switched off ([TCH.disabledEnv]), so nothing ever sweeps and every read goes to SQL. */
    disabled;

    /** Whether the caches are current: the next cached read sweeps nothing. */
    val isCurrent: Boolean get() = this == current

    /**
     * Whether the next cached read will sweep. Deliberately **not** the negation of [isCurrent]: with caching
     * switched off neither holds, because nothing is refreshed and nothing ever will be. A caller waiting for
     * freshness that read `disabled` as "needs refresh" would wait forever.
     */
    val needsRefresh: Boolean get() = this != current && this != disabled
}

/**
 * What a context's refresh memo says right now: whether the caches on this node have been swept for it, and
 * if not, why the next cached read will sweep them. Produced by [SqlTableCacheService.refreshState], and a
 * plain snapshot -- reading it changes nothing and refreshes nothing.
 *
 * **It answers the question the memo was already holding the answer to.** Every cached read goes through
 * [SqlTableCacheService.getAndRefresh], which decides from the memo in
 * [com.dynamicruntime.common.context.KdrCxt.locals] whether a sweep is due -- a decision otherwise reachable
 * only by triggering it. The intended use is a quick edit or a test: write a row, ask whether the next read
 * will pick it up, and get a named [SqlCacheRefreshNeed] rather than an inference from a row count that may
 * be right for the wrong reason.
 *
 * The same predicate drives both -- `getAndRefresh` branches on [SqlCacheRefreshNeed.isCurrent], reached
 * through the same [SqlTableCacheService.refreshNeed] -- so this cannot report one thing while the reader
 * does another.
 */
class SqlCacheRefreshState(
    /** Why the next read would sweep, or [SqlCacheRefreshNeed.current] when it would not. */
    val need: SqlCacheRefreshNeed,
    /** When this context last swept, or null if it never has. */
    val refreshedAt: Instant?,
    /** The node-wide change generation *now*; the memo's own is [refreshedAtGeneration]. */
    val generation: Long,
    /** The generation this context swept at, or null if it never has. A gap means a write landed since. */
    val refreshedAtGeneration: Long?,
    /** Cached tables this node has written and not yet reloaded, sorted. Empty in the ordinary case. */
    val pendingTables: List<String>,
) {
    /** Whether the caches are current for this context: the next cached read sweeps nothing. */
    val isRefreshed: Boolean get() = need.isCurrent

    /** Whether the next cached read on this context will sweep; see [SqlCacheRefreshNeed.needsRefresh]. */
    val needsRefresh: Boolean get() = need.needsRefresh

    /** One line, for a log or a failed assertion: the reason first, since it is what is being asked. */
    override fun toString(): String {
        val at = refreshedAt?.formatDate() ?: "never"
        val pending = if (pendingTables.isEmpty()) "" else ", pending=$pendingTables"
        return "SqlCacheRefreshState(need=$need, refreshedAt=$at, generation=$generation" +
            "${refreshedAtGeneration?.let { " (swept at $it)" } ?: ""}$pending)"
    }
}
