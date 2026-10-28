package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.report.RMETA
import com.dynamicruntime.common.gedra.report.RUSR
import com.dynamicruntime.common.gedra.report.ReportSource
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WfDef
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.normalizeUserLabels
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Creating a client from data (issue #1054): its definition written as its first stored configuration, published,
 * and the client loaded -- one call, where it used to be a hand-built bundle write followed by a reload.
 *
 * It adds no rule of its own beyond "the id is not taken" -- and that one is kept twice: asked before anything is
 * built, so a taken id is refused in words that say who holds it, and enforced by the write itself, under the
 * client's lock (`ConfigWrite.mustBeNew`), so two creates of one id cannot both succeed whichever asked first. An id
 * that differs from a taken one only by case is taken too (issue #1106), asked before the write (`caseVariant`).
 *
 * The definition goes through the write every definition
 * goes through: the slot gate (`reassembleForWrite`, issue #1051) holds it to its schema, `writeConfig` to the
 * write's own guards (its namespace, a domain no other client holds) and to a trial reload, which is where the
 * rules that need the deployment's other clients are judged -- that its id is well-formed, its environments a legal
 * set, the client it extends a template that exists. So a create is refused for exactly what a bundle write of the
 * same definition would be, with the same words.
 */
object ClientCreate {
    /** The configuration a new client's starter report is written to (issue #1429), and the report's id. */
    @Suppress("ConstPropertyName")
    const val starterReportsConfig = "reports"

    @Suppress("ConstPropertyName")
    const val starterReport = "forms"

    /** What a create did: the configuration the definition was written to, the definition as stored, and whether this node now carries the client. */
    class Result(
        val client: String,
        val configName: String,
        /** The stored definition, as [ClientDef.toInfo] shapes it. */
        val info: Map<String, Any?>,
        /** Whether this node carries the client now: false when it is not enabled in this node's environment. */
        val present: Boolean,
        /** What the load found in the client's configuration and forgave; empty for a definition that is sound. */
        val issues: List<GedraConfigIssue>,
        /**
         * Why loading the client failed on this node, when it did -- after the definition was stored and published,
         * so the client **was** created. Said in the result rather than thrown: an error would send the caller to
         * create it again, and be refused for an id they would then have no way of knowing they held.
         */
        val loadFailure: String? = null,
        /** The customer made with the client (issue #1107), as stored; null when none was asked for. */
        val customer: Map<String, Any?>? = null,
        /**
         * Why the customer made with the client was left without the client as its root, when it was: the client
         * and its customer were made, and the root is named through the customer's configuration once the cause is
         * mended.
         */
        val customerFailure: String? = null,
    )

    /**
     * Creates the client [request] describes -- [ClientCreateFields] keys to their values, **as the endpoint's
     * input type validated them**: reading a request is what trims its text and leaves a blank optional field out,
     * so neither is done again here. The suggested labels are held to the one label rule ([normalizeUserLabels]),
     * as the definition editor holds them, rather than refused for a stray space.
     *
     * With [CLD.newCustomer] (issue #1107) it makes a **customer** too, whose id is the client's and whose root the
     * client is -- the first client of a new customer, in one call. Three writes, each judged as any is: the
     * customer, through [CustomerStore]; the client, under it; then the customer's root, which can name only a
     * client that is its own. They take two clients' locks (`hub`'s and the new client's), so they are not one
     * transaction: a client the write refuses takes its customer back with it, and a root that cannot be named is
     * said in the result ([Result.customerFailure]) rather than thrown, since the client was made.
     */
    fun create(
        cxt: KdrCxt,
        request: Map<String, Any?>,
        /**
         * The client's first copy (issue #1336): a fragment file's id to its overlay, `namespace -> key -> value`, as
         * the copy editor stores one. Written in the editors' own config ([CPY.copyConfigName]) beside the definition,
         * in the same write -- judged with it, so a key no shipped file declares refuses the create -- and published
         * with it, so it is live at once although a client with a sandbox otherwise saves its copy as drafts. Later
         * edits from the copy editor land in that same config, as they would have. None by default.
         */
        copy: Map<String, Map<String, Map<String, String>>> = emptyMap(),
        /**
         * Whether the client starts with workflows made from the starter templates (issue #1346): each in a
         * configuration of its own, written and published with the definition, and the trait they collect -- the
         * form's name -- added to what the definition includes. Read from the request ([CLD.starterWorkflows]) unless
         * a caller says.
         */
        starterWorkflows: Boolean = request[CLD.starterWorkflows] != false,
    ): Result {
        val client = request[CLD.clientId].toOptStr().orEmpty()
        // First, since a live sandbox's id is one this node knows: it would otherwise read as a client that exists.
        sandboxParentOf(client)?.let { parent ->
            throw KdrException.mkInput(
                "'$client' is a sandbox's id. A sandbox is made by the system from its parent, '$parent', when that " +
                    "client asks for one; it is never created.",
            )
        }
        val bound = cxt.mkSubContext("clientCreate", client)
        val svc = GedraConfigService.get(bound)
        if (svc.clientExists(bound)) throw taken(cxt, client)
        caseVariant(cxt, client)?.let { throw takenByCase(cxt, client, it) }
        val newCustomer = newCustomerOf(cxt, client, request)
        val customer = newCustomer?.let { CustomerStore.put(cxt, it) }
        val starters = if (starterWorkflows) starterTemplates(cxt) else emptyList()
        val definition = withStarterTraits(definitionOf(request), starters) +
            (newCustomer?.let { mapOf(CLD.customerId to client) } ?: emptyMap())
        val name = CLD.definitionConfigName
        val written = try {
            val config = reassembleForWrite(bound, name, clientNamespace(client), client, mapOf(CCT.clientDef to listOf(definition)))
            val writes = buildList {
                add(ConfigWrite(config, mustBeNew = true))
                if (copy.isNotEmpty()) add(ConfigWrite(copyConfig(bound, client, copy), mustBeNew = true))
                starterConfigs(bound, client, starters).forEach { add(ConfigWrite(it, mustBeNew = true)) }
                add(ConfigWrite(starterReportConfig(bound, client, definition), mustBeNew = true))
            }
            try {
                svc.writeConfigs(bound, client, writes, trial = true)
            } catch (e: KdrException) {
                // The id was free when asked and taken by the time the write held the lock: another create of it.
                if (e.code != EXC.conflict) throw e
                throw KdrException(
                    "Client '$client' already exists: its definition was written while this create was under way.", e, EXC.conflict,
                )
            }
        } catch (e: Exception) {
            // Nothing of the client was stored, so the customer made for it goes too.
            if (newCustomer != null) {
                try {
                    CustomerStore.remove(cxt, client)
                } catch (undo: Exception) {
                    LogGedra.error(cxt, "Customer '$client' was made for a client that was refused, and could not be removed.", undo)
                }
            }
            throw e
        }
        // Published, so the client loads whatever its tier: one asking for a sandbox runs only what is published.
        // The write's trial judged this very definition, with the copy written beside it, so the publish has nothing
        // new to refuse; were it to, the configuration stays written and unpublished, and a second create is told the
        // id has one.
        val published = svc.publish(bound, listOf(written.first().configId), trial = true).single()
        // The copy, the starter workflows, and the starter report with it (issues #1336, #1346, #1429): judged with the
        // definition by the write, so their publish has nothing new to refuse either. Were one to fail, the client is
        // made all the same -- its definition is published -- so that config is left a draft, for its administrator to
        // publish, rather than failing a create that has happened.
        for (row in written.drop(1)) {
            try {
                svc.publish(bound, listOf(row.configId), trial = true)
            } catch (e: Exception) {
                LogGedra.error(cxt, "Client '$client' was created, but its copy config '${row.configId}' could not be published.", e)
            }
        }
        // From here the client exists, whatever becomes of loading it: a failure is the result's to say.
        var loadFailure: String? = null
        val issues = try {
            val reload = GedraConfigReload.reloadClient(cxt, client)
            ClientSyncService.get(cxt).announceReload(cxt, reload)
            reload.issues
        } catch (e: Exception) {
            LogGedra.error(cxt, "Client '$client' was created, but loading it failed.", e)
            loadFailure = e.message ?: "The load failed."
            emptyList()
        }
        // The customer's root, now that the client is its own (issue #1107).
        var customerFailure: String? = null
        val rooted = newCustomer?.let { def ->
            try {
                CustomerStore.put(cxt, def.copy(rootClientId = client))
            } catch (e: Exception) {
                LogGedra.error(cxt, "Client '$client' and its customer were created, but naming it the customer's root failed.", e)
                customerFailure = e.message ?: "Naming the root failed."
                customer
            }
        }
        return Result(
            client = client,
            configName = name,
            // Redacted as every stored read is (`testFeatures` off a test instance, #696).
            info = published.slotsForEmission(cxt.instanceConfig.isTestInstance)[CCT.clientDef]?.firstOrNull().toJsonMapOrEmpty(),
            present = ClientService.get(cxt).present(client) != null,
            issues = issues,
            loadFailure = loadFailure,
            customer = rooted,
            customerFailure = customerFailure,
        )
    }

    /**
     * The customer [request] asks to be made with [client] (issue #1107), or null when it asks for none: its id the
     * client's, which is refused when it cannot be a customer's -- a client id holding `_`, a reserved one, one a
     * customer already holds, case aside -- or when the request also names an existing customer.
     */
    private fun newCustomerOf(cxt: KdrCxt, client: String, request: Map<String, Any?>): CustomerDef? {
        val asked = request[CLD.newCustomer].toJsonMapOrEmpty().takeIf { it.isNotEmpty() } ?: return null
        if (request[CLD.customerId].toOptStr() != null) {
            throw KdrException.mkInput("A create names an existing customer or asks for a new one, not both.")
        }
        customerIdFault(client)?.let {
            throw KdrException.mkInput("A new customer takes the client's id, and '$client' cannot be a customer's: it $it.")
        }
        CUST.reserved.firstOrNull { it.equals(client, ignoreCase = true) }?.let {
            throw KdrException.mkInput("A new customer takes the client's id, and '$client' is the reserved '$it'.")
        }
        ClientService.get(cxt).customers.keys.firstOrNull { it.equals(client, ignoreCase = true) }?.let {
            throw KdrException("The customer '$it' already exists; create the client under it instead.", code = EXC.conflict)
        }
        return CustomerDef(
            customerId = client,
            name = asked[CUST.name].toOptStr() ?: throw KdrException.mkInput("A new customer needs a '${CUST.name}'."),
            description = asked[CUST.description].toOptStr(),
        )
    }

    /**
     * The refusal for an id that is taken, saying who holds it: a client this node knows, defined in source code or
     * in stored configuration -- or stored configuration this node has not made a client of, a definition a check
     * dropped or one never published, which is mended or published through the configuration endpoints.
     */
    private fun taken(cxt: KdrCxt, client: String): KdrException {
        val clients = ClientService.get(cxt)
        val message = if (clients.known(client) != null) {
            val where = if (clients.originOf(client) == GedraConfigOrigin.source) "source code" else "stored configuration"
            "Client '$client' already exists: it is defined in $where."
        } else {
            "Client '$client' already has stored configuration, though this node does not carry the client. Its " +
                "configuration is published, or mended, through the configuration endpoints."
        }
        return KdrException(message, code = EXC.conflict)
    }

    /**
     * A client id that differs from [client] only by case (issue #1106): one this node knows, declared anywhere and
     * kept or not, or one with stored configuration. Asked before the write, as "the id is not taken" is, but not
     * under a lock: two ids differing by case take two clients' locks, so creates of `Acme` and `acme` racing could
     * both be stored. The load's check is what holds then -- the later is dropped and reported (`caseVariantOf`).
     */
    private fun caseVariant(cxt: KdrCxt, client: String): String? {
        val clients = ClientService.get(cxt)
        val ids = clients.clients.keys + clients.declaredClients().map { it.clientId } +
            GedraConfigService.get(cxt).storedClients(cxt)
        return caseVariantOf(client, ids)
    }

    /** The refusal for an id that [holder] takes up to case, saying where [holder] is defined. */
    private fun takenByCase(cxt: KdrCxt, client: String, holder: String): KdrException {
        val clients = ClientService.get(cxt)
        val where = when {
            clients.known(holder) == null -> "has stored configuration"
            clients.originOf(holder) == GedraConfigOrigin.source -> "is defined in source code"
            else -> "is defined in stored configuration"
        }
        return KdrException(
            "Client '$client' cannot be created: '$holder' $where, and two client ids may not differ only by case.",
            code = EXC.conflict,
        )
    }

    /** The starter templates ([WFT.starters]) this deployment declares, as its checks admitted them (issue #1346). */
    private fun starterTemplates(cxt: KdrCxt): List<WfDef> =
        WFT.starters.mapNotNull { WorkflowService.get(cxt).forClient(null).workflow(it)?.def }

    /**
     * [definition] including every trait the [starters] collect (issue #1346) -- the form's name -- unless it takes them
     * already, by name or as every global trait, so the workflows it starts with are ones it may run.
     */
    private fun withStarterTraits(definition: Map<String, Any?>, starters: List<WfDef>): Map<String, Any?> {
        val included = definition[CLD.includedTraits].toJsonListOfStrings()
        if (CLD.allGlobal in included) return definition
        val needed = starters.flatMap { w -> w.tasks.flatMap { t -> t.traits.map { it.traitId } } }.distinct()
        val added = needed.filterNot { it in included }
        return if (added.isEmpty()) definition else definition + (CLD.includedTraits to included + added)
    }

    /**
     * A configuration per starter workflow (issue #1346), named for it as the client page names one it makes
     * (`workflow_<id>`): each a workflow made from its template with the template's own slots, so all it stores is the
     * reference, and the administrator renames or re-fills it from the client page.
     */
    private fun starterConfigs(bound: KdrCxt, client: String, starters: List<WfDef>): List<GedraConfig> {
        val taken = LinkedHashSet<String>()
        return starters.map { template ->
            val id = keyFromLabel(template.label, taken, "workflow").also { taken.add(it) }
            val overlay = mapOf(WFD.workflowId to id, WFD.fromTemplate to template.workflowId)
            reassembleForWrite(
                bound, CWF.configPrefix + id, clientNamespace(client), client,
                mapOf(CCT.workflowDef to listOf(mapOf(CCT.workflowId to id, CCT.definition to overlay))),
            )
        }
    }

    /**
     * The configuration holding a new client's starter report (issue #1429), so its Reports page has a report to run
     * from the start: every form with its name, its owner, its status, and when it was made, grouped by status. There is
     * no editor for reports yet, so this is the report a client made from data has. The name column is there only when
     * [definition] includes the trait it reads, as it does whenever the client starts with the starter workflows: a
     * report naming a trait the client does not support would be refused at the write.
     */
    private fun starterReportConfig(bound: KdrCxt, client: String, definition: Map<String, Any?>): GedraConfig {
        val included = definition[CLD.includedTraits].toJsonListOfStrings()
        val withName = GT.name in included || CLD.allGlobal in included
        return gedraConfig(bound, starterReportsConfig, clientNamespace(client), client) {
            report(starterReport, "Forms") {
                description = "Every form, with its name, its owner, its status, and when it was made. Grouped, how many forms " +
                    "have each status."
                if (withName) column("name", "Name", "${ReportSource.form.name}.${GT.name}.${GT.nameField}")
                column("owner", "Owner", "${ReportSource.user.name}.${RUSR.email}")
                column("status", "Status", "${ReportSource.meta.name}.${RMETA.formStatus}")
                column("created", "Created", "${ReportSource.meta.name}.${RMETA.createdAt}")
                groupBy = listOf("status")
            }
        }
    }

    /** The editors' own config ([CPY.copyConfigName]) of [client]'s, holding [copy]'s overlays (issue #1336). */
    private fun copyConfig(bound: KdrCxt, client: String, copy: Map<String, Map<String, Map<String, String>>>): GedraConfig =
        gedraConfig(bound, CPY.copyConfigName, clientNamespace(client), client) {
            for ((fileId, content) in copy) fragmentOverlay(fileId, content)
        }

    /** The definition [request] asks for, as the `kdr:clientDef` slot holds one: its [ClientCreateFields] that were given. */
    private fun definitionOf(request: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (field in ClientCreateFields.names) {
            val value = when (field) {
                CLD.userLabels -> normalizeUserLabels(request[field].toJsonListOfStrings()).ifEmpty { null }
                else -> request[field]
            }
            if (value != null) out[field] = value
        }
        return out
    }
}
