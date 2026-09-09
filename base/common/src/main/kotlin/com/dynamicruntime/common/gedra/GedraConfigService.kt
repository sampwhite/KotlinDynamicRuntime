package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlCxt
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.sql.cache.SqlTableCache
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.mkUniqueId
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

/**
 * Stores a client [GedraConfig] as a versioned config row, and publishes a revision (issue #633): the write
 * half of #611's "dynamic client configuration in a database", built on the tables #612 declared and the
 * serialization #613 wrote. It holds no identity space of its own -- it shares [GedraService] with
 * [GedraDataService], since a config gedra lives in the same id space as a data one.
 *
 * ### The version / publish transition
 *
 * A config's revision class ([GedraId.revisionClass]) collates all of its revisions, and #611 gives two rules
 * over that class, which [writeConfig] enforces under one lock on [GCT.gedraConfigTran]:
 *
 * - **Edit the latest revision until it is published.** While the latest revision has no [GC.publishedAt], a
 *   write updates that row in place at the same [GC.version] -- an author is still working on it.
 * - **The next edit after publishing starts a new revision.** Once the latest revision is published, a write
 *   mints [GC.version] + 1 as a fresh, unpublished row; the published one stays exactly as it was.
 *
 * The very first write of a class has no latest, so it is version 1.
 *
 * ### Diff before stamp, per slot
 *
 * A config is stored as one entry per config-trait slot ([gedraConfigToEntries]), each carrying its own
 * accounting -- who last changed *this slot*, and when. So a write does not restamp what it did not change: a
 * slot whose data is byte-for-byte what is already stored ([entryDataUnchanged]) keeps its stored envelope
 * whole, both the `created` and the `updated` half; a changed slot moves only its `updated` half, preserving
 * `created`; a brand-new slot is stamped fresh. This is the same diff-before-stamp the data patch runs (#626),
 * over config slots rather than data traits. When a new revision is minted after a publish, the prior
 * revision's entries are the diff source, so an unchanged slot carries its original stamps forward across the
 * version bump rather than looking freshly authored.
 *
 * ### Implied delete
 *
 * A write is authoritative by default (`impliedDelete = true`): the stored revision holds exactly the slots the
 * config carries, so a slot dropped from the config is dropped from the row. With `impliedDelete = false` a
 * write is additive -- a slot the config no longer mentions is carried forward from the prior revision, stamps
 * intact -- which is how a partial contribution can touch one slot without restating the rest.
 *
 * ### What is not here
 *
 * No validation of the config's own shape: [writeConfig] takes a [GedraConfig] the builder already assembled,
 * so its traits, schemas and workflows are well-formed by construction (a config carrying config traits is the
 * one thing refused, by [gedraConfigToEntries], since those are hardwired and never stored). Reading a row back
 * into a [GedraConfig] and loading configs at boot are #614; the two-revision cache is #615.
 */
class GedraConfigService : ServiceInitializer {
    override val serviceName: String = GedraConfigService.serviceName

    private lateinit var gedraService: GedraService

    /**
     * The in-memory `GedraConfig` cache (issue #615), or null when the table-cache service is absent (see
     * [GedraConfigCache]). [readLatest] and [listConfigs] consult it first and fall back to SQL on a miss, so
     * its absence costs queries and nothing else. Public so a test can null it around a call to force the SQL
     * the cached answer must equal.
     */
    var configCache: SqlTableCache<Map<String, Any?>>? = null

    /** Per slot ([CCT] trait id), the ordered primary-key fields its entries are addressed by (issue #625). */
    private lateinit var slotPrimaryKeys: Map<String, List<String>>

    override fun checkInit(cxt: KdrCxt) {
        gedraService = GedraService.get(cxt)
        // Registered during this pass so the cache service's own checkReady -- which runs after every service's
        // checkInit -- performs the initial load at startup rather than in a request.
        configCache = GedraConfigCache.register(cxt)
        // The config-trait vocabulary is the source of truth for how each slot's entries are keyed, read once
        // here rather than rebuilt per write. A single-instance slot (the client) has an empty key.
        slotPrimaryKeys = coreConfigTraits(cxt).configTraits.mapValues { it.value.primaryKey }
    }

