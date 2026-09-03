package com.dynamicruntime.common.sql.cache

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.operator.TCI
import com.dynamicruntime.common.schema.JsonMappable
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.sql.KdrIndex
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.LogSql
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlCxt
import com.dynamicruntime.common.sql.SqlSession
import com.dynamicruntime.common.sql.SqlStatement
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.util.formatDate
import com.dynamicruntime.common.util.toOptInstant
import java.util.TreeMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Attribute keys of a cache's operator report (see [SqlTableCache.toJsonMap]). Each name matches its value.
 */

/**
 * An in-memory copy of one database table, kept current by *incremental* reload.
 *
 * The mechanism is a query on [PF.updatedAt]: each pass asks for the rows changed since the point the
 * previous pass reached, and then walks that point forward. The point is deliberately set *behind* where the
 * query actually got to -- by [TCH.expectedMaxDriftMs], and never further ahead than [TCH.potentialLagMs] --
 * because a row's `updatedAt` is stamped slightly before it becomes visible to other connections, and
 * because two nodes' clocks do not agree. Re-reading a handful of rows every pass is the price of never
 * stepping over one.
 *
 * **Reads are lock-free.** Everything a reader touches lives on an immutable [SqlCacheSnapshot] published to
 * a `@Volatile` field; the lock below is taken only by a reload. See [SqlCacheSnapshot] for why the cost
 * belongs on the reload rather than on every read.
 *
 * **Who drives the refresh** depends on [isDetached]. A cache registered with [SqlTableCacheService] (the
 * normal case) is *attached*: the service refreshes every cache together, once per context, off the shared
 * state row. A detached cache queries for itself on each [checkRefresh] -- more flexible, less efficient --
 * which is what a cache used outside a request, or in a test, gets.
 */
class SqlTableCache<T : Any>(val params: SqlCacheParams<T>) : JsonMappable {
    /**
     * The current read view. Replaced wholesale after a reload that changed something; never mutated. Safe to
     * hold on to -- what a caller reads from it stays consistent even as a newer snapshot is published.
     */
    @Volatile
    var snapshot: SqlCacheSnapshot<T> = SqlCacheSnapshot.empty(params.indexes)
        private set

    /** Whether this cache refreshes itself ([checkRefresh]) rather than being driven by the service. */
    var isDetached: Boolean = true

    /** Whether a first load has completed. */
    @Volatile
    var isLoaded: Boolean = false
        private set

    /**
     * Set by [markChanged] when this node itself writes the table, and cleared by the next completed reload.
     * While set, a reload runs even though the clock says nothing can have changed yet.
     *
     * This is what makes read-your-writes hold **within** a unit of work. Relying on the shared state row
     * alone would not: it is not written until the request ends, so a write and a read in the same request
     * would see a stale cache unless the caller remembered to arrange otherwise. A local write is the one
     * change this node knows about with certainty and for free, so it acts on it directly.
     */
    @Volatile
    var forceReload: Boolean = false
        private set

    // The mutable interior. Real `private` plus a lock rather than the guide's @KdrPrivate marker: this is a
    // synchronized cache, the case the guide names as one where enforcement genuinely matters.
    private val refreshLock = ReentrantLock(true)

    /** Every row held, keyed by its current counter. A reloaded row moves to a new key. */
    private val entriesByCounter = TreeMap<Long, SqlCacheRow<T>>()

    /** The same rows keyed by id, so a reload can find the entry it is replacing. */
    private val entriesById = HashMap<String, SqlCacheRow<T>>()

    /**
     * Per-key change streams: non-unique index name -> key -> counter-ordered rows, departure tombstones
     * included. Maintained incrementally by [updateMultiStreams], because unlike every other derived structure
     * here it is **not** a function of the current rows: a row that left a key is remembered only here.
     */
    private val multiStreams = HashMap<String, HashMap<String, TreeMap<Long, SqlCacheRow<T>>>>()

    private var counter = 0L

    /** The latest [PF.updatedAt] any pass has seen, whether or not the row was stored. */
    private var lastSeen: Instant? = null

    /** When the most recent pass began; lets a pass that started earlier bow out to a later one. */
    private var lastStartTime: Instant? = null

    /** Where the next reload query starts, or null before the first (full) load. */
    private var queryFromDate: Instant? = null

    /** The date a caller's `compareDate` is tested against to decide a reload is unnecessary. `@Volatile`
     *  because [checkLoadCache]'s cheap pre-check reads it without the lock (writes stay under it). */
    @Volatile
    private var checkQueryFromDate: Instant? = null

