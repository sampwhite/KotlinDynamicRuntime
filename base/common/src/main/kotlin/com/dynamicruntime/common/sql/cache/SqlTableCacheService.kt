package com.dynamicruntime.common.sql.cache

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.LogSql
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlWriteListener
import com.dynamicruntime.common.sql.tableModule
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.formatDate
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** Attribute keys of the `/operator/cache/state` report. Each name matches its value. */
@Suppress("ConstPropertyName")
object TCS {
    const val isDisabled = "isDisabled"
    const val minRecheckMs = "minRecheckMs"
    const val caches = "caches"
    const val sharedState = "sharedState"
}

/**
 * Owns the registered [SqlTableCache]s and the coherence between nodes.
 *
 * **The problem it solves.** A cache reloads by asking for rows changed since it last looked, which means a
 * node only finds out about a change by *going to look*. Left alone, that is a poll, and the interval is a
 * straight trade between staleness and load. So instead every node records, in one shared row
 * ([CST.kdrCacheState]), the moment it changed a cached table, and every node reads that row once per unit of
 * work. A table nobody has touched costs one row read and no table queries at all; a table somebody has just
 * touched is reloaded by every node at its next read.
 *
 * **Three things keep a cache current**, in descending order of promptness:
 *
 *  1. *This node wrote it.* [noteTableChanged] marks the cache directly, so a read later in the same request
 *     sees the write. No round trip, and nothing for the caller to remember (see [SqlTableCache.forceReload]).
 *  2. *Another node wrote it.* Its request end wrote the date into the state row; ours reads that row and
 *     asks the cache to reconsider from it.
 *  3. *Nobody said.* A migration script or a DBA writes rows without going through any of this. The
 *     [minRecheckMs] floor means every cache reconsiders the last few seconds regardless, so such a change is
 *     late rather than lost.
 */
class SqlTableCacheService : ServiceInitializer {
    override val serviceName: String = SqlTableCacheService.serviceName

    /** Registered caches by table name -- one cache per table. */
    val caches: MutableMap<String, SqlTableCache<*>> = ConcurrentHashMap()

    /** Memo of [caches] in refresh order; cleared by a registration and rebuilt by [getSortedCaches]. */
    @Volatile
    var sortedCachesMemo: List<SqlTableCache<*>>? = null

    /** Whether caching is off entirely ([TCH.disabledEnv]); every lookup then misses and falls back to SQL. */
    var isDisabled: Boolean = false

    /** How far back a cache reconsiders when the state row reports nothing ([TCH.minRecheckMsEnv]). */
    var minRecheckMs: Long = TCH.defaultMinRecheckMs

    /**
     * Bumped by every local write to a cached table ([noteTableChanged]). The per-context refresh memo
     * ([getAndRefresh]) records the generation it refreshed at and re-refreshes when it has moved -- so a
     * write is seen by **every** context on this node, including a parent whose sub-context did the writing.
     * The earlier design invalidated by removing a `cxt.locals` key, which only ever reached the writing
     * context's own map: `mkSubContext` copies `locals`, so the parent's memo survived and served the
     * pre-write row for the rest of the request.
     */
    val changeGeneration = AtomicLong(0)

    /** When this node last read the shared state row (epoch ms); see the throttle in [checkRefresh]. */
    @Volatile
    var lastStateReadMs: Long = 0

    /**
     * How this service hears about a write: it **subscribes** to the data layer rather than being called by
     * it (see [SqlWriteListener]). A stable instance so [SqlTopicService.addWriteListener]'s identity check
     * makes a repeated `checkInit` harmless.
     */
    val writeListener = SqlWriteListener { c, tableNames ->
        for (tableName in tableNames) {
            noteTableChanged(c, tableName)
        }
    }

    override fun checkInit(cxt: KdrCxt) {
        isDisabled = cxt.getEnvBool(TCH.disabledEnv) ?: false
        // toOptLong (not toLongOrNull): a malformed value THROWS, per the house rule for numeric env vars
        // (KDR_PORT does the same). This variable is sold as the 3am staleness lever, and a silent fall-back
        // to the default on a typo ("5s", "5_000") is worst exactly there -- the operator changes the value,
        // nothing changes, and nothing says why.
        minRecheckMs = cxt.getEnvVar(TCH.minRecheckMsEnv).toOptLong() ?: TCH.defaultMinRecheckMs
        if (isDisabled) {
            LogSql.info(cxt, "Table caches are disabled by ${TCH.disabledEnv}; reads fall back to SQL.")
        }
        // SqlTopicService is a startup service, so it is fully initialized before this regular one runs.
        SqlTopicService.get(cxt)?.addWriteListener(writeListener)
    }