    /** The config slots a bundle may carry -- the config-trait ids (issue #627), for refusing an unknown one. */
    fun knownSlots(): Set<String> = slotPrimaryKeys.keys

    private fun configTable(cxt: KdrCxt): KdrTable = cxt.getSchema().tables[GCT.gedraConfig]
        ?: throw KdrException("${GCT.gedraConfig} table is not registered in the schema store.")

    /**
     * Stores [config] as a revision of its class, applying the version/publish transition and diffing each slot
     * before stamping it (see the class note). Returns the revision as stored -- the editable latest that an
     * edit landed on or the new row it created.
     *
     * The context is bound to the config's own client before writing, so ownership and audit stamp from the
     * right owner whatever the caller was bound to -- the same move the patch path's `oneClient` makes.
     */
    fun writeConfig(cxt: KdrCxt, config: GedraConfig, impliedDelete: Boolean = true): GedraConfigRow {
        // A stored config is a client's own. Authoring into the reserved `globalconfig` namespace, or under the
        // `global` client, is refused here -- the write path is the first place a client can author config from
        // data (#292), so this is where that refusal has to bite; today namespace ownership is checked only in
        // the source collector, which sees component-declared configs, not authored ones.
        if (config.namespace == GCFG.globalNamespace) {
            throw KdrException.mkInput(
                "Config '${config.gedraId}' declares its types in the reserved '${GCFG.globalNamespace}' " +
                    "namespace, which belongs to the runtime. A client's config must use its own namespace.",
            )
        }
        if (config.gedraId.client == GID.globalClient) {
            throw KdrException.mkInput(
                "Config '${config.gedraId}' is owned by the '${GID.globalClient}' client, which is the runtime's. " +
                    "A stored config belongs to a real client.",
            )
        }
        // The general rule the `globalconfig` refusal above is one case of (#292): a namespace has one owner, and
        // a client may only author into its own or an unclaimed one. This is the write-time half of the check
        // `GedraConfigCollector.firstProblem` runs at load; it catches authoring into any namespace a kept config
        // already holds -- `globalconfig`, or another client's component namespace. A namespace no config has
        // claimed reads null and is allowed (it becomes this client's on the first write); two data-authored
        // configs racing for one unclaimed namespace is the load-time collision #614 resolves, which nothing here
        // can see before either is stored.
        val nsOwner = SchemaService.get(cxt).gedraNamespaceOwner(config.namespace)
        if (nsOwner != null && nsOwner != config.gedraId.client) {
            throw KdrException.mkInput(
                "Config '${config.gedraId}' declares its types in namespace '${config.namespace}', which belongs " +
                    "to '$nsOwner'. A client may only author into a namespace it owns.",
            )
        }
        // Refuses a config carrying config traits (they are hardwired, never stored); produces one raw entry
        // map per slot, keyed by the slot's trait id.
        val newBySlot = gedraConfigToEntries(config)
        val configId = config.gedraId.revisionClass()
        val wcxt = boundToClient(cxt, configId.client)
        val sqlCxt = SqlTopicService.mkSqlCxt(wcxt, gedraConfigTopic)
        val table = configTable(wcxt)
        var result: GedraConfigRow? = null
        SqlTopicTranProvider.executeTopicTran(sqlCxt, tranWrite, null, mapOf(GC.configId to configId.fullId)) {
            val latest = readLatestUnderLock(wcxt, sqlCxt, table, configId)
            val priorByKey = latest?.let { keyStoredEntries(it.entries) } ?: emptyMap()
            // Each write path stamps its slot entries with the very instant its row is stamped with, so the two
            // cannot disagree (see the write helpers); nothing is stamped out here on a pre-lock clock read.
            result = when {
                // No revision yet: this is version 1.
                latest == null ->
                    insertRevision(wcxt, sqlCxt, table, configId, 1, config.namespace, newBySlot, priorByKey, impliedDelete, null)
                // Latest is still editable: rewrite it in place at the same version.
                !latest.isPublished ->
                    updateRevision(wcxt, sqlCxt, table, latest, config.namespace, newBySlot, priorByKey, impliedDelete)
                // Latest is published: start the next revision.
                else ->
                    insertRevision(wcxt, sqlCxt, table, configId, latest.version + 1, config.namespace, newBySlot, priorByKey, impliedDelete, latest)
            }
        }
        return result!!
    }

