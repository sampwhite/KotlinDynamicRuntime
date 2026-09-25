package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.uiblock.UiBlockService

/** What a reload did: how many stored configs the node now runs for the client, and any it had to drop. */
class ConfigReloadResult(
    val client: String,
    /** The stored configs the collector took -- what the node now runs for this client. */
    val loaded: Int,
    /** Path-keyed compiled-type cache entries dropped, so the next request re-parses against the new store. */
    val evictedTypes: Int,
    /**
     * Every issue the client's configuration now has, from every phase of the reload (issue #840) -- what the
     * client's own list holds afterward, not just what the collector reported while taking the configs.
     */
    val issues: List<GedraConfigIssue>,
    /** The newest configuration date the client now runs at (issue #618): what the sync tracking announces. */
    val marker: kotlin.time.Instant?,
)

/**
 * Reloads one client's stored configuration on a running node (issue #616) -- the mechanism the multi-node
 * sync trigger (#618) will call, and the one thing that makes the configuration "dynamic".
 *
 * ### Two phases, and the line between them
 *
 * **Phase one is reversible.** The client's current stored configs are read (through #615's cache, the latest
 * revision per class -- #617 will choose the published one under protection), reassembled, and swapped into
 * the collectors: the data-loaded configs the node holds for the client are withdrawn and the new ones added
 * through the very checks the boot runs. If any of that throws, the withdrawal is undone and nothing has
 * changed -- the collectors are the *input* to everything below, so they must be right before anything is
 * rebuilt from them.
 *
 * **Phase two publishes, in a fixed order.** Each service rebuilds the client's derived state off the
 * collectors and swaps it in under its own reference: the schema (variant, endpoint copies, cfacts, in one
 * snapshot) with its path-keyed type-cache entries dropped right behind it, the client set, the fragment and
 * UiBlock overlays, and then the workflow registries -- overlays before workflows, as at boot, because a
 * workflow is admitted by validating its labels against the fragments. A request in flight keeps the
 * store it started with; a new request sees a whole new set. What this does **not** promise is atomicity
 * *across* those services: a rebuild that throws part-way (a check that would have refused the boot) leaves
 * the earlier swaps in place. That is deliberate rather than papered over -- the collectors already hold the
 * new configuration by then, so the recorded state is the new one and **re-running the reload is the repair**:
 * it withdraws and re-adds the same configs and re-runs every publish, idempotently.
 *
 * Reloads are serialized. Per client only; a global reload is a restart.
 */
object GedraConfigReload {
    private val lock = Any()

    /**
     * Runs [block] under the reload lock -- for a trial (issue #843), which reads the collectors a reload swaps and
     * so must not overlap one, or it could copy them half-swapped.
     */
    fun <T> underReloadLock(block: () -> T): T = synchronized(lock) { block() }

    fun reloadClient(cxt: KdrCxt, client: String): ConfigReloadResult = synchronized(lock) {
        // The client's issue list is replaced by what this reload finds (issue #840): cleared first, so every
        // phase records afresh, and restored if the reload throws, so a refused reload leaves it as it was.
        val issueRegistry = ClientConfigIssues.get(cxt)
        val priorIssues = issueRegistry.replace(client, emptyList())
        try {
            reloadLocked(cxt, client)
        } catch (e: Exception) {
            issueRegistry.replace(client, priorIssues)
            throw e
        }
    }