    /**
     * Loads every registered cache. Registration happens during the `checkInit` pass (each owning service
     * registers its own), and every service's `checkInit` runs before any `checkReady`, so by here the set is
     * complete. Loading now puts the cost of the first, full load on startup rather than on whichever request
     * arrives first.
     */
    override fun checkReady(cxt: KdrCxt) {
        checkRefresh(cxt)
    }

    // --- registration -------------------------------------------------------

    /**
     * Registers (or returns the already-registered) cache for [params]'s table. Startup only: a cache
     * registered after [checkReady] misses the initial load and refreshes for the first time on its first
     * read, which works but pays for it in a request.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> register(params: SqlCacheParams<T>): SqlTableCache<T> {
        synchronized(caches) {
            // One cache per table, so an existing entry is this same cache -- registration is idempotent
            // rather than a second, divergent copy of the same rows.
            caches[params.tableName]?.let { return it as SqlTableCache<T> }
            val cache = SqlTableCache(params)
            cache.isDetached = false // this service drives its refresh from here on
            caches[params.tableName] = cache
            sortedCachesMemo = null
            return cache
        }
    }

    /** The registered caches in refresh order (ascending [SqlCacheParams.priority]). */
    fun getSortedCaches(): List<SqlTableCache<*>> {
        sortedCachesMemo?.let { return it }
        synchronized(caches) {
            sortedCachesMemo?.let { return it }
            return caches.values.sortedBy { it.params.priority }.also { sortedCachesMemo = it }
        }
    }

    // --- refreshing ---------------------------------------------------------

    /**
     * Reads the shared state row and asks every cache to reconsider from what it says. A table the row
     * reports as unchanged is still reconsidered back to the [minRecheckMs] floor, so a change made outside
     * this machinery is late rather than invisible.
     *
     * The state read is throttled **node-globally** ([TCH.stateReadThrottleMs]): without the throttle, every
     * request's first cached lookup queried the row, which merely replaced the query the cache saved. Inside
     * the throttle window the caches are still asked to reconsider (a local write's [SqlTableCache.forceReload]
     * bites regardless); only news from *other* nodes waits out the window, which is well inside the
     * [minRecheckMs] promise.
     */
    fun checkRefresh(cxt: KdrCxt) {
        if (isDisabled) return
        val cacheList = getSortedCaches()
        if (cacheList.isEmpty()) return // nothing registered: do not even read the state row
        val nowMs = cxt.instanceNow().toEpochMilliseconds()
        val reported = if (nowMs - lastStateReadMs >= TCH.stateReadThrottleMs) {
            lastStateReadMs = nowMs
            dbQueryState(cxt)
        } else {
            emptyMap()
        }
        val floor = cxt.instanceNow() - minRecheckMs.milliseconds
        for (cache in cacheList) {
            val date = reported[cache.params.tableName]
            val compareDate = if (date != null && date > floor) date else floor
            cache.checkLoadCache(cxt, compareDate)
        }
    }

    // --- change monitoring --------------------------------------------------

    /**
     * The cached tables changed during one request (or [withMonitoring] block). Rarely more than one.
     *
     * The list is a [CopyOnWriteArrayList] because the monitor travels **by reference** into sub-contexts
     * (`mkSubContext` copies `locals` shallowly), whose documented contract requires values to be immutable
     * or thread-safe: a sub-thread may add while the parent's `endRequest` iterates.
     */
    class ChangeMonitor {
        val changedTables: MutableList<String> = CopyOnWriteArrayList()

        /** Adds [tableName] if not already recorded (atomic, so two threads cannot double-add). */
        fun checkAdd(tableName: String) {
            (changedTables as CopyOnWriteArrayList<String>).addIfAbsent(tableName)
        }
    }

