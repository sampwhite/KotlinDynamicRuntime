package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfigIssue
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraTrait
import com.dynamicruntime.common.gedra.supportedTraits
import com.dynamicruntime.common.gedra.workflow.WorkflowRegistry
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.startup.GeneratedSurface
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * Holds the report registries -- the global one and each client's -- with every report bound to what it reads, and
 * checks them as configuration loads (issue #980; see [buildReportRegistries]). The shape of `WorkflowService`, and
 * built after it: a report's workflow paths are bound against the workflows a scope actually kept.
 *
 * Built at boot in `checkInit`, again for one client on its reload (after its workflows), and in the trial a
 * configuration write runs, against the candidate's own types and workflows -- so a write whose report names a field
 * that is not there is refused, naming the path, rather than accepted and shown as a blank column.
 */
class ReportService : ServiceInitializer {
    override val serviceName: String = ReportService.serviceName

    /** The registries, once built; [ReportRegistries.empty] before. Volatile: a reload swaps it. */
    @Volatile
    var registries: ReportRegistries = ReportRegistries.empty
        private set

    /** Problems found while building, in the order found. Empty unless a report was dropped. */
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
        WorkflowService.get(cxt).checkInit(cxt)

        val found = mutableListOf<GedraConfigIssue>()
        val clients = clientService.presentClients.associateBy { it.clientId }
        registries = buildReportRegistries(cxt, collector.gedraConfigs, clients, { liveScope(cxt, collector, clients, it) }, found)
        issues = found.toList()
        publishTyped(cxt, clients.keys, registries)
        isInit = true
    }

    /**
     * Rebuilds [client]'s registry off the current collector, schema and workflows and swaps it in, after the
     * client's workflows have been reloaded. Only this client's reports are judged; the global ones stand as the boot
     * bound them. A problem that refuses throws before anything is published.
     *
     * The client's typed report endpoints are regenerated from the rebuilt registry ([publishTyped]). Returns their
     * collation keys before and after, whose cached types the caller must drop: a report redefined keeps its paths,
     * and a type cached for the old definition would refuse every row of the new.
     */
    fun reloadClient(cxt: KdrCxt, client: String): Set<String> {
        val collector = SchemaCollector.get(cxt)
            ?: throw KdrException("$serviceName.reloadClient ran with no schema collector.")
        val clients = ClientService.get(cxt).presentClients.associateBy { it.clientId }
        val found = mutableListOf<GedraConfigIssue>()
        val current = registries
        val rebuilt = buildReportRegistries(
            cxt, collector.gedraConfigs, clients, { liveScope(cxt, collector, clients, it) }, found,
            onlyClient = client, runningGlobal = current.global,
        )
        val byClient = (current.byClient - client) + (rebuilt.byClient[client]?.let { mapOf(client to it) } ?: emptyMap())
        val next = ReportRegistries(current.global, byClient)
        // The typed endpoints first, and the registry only once they are published: a publication that is refused
        // then leaves both as they were, where the other order would have the shared run answering the new
        // definition and the typed endpoints the old.
        val stale = publishTyped(cxt, setOf(client), next)
        registries = next
        issues = issues.filter { it.client != client } + found
        return stale
    }

    /** The clients whose typed report endpoints are published now. Written by [publishTyped], under the boot or a reload. */
    private var typedClients: Set<String> = emptySet()

    /**
     * Publishes each of [clients]' typed report endpoints (issue #1088; [typedReportSurface]) from the registry it
     * sees in [from] -- its own reports and the global ones -- or withdraws them, for a client no longer present here
     * or left with no report. Returns the collation keys the publication says went stale.
     *
     * Nothing is generated on a node that does not serve the shared run endpoint (an edge): the typed endpoints are
     * that run under other names, and where it is not served they would have nothing to run. And a client that has
     * no typed endpoints and gains none is not published at all, so a reload that touches no report leaves the
     * published schema alone.
     */
    private fun publishTyped(cxt: KdrCxt, clients: Collection<String>, from: ReportRegistries): Set<String> {
        val schema = SchemaService.get(cxt)
        val shared = sharedRun(schema) ?: return emptySet()
        val present = ClientService.get(cxt).presentClients.mapTo(HashSet()) { it.clientId }
        val surfaces = LinkedHashMap<String, GeneratedSurface?>()
        for (client in clients) {
            val surface = if (client in present) typedReportSurface(cxt, client, from.forClient(client), shared) else null
            if (surface != null || client in typedClients) surfaces[client] = surface
        }
        if (surfaces.isEmpty()) return emptySet()
        val stale = schema.publishGenerated(cxt, surfaces)
        typedClients = (typedClients - surfaces.keys) + surfaces.filterValues { it != null }.keys
        return stale
    }

    /** The shared run endpoint the typed ones are made beside, or null on a node that does not serve it. */
    private fun sharedRun(schema: SchemaService): KdrEndpoint? =
        schema.schemaStore.endpoints["${UADEP.reportRun}:${HttpMethod.GET.name}"]

    /**
     * A trial of [client]'s candidate reports over a trial's [scratch] collector: bound against the candidate's
     * supported traits, its variant's [types] and the [workflows] its trial kept, with every problem going to the
     * trial's capture and nothing published. [def] is the candidate definition, null when the client would not be
     * present -- and then it has no reports of its own to judge.
     */
    fun trialClient(
        cxt: KdrCxt,
        scratch: SchemaCollector,
        client: String,
        def: ClientDef?,
        types: Map<String, SchType>,
        droppedTypes: Set<String>,
        workflows: WorkflowRegistry,
    ) {
        val present = if (def != null && def.isEnabledIn(cxt.instanceConfig.env)) mapOf(client to def) else emptyMap()
        // The only scope a trial binds: the global one is inherited from the running node, and the client is asked
        // for only when present, which needs its definition. Anything else would be the scratch collector beside the
        // running node's types and workflows -- a mix nothing should bind against -- so it is a defect, said so.
        val trialDef = present[client] ?: return
        val overlaid = scratch.clientOverlays[client]?.keys ?: emptySet()
        val scope = ReportScope(
            formTraitsOf(supportedTraits(scratch.gedraConfigs, client, trialDef, overlaid, droppedTypes)),
            types,
            workflows.workflows.mapValues { it.value.def },
        )
        val candidate = buildReportRegistries(
            cxt, scratch.gedraConfigs, present,
            scopeOf = { asked ->
                if (asked != client) throw KdrException("A report trial of '$client' was asked to bind the scope '$asked'.")
                scope
            },
            issues = mutableListOf(), onlyClient = client, runningGlobal = registries.global,
        )
        // And the typed endpoints the candidate's reports would be given (issue #1088), asked of the schema without
        // publishing: a surface that cannot be published refuses the write here, not the reload that follows it.
        val schema = SchemaService.get(cxt)
        val shared = sharedRun(schema) ?: return
        typedReportSurface(cxt, client, candidate.forClient(client), shared)?.let { schema.trialGenerated(cxt, it) }
    }

    /** The registry [client] sees; see [ReportRegistries.forClient]. */
    fun forClient(client: String?): ReportRegistry = registries.forClient(client)

    /**
     * What the running node binds [scope]'s reports against: for a client, the traits it supports, its variant's
     * types and its workflow registry; for the global scope (null), the global traits, types and workflows.
     */
    private fun liveScope(cxt: KdrCxt, collector: SchemaCollector, clients: Map<String, ClientDef>, scope: String?): ReportScope {
        val schema = SchemaService.get(cxt)
        val workflows = WorkflowService.get(cxt).forClient(scope).workflows.mapValues { it.value.def }
        val def = scope?.let { clients[it] }
        val traits = if (scope == null || def == null) {
            collector.gedraConfigs.traitsFor(GID.globalClient)
        } else {
            val overlaid = collector.clientOverlays[scope]?.keys ?: emptySet()
            supportedTraits(collector.gedraConfigs, scope, def, overlaid, schema.droppedTypesFor(scope))
        }
        return ReportScope(formTraitsOf(traits), schema.storeFor(scope).types, workflows)
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "ReportService"

        fun get(cxt: KdrCxt): ReportService = cxt.instanceConfig.get(serviceName) as? ReportService
            ?: throw KdrException("The $serviceName is not available on this node.")

        /** The traits of [traits] a form may carry, by id. */
        fun formTraitsOf(traits: List<GedraTrait>): Map<String, GedraTrait> =
            traits.filter { GedraDataType.formDoc in it.appliesTo }.associateBy { it.traitId }
    }
}
