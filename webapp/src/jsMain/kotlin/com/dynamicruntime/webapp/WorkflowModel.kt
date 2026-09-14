package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseDeliveredLayouts
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
    /** The qualified name [type] was resolved under -- the key the view's `layouts` are joined on. */
    val typeName: String,
    /**
     * The data type's layout (issue #585), joined from the view's `layouts` by [typeName]; null when the type
     * declares none, and the trait then renders from its schema alone. Not yet consumed by [SchemaForm].
     */
    val layout: SchLayout?,
)

/** One save option a task offers: what a button says and what it does (`WfSaveKind.name`, e.g. create/edit). */
class WfSaveView(val id: String, val label: String, val kind: String)

/**
 * One task of the workflow: its traits in the order the page draws them, its saves, and — when the view was
 * resolved against an existing form (a survey edit, issue #659) — that task's **current entries**, the source a
 * page seeds its fields from. A creation view carries none, so [entries] defaults empty.
 */
class WfTaskView(
    val id: String,
    val label: String,
    val traits: List<WfTraitView>,
    val saves: List<WfSaveView>,
    val entries: List<Map<String, Any?>> = emptyList(),
)

/**
 * A resolved workflow view, ready to render (issue #536, #659). It serves both the **creation** workflow (no
 * form yet) and a **survey** resolved against an existing form (each task seeded from its current [WfTaskView.entries]);
 * [entry] says which (`WfEntry.name`, e.g. `"creation"`/`"survey"`).
 */
class WorkflowView(
    val workflowId: String,
    val entry: String,
    val showTaskList: Boolean,
    val tasks: List<WfTaskView>,
    /**
     * The caller's frontend-delivered cfacts (issue #569), `name -> present`, which each rendered trait's
     * [SchemaForm] evaluates a property's `g-visibleWhen` against — so an admin-only field is hidden from an
     * ordinary caller. The same shape the endpoint catalog delivers.
     */
    val cfacts: Map<String, Boolean>,
    /**
     * The per-type layouts the view delivered (issue #585), keyed by qualified type name like its `$defs` --
     * the same shape the endpoint catalog carries. Each trait's own is already joined onto [WfTraitView.layout];
     * this is the whole closure, for a renderer that reaches a nested type by name.
     */
    val layouts: Map<String, SchLayout> = emptyMap(),
)

/**
 * Parses a `/gedra/workflow/view` `results` map into a [WorkflowView], or **null** when the caller has no such
 * workflow (`found=false`) — the signal the page uses (a create page falls back to the trait picker; a survey
 * edit page reports there is no survey). Each trait's `schemaRef` is resolved against the view's `$defs`; a ref
 * that names no carried type is a fault, since the view is supposed to carry every type it points at.
 */
fun parseWorkflowView(results: Map<String, Any?>): WorkflowView? {
    if (results[WVF.found] != true) return null
    val defTypes = parseSchemaTypes(results[SCH.dDefs].toJsonMapOrEmpty())
    // The third closure (issue #585), keyed like `$defs`; a trait's layout is the entry under its type name.
    val layouts = parseDeliveredLayouts(results[WVF.layouts])
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
                WfTraitView(tr[WFD.traitId].toOptStr() ?: "", tr[WFD.required] == true, type, name, layouts[name])
            },
            saves = t[WFD.saves].toJsonListOfMaps().map { s ->
                WfSaveView(s[WFD.id].toOptStr() ?: "", s[WFD.label].toOptStr() ?: "", s[WFD.kind].toOptStr() ?: "")
            },
            // Present only for a survey view resolved against a form (issue #659) -- the seed for each field.
            entries = t[WVF.entries].toJsonListOfMaps(),
        )
    }
    val cfacts = results[WVF.cfacts].toJsonMapOrEmpty().mapValues { it.value == true }
    return WorkflowView(
        workflowId = results[WFD.workflowId].toOptStr() ?: "",
        entry = results[WFD.entry].toOptStr() ?: "",
        showTaskList = results[WVF.showTaskList] == true,
        tasks = tasks,
        cfacts = cfacts,
        layouts = layouts,
    )
}

/**
 * The values to seed a task's fields from, keyed by trait id (issue #659): the inverse of [workflowSaveEntries].
 * A task's [WfTaskView.entries] are `{traitId, data}` maps; this pulls each `data` out under its `traitId`, so a
 * survey edit renders each field pre-filled with what is stored. A creation task has no entries, so this is empty.
 */
fun seedValuesFromEntries(entries: List<Map<String, Any?>>): Map<String, Map<String, Any?>> =
    entries.mapNotNull { entry ->
        entry[GE.traitId].toOptStr()?.let { it to entry[GE.data].toJsonMapOrEmpty() }
    }.toMap()

/**
 * The `entries` a save posts, from the values collected per trait (issue #536): each is a `{traitId, data}`
 * entry, the shape the save endpoint stores. A trait with no values collected is still sent as an empty entry,
 * so the gate sees it as present-but-empty rather than missing only because the page skipped it — the same
 * presence rule the backend applies.
 */
fun workflowSaveEntries(valuesByTrait: Map<String, Map<String, Any?>>): List<Map<String, Any?>> =
    valuesByTrait.map { (traitId, data) -> mapOf(GE.traitId to traitId, GE.data to data) }

/**
 * The body a workflow save posts: which workflow, task and save, and the collected entries. A survey `edit`
 * save (issue #659) also carries the [gedraId] of the form it updates; a create save omits it (null).
 */
fun workflowSaveBody(
    workflowId: String,
    taskId: String,
    saveId: String,
    entries: List<Map<String, Any?>>,
    gedraId: String? = null,
): Map<String, Any?> = buildMap {
    put(GDF.workflowId, workflowId)
    put(GDF.taskId, taskId)
    put(GDF.saveId, saveId)
    put(GDF.entries, entries)
    gedraId?.let { put(GDF.gedraId, it) }
}

/** The outcome of a save: whether it happened, the required trait ids left unmet, and the created form. */
class WorkflowSaveOutcome(val saved: Boolean, val unmetTraits: List<String>, val item: Map<String, Any?>)

/** Reads a `/gedra/workflow/save` `results` map into a [WorkflowSaveOutcome]. */
fun parseSaveOutcome(results: Map<String, Any?>): WorkflowSaveOutcome = WorkflowSaveOutcome(
    saved = results[WSF.saved] == true,
    unmetTraits = results[WSF.unmetTraits].toJsonListOfStrings(),
    item = results[WSF.item].toJsonMapOrEmpty(),
)