    private var allEnabledStmt: SqlStatement? = null
    private var updatedSinceStmt: SqlStatement? = null

    // --- reading ------------------------------------------------------------

    // A read is `cache.checkRefresh(cxt)` followed by `cache.snapshot.get/byIndex/allByIndex(...)` -- see
    // `UserService.cachedUser`. There is deliberately no `get(cxt, id)`-style wrapper pairing the two: it
    // would be a second way to say the same thing, and the two would drift the first time refresh semantics
    // changed. Taking the snapshot explicitly is also what lets a caller read several things from one
    // consistent view.

    /**
     * Renders primary-key values into the id form [SqlCacheRow.id] uses, so a caller looks a row up with the
     * values it already has rather than reconstructing the encoding. Pass the values in the table's declared
     * primary-key order.
     */
    fun idOf(vararg keyValues: Any?): String =
        keyValues.joinToString(TCH.idSeparator) { it?.toString() ?: "" }

    // --- refreshing ---------------------------------------------------------

    /**
     * Brings the cache up to date. An *attached* cache defers to [SqlTableCacheService], which refreshes every
     * registered cache once per context off one state-row read; a detached one queries its own table.
     */
    fun checkRefresh(cxt: KdrCxt) {
        if (isDetached) {
            checkLoadCache(cxt, cxt.instanceNow())
        } else {
            SqlTableCacheService.getAndRefresh(cxt)
        }
    }

    /**
     * Records that this node just wrote the table, so the next reload happens even though no time has passed.
     * Called through [SqlTableCacheService.noteTableChanged]; see [forceReload].
     */
    fun markChanged() {
        forceReload = true
    }

