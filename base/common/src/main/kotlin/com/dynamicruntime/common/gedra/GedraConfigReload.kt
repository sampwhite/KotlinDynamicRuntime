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
    /** Problems the collector reported while taking the configs (a degraded production node). */
    val issues: List<GedraConfigIssue>,
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

    fun reloadClient(cxt: KdrCxt, client: String): ConfigReloadResult = synchronized(lock) {
        val collector = SchemaCollector.get(cxt) ?: throw KdrException("No schema collector to reload into.")
        val loader = GedraConfigLoadService.get(cxt)
        val configService = GedraConfigService.get(cxt)

        // The client's current stored configuration, by its protection tier (issue #617): the latest revision
        // of each class, or the latest *published* one for a published-only (or static) client.
        val bound = if (cxt.client == client) cxt else cxt.mkSubContext("configReload", client)
        val fresh = configService.currentConfigs(bound, client).map { loader.toConfig(cxt, it) }
        // The extends rule a data config is held to, against the source-code clients alone.
        val loadedIds = loader.allLoadedIds()
        val sourceClients = collector.gedraConfigs.configs
            .filter { it.gedraId.fullId !in loadedIds }.mapNotNull { it.client }.associateBy { it.clientId }
        fresh.firstNotNullOfOrNull { loader.extendsProblem(it, sourceClients) }?.let {
            throw KdrException.mkInput(it.message)
        }

        // --- phase one: swap the collectors, reversibly ---
        val previous = loader.loadedFor(client)
        val issuesBefore = collector.gedraConfigs.issues.size
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
        val issues = collector.gedraConfigs.issues.drop(issuesBefore)

        // --- phase two: rebuild and publish, in order ---
        // The schema swap and the eviction of its path-keyed types go together, back to back: a request between
        // them would resolve against the new store but find a type parsed against the old one. The two cannot be
        // made one atomic step without versioning the cache, so the window is kept to the unavoidable minimum.
        val typeKeys = SchemaService.get(cxt).reloadClient(cxt, client)
        RequestService.get(cxt).evictTypes(typeKeys)
        ClientService.get(cxt).recheck(cxt)
        // Overlays before workflows, as at boot: admitting a workflow validates its labels against the
        // fragments, so the fragments a revision adds must be in place before its workflows are judged.
        MarkdownFragmentService.get(cxt).reloadClient(
            cxt, client, previous.flatMap { it.fragments }, taken.flatMap { it.fragments },
        )
        UiBlockService.get(cxt).reloadClient(cxt, client, previous.flatMap { it.uiBlocks }, taken.flatMap { it.uiBlocks })
        WorkflowService.get(cxt).reloadClient(cxt, client)

        LogStartup.info(cxt) { "Reloaded client '$client': ${taken.size} stored configuration(s), ${typeKeys.size} type-cache entries dropped." }
        ConfigReloadResult(client, taken.size, typeKeys.size, issues)
    }
}