    /**
     * Publishes the latest revision of the config class [configClassId] names (issue #633): stamps its
     * [GC.publishedAt], after which the next [writeConfig] mints a new revision rather than editing this one.
     * Idempotent -- if the latest revision is already published there is nothing editable to publish, so it is
     * returned unchanged. Throws when the class has no revision at all.
     */
    fun publish(cxt: KdrCxt, configClassId: GedraId): GedraConfigRow {
        val configId = configClassId.revisionClass()
        val wcxt = boundToClient(cxt, configId.client)
        val sqlCxt = SqlTopicService.mkSqlCxt(wcxt, gedraConfigTopic)
        val table = configTable(wcxt)
        val now = wcxt.instanceNow()
        var result: GedraConfigRow? = null
        SqlTopicTranProvider.executeTopicTran(sqlCxt, tranPublish, null, mapOf(GC.configId to configId.fullId)) {
            val latest = readLatestUnderLock(wcxt, sqlCxt, table, configId)
                ?: throw KdrException.mkInput("There is no config '$configId' to publish.")
            if (latest.isPublished) {
                result = latest
                return@executeTopicTran
            }
            val stmt = SqlTopicUtil.mkPartialUpdateStmt(
                sqlCxt, table, "uGedraConfigPublish",
                "c:${GC.publishedAt} = :${GC.publishedAt}", "c:${GC.gedraId} = :${GC.gedraId}",
            )
            val bind = mutableMapOf<String, Any?>(GC.gedraId to latest.gedraId.fullId, GC.publishedAt to now)
            SqlTopicUtil.prepForStdUpdate(wcxt, table, bind, latest.updatedAt)
            sqlCxt.sqlDb.executeStatement(wcxt, stmt, bind)
            result = readRowUnderLock(wcxt, sqlCxt, table, latest.gedraId)
        }
        return result!!
    }

    /** Binds [cxt] to [client] so ownership/audit stamp from the config's own client, or returns it unchanged. */
    private fun boundToClient(cxt: KdrCxt, client: String): KdrCxt =
        if (cxt.client == client) cxt else cxt.mkSubContext("configWrite", client)