    /**
     * Reloads if there is reason to. [compareDate] is the caller's evidence about when the table last
     * changed -- from the shared state row for an attached cache, or simply *now* for a detached one. A
     * reload is skipped when the cache has already queried past that point.
     */
    fun checkLoadCache(cxt: KdrCxt, compareDate: Instant) {
        // The cheap answer first, on volatile fields alone. The common case by far is "nothing to do", and
        // deciding that must not cost a pooled connection: taking the session first (required below, for lock
        // ordering) meant every no-op check consumed a checkout, so under pool pressure a pure cache hit could
        // block or fail on a connection it was never going to use. `shouldLoad` re-checks under the lock; the
        // clock-jump case (a gap past the tolerance) deliberately falls through to the locked path, which
        // owns the reset.
        if (!forceReload) {
            val checkFrom = checkQueryFromDate
            if (checkFrom != null && checkFrom > compareDate &&
                checkFrom.toEpochMilliseconds() - compareDate.toEpochMilliseconds() <= TCH.timeJumpToleranceMs
            ) {
                return
            }
        }
        // Never reload from inside an open transaction: the query would run on the caller's own connection
        // and read its uncommitted rows into a snapshot every thread sees -- and a rollback would strand them
        // there, since the committed row's older updatedAt is never re-read. The write's [forceReload] stays
        // pending (and getAndRefresh's memo does not mask a pending reload), so the first read after the
        // transaction ends performs the reload.
        if (SqlSession.get(cxt)?.inTran == true) {
            return
        }
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, params.topic)
        val tranStartTime = cxt.instanceNow()
        // Take the database session *before* the refresh lock, never the other way round: the reverse order
        // deadlocks a busy pool, with the lock holder waiting for a connection that a thread waiting for the
        // lock is holding.
        sqlCxt.sqlDb.withSession(cxt) {
            refreshLock.withLock { loadUnderLock(cxt, sqlCxt, tranStartTime, compareDate) }
        }
    }

    private fun loadUnderLock(cxt: KdrCxt, sqlCxt: SqlCxt, tranStartTime: Instant, compareDate: Instant) {
        val wasForced = forceReload
        if (!wasForced && !shouldLoad(cxt, tranStartTime, compareDate)) {
            return
        }
        // Assign *now* inside the lock, so requests piling up behind it are more likely to find the dates have
        // already moved far enough forward that they can skip the query entirely.
        val now = cxt.instanceNow()
        lastStartTime = now
        // Cleared before the query rather than after, so a write landing *during* it re-arms the flag and gets
        // its own pass. Restored below if this pass fails, since then it covered nothing.
        forceReload = false

        var succeeded = false
        try {
            runLoad(cxt, sqlCxt, now)
            succeeded = true
        } finally {
            if (!succeeded && wasForced) {
                forceReload = true
            }
        }
    }

    /** Whether a reload is warranted, applying (and correcting for) the clock-jump case. */
    private fun shouldLoad(cxt: KdrCxt, tranStartTime: Instant, compareDate: Instant): Boolean {
        // A pass that began after we did has already covered whatever we would have found.
        val started = lastStartTime
        if (started != null && started > tranStartTime) {
            return false
        }
        val checkFrom = checkQueryFromDate ?: return true
        if (checkFrom <= compareDate) {
            return true
        }
        // We believe we have queried past the point the caller is asking about. That is normal -- unless the
        // gap is so large that the clock, not the data, is what moved.
        if (checkFrom.toEpochMilliseconds() - compareDate.toEpochMilliseconds() <= TCH.timeJumpToleranceMs) {
            return false
        }
        val reset = compareDate - TCH.timeJumpToleranceMs.milliseconds
        checkQueryFromDate = reset
        queryFromDate = reset
        LogSql.warn(
            cxt,
            "TIMEJUMP query date ${checkFrom.formatDate()} for cache of ${params.tableName} is more than a " +
                "minute ahead of the compare date ${compareDate.formatDate()}; resetting it backwards.",
        )
        return true
    }

    private fun runLoad(cxt: KdrCxt, sqlCxt: SqlCxt, now: Instant) {
        val table = tableOf(cxt)
        val from = queryFromDate
        val isInitialLoad = from == null
        val startMs = System.currentTimeMillis()

        // The first pass takes the enabled rows only -- a table's soft-deleted history is not worth holding
        // in memory. Later passes take everything changed since, disabled rows included, because a row that
        // has *just been* disabled is exactly the change a cache must hear about.
        val stmt = if (isInitialLoad) allEnabledStmt(sqlCxt, table) else updatedSinceStmt(sqlCxt, table)
        val bind = if (isInitialLoad) mapOf(PF.enabled to true) else mapOf(PF.updatedAt to from)
        val rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, bind)

        var changed = false
        for (raw in rows) {
            // Guarded per row: an unreadable row (a hand-edited or migrated row the extractor throws on) is
            // skipped and logged rather than failing the pass. Without this, one bad row failed every reload
            // forever -- queryFromDate is only advanced after the loop, so each pass re-hit the same row, and
            // the throw propagated into every gated request (and into checkReady, refusing boot).
            val applied = try {
                applyRow(cxt, table, raw)
            } catch (e: Exception) {
                LogSql.error(
                    cxt,
                    "CACHE skipping unreadable row of ${params.tableName}; it will be invisible to cached " +
                        "lookups until repaired.",
                    e,
                )
                false
            }
            if (applied) {
                changed = true
            }
        }
        if (changed) {
            publish(cxt)
        }

        // We have now queried up to (at least) `now`, so the next pass may start from there -- pulled back by
        // the expected drift, and floored so a lagging clock cannot make us start in the future.
        val expectedDrift = now - TCH.expectedMaxDriftMs.milliseconds
        val potentialLag = now - TCH.potentialLagMs.milliseconds
        val seen = lastSeen
        val minDate = if (seen != null && seen < expectedDrift) seen else expectedDrift
        queryFromDate = maxOf(potentialLag, minDate)
        checkQueryFromDate = maxOf(potentialLag, expectedDrift)
        isLoaded = true

        if (isInitialLoad) {
            val duration = System.currentTimeMillis() - startMs
            LogSql.debug(cxt) {
                "CACHE loaded all of table ${params.tableName}: ${rows.size} rows in ${duration}ms."
            }
        }
        if (rows.size > params.largeLoadWarning) {
            LogSql.warn(
                cxt,
                "CACHE load of table ${params.tableName} returned ${rows.size} rows, which is more than the " +
                    "${params.largeLoadWarning} this cache expects to hold; consider whether it should be cached.",
            )
        }
    }

    /** Folds one queried row into the cache; returns whether it changed anything. */
    private fun applyRow(cxt: KdrCxt, table: KdrTable, raw: Map<String, Any?>): Boolean {
        val updatedAt = raw[PF.updatedAt].toOptInstant() ?: return false
        // Advanced for every row the query returned, stored or not: this records how far the *data* reached,
        // which is what lets the next pass start from the rows rather than from the clock.
        val seen = lastSeen
        if (seen == null || updatedAt > seen) {
            lastSeen = updatedAt
        }

        val id = idOfRow(table, raw)
        val prior = entriesById[id]
        // The overlap the drift fudge deliberately creates: most passes re-read rows we already hold.
        if (prior != null && updatedAt <= prior.updatedAt) {
            return false
        }
        val value = params.extract(cxt, raw) ?: return false

        counter++
        val row = SqlCacheRow(counter, id, updatedAt, raw[PF.enabled] == true, value)
        if (prior != null) {
            entriesByCounter.remove(prior.counter)
        }
        entriesByCounter[counter] = row
        entriesById[id] = row
        updateMultiStreams(prior, row)
        return true
    }

    /**
     * Files [row] into each non-unique index's stream, and -- when it has *left* a key it was under --
     * leaves a disabled copy behind there.
     *
     * That tombstone is the entire reason these streams exist. A key's consumer is told about a row leaving
     * the same way it is told about a soft delete, because to a cursor the two are the same event: the row is
     * no longer yours, drop it. Without it, a row moved from one key to another would simply stop appearing
     * in the old key's stream, and a consumer that had already seen it would keep it forever.
     *
     * A disabled row belongs under no key, so it is treated as absent on both sides.
     */
    private fun updateMultiStreams(prior: SqlCacheRow<T>?, row: SqlCacheRow<T>) {
        for (index in params.indexes) {
            if (index.unique) {
                continue
            }
            val newKey = if (row.enabled) index.keyOf(row.value) else null
            val priorKey = if (prior != null && prior.enabled) index.keyOf(prior.value) else null
            if (prior != null && priorKey != null) {
                streamFor(index.name, priorKey).remove(prior.counter)
            }
            if (priorKey != null && priorKey != newKey) {
                streamFor(index.name, priorKey)[row.counter] =
                    SqlCacheRow(row.counter, row.id, row.updatedAt, enabled = false, row.value)
            }
            if (newKey != null) {
                streamFor(index.name, newKey)[row.counter] = row
            }
        }
    }

    private fun streamFor(indexName: String, key: String): TreeMap<Long, SqlCacheRow<T>> =
        multiStreams.getOrPut(indexName) { HashMap() }.getOrPut(key) { TreeMap() }

    /** Rebuilds the immutable read view from the interior and publishes it. Called under the refresh lock. */
    private fun publish(cxt: KdrCxt) {
        val ordered = entriesByCounter.values.toList()
        // Iterated in counter order, so every derived index comes out in load order too.
        val live = ordered.filter { it.enabled }

        val byId = LinkedHashMap<String, SqlCacheRow<T>>(live.size)
        for (row in live) {
            byId[row.id] = row
        }

        val uniqueIndexes = LinkedHashMap<String, Map<String, SqlCacheRow<T>>>()
        val multiIndexes = LinkedHashMap<String, Map<String, List<SqlCacheRow<T>>>>()
        val multiStreamsOut = LinkedHashMap<String, Map<String, List<SqlCacheRow<T>>>>()
        for (index in params.indexes) {
            if (index.unique) {
                val map = LinkedHashMap<String, SqlCacheRow<T>>()
                for (row in live) {
                    val key = index.keyOf(row.value) ?: continue
                    val prev = map.put(key, row)
                    if (prev != null) {
                        // The database's own unique index should have made this impossible; if it did not, the
                        // cache is about to answer lookups with one of two rows and nothing else would say so.
                        LogSql.error(
                            cxt,
                            "Duplicate key '$key' in unique cache index '${index.name}' of table " +
                                "${params.tableName}: ids ${prev.id} and ${row.id}.",
                        )
                    }
                }
                uniqueIndexes[index.name] = map
            } else {
                // Both per-key views are derived from the one stream, rather than membership being rebuilt from
                // `live` in parallel: two derivations of the same thing are two chances to disagree about who
                // is under a key, and the disagreement would show up only as a cursor and a lookup telling one
                // consumer different stories.
                val streams = multiStreams[index.name] ?: emptyMap()
                val members = LinkedHashMap<String, List<SqlCacheRow<T>>>(streams.size)
                val changes = LinkedHashMap<String, List<SqlCacheRow<T>>>(streams.size)
                for ((key, stream) in streams) {
                    val all = stream.values.toList() // counter-ordered: a TreeMap keyed by counter
                    changes[key] = all
                    members[key] = all.filter { it.enabled }
                }
                multiIndexes[index.name] = members
                multiStreamsOut[index.name] = changes
            }
        }
        snapshot = SqlCacheSnapshot(byId, uniqueIndexes, multiIndexes, multiStreamsOut, ordered)
    }

    // --- statements & lookups -----------------------------------------------

    /** The table definition from the schema store. */
    fun tableOf(cxt: KdrCxt): KdrTable = cxt.getSchema().tables[params.tableName]
        ?: throw KdrException(
            "Table ${params.tableName} is not registered in the schema store, so it cannot be cached.",
        )

    private fun idOfRow(table: KdrTable, raw: Map<String, Any?>): String =
        table.primaryKey.joinToString(TCH.idSeparator) { raw[it]?.toString() ?: "" }

    private fun allEnabledStmt(sqlCxt: SqlCxt, table: KdrTable): SqlStatement =
        allEnabledStmt ?: SqlStmtUtil.prepareSql(
            sqlCxt, "qCache${table.tableName}AllEnabled", table.columns,
            "select * from t:${table.tableName} where c:${PF.enabled} = :${PF.enabled} " +
                "order by c:${PF.updatedAt} asc",
        ).also { allEnabledStmt = it }

    private fun updatedSinceStmt(sqlCxt: SqlCxt, table: KdrTable): SqlStatement =
        updatedSinceStmt ?: SqlStmtUtil.prepareSql(
            sqlCxt, "qCache${table.tableName}UpdatedSince", table.columns,
            "select * from t:${table.tableName} where c:${PF.updatedAt} >= :${PF.updatedAt} " +
                "order by c:${PF.updatedAt} asc",
        ).also { updatedSinceStmt = it }

    // --- operator report ----------------------------------------------------

    /**
     * This cache's state, for the operator endpoint (`/operator/cache/state`). The reload bookkeeping is read
     * under the refresh lock so the report cannot catch a pass halfway through updating it; the snapshot is
     * taken once, so the counts and the counter describe the same moment.
     *
     * The two dates are **omitted when absent** rather than reported as null (the [KdrIndex] convention): a
     * missing [TCI.queryFromDate] is the meaningful case -- it says this cache has never completed a load.
     */
    override fun toJsonMap(): Map<String, Any?> {
        val bookkeeping = refreshLock.withLock { queryFromDate to lastSeen }
        val current = snapshot
        return linkedMapOf<String, Any?>(
            TCI.tableName to params.tableName,
            TCI.topic to params.topic,
            TCI.isLoaded to isLoaded,
            TCI.isDetached to isDetached,
            TCI.pendingReload to forceReload,
            TCI.numRows to current.size,
            TCI.numEntries to current.ordered.size,
            TCI.highCounter to current.highCounter,
            TCI.indexes to params.indexes.map { it.name },
        ).also {
            bookkeeping.first?.let { date -> it[TCI.queryFromDate] = date.formatDate() }
            bookkeeping.second?.let { date -> it[TCI.lastSeen] = date.formatDate() }
        }
    }

    companion object {
        /** Schema type name for a cache's state report (the shape of [toJsonMap]). */
        const val infoTypeName = "TableCacheInfo"

        /**
         * Defines the [infoTypeName] schema type on [builder], beside the serialization it describes, so the
         * two cannot drift apart -- the same arrangement `KdrTable` uses.
         */
        fun defineInfoType(builder: SchTypesBuilder) {
            builder.type(infoTypeName) {
                type = SCT.kObject
                description = "The state of one node's in-memory copy of a database table."
                property(TCI.tableName, "The cached table.", required = true)
                property(TCI.topic, "The SQL topic the table belongs to.", required = true)
                property(TCI.isLoaded, "Whether a first, full load has completed.", required = true) {
                    type = SCT.boolean
                }
                property(
                    TCI.isDetached,
                    "Whether the cache queries for itself rather than being refreshed with the others.",
                    required = true,
                ) { type = SCT.boolean }
                property(
                    TCI.pendingReload,
                    "Whether this node has written the table and the next read will reload regardless of dates.",
                    required = true,
                ) { type = SCT.boolean }
                property(TCI.numRows, "How many live rows the cache holds.", required = true) {
                    type = SCT.integer
                }
                property(
                    TCI.numEntries,
                    "How many entries it holds in total, including the tombstones left by disabled rows.",
                    required = true,
                ) { type = SCT.integer }
                property(TCI.highCounter, "The highest load-order counter assigned.", required = true) {
                    type = SCT.integer
                }
                property(
                    TCI.queryFromDate,
                    "Where the next reload query will start. Absent until a first load has completed.",
                ) { dateTime() }
                property(
                    TCI.lastSeen,
                    "The latest row-update date any pass has seen. Absent when no row has been read.",
                ) { dateTime() }
                property(TCI.indexes, "The names of the secondary indexes maintained.", required = true) {
                    type = SCT.array
                    items { type = SCT.string }
                }
            }
        }
    }
}
