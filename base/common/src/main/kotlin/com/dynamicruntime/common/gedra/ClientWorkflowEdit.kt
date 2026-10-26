package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfDeclared
import com.dynamicruntime.common.gedra.workflow.WfDef
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
import com.dynamicruntime.common.gedra.workflow.ResolvedWorkflowParams
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WfParams
import com.dynamicruntime.common.gedra.workflow.WfParamsSetting
import com.dynamicruntime.common.gedra.workflow.WorkflowParamsService
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.WorkflowTemplates
import com.dynamicruntime.common.gedra.workflow.templateSlotProblems
import com.dynamicruntime.common.gedra.workflow.templateSlotsOf
import com.dynamicruntime.common.gedra.workflow.toJsonMap
import com.dynamicruntime.common.gedra.workflow.withTemplateSlots
import com.dynamicruntime.common.gedra.workflow.workflowTemplateOverlay
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.ReadScopeRules
import com.dynamicruntime.common.user.UserService
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
        // Named for a sandbox (issue #1358), the parent's stored configuration is what its editors change; the workflows
        // listed are what the sandbox runs, as [view] reads for a parent with one.
        val owner = sandboxParentOf(client) ?: client
        val workflows = WorkflowService.get(cxt)
        // What the editors change: for a client with a sandbox, its latest configuration, drafts included -- which is
        // what the sandbox runs -- so a workflow a draft removed or made is listed as the next edit will find it.
        // Named for a sandbox, that is the sandbox itself (issue #1358) -- asked before forming a sandbox's sandbox's id,
        // which is no id.
        val view = if (sandboxParentOf(client) != null) client
        else sandboxOf(client).takeIf { ClientService.get(cxt).present(it) != null } ?: client
        val own = workflows.forClient(view).workflows.values.toList()
        val templates = workflows.forClient(null).workflows
        val limits = PlanRoom.of(cxt, owner)
        return buildMap {
            put(CLD.client, client)
            put(CWF.templates, templates.values.map { templateInfo(it.def, own, limits) })
            val names = SetterNames(cxt)
            put(CWF.workflows, own.map { workflowInfo(cxt, client, view, it, templates.keys, names) })
            put(CWF.traits, usableTraits(cxt, owner))
            ClientService.get(cxt).displayZoneOf(owner)?.let { put(CWF.timeZone, it) }
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

    /**
     * One workflow for the catalog: its definition and where it is stored, as [client] -- whose configuration the page
     * lists -- holds it; and for a normal workflow, its parameters as [scope], the page's own client or sandbox, runs them
     * (issue #1356), which are set there whoever declared the workflow.
     */
    private fun workflowInfo(
        cxt: KdrCxt, scope: String, client: String, declared: WfDeclared, templateIds: Set<String>, names: SetterNames,
    ): Map<String, Any?> = buildMap {
        val def = declared.def
        put(CWF.definition, def.toJsonMap())
        if (declared.bundle.isStored && declared.bundle.gedraId.client == client) put(CWF.configName, declared.bundle.name)
        notEditable(client, declared, templateIds)?.let { put(CWF.notEditable, it) }
        shownLabels(cxt, scope, def)?.let { put(CWF.labels, it) }
        if (def.entry == WfEntry.normal) put(CWF.params, paramsInfo(WorkflowParamsService.get(cxt).resolve(cxt, scope, def.workflowId), names))
    }

    /**
     * [def]'s labels as [scope]'s page shows them ([CWF.labels]): each fragment pull resolved, or the label as written
     * where one does not resolve. Null when no label pulls any, which is every workflow made from a template.
     */
    private fun shownLabels(cxt: KdrCxt, scope: String, def: WfDef): Map<String, Any?>? {
        if ((listOf(def.label) + def.tasks.map { it.label }).none { "%{" in it }) return null
        val fragments = MarkdownFragmentService.get(cxt)
        val scoped = cxt.mkSubContext("workflowLabels", scope)
        fun shown(text: String) = fragments.backendPassReport(scoped, text).value ?: text
        return mapOf(WFD.label to shown(def.label), WFD.tasks to def.tasks.associate { it.id to shown(it.label) })
    }

    /**
     * [resolved] as a page is answered (issues #1356, #1386): each layer -- the scope's own setting, on a sandbox its
     * client's, and the source defaults -- with the scope's earlier settings, and for a reader of the API what they make
     * together: the parameters in force, and where each that is not the scope's own comes from ([WFP.from]). The page
     * recomputes those from the layers with the kernel's `WfParamsLayers`, as here. Each setting says who set it, named
     * only to a caller who may administer them ([SetterNames]).
     */
    fun paramsInfo(cxt: KdrCxt, resolved: ResolvedWorkflowParams): Map<String, Any?> = paramsInfo(resolved, SetterNames(cxt))

    private fun paramsInfo(resolved: ResolvedWorkflowParams, names: SetterNames): Map<String, Any?> = buildMap {
        put(WFP.inForce, resolved.layers.inForce.toInfo())
        resolved.layers.from.takeIf { it.isNotEmpty() }?.let { from -> put(WFP.from, from.mapValues { it.value.name }) }
        resolved.own?.let { put(WFP.current, names.named(it).toInfo()) }
        resolved.parent?.let { put(WFP.parent, names.named(it).toInfo()) }
        resolved.source.takeIf { !it.isEmpty }?.let { put(WFP.source, it.toInfo()) }
        put(WFP.history, resolved.history.map { names.named(it).toInfo() })
    }

    /**
     * Who set a workflow's parameters, for one answer: each user looked up once however many settings name them, and
     * only one the caller may administer (`ReadScopeRules.forUserAdmin`, as a by-id user read is scoped). Anyone else --
     * a platform operator who set a client's windows, say -- is left out, id and name both, as an administrator's
     * pages do not reveal users beyond their reach (issue #1298); the setting then says only when.
     */
    private class SetterNames(private val cxt: KdrCxt) {
        private val scope by lazy { ReadScopeRules.forUserAdmin(cxt) }
        private val users = HashMap<Long, AuthUserRow?>()

        fun named(s: WfParamsSetting): WfParamsSetting {
            val user = s.setBy?.let { id -> if (id in users) users[id] else UserService.get(cxt).queryAdministrableUser(cxt, id, scope).also { users[id] = it } }
            return WfParamsSetting(s.params, s.setAt, user?.userId, user?.let { it.name ?: it.publicName() })
        }
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
        // From a sandbox, its parent's: the sandbox holds no configuration, and a save is a draft of the parent's (#1358).
        val owner = sandboxParentOf(client) ?: client
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
            val taken = WorkflowService.get(cxt).forClient(owner).workflows.keys + storedWorkflowIds(bound) + leftover
            val id = keyFromLabel(slots.label, taken, "workflow")
            made = withTemplateSlots(bound, template, slots, id)
            val entry = mapOf(CCT.workflowId to id, CCT.definition to workflowTemplateOverlay(template, made))
            val config = reassembleForWrite(bound, CWF.configPrefix + id, clientNamespace(owner), owner, mapOf(CCT.workflowDef to listOf(entry)))
            written = svc.writeConfig(bound, config, trial = true, mustBeNew = true)
            // Nothing stored can be engaged with a workflow that did not exist, so there is no impact to judge.
            reload = ClientStoredEdit.takeEffect(cxt, target, written, undo = {}, impact = impact)
        } else {
            val (row, stored) = storedWorkflow(bound, workflowId)
                ?: throw KdrException("Client '$owner' stores no workflow '$workflowId'.", code = EXC.notFound)
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

    /**
     * Changes [client]'s own parameters for its normal workflow [workflowId] (issues #1356, #1386): those [edit] sets
     * become its own, and those named in [unset] stop being its own and follow the fallback again -- a sandbox its
     * client's, a client its source defaults. Any other parameter is left as it is. In force at once in that scope --
     * the client's people, or a sandbox's -- with no draft and no publish, and what they replace kept in the history
     * ([WorkflowParamsService]). The workflow may be one [client] runs, or, on a client with a sandbox, one its sandbox
     * runs and a publish has yet to bring. A change that would, now, freeze or hide forms [client] stores that are
     * engaged with the workflow -- an end brought forward, a start pushed back -- is refused with the impact report
     * until acknowledged. Returns the parameters as [client] now runs them.
     */
    fun saveParams(
        cxt: KdrCxt,
        client: String,
        workflowId: String,
        edit: WfParams,
        unset: Set<String> = emptySet(),
        impact: ImpactGate = ImpactGate.refuse,
    ): ResolvedWorkflowParams {
        val workflows = WorkflowService.get(cxt)
        // On a client with a sandbox, a workflow its sandbox runs that a publish has yet to bring is the client's to set too.
        val sandbox = if (sandboxParentOf(client) != null) null else sandboxOf(client).takeIf { ClientService.get(cxt).present(it) != null }
        val declared = workflows.forClient(client).workflow(workflowId) ?: sandbox?.let { workflows.forClient(it).workflow(workflowId) }
            ?: throw KdrException("Client '$client' has no workflow '$workflowId'.", code = EXC.notFound)
        if (declared.def.entry != WfEntry.normal) {
            throw KdrException.mkInput(
                "Workflow '$workflowId' is a ${declared.def.entry} workflow, which has no time windows; only a normal workflow does.",
            )
        }
        edit.problem(workflowId)?.let { throw KdrException.mkInput(it) }
        (unset - WfParams.allKeys).takeIf { it.isNotEmpty() }?.let {
            throw KdrException.mkInput("There is no parameter $it; a workflow's parameters are ${WfParams.allKeys}.")
        }
        (unset intersect edit.keys).takeIf { it.isNotEmpty() }?.let {
            throw KdrException.mkInput("Parameter(s) $it are both set and unset; a save does one or the other.")
        }
        val service = WorkflowParamsService.get(cxt)
        val before = service.resolve(cxt, client, workflowId)
        val own = before.own?.params ?: WfParams.none
        val after = own.edited(edit, unset)
        if (after == own) {
            throw KdrException.mkInput("Workflow '$workflowId' already has these values in client '$client'; nothing to save.")
        }
        if (impact == ImpactGate.refuse) {
            ConfigImpact.requireNoneForParams(cxt, client, workflowId, before.windows, before.layers.withOwn(after).inForce.windowsInForce)
        }
        service.save(cxt, client, workflowId, edit, unset)
        return service.resolve(cxt, client, workflowId)
    }

    /** What a removal did: the configuration the workflow was in, how it took effect, and a draft's impact. */
    class RemoveResult(val configName: String, val issues: List<GedraConfigIssue>, val mode: String, val draftImpact: ImpactReport?)

    /**
     * Removes [client]'s workflow [workflowId] (issue #1346): one made from a template, which the client stores --
     * the starters a new client is given among them. Its entry leaves the configuration holding it, which stays,
     * and the workflow's name may be taken again. Takes effect as [save] does. A live removal of a workflow forms take
     * part in is refused with the impact report until acknowledged; a draft's says what publishing it would do.
     */
    fun remove(cxt: KdrCxt, client: String, workflowId: String, expected: String? = null, impact: ImpactGate = ImpactGate.refuse): RemoveResult {
        // From a sandbox, its parent's: the sandbox holds no configuration, and a removal is a draft of the parent's (#1358).
        val owner = sandboxParentOf(client) ?: client
        val target = ClientStoredEdit.target(cxt, client, "workflowRemove", expected)
        val bound = target.bound
        val svc = GedraConfigService.get(bound)
        val (row, stored) = storedWorkflow(bound, workflowId)
            ?: throw KdrException("Client '$owner' stores no workflow '$workflowId'.", code = EXC.notFound)
        if (stored[WFD.fromTemplate] == null) {
            throw KdrException.mkInput("Workflow '$workflowId' is written whole, not made from a template, so it is removed where it is written.")
        }
        if (!row.configId.baseId.startsWith(CWF.configPrefix)) ClientStoredEdit.requireNoForeignDraft(target, row, "a change to its workflows")
        val written = svc.patchConfigs(bound, trial = true) {
            edit(row.configId) { slots ->
                val kept = slots[CCT.workflowDef].orEmpty().filter { it[CCT.workflowId] != workflowId }
                if (kept.isEmpty()) slots - CCT.workflowDef else slots + (CCT.workflowDef to kept)
            }
        }.single()
        val reload = ClientStoredEdit.takeEffect(
            cxt, target, written,
            undo = { svc.patchConfigs(bound) { edit(written.configId) { row.entriesBySlot() } } },
            restorePublished = row.isPublished,
            impact = impact,
        )
        return RemoveResult(written.configId.baseId, reload.issues, target.mode, ClientStoredEdit.draftImpact(target, written))
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