    /** Binds a fresh [ChangeMonitor] to [cxt]; called by the dispatcher at the start of a request. */
    fun beginRequest(cxt: KdrCxt) {
        if (isDisabled || caches.isEmpty()) return
        cxt.locals[TCH.monitorKey] = ChangeMonitor()
    }

    /**
     * Publishes whatever the request changed into the shared state row, and unbinds the monitor.
     *
     * **Never throws.** The dispatcher calls this in a `finally`, so a failure here would replace whatever
     * actually went wrong with a database error about a bookkeeping row. And the row is an optimization: all
     * that is lost is promptness -- the other nodes pick the change up at their [minRecheckMs] floor instead.
     */
    fun endRequest(cxt: KdrCxt) {
        val monitor = cxt.locals.remove(TCH.monitorKey) as? ChangeMonitor ?: return
        try {
            publishChanges(cxt, monitor)
        } catch (e: Exception) {
            LogSql.error(
                cxt,
                "Could not publish table-cache changes for ${monitor.changedTables}; other nodes will pick " +
                    "them up within ${minRecheckMs}ms instead.",
                e,
            )
        }
    }

    /**
     * Records that this node just wrote [tableName]. Two separate effects, and both matter:
     *
     *  - the local cache is marked so its next read reloads (read-your-writes on this node, immediately);
     *  - the table is added to the request's monitor so the shared state row is updated at request end
     *    (read-your-writes on every *other* node, at their next read).
     *
     * With no monitor bound -- a background job, a script -- only the first happens, and other nodes hear
     * about it within [minRecheckMs] instead. Wrap such code in [withMonitoring] when that is not good enough.
     *
     * **Nobody calls this by hand.** [writeListener] is subscribed to the data layer, which publishes every
     * write with the tables the statement touched, so a new write path announces itself by existing. It is
     * public for the case that is genuinely outside that path -- a bulk load through raw JDBC, or a test
     * standing in for one.
     *
     * A table with no cache is ignored, so a caller may announce a write unconditionally.
     */
    fun noteTableChanged(cxt: KdrCxt, tableName: String) {
        if (isDisabled) return
        val cache = caches[tableName] ?: return
        cache.markChanged()
        // Advance the node-wide change generation, which is what invalidates every context's refresh memo --
        // including a parent context whose sub-context did the writing (see [changeGeneration]).
        changeGeneration.incrementAndGet()
        (cxt.locals[TCH.monitorKey] as? ChangeMonitor)?.checkAdd(tableName)
    }

    /**
     * Runs [body] with change monitoring bound, publishing what it changed on the way out -- the equivalent of
     * a request, for code that is not one (a script, a test, a background job).
     *
     * Publishing happens only when [body] returns normally: a failed unit of work has no change to announce.
     * A monitor already bound is restored afterward, so nesting is safe.
     */
    fun withMonitoring(cxt: KdrCxt, body: () -> Unit) {
        val existing = cxt.locals[TCH.monitorKey]
        beginRequest(cxt)
        try {
            body()
            endRequest(cxt)
        } finally {
            if (existing != null) cxt.locals[TCH.monitorKey] = existing else cxt.locals.remove(TCH.monitorKey)
        }
    }

    /**
     * Merges the monitor's changed tables into the shared state row. Always written, never pre-filtered: an
     * earlier version skipped the write when this node's clock was not ahead of the row's dates, which meant a
     * node whose clock lagged another's *never announced its own changes* -- the comparison conflated "someone
     * already announced something later" with "my clock is behind". The merge itself dedups (it advances the
     * date on every announcement), so the write is cheap and always says something true: the table changed
     * again.
     */
    fun publishChanges(cxt: KdrCxt, monitor: ChangeMonitor) {
        if (isDisabled || monitor.changedTables.isEmpty()) return
        val now = cxt.instanceNow()
        dbMergeState(cxt, monitor.changedTables.associateWith { now })
    }

    // --- the shared state row -----------------------------------------------

