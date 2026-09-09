package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.content.FragmentAudience
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.gedraConfigCheckMode
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
    ): WorkflowRegistries {
        val clients: Map<String, ClientDef?> = clientService.presentClients.associateBy { it.clientId }
        return buildWorkflowRegistries(
            cxt, collector.gedraConfigs, clients,
            overlaidTypes = { collector.clientOverlays[it]?.keys ?: emptySet() },
            fragments = { client, fileId, namespace, key ->
                fragmentService.effectiveFragmentsFor(cxt, fileId, client)?.let {
                    WfFragmentHit(
                        found = it.found,
                        backend = it.audience == FragmentAudience.backend,
                        present = it.content[namespace]?.get(key) != null,
                    )
                }
            },
            mode = gedraConfigCheckMode(cxt), issues = found,
        )
    }

    /**
     * Rebuilds [client]'s workflow registry off the current collector and swaps it in (issue #616). The whole
     * build runs -- it is the one place every check lives, and a client's registry is global plus its own, so
     * the global half comes out identical -- and only this client's entry is taken from it, published by
     * copy-on-write over the registries the other clients keep. A problem that would have refused the boot
     * throws here before anything is published, leaving the running registries as they were.
     */
    fun reloadClient(cxt: KdrCxt, client: String) {
        val collector = SchemaCollector.get(cxt)
            ?: throw KdrException("$serviceName.reloadClient ran with no schema collector.")
        val found = mutableListOf<GedraConfigIssue>()
        val rebuilt = build(cxt, collector, ClientService.get(cxt), MarkdownFragmentService.get(cxt), found)
        val current = registries
        val byClient = (current.byClient - client) + (rebuilt.byClient[client]?.let { mapOf(client to it) } ?: emptyMap())
        registries = WorkflowRegistries(current.global, byClient)
        issues = issues + found
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
