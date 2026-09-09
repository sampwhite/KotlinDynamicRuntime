package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.content.FRAG
import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiBlockSource
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Loads stored client configurations from the database into the schema collector at boot, beside the
 * source-declared ones (issue #614) -- the first restart-visible behavior in #611: a node that restarts picks
 * up config a peer wrote through the endpoints (#627) to the tables (#612) in the shape #613 serializes.
 *
 * ### Why this is a startup service registered first
 *
 * The stored configs have to be in the collector **before** its consumers read it -- `ClientService` and
 * `SchemaService`, the first two startup services, both build from `SchemaCollector.gedraConfigs`. Once a
 * config is added here, every consumer sees it with no new plumbing: `ClientService` gets its client,
 * `SchemaService` compiles its traits into the unions and its defs into the store, `WorkflowService` reads it
 * off the collector, and `SchemaCollector.addGedraConfig` folds in its cfacts. So this runs **ahead of all of
 * them** in the startup tier (`CommonComponent.startupServices`).
 *
 * ### The bootstrap read
 *
 * Reading the `GedraConfig` table normally needs the compiled schema store (for the table definitions) and
 * reconciled tables -- both of which only exist at or after `SchemaService.checkInit`, which is exactly what
 * this must precede. It breaks the cycle with [SqlTopicService.reconcileTopicFromTables], which reconciles the
 * config topic from the collector's **raw** table definitions rather than the store, and caches it so the
 * ordinary read paths work unchanged afterward. See that method.
 *
 * ### Rules at the boundary
 *
 * - **Which revision**: the latest (most recent) enabled revision of each class. The published-only gating for
 *   a client in a protected state is #617; until then, latest.
 * - **Reserved namespace / trait collisions / degrade-vs-refuse**: all come free through
 *   [SchemaCollector.addGedraConfig] -> [GedraConfigCollector.add], which refuses a config in the `globalconfig`
 *   namespace (owned by `global`, not the client) and one colliding with a kept trait, and does so as
 *   [gedraConfigCheckMode] says -- refuse the boot in `unit`/`local`, degrade (log, skip) in production.
 * - **`extendsFromClientId` from data** ([extendsProblem]): a data config may extend only a client that is
 *   **defined in source** and is a **template** -- which enforces both halves of the rule `ClientDef`
 *   documents ("only a template may be named" and "only the source-code definition is pulled in"), since a
 *   source template is the only thing that both exists in the source set and is a template.
 * - **A bad row must not take down a node**: a row that fails to reassemble is turned into a config problem
 *   and handled by the same mode split, so one corrupt row degrades in production and refuses the boot in
 *   `unit`/`local`, exactly as a bad source config does.
 *
 * ### Sync awareness
 *
 * A restarted node now runs the latest stored config, so it is ahead of peers that have not reloaded. #611
 * routes that through a `ClientSyncTracking` write so other nodes learn to sync (#618). Nothing is written yet;
 * [recordRestartLoad] is the one obvious place for it.
 */
class GedraConfigLoadService : ServiceInitializer {
    override val serviceName: String = GedraConfigLoadService.serviceName

    private var schemaCollector: SchemaCollector? = null
    private var sqlTopicService: SqlTopicService? = null
    private var isInit: Boolean = false

    /** Config problems found while loading, in order -- empty unless a production node degraded (issue #303). */
    val issues: MutableList<GedraConfigIssue> = mutableListOf()

    override fun onCreate(cxt: KdrCxt) {
        // Both peers are constructed in the factory pass before any `onCreate`, so they are resolvable here even
        // though their own `checkInit` has not run.
        schemaCollector = SchemaCollector.get(cxt)
            ?: throw KdrException("$serviceName ran with no schema collector.")
        sqlTopicService = SqlTopicService.get(cxt)
    }

    override fun checkInit(cxt: KdrCxt) {
        // Idempotent, and it must be: this is a startup service a later one may force to have run by calling
        // `checkInit` directly (as `WorkflowService` forces its peers), and unlike them this pass *adds* to the
        // collector -- so a second, unguarded run would re-add every config and the collector would then report
        // each as contributed twice, refusing the boot. Marked done as soon as it commits to running (there are
        // several early returns below, and a strict-mode problem throws to fail the boot rather than retrying).
        if (isInit) {
            return
        }
        isInit = true
        val collector = schemaCollector ?: throw KdrException("$serviceName.checkInit ran before onCreate.")
        val sql = sqlTopicService ?: throw KdrException("$serviceName.checkInit ran before onCreate.")

        // Load on a persistent node, not an in-memory one. A production node is Postgres-backed and loads what
        // was stored; an in-memory node is a test or a throwaway, whose database is shared across specs within a
        // JVM -- so loading there would drag a config one spec wrote into every other spec's node, for no gain
        // (nothing meaningfully persists across an in-memory node's life). [loadEnvVar] overrides either way, so
        // a test that means to exercise the load turns it on with a database of its own. `isInMemory` is
        // resolved by the topic service's own `checkInit`, called here (idempotently) because this may run
        // before the startup pass reaches it.
        sql.checkInit(cxt)
        val enabled = cxt.getEnvBool(loadEnvVar) ?: !sql.isInMemory
        if (!enabled) {
            return
        }

        // The config topic's whole table set, from the collector rather than the (not-yet-built) store.
        val configTables = collector.tables.filter { it.topic == gedraConfigTopic }
        if (configTables.isEmpty()) {
            // No config tables on this node (the component that declares them is not loaded): nothing to load.
            return
        }
        val contentTable = configTables.firstOrNull { it.tableName == GCT.gedraConfig } ?: return
        sql.reconcileTopicFromTables(cxt, gedraConfigTopic, configTables)

        val rows = readLatestConfigRows(cxt, contentTable)
        if (rows.isEmpty()) {
            return
        }
        // The source clients, snapshotted before any stored config is added, so the extends rule tests against
        // the source-code set alone (a stored config may not become the base another extends).
        val sourceClients = collector.gedraConfigs.configs.mapNotNull { it.client }.associateBy { it.clientId }
        val mode = gedraConfigCheckMode(cxt)

        var loaded = 0
        for (row in rows) {
            val config = try {
                reassemble(cxt, row)
            } catch (e: KdrException) {
                // A row that cannot be turned back into a config is the "bad row" case: refuse in unit/local,
                // degrade in production, exactly as a malformed source config would.
                reportConfigProblem(
                    cxt, mode,
                    GedraConfigIssue(
                        "Stored config '${row[GC.gedraId].toOptStr()}' could not be loaded: ${e.message}",
                        "Dropping the stored config '${row[GC.gedraId].toOptStr()}'.",
                    ),
                    issues,
                )
                continue
            }
            val extendsProblem = extendsProblem(config, sourceClients)
            if (extendsProblem != null) {
                reportConfigProblem(cxt, mode, extendsProblem, issues)
                continue
            }
            // Routes through the same checks and degrade behavior a source config gets; a taken config's
            // fragment/UiBlock overlays are then folded in, gated on the take exactly as the boot loop does.
            if (collector.addGedraConfig(cxt, config)) {
                appendOverlays(cxt, config)
                loaded++
            }
        }
        if (loaded > 0) {
            LogStartup.info(cxt) { "Loaded $loaded stored client configuration(s) at boot." }
        }
        recordRestartLoad(cxt, loaded)
    }

    /** The latest enabled revision of every stored config, across all clients. */
    private fun readLatestConfigRows(cxt: KdrCxt, contentTable: KdrTable): List<Map<String, Any?>> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraConfigTopic)
        // Ordered class then version-desc, so the first enabled row of each class is its latest -- the same
        // reduction `GedraConfigService.listConfigs` does, but across every client rather than one.
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qGedraConfigAllLatest", contentTable.columns,
            "select * from t:${GCT.gedraConfig} order by c:${GC.configId} asc, c:${GC.version} desc",
        )
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) {
            rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, emptyMap())
        }
        val latestByClass = LinkedHashMap<String, Map<String, Any?>>()
        for (row in rows.filter { it[PF.enabled] == true }) {
            val cls = row[GC.configId].toOptStr() ?: continue
            latestByClass.putIfAbsent(cls, row)
        }
        return latestByClass.values.toList()
    }

    /** Turns one stored row into a [GedraConfig] via [reassembleGedraConfig], recovering the namespace it needs. */
    private fun reassemble(cxt: KdrCxt, rowMap: Map<String, Any?>): GedraConfig {
        // Parse the id rather than intern it: GedraService is a regular service and does not exist yet.
        val row = GedraConfigRow.extract(rowMap) { GedraId.parse(it) }
        return reassembleGedraConfig(cxt, row.configId.baseId, namespaceOf(row), row.client, row.entriesBySlot())
    }

    /**
     * The namespace to reassemble a stored config in: the persisted [GedraConfigRow.namespace] (issue #614),
     * falling back to the prefix of a stored qualified type name for a row written before it was persisted, and
     * to empty when the config declares no types (in which case there is nothing to qualify and no namespace to
     * own).
     */
    private fun namespaceOf(row: GedraConfigRow): String {
        if (row.namespace.isNotEmpty()) return row.namespace
        val bySlot = row.entriesBySlot()
        for (slot in listOf(CCT.traitDef, CCT.stateTraitDef, CCT.schemaDef)) {
            for (entry in bySlot[slot].orEmpty()) {
                val typeName = entry[CCT.typeName].toOptStr()
                if (typeName != null && '.' in typeName) return typeName.substringBefore('.')
            }
        }
        return ""
    }

    /**
     * The problem with a stored config's `extendsFromClientId`, or null (issue #614). A config authored from
     * data may extend only a **source template**: the named client must be in [sourceClients] (so a stored
     * config cannot become the base another extends -- "only the source-code definition is pulled in") and must
     * be a [ClientUsageType.template] ("only a template may be named"). A config that extends nothing is fine.
     */
    private fun extendsProblem(config: GedraConfig, sourceClients: Map<String, ClientDef>): GedraConfigIssue? {
        val parentId = config.client?.extendsFromClientId ?: return null
        val parent = sourceClients[parentId]
            ?: return GedraConfigIssue(
                "Stored config '${config.gedraId}' extends '$parentId', which is not defined in source. A " +
                    "configuration authored from data may extend only a source-code client, so that only the " +
                    "source definition is ever pulled in.",
                "Dropping the stored config '${config.gedraId}'.",
            )
        if (parent.usageType != ClientUsageType.template) {
            return GedraConfigIssue(
                "Stored config '${config.gedraId}' extends '$parentId', which is not a template " +
                    "(${parent.usageType}). A configuration authored from data may extend only a template.",
                "Dropping the stored config '${config.gedraId}'.",
            )
        }
        return null
    }

    /**
     * Folds a taken config's fragment and UiBlock overlays into the registries the content services read
     * (issue #456), the way the boot loop does for a source config -- and, like it, only for a config that was
     * taken, so a rejected bundle cannot still change what its client's people read. Safe here because those
     * services are regular-tier and read the registries only after this startup-tier pass.
     */
    private fun appendOverlays(cxt: KdrCxt, config: GedraConfig) {
        if (config.fragments.isNotEmpty()) {
            val existing = (cxt.instanceConfig.get(FRAG.registryKey) as? List<*>)?.filterIsInstance<FragmentSource>().orEmpty()
            cxt.instanceConfig.put(FRAG.registryKey, existing + config.fragments)
        }
        if (config.uiBlocks.isNotEmpty()) {
            val existing = (cxt.instanceConfig.get(UIB.registryKey) as? List<*>)?.filterIsInstance<UiBlockSource>().orEmpty()
            cxt.instanceConfig.put(UIB.registryKey, existing + config.uiBlocks)
        }
    }

    /**
     * Where a restart's "I just loaded newer config" announcement will go (issue #618): a write to a
     * `ClientSyncTracking` table so peers that have not reloaded learn they are behind. Nothing is written yet
     * -- #611 puts the write here so a single obvious place holds it when #618 arrives.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun recordRestartLoad(cxt: KdrCxt, loadedCount: Int) {
        // #618: record this node's restart load into ClientSyncTracking here.
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "GedraConfigLoadService"

        /**
         * Whether to load stored client configurations at boot (issue #614). Unset, a node loads iff it is
         * persistent (not in-memory) -- so a real deployment loads and an in-memory test does not, since an
         * in-memory database is shared across specs within a JVM and has nothing to meaningfully persist. Set it
         * explicitly to force either way; a test exercising the load sets it on with a database of its own.
         */
        val loadEnvVar = EnvVarDef(
            "KDR_LOAD_STORED_CONFIG", group = ENVGRP.gedra,
            defaultDoc = "on for a persistent node, off for an in-memory one",
            description = "Whether a node loads stored client configurations from the database at boot, beside " +
                "the source-declared ones. Unset, it loads only on a persistent (non-in-memory) node.",
        )

        fun get(cxt: KdrCxt): GedraConfigLoadService = cxt.instanceConfig.get(serviceName) as? GedraConfigLoadService
            ?: throw KdrException("The $serviceName is not available on this node.")
    }
}