    /** Reads the cache-state row: cached table name -> when it was last changed. Empty when there is no row. */
    fun dbQueryState(cxt: KdrCxt): Map<String, Instant> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, cacheTopic)
        val query = sqlCxt.sqlTopic?.qTranLockQuery ?: return emptyMap()
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) {
            row = sqlCxt.sqlDb.queryOneStatement(cxt, query, mapOf(CST.cacheId to CST.defaultCacheId))
        }
        return readState(row)
    }

    /**
     * Merges [state] into the cache-state row and returns the merged result. A topic transaction, because two
     * nodes publishing at once must not each overwrite the other's tables -- a merge, not a replace, and the
     * row's lock is what makes read-modify-write safe.
     *
     * An announced table's date **always advances**, by at least a millisecond past whatever the row held.
     * Simply keeping the later of the two dates looks equivalent but is not: a node whose clock lags the
     * row's date would have its announcement absorbed without moving anything, and an unmoved date tells the
     * other nodes nothing -- the lagging node's change would wait for their [minRecheckMs] floor. An
     * announcement means "the table changed *again*", which is an ordering fact, not a clock reading.
     */
    fun dbMergeState(cxt: KdrCxt, state: Map<String, Instant>): Map<String, Instant> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, cacheTopic)
        sqlCxt.sqlDb.withSession(cxt) {
            SqlTopicTranProvider.executeTopicTran(
                sqlCxt, "cacheStateMerge", null, mapOf(CST.cacheId to CST.defaultCacheId),
            ) {
                val merged = readState(sqlCxt.tranData).toMutableMap()
                for ((table, date) in state) {
                    val existing = merged[table]
                    merged[table] = if (existing != null && existing >= date) existing + 1.milliseconds else date
                }
                sqlCxt.tranData[CST.cacheState] = merged.mapValues { it.value.formatDate() }
            }
        }
        return readState(sqlCxt.tranData)
    }

    /** Decodes the stored state map, skipping any entry that is not a readable date. */
    fun readState(row: Map<String, Any?>?): Map<String, Instant> {
        val stored = row?.get(CST.cacheState).toJsonMapOrEmpty()
        val result = LinkedHashMap<String, Instant>(stored.size)
        for ((key, value) in stored) {
            // The column is a free-form map, so an entry that is not a date is skipped rather than fatal: a
            // future use of this row for something else must not be able to stop the caches refreshing.
            val date = runCatching { value.toOptInstant() }.getOrNull() ?: continue
            result[key] = date
        }
        return result
    }

    /** Whether any registered cache has a local write pending a reload; consulted by [getAndRefresh]. */
    fun hasPendingReload(): Boolean = caches.values.any { it.forceReload }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "SqlTableCacheService"

        fun get(cxt: KdrCxt): SqlTableCacheService? = cxt.instanceConfig.get(serviceName) as? SqlTableCacheService

        /**
         * Registers a cache with the running service, or returns null when there is none (caching is simply
         * absent then, and every consumer's SQL fallback carries the load).
         */
        fun <T : Any> registerCache(cxt: KdrCxt, params: SqlCacheParams<T>): SqlTableCache<T>? =
            get(cxt)?.register(params)

        /**
         * What [getAndRefresh] memoizes in [KdrCxt.locals]: which [changeGeneration] the context refreshed
         * at, and when. Immutable, satisfying the locals contract when copied into a sub-context.
         */
        class RefreshMemo(val generation: Long, val refreshedAtMs: Long)

        /**
         * Refreshes every cache at most **once per context per state of the world**, memoized in
         * [KdrCxt.locals]. This is what makes an attached cache cheap: a request reading four cached tables
         * costs one refresh sweep between them, not four.
         *
         * The memo is **self-checking** rather than invalidated by its writers: it is stale when the node-wide
         * [changeGeneration] has moved (any local write, from any context -- a sub-context's write invalidates
         * its parent's memo, which key-removal could never do), when a cache still has a reload pending (a
         * refresh that could not run, e.g. inside a transaction), or when it is older than [minRecheckMs] (so
         * a long-lived background context re-refreshes rather than serving its first snapshot forever).
         */
        fun getAndRefresh(cxt: KdrCxt): SqlTableCacheService? {
            val service = get(cxt) ?: return null
            if (service.isDisabled) return service
            val memo = cxt.locals[TCH.refreshedKey] as? RefreshMemo
            val nowMs = cxt.instanceNow().toEpochMilliseconds()
            if (memo != null && memo.generation == service.changeGeneration.get() &&
                nowMs - memo.refreshedAtMs < service.minRecheckMs && !service.hasPendingReload()
            ) {
                return service
            }
            // Capture the generation before refreshing: a write landing mid-refresh then invalidates this
            // memo, rather than being hidden behind it.
            val generation = service.changeGeneration.get()
            service.checkRefresh(cxt)
            cxt.locals[TCH.refreshedKey] = RefreshMemo(generation, nowMs)
            return service
        }

        /**
         * The cache-state table, contributed by the `common` component. It carries the transaction-lock
         * columns because the merge is a read-modify-write shared by every node.
         */
        fun tables(cxt: KdrCxt): List<KdrTable> = tableModule(cxt, namespace = "cache", topic = cacheTopic) {
            table(CST.kdrCacheState, "Records when each cached table was last changed, by any node.") {
                column(CST.cacheId, "Id of the collection of caches this row describes.", required = true)
                column(CST.cacheState, "Cached table name -> the date it was last changed.") { type = SCT.kObject }
                primaryKey(CST.cacheId)
                withTransactions()
            }
        }

        /** Schema type name for the [cacheReport] dump. */
        const val reportTypeName = "TableCacheReport"

        /**
         * The operator endpoint reporting cache state, defined with the service that owns it. It answers the
         * question an operator actually has when a cache is suspected: **is this node current, and does it
         * agree with the cluster?** -- so it reports both halves side by side, this node's in-memory state per
         * cache and the shared row every node reads.
         *
         * Reporting the shared row alone would not answer it: the row says when a table last changed, not
         * whether *this* node has caught up with that, which is the half a stale read comes from.
         *
         * **What it cannot show you is a node that simply had not looked yet.** The handler adds no refresh of
         * its own, but the `operator` section is gated, and the gate resolves the caller's roles through the
         * user cache -- which refreshes every cache on this node before the handler runs. So a transient "not
         * looked recently" lag is repaired by the act of asking, and no gated endpoint could report it. What
         * survives the gate is everything an operator is actually chasing: caching switched off, a cache that
         * has never completed a load (no [TCI.queryFromDate]), a reload stuck pending ([TCI.pendingReload]),
         * row counts, and this node's dates against the shared row the whole cluster reads.
         */
        fun schema(cxt: KdrCxt): SchModule = schemaModule(cxt, "cache") {
            // The per-cache type is owned by SqlTableCache, alongside its serialization (toJsonMap).
            SqlTableCache.defineInfoType(this)
            type(reportTypeName) {
                type = SCT.kObject
                description = "The table caches on this node, and the change dates every node shares."
                property(TCS.isDisabled, "Whether caching is switched off for this node.", required = true) {
                    type = SCT.boolean
                }
                property(
                    TCS.minRecheckMs,
                    "How far back a cache reconsiders when the shared row reports no change, in milliseconds.",
                    required = true,
                ) { type = SCT.integer }
                property(TCS.caches, "This node's caches, in refresh order.", required = true) {
                    type = SCT.array
                    items { ref(SqlTableCache.infoTypeName) }
                }
                // Free-form on purpose: its keys are table names, which are data rather than a contract, and
                // the row also records tables cached by *other* nodes -- which this node may not have.
                property(
                    TCS.sharedState,
                    "The shared row: cached table name -> when it was last changed, by any node.",
                    required = true,
                ) { type = SCT.kObject }
            }
            generalEndpoint(
                "/operator/cache/state",
                "Reports this node's in-memory table caches and the change dates all nodes share.",
                HttpMethod.GET,
                outputRef = reportTypeName,
            ) { c, _ -> cacheReport(c) }
        }

        /** Handler for `/operator/cache/state`; see [schema]. */
        fun cacheReport(cxt: KdrCxt): Map<String, Any?> {
            val service = get(cxt) ?: throw KdrException("The table-cache service is not available.")
            return linkedMapOf(
                TCS.isDisabled to service.isDisabled,
                TCS.minRecheckMs to service.minRecheckMs,
                TCS.caches to service.getSortedCaches().map { it.toJsonMap() },
                // Read even when caching is disabled: an operator who has just switched it off still wants to
                // see what the other nodes are recording.
                TCS.sharedState to service.dbQueryState(cxt).mapValues { it.value.formatDate() },
            )
        }
    }
}
