package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfDef
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WfTaskSlot
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
import com.dynamicruntime.common.gedra.workflow.WfTraitRef
import com.dynamicruntime.common.gedra.workflow.isDataEntry
import com.dynamicruntime.common.gedra.workflow.parseWfDef
import com.dynamicruntime.common.gedra.workflow.templateSlotProblems
import com.dynamicruntime.common.gedra.workflow.templateSlotsFromInfo
import com.dynamicruntime.common.gedra.workflow.templateSlotsOf
import com.dynamicruntime.common.gedra.workflow.withTemplateSlots
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * A client's workflows made from templates, on its page (issue #1120; `ClientWorkflowsSection`): the catalog read, the
 * slot form's edits and checks, the words that explain what a template fixes, and the save. The slots, the checks and
 * the workflow a template makes are the kernel's, so what the page shows is what the backend writes.
 *
 * Everything here is pure, and covered under `jsNodeTest`.
 */

/** A context for the kernel's definition reader, which needs no service here. */
private val readCxt = LiteCxt()

/** A template a workflow can be made from, and why it cannot be now, when it cannot. */
class WorkflowTemplateView(val def: WfDef, val refusal: String?)

/** One of the client's workflows as it runs, the configuration holding it, and why its slots cannot be changed here. */
class ClientWorkflowView(
    val def: WfDef,
    val configName: String?,
    val notEditable: String?,
    /** For a normal workflow, why its time windows cannot be changed here (issue #1121); null when they can. */
    val windowsNotEditable: String? = null,
) {
    /** Whether the page offers its time windows: a normal workflow's, where the client may change them. */
    val windowsOffered: Boolean get() = def.entry == WfEntry.normal && windowsNotEditable == null
}

/** A trait the client's workflows may collect. */
class WorkflowTraitView(val traitId: String, val description: String?)

/** The workflows catalog: the templates, the client's workflows, and the traits they may collect. */
class WorkflowCatalogView(
    val clientId: String,
    val templates: List<WorkflowTemplateView>,
    val workflows: List<ClientWorkflowView>,
    val traits: List<WorkflowTraitView>,
    /** The client's time zone, which a workflow's time windows are entered and shown in; null for the deployment's. */
    val timeZone: String? = null,
) {
    fun template(templateId: String): WorkflowTemplateView? = templates.firstOrNull { it.def.workflowId == templateId }
}

fun parseWorkflowCatalog(raw: Map<String, Any?>): WorkflowCatalogView = WorkflowCatalogView(
    clientId = raw[CLD.client].toOptStr().orEmpty(),
    templates = raw[CWF.templates].toJsonListOfMaps().map {
        WorkflowTemplateView(parseWfDef(readCxt, it[CWF.definition].toJsonMapOrEmpty()), it[CWF.refusal].toOptStr())
    },
    workflows = raw[CWF.workflows].toJsonListOfMaps().map {
        ClientWorkflowView(
            parseWfDef(readCxt, it[CWF.definition].toJsonMapOrEmpty()),
            it[CWF.configName].toOptStr(),
            it[CWF.notEditable].toOptStr(),
            it[CWF.windowsNotEditable].toOptStr(),
        )
    },
    traits = raw[CWF.traits].toJsonListOfMaps().map { WorkflowTraitView(it[CCT.traitId].toOptStr().orEmpty(), it[CWF.description].toOptStr()) },
    timeZone = raw[CWF.timeZone].toOptStr(),
)

/** What a kind of workflow is, in a word. */
fun workflowEntryText(entry: WfEntry): String = when (entry) {
    WfEntry.creation -> "Creation"
    WfEntry.survey -> "Survey"
    WfEntry.normal -> "Step"
}

/** What a kind of workflow does, in a sentence: what the template fixes about when it runs. */
fun workflowEntryExplained(entry: WfEntry): String = when (entry) {
    WfEntry.creation -> "Runs once, when somebody makes a form: its save creates the form. A client can have only one."
    WfEntry.survey -> "What a form's owner fills in about it, and saves. A client can have only one."
    WfEntry.normal -> "A step taken on a form, which whoever may see the form fills in and saves. A client may have many."
}