    private fun reloadLocked(cxt: KdrCxt, client: String): ConfigReloadResult {
        val collector = SchemaCollector.get(cxt) ?: throw KdrException("No schema collector to reload into.")
        val loader = GedraConfigLoadService.get(cxt)
        val configService = GedraConfigService.get(cxt)

        // The client's current stored configuration, by its protection tier (issue #617): the latest revision
        // of each class, or the latest *published* one for a published-only (or static) client.
        val bound = if (cxt.client == client) cxt else cxt.mkSubContext("configReload", client)
        val currentRows = configService.currentConfigs(bound, client)
        // The marker peers compare against (issue #618) is the newest of what this client now consumes: the
        // newest consumed revision, and the tier row's own date -- because a tier toggle (#617) changes what is
        // consumed without touching a content row, and can even make the consumed set older, so a content-only
        // marker with a monotonic-max announce would never carry a toggle to the other nodes.
        val contentMarker = currentRows.mapNotNull { it.updatedAt }.maxOrNull()
        val marker = listOfNotNull(contentMarker, configService.tierMarker(bound, client)).maxOrNull()
        // The extends rule a data config is held to, against the source-code clients alone.
        val loadedIds = loader.allLoadedIds()
        val sourceClients = collector.gedraConfigs.configs
            .filter { it.gedraId.fullId !in loadedIds }.mapNotNull { it.client }.associateBy { it.clientId }
        // A row that will not reassemble, or a config breaking the extends rule, costs only that config (issue
        // #841) -- judged as stored config, as the boot load judges it -- rather than refusing the whole reload.
        // Reported before phase one, so a strict refusal changes nothing.
        val reloadIssues = mutableListOf<GedraConfigIssue>()
        // A client static here takes nothing stored (issue #824): `currentConfigs` gave none, and it is said so when
        // the database holds some.
        if (configService.isStaticHere(bound, client) && configService.listConfigs(bound).isNotEmpty()) {
            reportConfigProblem(cxt, loader.staticIgnoredIssue(client), reloadIssues)
        }
        val fresh = currentRows.mapNotNull { row ->
            val config = try {
                loader.toConfig(cxt, row)
            } catch (e: KdrException) {
                reportConfigProblem(cxt, loader.unloadableIssue(row.configId.fullId, row.client, e), reloadIssues)
                return@mapNotNull null
            }
            val problem = loader.storedConfigProblem(config, sourceClients)
            if (problem != null) {
                reportConfigProblem(cxt, problem, reloadIssues)
                null
            } else {
                loader.unknownSlotsIssue(row)?.let { reportConfigProblem(cxt, it, reloadIssues) }
                config
            }
        }

        // --- phase one: swap the collectors, reversibly ---
        val previous = loader.loadedFor(client)
        val taken = mutableListOf<GedraConfig>()
        try {
            previous.forEach { collector.removeGedraConfig(it) }
            for (config in fresh) {
                if (collector.addGedraConfig(cxt, config)) taken.add(config)
            }
        } catch (e: Exception) {
            taken.forEach { collector.removeGedraConfig(it) }
            previous.forEach { collector.addGedraConfig(cxt, it) }
            throw e
        }
        // Recorded now, so a failure below is repaired by running the reload again over the right prior set.
        loader.recordLoaded(client, taken)

        // --- phase two: rebuild and publish, in order ---
        // The schema swap and the eviction of its path-keyed types go together, back to back: a request between
        // them would resolve against the new store but find a type parsed against the old one. The two cannot be
        // made one atomic step without versioning the cache, so the window is kept to the unavoidable minimum.
        val typeKeys = SchemaService.get(cxt).reloadClient(cxt, client)
        RequestService.get(cxt).evictTypes(typeKeys)
        ClientService.get(cxt).recheck(cxt, client)
        // Overlays before workflows, as at boot: admitting a workflow validates its labels against the
        // fragments, so the fragments a revision adds must be in place before its workflows are judged.
        MarkdownFragmentService.get(cxt).reloadClient(
            cxt, client, previous.flatMap { it.fragments }, taken.flatMap { it.fragments },
        )
        UiBlockService.get(cxt).reloadClient(cxt, client, previous.flatMap { it.uiBlocks }, taken.flatMap { it.uiBlocks })
        WorkflowService.get(cxt).reloadClient(cxt, client)

        LogStartup.info(cxt) { "Reloaded client '$client': ${taken.size} stored configuration(s), ${typeKeys.size} type-cache entries dropped." }
        val issues = ClientConfigIssues.get(cxt).issuesFor(client)
        return ConfigReloadResult(client, taken.size, typeKeys.size, issues, marker)
    }
}
