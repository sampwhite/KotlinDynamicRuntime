package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.content.FragmentAudience
import com.dynamicruntime.common.content.FragmentSource
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.content.mergeFragmentLayers
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * Holds the workflow registries -- the global one and each client's -- and builds them at boot with the
 * checks that decide what a scope actually sees (issue #533; see [buildWorkflowRegistries]).
 *
 * Built in `checkInit` rather than as part of `SchemaService`, because the checks need three peers to be
 * ready: the clients (`ClientService`), the compiled schema and its per-client overlays (`SchemaService`),
 * and the fragment registry (`MarkdownFragmentService`) for the labels. Each is asked to `checkInit` first,
 * which the service contract makes safe.
 */
class WorkflowService : ServiceInitializer {
    override val serviceName: String = WorkflowService.serviceName

    /** The registries, once built; [WorkflowRegistries.empty] before. Volatile: a reload swaps it (issue #616). */
    @Volatile
    var registries: WorkflowRegistries = WorkflowRegistries.empty
        private set

    /** Problems found while building, in the order found. Empty unless something degraded. */
    var issues: List<GedraConfigIssue> = emptyList()
        private set

    private var isInit = false

    override fun checkInit(cxt: KdrCxt) {
        if (isInit) {
            return
        }
        val collector = SchemaCollector.get(cxt)
            ?: throw KdrException("$serviceName.checkInit ran with no schema collector.")
        val clientService = ClientService.get(cxt).also { it.checkInit(cxt) }
        SchemaService.get(cxt).checkInit(cxt)
        val fragmentService = MarkdownFragmentService.get(cxt)

        val found = mutableListOf<GedraConfigIssue>()
        registries = build(cxt, collector, clientService, fragmentService, found)
        issues = found.toList()
        isInit = true
    }

    /** The whole build over the current collector; run at boot, and again by [reloadClient]. */
    private fun build(
        cxt: KdrCxt,
        collector: SchemaCollector,
        clientService: ClientService,
        fragmentService: MarkdownFragmentService,
        found: MutableList<GedraConfigIssue>,
        onlyClient: String? = null,
    ): WorkflowRegistries {
        val clients: Map<String, ClientDef?> = clientService.presentClients.associateBy { it.clientId }
        val registries = buildWorkflowRegistries(
            cxt, collector.gedraConfigs, clients,
            overlaidTypes = { collector.clientOverlays[it]?.keys ?: emptySet() },
            droppedTypes = { SchemaService.get(cxt).droppedTypesFor(it) },
            fragments = { client, fileId, namespace, key ->
                fragmentService.effectiveFragmentsFor(cxt, fileId, client)?.let {
                    WfFragmentHit(
                        found = it.found,
                        backend = it.audience == FragmentAudience.backend,
                        present = it.content[namespace]?.get(key) != null,
                    )
                }
            },
            cfactNames = { SchemaService.get(cxt).cfactsFor(it).names },
            issues = found,
            onlyClient = onlyClient,
            runningGlobal = if (onlyClient != null) this.registries.global else null,
        )
        // The second pass (issue #677): now that every component has registered its function kinds, resolve each
        // definition's function usages into runnable functions, in place. A function that will not resolve is a
        // config problem reported into `found`, exactly as an unusable trait is above.
        resolveWorkflowFunctions(cxt, collector.gedraConfigs, collector.workflowFunctions, found, onlyClient)
        return registries
    }

    /**
     * Rebuilds [client]'s workflow registry off the current collector and swaps it in (issue #616). Only this
     * client's scope is built and checked (issue #842) -- its registry is global plus its own, and global is the
     * running one, carried across rather than re-judged -- and only its function usages are resolved, so no other
     * client's problems are reported again or can refuse this reload. The result is published by copy-on-write
     * over the registries the other clients keep. A problem that would have refused the boot throws here before
     * anything is published, leaving the running registries as they were.
     */
    fun reloadClient(cxt: KdrCxt, client: String) {
        val collector = SchemaCollector.get(cxt)
            ?: throw KdrException("$serviceName.reloadClient ran with no schema collector.")
        val found = mutableListOf<GedraConfigIssue>()
        val rebuilt = build(cxt, collector, ClientService.get(cxt), MarkdownFragmentService.get(cxt), found, client)
        val current = registries
        val byClient = (current.byClient - client) + (rebuilt.byClient[client]?.let { mapOf(client to it) } ?: emptyMap())
        registries = WorkflowRegistries(current.global, byClient)
        // Only this client's scope was judged (issue #842), so only its issues are replaced; the rest stand.
        issues = issues.filter { it.client != client } + found
    }

    /**
     * A trial of [client]'s candidate workflows (issue #843) over a trial's [scratch] collector: its scope built and
     * checked against the candidate definition [def], cfact names and layers, inheriting from the running global
     * registry, and its function usages resolved -- every problem going to the trial's capture, nothing published.
     */
    fun trialClient(
        cxt: KdrCxt,
        scratch: SchemaCollector,
        client: String,
        def: ClientDef?,
        fragmentSources: List<FragmentSource>,
        cfactNames: Set<String>,
        droppedTypes: Set<String>,
    ) {
        val found = mutableListOf<GedraConfigIssue>()
        val clients: Map<String, ClientDef?> =
            if (def != null && def.isEnabledIn(cxt.instanceConfig.env)) mapOf(client to def) else emptyMap()
        buildWorkflowRegistries(
            cxt, scratch.gedraConfigs, clients,
            overlaidTypes = { scratch.clientOverlays[it]?.keys ?: emptySet() },
            fragments = { c, fileId, namespace, key ->
                mergeFragmentLayers(fileId, fragmentSources.filter { it.fileId == fileId }, c).let {
                    WfFragmentHit(
                        found = it.found,
                        backend = it.audience == FragmentAudience.backend,
                        present = it.content[namespace]?.get(key) != null,
                    )
                }
            },
            cfactNames = { scope ->
                if (scope == client) cfactNames else SchemaService.get(cxt).cfactsFor(scope).names
            },
            issues = found,
            onlyClient = client,
            runningGlobal = registries.global,
            droppedTypes = { if (it == client) droppedTypes else emptySet() },
        )
        resolveWorkflowFunctions(
            cxt, scratch.gedraConfigs, scratch.workflowFunctions, found, client,
            clientDefOf = { if (it == client) def else ClientService.get(cxt).present(it) },
            cfactNamesOf = { if (it == client) cfactNames else SchemaService.get(cxt).cfactsFor(it).names },
            assign = false,
        )
    }

    /** The registry [client] sees; see [WorkflowRegistries.forClient]. */
    fun forClient(client: String?): WorkflowRegistry = registries.forClient(client)

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "WorkflowService"

        fun get(cxt: KdrCxt): WorkflowService = cxt.instanceConfig.get(serviceName) as? WorkflowService
            ?: throw KdrException("The $serviceName is not available on this node.")
    }
}