/** The workflow's name for a listing: its label, else its id. */
fun workflowName(def: WfDef): String = def.label.ifBlank { def.workflowId }

/**
 * What [def] fixes that its slots do not choose, in words, one line each (issue #1120): how each save works, a review,
 * and the rules a form is held to. The page explains them; it does not edit them.
 */
fun workflowFixedParts(def: WfDef): List<String> = buildList {
    add(workflowEntryExplained(def.entry))
    for (task in def.tasks) {
        val approval = task.approval
        if (approval != null) {
            add("${task.label}: a reviewer approves it with “${approval.button}”.")
            continue
        }
        task.saves.forEach { save ->
            val what = if (save.kind == WfSaveKind.create) "creates the form" else "saves what was filled in"
            add("${task.label}: “${save.label}” $what.")
        }
        if (task.saves.isEmpty()) add("${task.label}: has no save of its own.")
    }
    if (def.eligibility.isNotEmpty()) add("A form may take it only while ${def.eligibility.size} rule(s) hold.")
    if (def.singletons.isNotEmpty()) add("While a form is engaged, it marks the form ${def.singletons.joinToString(", ") { it.cfact }}.")
    if (def.locks.isNotEmpty()) add("While a form is engaged, some of its answers are locked.")
}

/** The slots a new workflow made from [template] starts from: the template's own, with no id. */
fun newWorkflowSlots(template: WfDef): WfTemplateSlots = templateSlotsOf(template)

/** The slots of [workflow], made from a template, as they stand. */
fun workflowSlots(workflow: WfDef): WfTemplateSlots = templateSlotsOf(workflow)

/** These slots with the workflow named [label]. */
fun WfTemplateSlots.withLabel(label: String) = WfTemplateSlots(workflowId, templateId, label, tasks)

/** These slots with task [taskId] relabeled [label]. */
fun WfTemplateSlots.withTaskLabel(taskId: String, label: String) =
    WfTemplateSlots(workflowId, templateId, this.label, tasks.map { if (it.taskId == taskId) WfTaskSlot(it.taskId, label, it.traits) else it })

/**
 * These slots with task [taskId] collecting [traitIds], in that order: a trait it already collects keeps whether it is
 * required, and one added is required, as a workflow naming a trait wants it.
 */
fun WfTemplateSlots.withTaskTraits(taskId: String, traitIds: List<String>) = WfTemplateSlots(
    workflowId, templateId, label,
    tasks.map { t ->
        if (t.taskId != taskId) t else WfTaskSlot(t.taskId, t.label, traitIds.distinct().map { id -> t.traits.firstOrNull { it.traitId == id } ?: WfTraitRef(id) })
    },
)

/** These slots with trait [traitId] of task [taskId] required, or not. */
fun WfTemplateSlots.withTraitRequired(taskId: String, traitId: String, required: Boolean) = WfTemplateSlots(
    workflowId, templateId, label,
    tasks.map { t -> if (t.taskId != taskId) t else WfTaskSlot(t.taskId, t.label, t.traits.map { if (it.traitId == traitId) WfTraitRef(traitId, required) else it }) },
)

/** What is wrong with [slots] for [template], in the kernel's words. */
fun workflowSlotProblems(template: WfDef, slots: WfTemplateSlots): List<String> = templateSlotProblems(template, slots)

/** Whether [slots] differ from the [baseline] they were opened from; a new workflow always does. */
fun workflowSlotsChanged(slots: WfTemplateSlots, baseline: WfTemplateSlots?): Boolean = baseline == null || slots.toInfo() != baseline.toInfo()

/**
 * The workflow [slots] would make of [base] -- the template, or the workflow they change -- for the preview: the
 * kernel's, so it is the one the backend will write. Null when the slots do not make a workflow yet.
 */
fun workflowPreview(base: WfDef, slots: WfTemplateSlots): WfDef? =
    try {
        withTemplateSlots(readCxt, base, slots, slots.workflowId ?: "preview")
    } catch (e: Exception) {
        null
    }

