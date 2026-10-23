package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfDeclared
import com.dynamicruntime.common.gedra.workflow.WfDef
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
import com.dynamicruntime.common.gedra.workflow.WfWindows
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.WorkflowTemplates
import com.dynamicruntime.common.gedra.workflow.templateSlotProblems
import com.dynamicruntime.common.gedra.workflow.templateSlotsOf
import com.dynamicruntime.common.gedra.workflow.toJsonMap
import com.dynamicruntime.common.gedra.workflow.withTemplateSlots
import com.dynamicruntime.common.gedra.workflow.withWindows
import com.dynamicruntime.common.gedra.workflow.workflowTemplateOverlay
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * A client's workflows made from templates, on the client page (issue #1120): the catalog the page draws -- the
 * deployment's templates, the client's workflows, and the traits a workflow may collect -- and the save behind
 * `POST /clientAdmin/client/workflows/save`, which makes a workflow from a template or changes the slots of one.
 *
 * ### What is stored
 *
 * A workflow made from a template is stored as its overlay of the template (`WFD.fromTemplate`): only what the client
 * chose. A new one gets a stored configuration of its own, named for it ([CWF.configPrefix]); a change is patched where
 * the workflow is stored, and keeps whatever else its overlay holds -- Design View's layouts and save labels. The
 * slots are the workflow's name and, per data-entry task, its label and traits ([WfTemplateSlots]); everything else is
 * the template's, and the page explains it rather than editing it.
 *
 * ### How it takes effect
 *
 * As every editor's save on the client's page does ([ClientStoredEdit]): live, or a draft for a client with a sandbox.
 * The write's trial judges the workflow the template makes -- a trait the client cannot collect, a cfact a template's
 * rule reads that the client lacks, a plan's limit -- as the load would. A change to a workflow forms already engage
 * goes through the impact report (issue #935), and a draft reports what publishing it would do, for information.
 */
object ClientWorkflowEdit {
    /** What a save did: the configuration the workflow is in, its slots now, how it took effect, and a draft's impact. */
    class Result(
        val configName: String,
        val slots: WfTemplateSlots,
        val issues: List<GedraConfigIssue>,
        val mode: String,
        val draftImpact: ImpactReport?,
    )

    /** The catalog for [client]'s page -- see the class note -- as the catalog endpoint answers it. */
    fun catalog(cxt: KdrCxt, client: String): Map<String, Any?> {
        val workflows = WorkflowService.get(cxt)
        val own = workflows.forClient(client).workflows.values.toList()
        val templates = workflows.forClient(null).workflows
        val limits = PlanRoom.of(cxt, client)
        return buildMap {
            put(CLD.client, client)
            put(CWF.templates, templates.values.map { templateInfo(it.def, own, limits) })
            put(CWF.workflows, own.map { workflowInfo(client, it, templates.keys) })
            put(CWF.traits, usableTraits(cxt, client))
            ClientService.get(cxt).displayZoneOf(client)?.let { put(CWF.timeZone, it) }
        }
    }

    /** What a client's plan leaves room for, read once for the catalog: its name, its quotas, and the workflows held. */
    private class PlanRoom(val plan: String, val quotas: PlanQuotas, val workflowsHeld: Int) {
        companion object {
            fun of(cxt: KdrCxt, client: String): PlanRoom? {
                val plan = PlanLimits.planOf(cxt, client) ?: return null
                val held = SchemaCollector.get(cxt)?.let { PlanLimits.configUsageOf(it, client).workflows } ?: 0
                return PlanRoom(plan, PLAN.quotasOf(plan), held)
            }
        }
    }

    private fun templateInfo(template: WfDef, own: List<WfDeclared>, limits: PlanRoom?): Map<String, Any?> = buildMap {
        put(CWF.definition, template.toJsonMap())
        refusalOf(template, own, limits)?.let { put(CWF.refusal, it) }
    }

    /**
     * Why a workflow cannot be made from [template] now, or null (issue #1120): a client has one creation and one
     * survey, and a plan ([limits]; null for a client on none) limits its workflows and their tasks. The trial would
     * refuse each; this says so first.
     */
    private fun refusalOf(template: WfDef, own: List<WfDeclared>, limits: PlanRoom?): String? {
        if (template.entry in WfEntry.singletons) {
            own.firstOrNull { it.def.entry == template.entry }?.let {
                return "This client already has a ${template.entry} workflow, '${it.def.workflowId}', and can have only one."
            }
        }
        limits ?: return null
        limits.quotas.workflows?.let { limit ->
            if (limits.workflowsHeld >= limit) {
                return "The ${limits.plan} plan allows $limit workflows, and this client has ${limits.workflowsHeld}."
            }
        }
        limits.quotas.tasksPerWorkflow?.let { limit ->
            if (template.tasks.size > limit) return "It has ${template.tasks.size} tasks, and the ${limits.plan} plan allows $limit in a workflow."
        }
        return null
    }

    private fun workflowInfo(client: String, declared: WfDeclared, templateIds: Set<String>): Map<String, Any?> = buildMap {
        val def = declared.def
        put(CWF.definition, def.toJsonMap())
        if (declared.bundle.isStored && declared.bundle.gedraId.client == client) put(CWF.configName, declared.bundle.name)
        notEditable(client, declared, templateIds)?.let { put(CWF.notEditable, it) }
        if (def.entry == WfEntry.normal) windowsNotEditable(client, declared)?.let { put(CWF.windowsNotEditable, it) }
    }

    /**
     * Why a normal workflow's time windows cannot be changed here (issue #1121), or null: they are the client's to set
     * on any workflow it stores as its own, made from a template or written whole.
     */
    private fun windowsNotEditable(client: String, declared: WfDeclared): String? = when {
        !declared.bundle.isStored -> "It is declared in source code, where its windows are set."
        declared.bundle.gedraId.client != client || declared.bundle.inheritedFrom != null ->
            "It comes from the client this one is built on, '${declared.bundle.inheritedFrom ?: declared.bundle.gedraId.client}'."
        else -> null
    }

    /** Why [declared]'s slots cannot be changed here, or null when they can. */
    private fun notEditable(client: String, declared: WfDeclared, templateIds: Set<String>): String? = when {
        declared.def.fromTemplate == null -> "It is written whole, not made from a template: its layouts and labels are edited in Design View."
        declared.def.fromTemplate !in templateIds -> "Its template, '${declared.def.fromTemplate}', is not offered by this deployment now."
        !declared.bundle.isStored -> "It is declared in source code, where it is edited."
        declared.bundle.gedraId.client != client || declared.bundle.inheritedFrom != null ->
            "It comes from the client this one is built on, '${declared.bundle.inheritedFrom ?: declared.bundle.gedraId.client}'."
        else -> null
    }

    /** The traits [client]'s workflows may collect: those it supports that a form may carry, with their descriptions. */
    private fun usableTraits(cxt: KdrCxt, client: String): List<Map<String, Any?>> {
        val def = ClientService.get(cxt).known(client) ?: return emptyList()
        val collector = SchemaCollector.get(cxt) ?: return emptyList()
        val schema = SchemaService.get(cxt)
        val served = schema.schemaStore.servedDefs
        return supportedTraits(collector.gedraConfigs, client, def, schema.overlaidTypesFor(client), schema.droppedTypesFor(client))
            .filter { GedraDataType.formDoc in it.appliesTo }
            .map { trait ->
                buildMap {
                    put(CCT.traitId, trait.traitId)
                    (served[trait.typeName] ?: schema.storeFor(client).defs[trait.typeName]).toJsonMapOrEmpty()[SCH.description]
                        .toOptStr()?.let { put(CWF.description, it) }
                }
            }
    }

    /**
     * Saves [slots] for [client]: a new workflow made from their template when they name no workflow, a change of the
     * stored one they name otherwise -- see the class note. [expected] is how the caller told its user the save would
     * take effect; [impact] whether a save that affects stored forms goes ahead.
     */
    fun save(cxt: KdrCxt, client: String, slots: WfTemplateSlots, expected: String? = null, impact: ImpactGate = ImpactGate.refuse): Result {
        sandboxParentOf(client)?.let { parent ->
            throw KdrException.mkInput("'$client' is a sandbox: its workflows are its parent's, which are made on '$parent'.")
        }
        // Only a template the deployment's checks admitted.
        val template = WorkflowService.get(cxt).forClient(null).workflow(slots.templateId)?.def
            ?: throw KdrException("There is no workflow template '${slots.templateId}'.", code = EXC.notFound)
        templateSlotProblems(template, slots).takeIf { it.isNotEmpty() }?.let { throw KdrException.mkInput(it.joinToString(" ")) }
        val target = ClientStoredEdit.target(cxt, client, "workflowSave", expected)
        val bound = target.bound
        val svc = GedraConfigService.get(bound)
        val written: GedraConfigRow
        val reload: ConfigReloadResult
        val made: WfDef
        val workflowId = slots.workflowId
        if (workflowId == null) {
            // Nor the name of a configuration left from one, which a new workflow's configuration may not take.
            val leftover = svc.listConfigs(bound).map { it.configId.baseId }.filter { it.startsWith(CWF.configPrefix) }
                .map { it.removePrefix(CWF.configPrefix) }
            val taken = WorkflowService.get(cxt).forClient(client).workflows.keys + storedWorkflowIds(bound) + leftover
            val id = keyFromLabel(slots.label, taken, "workflow")
            made = withTemplateSlots(bound, template, slots, id)
            val entry = mapOf(CCT.workflowId to id, CCT.definition to workflowTemplateOverlay(template, made))
            val config = reassembleForWrite(bound, CWF.configPrefix + id, clientNamespace(client), client, mapOf(CCT.workflowDef to listOf(entry)))
            written = svc.writeConfig(bound, config, trial = true, mustBeNew = true)
            // Nothing stored can be engaged with a workflow that did not exist, so there is no impact to judge.
            reload = ClientStoredEdit.takeEffect(cxt, target, written, undo = {}, impact = impact)
        } else {
            val (row, stored) = storedWorkflow(bound, workflowId)
                ?: throw KdrException("Client '$client' stores no workflow '$workflowId'.", code = EXC.notFound)
            if (stored[WFD.fromTemplate] != slots.templateId) {
                throw KdrException.mkInput("Workflow '$workflowId' is not made from template '${slots.templateId}', so its slots are not these.")
            }
            if (!row.configId.baseId.startsWith(CWF.configPrefix)) ClientStoredEdit.requireNoForeignDraft(target, row, "a change to its workflows")
            made = withTemplateSlots(bound, WorkflowTemplates.readStored(bound, stored), slots, workflowId)
            val overlay = workflowTemplateOverlay(template, made)
            if (overlay == stored) throw KdrException.mkInput("Workflow '$workflowId' is already so; nothing to save.")
            fun patch(on: GedraConfigRow, to: Map<String, Any?>) = svc.patchConfigs(bound, trial = true) { edit(on.configId) { slotsOf ->
                slotsOf + (CCT.workflowDef to slotsOf[CCT.workflowDef].orEmpty().map { if (it[CCT.workflowId] == workflowId) it + (CCT.definition to to) else it })
            } }.single()
            written = patch(row, overlay)
            reload = ClientStoredEdit.takeEffect(
                cxt, target, written,
                undo = { patch(written, stored) },
                restorePublished = row.isPublished,
                impact = impact,
            )
        }
        return Result(written.configId.baseId, templateSlotsOf(made), reload.issues, target.mode, ClientStoredEdit.draftImpact(target, written))
    }

    /** What a windows save did: the configuration the workflow is in, its windows now, how it took effect, and a draft's impact. */
    class WindowsResult(
        val configName: String,
        val windows: WfWindows,
        val issues: List<GedraConfigIssue>,
        val mode: String,
        val draftImpact: ImpactReport?,
    )

    /**
     * Sets the time windows of [client]'s normal workflow [workflowId] to [windows] (issue #1121), where the workflow is
     * stored: into its overlay for one made from a template, into its definition otherwise. Takes effect as [save]
     * does. A live change that would, now, freeze or hide forms engaged with the workflow -- an end brought forward, a
     * start pushed back -- is refused with the impact report until acknowledged.
     */
    fun saveWindows(
        cxt: KdrCxt,
        client: String,
        workflowId: String,
        windows: WfWindows,
        expected: String? = null,
        impact: ImpactGate = ImpactGate.refuse,
    ): WindowsResult {
        sandboxParentOf(client)?.let { parent ->
            throw KdrException.mkInput("'$client' is a sandbox: its workflows are its parent's, which are changed on '$parent'.")
        }
        val target = ClientStoredEdit.target(cxt, client, "workflowWindows", expected)
        val bound = target.bound
        val svc = GedraConfigService.get(bound)
        val (row, stored) = storedWorkflow(bound, workflowId)
            ?: throw KdrException("Client '$client' stores no workflow '$workflowId'; its windows are set where it is declared.", code = EXC.notFound)
        val current = WorkflowTemplates.readStored(bound, stored)
        if (current.entry != WfEntry.normal) {
            throw KdrException.mkInput("Workflow '$workflowId' is a ${current.entry} workflow, which has no time windows; only a normal workflow does.")
        }
        windows.problem(workflowId)?.let { throw KdrException.mkInput(it) }
        if (current.windows == windows) throw KdrException.mkInput("Workflow '$workflowId' already has these windows; nothing to save.")
        val definition = WorkflowTemplates.storedForm(bound, withWindows(bound, current, windows))
        if (!row.configId.baseId.startsWith(CWF.configPrefix)) ClientStoredEdit.requireNoForeignDraft(target, row, "a change to its workflows")
        fun patch(on: GedraConfigRow, to: Map<String, Any?>) = svc.patchConfig(bound, on.configId, trial = true) { slots ->
            slots + (CCT.workflowDef to slots[CCT.workflowDef].orEmpty().map { if (it[CCT.workflowId] == workflowId) it + (CCT.definition to to) else it })
        }
        val written = patch(row, definition)
        val reload = ClientStoredEdit.takeEffect(cxt, target, written, undo = { patch(written, stored) }, restorePublished = row.isPublished, impact = impact)
        return WindowsResult(written.configId.baseId, windows, reload.issues, target.mode, ClientStoredEdit.draftImpact(target, written))
    }

    /** Every workflow id [bound]'s client stores, latest revisions, drafts included: none a new one may take. */
    private fun storedWorkflowIds(bound: KdrCxt): Set<String> = GedraConfigService.get(bound).listConfigs(bound)
        .flatMap { row -> row.entriesBySlot()[CCT.workflowDef].orEmpty().mapNotNull { it[CCT.workflowId].toOptStr() } }.toSet()

    /** The stored configuration holding [bound]'s client's workflow [workflowId], and its definition as stored. */
    private fun storedWorkflow(bound: KdrCxt, workflowId: String): Pair<GedraConfigRow, Map<String, Any?>>? {
        for (row in GedraConfigService.get(bound).listConfigs(bound)) {
            val entry = row.entriesBySlot()[CCT.workflowDef].orEmpty().firstOrNull { it[CCT.workflowId] == workflowId } ?: continue
            return row to entry[CCT.definition].toJsonMapOrEmpty()
        }
        return null
    }
}