    /**
     * The latest revision of the config class [configClassId] names, or null when the caller's client has none
     * (issue #627). The read counterpart of [writeConfig]: it reads the most recent enabled revision -- the
     * editable latest, or, once that is published, the published one -- so an editor opens what a write would
     * land on or descend from. Confined to the caller's client by construction: [configClassId] carries the
     * client, and callers build it from their own scope, so this never reaches another client's row.
     */
    fun readLatest(cxt: KdrCxt, configClassId: GedraId): GedraConfigRow? {
        val configId = configClassId.revisionClass()
        cachedLatest(cxt, configId)?.let { return it }
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraConfigTopic)
        val table = configTable(cxt)
        val stmt = latestQuery(sqlCxt, table)
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) {
            row = sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(GC.configId to configId.fullId, PF.client to cxt.client))
                .firstOrNull { it[PF.enabled] == true }
        }
        return row?.let { GedraConfigRow.extract(gedraService, it) }
    }

    /**
     * The latest revision of every config the caller's client owns (issue #627), one row per revision class, most-recently
     * written first. Reads the client's rows -- the config tables are `forClient`, so the client column is the
     * scope -- and reduces to the highest enabled version per class in memory, which is cheap: a client has few
     * configs and few revisions each. The listing surface behind the config catalog.
     */
    fun listConfigs(cxt: KdrCxt): List<GedraConfigRow> {
        cachedList(cxt)?.let { return it }
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraConfigTopic)
        val table = configTable(cxt)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qGedraConfigsForClient", table.columns,
            "select * from t:${GCT.gedraConfig} where c:${PF.client} = :${PF.client} " +
                "order by c:${GC.configId} asc, c:${GC.version} desc",
        )
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) {
            rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(PF.client to cxt.client))
        }
        // The rows are ordered class then version-desc, so the first enabled row of each class is its latest.
        // The SQL order groups revisions; the *listing* order is recency, as the doc promises and `listGedras`
        // does -- so the config just written is at the top rather than wherever its name sorts.
        return latestPerClass(rows.filter { it[PF.enabled] == true })
    }

    /**
     * Serves [readLatest] from [configCache], or null when it cannot -- which the caller turns into its SQL
     * query, so the cache only ever saves a round trip and never changes an answer. The class's rows come off
     * the [GCX.configId] index and the pair is reduced by the one shared rule ([GedraConfigCache.revisionsOf]);
     * the caller's client is then checked **per row** on the one row found, exactly the confinement the SQL
     * carries as its `client` predicate, so the cached path has no way to widen a read.
     */
    private fun cachedLatest(cxt: KdrCxt, configId: GedraId): GedraConfigRow? {
        val cache = configCache ?: return null
        cache.checkRefresh(cxt)
        val latest = GedraConfigCache.revisionsOf(cache, configId.fullId)?.latest ?: return null
        if (latest[PF.client].toOptStr() != cxt.client) return null
        return GedraConfigRow.extract(gedraService, latest)
    }

    /**
     * Serves [listConfigs] from [configCache], or null when it cannot. Served from the [GCX.client] index -- an
     * index that already *is* the scope, so no predicate is composed in memory -- and reduced by the same
     * [latestPerClass] the SQL path uses, so the two cannot page or order differently.
     */
    private fun cachedList(cxt: KdrCxt): List<GedraConfigRow>? {
        val cache = configCache ?: return null
        cache.checkRefresh(cxt)
        return latestPerClass(GedraConfigCache.rowsForClient(cache, cxt.client))
    }

    /**
     * The order [listConfigs] returns: most recently written first, with the revision id breaking a tie so the
     * order is **total** (issue #615 review). It has to be total because the two sources feed the sort in
     * different orders -- SQL by `configId asc`, the cache in load order -- and the sort is stable, so two configs
     * written in the same millisecond would otherwise come back in one order from the cache and the other from
     * SQL. The same shape as `GedraDataService.gedraListOrder`, for the same reason.
     */
    private val configListOrder: Comparator<GedraConfigRow> =
        compareByDescending<GedraConfigRow> { it.updatedAt ?: Instant.DISTANT_PAST }
            .thenByDescending { it.gedraId.fullId }

    /**
     * The latest revision of each class among [rows] (by the shared [latestRevisionRow] rule), extracted and
     * ordered by [configListOrder]. The single reduction both [listConfigs] paths run, so the cached listing and
     * the SQL listing are the same computation over different row sources -- which is only enough because the
     * order it applies is total.
     */
    private fun latestPerClass(rows: List<Map<String, Any?>>): List<GedraConfigRow> =
        rows.groupBy { it[GC.configId].toOptStr() ?: "" }
            .filterKeys { it.isNotEmpty() }
            .values
            .mapNotNull { latestRevisionRow(it) }
            .map { GedraConfigRow.extract(gedraService, it) }
            .sortedWith(configListOrder)

    /**
     * The "latest revision of this class" query -- ordered so the first enabled row is the latest, and confined
     * to the caller's own client (issue #627 review). The config id already carries a client, so a same-client
     * read is unchanged; the predicate is defense in depth, so a caller that comes to hold an id built for
     * another client -- a future id-taking endpoint, a lineage id resolved elsewhere -- reads null rather than
     * that client's row, the way `SqlScopeUtil` confines the data reads rather than trusting the id.
     */
    private fun latestQuery(sqlCxt: SqlCxt, table: KdrTable) = SqlStmtUtil.prepareSql(
        sqlCxt, "qGedraConfigLatest", table.columns,
        "select * from t:${GCT.gedraConfig} where c:${GC.configId} = :${GC.configId} " +
            "and c:${PF.client} = :${PF.client} order by c:${GC.version} desc",
    )

    /** The [GCT.gedraConfigControl] table from the schema store, where a client's protection tier is stored (#617). */
    private fun controlTable(cxt: KdrCxt): KdrTable = cxt.getSchema().tables[GCT.gedraConfigControl]
        ?: throw KdrException("${GCT.gedraConfigControl} table is not registered in the schema store.")

    /**
     * Whether [client] consumes only its published configuration in this node's environment (issue #617): its
     * `staticConfig` (a source tier, forced on) **or** its toggled state. The one place the tier collapses to
     * the single question the loader and the reload ask. Reads `staticConfig` from the source-code client
     * definitions -- the configs that are not data-loaded -- since static is the source tier.
     */
    fun publishedOnly(cxt: KdrCxt, client: String): Boolean {
        if (client in staticClients(cxt)) return true
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraConfigTopic)
        return GedraConfigControl.isToggledPublishedOnly(cxt, sqlCxt, controlTable(cxt), client, cxt.instanceConfig.env)
    }

    /**
     * When [client]'s protection tier last changed in this node's environment (issue #618), or null if it has
     * never toggled. Part of the sync marker: a tier toggle changes what the client consumes without changing
     * any content row (see [GedraConfigControl.controlMarker]).
     */
    fun tierMarker(cxt: KdrCxt, client: String): Instant? {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraConfigTopic)
        return GedraConfigControl.controlMarker(cxt, sqlCxt, controlTable(cxt), client, cxt.instanceConfig.env)
    }

    /** The clients a source-code definition marks `staticConfig`; the source configs are the not-data-loaded ones. */
    private fun staticClients(cxt: KdrCxt): Set<String> {
        val loadedIds = GedraConfigLoadService.get(cxt).allLoadedIds()
        val collector = SchemaCollector.get(cxt) ?: return emptySet()
        return GedraConfigControl.staticClients(collector.gedraConfigs.configs.filter { it.gedraId.fullId !in loadedIds })
    }

    /**
     * Sets [client]'s published-only state in this node's environment (issue #617), refusing a `staticConfig`
     * client -- its tier is fixed in source and is not the toggle's to change. Returns the effective state after
     * the write, which for a non-static client is [value].
     */
    fun setPublishedOnly(cxt: KdrCxt, client: String, value: Boolean): Boolean {
        if (client in staticClients(cxt)) {
            throw KdrException.mkInput(
                "Client '$client' is statically configured: its configuration comes from source in production, " +
                    "so the published-only tier cannot be toggled for it.",
            )
        }
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraConfigTopic)
        GedraConfigControl.setPublishedOnly(cxt, sqlCxt, controlTable(cxt), client, cxt.instanceConfig.env, value)
        return value
    }

    /**
     * The configuration [client] should **consume** now (issue #617), one [GedraConfigRow] per class: its
     * latest revision, or -- when the client is published-only -- its latest *published* revision, and nothing
     * for a class that has none. This is the tier-aware read the reload (#616) loads from, distinct from
     * [listConfigs], which serves the editing surface and always shows the latest.
     */
    fun currentConfigs(cxt: KdrCxt, client: String): List<GedraConfigRow> {
        val publishedOnly = publishedOnly(cxt, client)
        val rowsByClass = listRevisionRows(cxt, client)
        return rowsByClass.mapNotNull { classRows ->
            val chosen = if (publishedOnly) latestPublishedRow(classRows) else latestRevisionRow(classRows)
            chosen?.let { GedraConfigRow.extract(gedraService, it) }
        }
    }

    /** Every enabled revision row of [client]'s configs, grouped by class -- the raw material both list reads reduce. */
    private fun listRevisionRows(cxt: KdrCxt, client: String): List<List<Map<String, Any?>>> {
        // The cache holds every client's rows, so it serves this regardless of which client is asked for; SQL
        // is the fallback on a miss, as everywhere else.
        configCache?.let { cache ->
            cache.checkRefresh(cxt)
            return GedraConfigCache.rowsForClient(cache, client)
                .groupBy { it[GC.configId].toOptStr() ?: "" }.filterKeys { it.isNotEmpty() }.values.toList()
        }
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraConfigTopic)
        val table = configTable(cxt)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qGedraConfigRowsForClient", table.columns,
            "select * from t:${GCT.gedraConfig} where c:${PF.client} = :${PF.client} and c:${PF.enabled} = true",
        )
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) { rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(PF.client to client)) }
        return rows.groupBy { it[GC.configId].toOptStr() ?: "" }.filterKeys { it.isNotEmpty() }.values.toList()
    }

    /**
     * The latest revision of [configId] as it stands inside the transaction, or null when the class has no
     * enabled revision yet. Ordered by version so the first enabled row is the latest; read with
     * `queryStatement` because the lock this transaction holds is on [GCT.gedraConfigTran], not on these rows.
     */
    private fun readLatestUnderLock(cxt: KdrCxt, sqlCxt: SqlCxt, table: KdrTable, configId: GedraId): GedraConfigRow? {
        val row = sqlCxt.sqlDb.queryStatement(
            cxt, latestQuery(sqlCxt, table), mapOf(GC.configId to configId.fullId, PF.client to cxt.client),
        ).firstOrNull { it[PF.enabled] == true } ?: return null
        return GedraConfigRow.extract(gedraService, row)
    }

    /** One revision row by its versioned id, read under the lock (used to return a row just written). */
    private fun readRowUnderLock(cxt: KdrCxt, sqlCxt: SqlCxt, table: KdrTable, gedraId: GedraId): GedraConfigRow {
        val row = sqlCxt.sqlDb.queryOneStatement(cxt, SqlTopicUtil.mkTableSelectStmt(sqlCxt, table), mapOf(GC.gedraId to gedraId.fullId))
            ?: throw KdrException("Revision '$gedraId' vanished within its own write transaction.")
        return GedraConfigRow.extract(gedraService, row)
    }

    /**
     * Inserts a fresh, unpublished revision at [version] and returns it as stored. When a new revision is minted
     * after a publish, [prior] is the revision it descends from: its entries are the diff source
     * [buildFinalEntries] reads, and its forward-compatibility [GedraConfigRow.extra] keys are carried onto the
     * new row -- a version bump must not drop what an in-place edit would have kept.
     */
    private fun insertRevision(
        cxt: KdrCxt,
        sqlCxt: SqlCxt,
        table: KdrTable,
        configId: GedraId,
        version: Int,
        namespace: String,
        newBySlot: Map<String, List<Map<String, Any?>>>,
        priorByKey: Map<String, Map<String, Any?>>,
        impliedDelete: Boolean,
        prior: GedraConfigRow?,
    ): GedraConfigRow {
        val gedraId = gedraService.intern(configId.withRevision(version))
        val data = mutableMapOf<String, Any?>(
            GC.gedraId to gedraId.fullId,
            // Both denormalizations come from the id, never from separate inputs, so the row cannot disagree
            // with its own id (see GC.configId).
            GC.configId to configId.fullId,
            GC.version to version,
            // A newly written revision is the editable latest, so it has no publish time yet.
            GC.publishedAt to null,
        )
        // Stamp the row first, then stamp the entries with the very instant it took, so the row's `updatedAt`
        // column and the entries' own stamps cannot disagree -- the move the patch path makes.
        SqlTopicUtil.prepForStdExecute(cxt, table, data)
        val now = data[PF.updatedAt].toOptInstant() ?: cxt.instanceNow()
        val entries = buildFinalEntries(cxt, configId, newBySlot, priorByKey, now, impliedDelete, prior)
        // Carry the prior revision's unknown keys across the bump, the same forward-compatibility promise an
        // in-place edit keeps through `GedraConfigRow.storedData`.
        val stored = LinkedHashMap<String, Any?>(prior?.extra ?: emptyMap())
        // The namespace, so the boot loader (#614) can reassemble this config faithfully even when it declares
        // no types to recover it from.
        if (namespace.isNotEmpty()) stored[GC.namespace] = namespace
        stored[GD.entries] = entries
        data[GC.data] = stored
        val row = GedraConfigRow.extract(gedraService, data)
        sqlCxt.sqlDb.executeStatement(cxt, SqlTopicUtil.mkTableInsertStmt(sqlCxt, table), data)
        return row
    }

    /**
     * Rewrites the editable [latest] revision's entries in place at the same version, and returns it as it now
     * stands. A partial update of the one business column ([GC.data]); the audit pair is appended by the shared
     * builder and stamped by [SqlTopicUtil.prepForStdUpdate], its `updatedAt` strictly past the value read
     * under this lock so the config cache (#615) cannot miss the write.
     */
    private fun updateRevision(
        cxt: KdrCxt,
        sqlCxt: SqlCxt,
        table: KdrTable,
        latest: GedraConfigRow,
        namespace: String,
        newBySlot: Map<String, List<Map<String, Any?>>>,
        priorByKey: Map<String, Map<String, Any?>>,
        impliedDelete: Boolean,
    ): GedraConfigRow {
        // The config being written is authoritative on the namespace -- a prior row may predate it being stored.
        latest.namespace = namespace
        val stmt = SqlTopicUtil.mkPartialUpdateStmt(
            sqlCxt, table, "uGedraConfigData",
            "c:${GC.data} = :${GC.data}", "c:${GC.gedraId} = :${GC.gedraId}",
        )
        val bind = mutableMapOf<String, Any?>(GC.gedraId to latest.gedraId.fullId)
        // Take the canonical `now` from the stamp helper before building the entries, so the entries carry the
        // very instant the row's `updatedAt` column will -- strictly past the value read under this lock, so the
        // config cache (#615) cannot miss the write. The move the patch path makes.
        val now = SqlTopicUtil.prepForStdUpdate(cxt, table, bind, latest.updatedAt)
            ?: throw KdrException("${GCT.gedraConfig} must declare ${PF.updatedAt} for a config write to stamp it.")
        val entries = buildFinalEntries(cxt, latest.configId, newBySlot, priorByKey, now, impliedDelete, latest)
        // `storedData` merges the prior revision's unknown keys back in, keeping the forward-compatibility promise.
        bind[GC.data] = latest.storedData(entries)
        sqlCxt.sqlDb.executeStatement(cxt, stmt, bind)
        return readRowUnderLock(cxt, sqlCxt, table, latest.gedraId)
    }

    /**
     * The stored entry list for a revision: one stamped entry per slot the config carries, diffed against the
     * prior revision so an unchanged slot keeps its stored envelope whole (both halves) and a changed one moves
     * only its `updated` half at [now], plus -- when [impliedDelete] is off -- the prior slots the config no
     * longer mentions, carried forward untouched. Two entries of one slot sharing a key are refused rather than
     * silently collapsed, the config twin of the kernel `checkEntryKeys` every data write runs (the builder
     * keeps several slots as plain lists, so a doubled usage or cfact reaches here as two same-key entries).
     */
    private fun buildFinalEntries(
        cxt: KdrCxt,
        configId: GedraId,
        newBySlot: Map<String, List<Map<String, Any?>>>,
        priorByKey: Map<String, Map<String, Any?>>,
        now: Instant,
        impliedDelete: Boolean,
        latest: GedraConfigRow?,
    ): List<Map<String, Any?>> {
        val stampedByKey = LinkedHashMap<String, Map<String, Any?>>()
        for ((slot, rawEntries) in newBySlot) {
            val pk = slotPrimaryKeys[slot].orEmpty()
            for (raw in rawEntries) {
                val key = entryKey(slot, pk.map { raw[it] })
                if (stampedByKey.containsKey(key)) {
                    throw KdrException.mkInput(
                        "Config '$configId' carries two '$slot' entries with the same key " +
                            "(${pk.joinToString(", ").ifEmpty { "single-instance" }}). A config slot holds one " +
                            "entry per key, so there would be no way to say afterward which was meant.",
                    )
                }
                val existing = priorByKey[key]
                stampedByKey[key] = if (entryDataUnchanged(existing, raw)) {
                    existing!! // unchanged: keep the stored envelope, both halves
                } else {
                    stampEntry(cxt, slot, raw, existing, now)
                }
            }
        }
        // Carry forward the prior revision's slots the new config no longer mentions, unless a write is
        // authoritative (implied delete) -- then a dropped slot really is dropped.
        val carried = if (impliedDelete || latest == null) {
            emptyList()
        } else {
            latest.entries.filter { keyOfStored(it) !in stampedByKey.keys }
        }
        return stampedByKey.values.toList() + carried
    }

    /**
     * One config-trait entry stamped for storage: a new slot gets a fresh envelope, a changed one keeps who
     * first wrote it and when while its `updated` half moves to [now] -- the config twin of
     * `GedraDataService.mkStoredEntry`, over a slot's [data] rather than a trait's.
     */
    private fun stampEntry(cxt: KdrCxt, slot: String, data: Map<String, Any?>, existing: Map<String, Any?>?, now: Instant): Map<String, Any?> {
        val actor = cxt.userProfile.userId
        val base = linkedMapOf<String, Any?>(GE.traitId to slot, GE.data to data)
        if (existing == null) {
            return base.asStoredEntry(cxt.mkUniqueId(), GSRC.user, now, actor)
        }
        return base.asStoredEntry(
            entryId = existing[GE.entryId].toOptStr() ?: cxt.mkUniqueId(),
            source = GSRC.user,
            createdAt = existing[GE.createdAt].toOptInstant() ?: now,
            createdBy = existing[GE.createdBy].toOptLong() ?: actor,
            updatedAt = now,
            updatedBy = actor,
        )
    }

    /** Folds stored entries into a map addressed by their slot key -- the diff source a write reads under lock. */
    private fun keyStoredEntries(entries: List<Map<String, Any?>>): Map<String, Map<String, Any?>> {
        val byKey = LinkedHashMap<String, Map<String, Any?>>()
        for (entry in entries) {
            byKey[keyOfStored(entry)] = entry
        }
        return byKey
    }

    /** The slot key of one **stored** entry -- its slot trait id plus that slot's primary-key values. */
    private fun keyOfStored(entry: Map<String, Any?>): String {
        val slot = entry[GE.traitId].toOptStr() ?: return ""
        val pk = slotPrimaryKeys[slot].orEmpty()
        return entryKey(slot, entryKeyValues(entry, slot, pk, stored = true))
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "GedraConfigService"

        /** The transaction that writes a config revision. */
        const val tranWrite = "writeGedraConfig"

        /** The transaction that publishes a config revision. */
        const val tranPublish = "publishGedraConfig"

        fun get(cxt: KdrCxt): GedraConfigService = cxt.instanceConfig.get(serviceName) as? GedraConfigService
            ?: throw KdrException("The $serviceName is not available on this node.")
    }
}