/** The traits a task's choice offers: those the client's workflows may collect, and any the task collects already. */
fun traitChoices(catalog: WorkflowCatalogView, task: WfTaskSlot): List<Pair<String, String>> =
    (catalog.traits.map { it.traitId } + task.traits.map { it.traitId }).distinct().map { id ->
        val description = catalog.traits.firstOrNull { it.traitId == id }?.description
        (description?.let { "$id — $it" } ?: id) to id
    }

/** The save's request: the slots, how the page expects it to take effect, and whether an impact is acknowledged. */
fun workflowSaveRequest(clientId: String, slots: WfTemplateSlots, expected: String, acknowledge: Boolean): Map<String, Any?> = buildMap {
    put(CLD.client, clientId)
    put(CWF.slots, slots.toInfo())
    put(CPY.mode, expected)
    if (acknowledge) put(IMP.acknowledgeImpact, true)
}

/** What a save did: the configuration the workflow is in, its slots now, how it took effect, and a draft's impact. */
class WorkflowSaveResult(val configName: String, val slots: WfTemplateSlots, val mode: String, val draftImpact: ImpactReportView?)

fun parseWorkflowSaveResult(raw: Map<String, Any?>): WorkflowSaveResult = WorkflowSaveResult(
    configName = raw[CWF.configName].toOptStr().orEmpty(),
    slots = templateSlotsFromInfo(raw[CWF.slots].toJsonMapOrEmpty()),
    mode = raw[CPY.mode].toOptStr().orEmpty(),
    draftImpact = raw[CIT.draftImpact]?.toJsonMapOrEmpty()?.let(::parseImpactReport),
)

/** A workflow's data-entry tasks, the ones whose slots the form edits. */
fun dataEntryTaskIds(def: WfDef): List<String> = def.tasks.filter { it.isDataEntry }.map { it.id }

/**
 * Whether the page offers to remove [workflow] (issue #1346): one made from a template that the client stores as its
 * own -- what the slot editor may change -- the starters a new client is given among them.
 */
fun workflowRemovable(workflow: ClientWorkflowView): Boolean = workflow.notEditable == null && workflow.def.fromTemplate != null

/** The question a removal asks first, naming where it acts (issue #1049's rule): the live client, or the draft of it. */
fun workflowRemoveQuestion(def: WfDef, clientId: String, draft: Boolean): String =
    if (draft) "Remove the workflow '${workflowName(def)}' from the draft of ${sandboxParentOf(clientId) ?: clientId}?"
    else "Remove the workflow '${workflowName(def)}' of ${deleteTargetText(clientId)}?"

/** What removing a workflow does, under [workflowRemoveQuestion]. */
fun workflowRemoveConsequence(draft: Boolean): String =
    if (draft) "Its people keep it until the draft is published; the template can make it again."
    else "Its people no longer see it, at once. The template can make it again."

/** The removal's request: the workflow, how the page expects it to take effect, and whether an impact is acknowledged. */
fun workflowRemoveRequest(clientId: String, workflowId: String, expected: String, acknowledge: Boolean): Map<String, Any?> = buildMap {
    put(CLD.client, clientId)
    put(WFD.workflowId, workflowId)
    put(CPY.mode, expected)
    if (acknowledge) put(IMP.acknowledgeImpact, true)
}

/** What a removal did: the configuration the workflow was in, how it took effect, and a draft's impact. */
class WorkflowRemoveResult(val configName: String, val workflowId: String, val mode: String, val draftImpact: ImpactReportView?)

fun parseWorkflowRemoveResult(raw: Map<String, Any?>): WorkflowRemoveResult = WorkflowRemoveResult(
    configName = raw[CWF.configName].toOptStr().orEmpty(),
    workflowId = raw[WFD.workflowId].toOptStr().orEmpty(),
    mode = raw[CPY.mode].toOptStr().orEmpty(),
    draftImpact = raw[CIT.draftImpact]?.toJsonMapOrEmpty()?.let(::parseImpactReport),
)
