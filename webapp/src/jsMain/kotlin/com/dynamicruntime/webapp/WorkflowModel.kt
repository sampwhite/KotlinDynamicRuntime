package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.refName
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * The frontend's model of a resolved creation workflow (issue #536) — the `/gedra/workflow/view` response
 * parsed into what the create page renders. **Self-contained**: each trait's schema comes from the view's own
 * `$defs`, resolved here with the same kernel `parseSchemaTypes` the endpoint catalog uses, so the page needs
 * no second fetch and a workflow that narrows a trait renders the narrowed shape.
 *
 * These are pure maps-in, model-out functions with no React and no server, so they carry the `jsNodeTest`
 * coverage the issue asks for; the component that renders them is driven in a browser.
 */
class WfTraitView(
    val traitId: String,
    val required: Boolean,
    /** The trait's data type, resolved from the view's `$defs` — what a field renders and validates against. */
    val type: SchType,
)

/** One save option a task offers: what a button says and what it does. */
class WfSaveView(val id: String, val label: String, val kind: String)

/** One task of the workflow: its traits in the order the page draws them, and its saves. */
class WfTaskView(val id: String, val label: String, val traits: List<WfTraitView>, val saves: List<WfSaveView>)

/** A resolved creation workflow, ready to render. */
class WorkflowCreation(val workflowId: String, val showTaskList: Boolean, val tasks: List<WfTaskView>) {
    /** The single task of a creation workflow (exactly one, by the backend's boot rule). */
    val task: WfTaskView get() = tasks.single()
}

/**
 * Parses a `/gedra/workflow/view` `results` map into a [WorkflowCreation], or **null** when the client has no
 * creation workflow (`found=false`) — the signal the page uses to fall back to the trait picker. Each trait's
 * `schemaRef` is resolved against the view's `$defs`; a ref that names no carried type is a fault, since the
 * view is supposed to carry every type it points at.
 */
fun parseWorkflowView(results: Map<String, Any?>): WorkflowCreation? {
    if (results[WVF.found] != true) return null
    val defTypes = parseSchemaTypes(results[SCH.dDefs].toJsonMapOrEmpty())
    val tasks = results[WFD.tasks].toJsonListOfMaps().map { t ->
        WfTaskView(
            id = t[WFD.id].toOptStr() ?: "",
            label = t[WFD.label].toOptStr() ?: "",
            traits = t[WFD.traits].toJsonListOfMaps().map { tr ->
                val ref = tr[WVF.schemaRef].toOptStr() ?: ""
                val name = refName(ref)
                    ?: error($$"Workflow view trait '$${tr[WFD.traitId]}' has a schemaRef '$$ref' that is not a local $defs pointer.")
                val type = defTypes[name]
                    ?: error($$"Workflow view references '$$name', which its own $defs does not carry.")
                WfTraitView(tr[WFD.traitId].toOptStr() ?: "", tr[WFD.required] == true, type)
            },
            saves = t[WFD.saves].toJsonListOfMaps().map { s ->
                WfSaveView(s[WFD.id].toOptStr() ?: "", s[WFD.label].toOptStr() ?: "", s[WFD.kind].toOptStr() ?: "")
            },
        )
    }
    return WorkflowCreation(results[WFD.workflowId].toOptStr() ?: "", results[WVF.showTaskList] == true, tasks)
}

/**
 * The `entries` a save posts, from the values collected per trait (issue #536): each is a `{traitId, data}`
 * entry, the shape the save endpoint stores. A trait with no values collected is still sent as an empty entry,
 * so the gate sees it as present-but-empty rather than missing only because the page skipped it — the same
 * presence rule the backend applies.
 */
fun workflowSaveEntries(valuesByTrait: Map<String, Map<String, Any?>>): List<Map<String, Any?>> =
    valuesByTrait.map { (traitId, data) -> mapOf(GE.traitId to traitId, GE.data to data) }

/** The body a workflow save posts: which workflow, task and save, and the collected entries. */
fun workflowSaveBody(
    workflowId: String,
    taskId: String,
    saveId: String,
    entries: List<Map<String, Any?>>,
): Map<String, Any?> = mapOf(
    GDF.workflowId to workflowId, GDF.taskId to taskId, GDF.saveId to saveId, GDF.entries to entries,
)

/** The outcome of a save: whether it happened, the required trait ids left unmet, and the created form. */
class WorkflowSaveOutcome(val saved: Boolean, val unmetTraits: List<String>, val item: Map<String, Any?>)

/** Reads a `/gedra/workflow/save` `results` map into a [WorkflowSaveOutcome]. */
fun parseSaveOutcome(results: Map<String, Any?>): WorkflowSaveOutcome = WorkflowSaveOutcome(
    saved = results[WSF.saved] == true,
    unmetTraits = results[WSF.unmetTraits].toJsonListOfStrings(),
    item = results[WSF.item].toJsonMapOrEmpty(),
)
