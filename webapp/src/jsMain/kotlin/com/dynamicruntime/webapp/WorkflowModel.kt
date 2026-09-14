package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.workflow.SVY
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

/** One friendly content failure a task's data has (issue #700): which trait, and the wording to show. */
class WfProblem(val traitId: String, val message: String)

/**
 * A task's status for the task rail (issue #700), as the view computed it: presence ([complete], [missingTraits])
 * and content ([valid], [invalidTraits], [problems]) kept apart, so "needs information" and "has a problem" draw
 * as different marks. Null on a task the view carried none for.
 */
class WfTaskStatus(
    val complete: Boolean,
    val valid: Boolean,
    val missingTraits: List<String>,
    val invalidTraits: List<String>,
    val problems: List<WfProblem>,
)

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
    /** The task's status for the rail (issue #700), as the view computed it; null when it carried none. */
    val status: WfTaskStatus? = null,
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
    /**
     * The earliest task still needing action (issue #700) -- the first, in order, whose status is incomplete or
     * invalid -- or null when every task is done. What the rail opens on when the URL names no task.
     */
    val focusTask: String? = null,
)

/** A task's [WVF.status] map as a [WfTaskStatus], or null when the task carried none. */
private fun parseTaskStatus(raw: Any?): WfTaskStatus? {
    val s = raw.toJsonMapOrEmpty()
    if (s.isEmpty()) return null
    return WfTaskStatus(
        complete = s[SVY.complete] == true,
        valid = s[SVY.valid] == true,
        missingTraits = s[SVY.missingTraits].toJsonListOfStrings(),
        invalidTraits = s[SVY.invalidTraits].toJsonListOfStrings(),
        problems = s[WVF.problems].toJsonListOfMaps().map {
            WfProblem(it[GE.traitId].toOptStr() ?: "", it[WVF.message].toOptStr() ?: "")
        },
    )
}

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
            status = parseTaskStatus(t[WVF.status]),
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
        focusTask = results[WVF.focusTask].toOptStr(),
    )
}

/**
 * The task the survey page opens on (issue #700): the one the URL names when it is a task of the view, else the
 * view's earliest task needing action, else the first task. Null only for a view with no tasks.
 */
fun initialTaskFor(view: WorkflowView, requested: String?): String? {
    val ids = view.tasks.map { it.id }
    return requested?.takeIf { it in ids } ?: view.focusTask?.takeIf { it in ids } ?: ids.firstOrNull()
}

/**
 * The mark a rail entry draws for a task (issue #700). Absence and invalidity are different marks: a task missing
 * a required trait is [incomplete] whatever else is wrong with it -- the orange check means "complete, but with a
 * problem", so it must not be drawn on a task that is not complete; its problems still reach the tooltip.
 */
@Suppress("EnumEntryName")
enum class RailMark { incomplete, invalid, complete }

fun railMark(status: WfTaskStatus?): RailMark = when {
    status == null || !status.complete -> RailMark.incomplete
    !status.valid -> RailMark.invalid
    else -> RailMark.complete
}

/**
 * What a rail mark means, in words, for the entry's tooltip and screen-reader label (issue #700): unsaved edits,
 * the required traits still missing (by their friendly names), each friendly problem, or -- when nothing else
 * applies -- "Complete". One line each, in that order.
 */
fun railExplanation(status: WfTaskStatus?, unsaved: Boolean): String {
    val lines = buildList {
        if (unsaved) add("Unsaved changes")
        if (status == null || !status.complete) {
            val missing = status?.missingTraits.orEmpty()
            add(if (missing.isEmpty()) "Not started" else "Needs information: " + missing.joinToString(", ") { humanizeFieldName(it) })
        }
        status?.problems?.forEach { add(it.message) }
        if (isEmpty()) add("Complete")
    }
    return lines.joinToString("\n")
}

/**
 * Whether a task has edits not yet saved (issue #700): any of its traits' working [values] differ from the last
 * [stored] ones. Client-side only -- the backend has no notion of an unsaved draft -- and orthogonal to the
 * status mark, so the rail overlays it as a badge rather than drawing it as a fourth state.
 */
fun taskUnsaved(
    task: WfTaskView,
    values: Map<String, Map<String, Any?>>,
    stored: Map<String, Map<String, Any?>>,
): Boolean = task.traits.any { trait ->
    val working: Map<String, Any?> = values[trait.traitId] ?: emptyMap()
    val kept: Map<String, Any?> = stored[trait.traitId] ?: emptyMap()
    working != kept
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

/**
 * The outcome of a save: whether it happened, the required trait ids left unmet, the created or updated form,
 * and -- on a survey edit (issue #700) -- the **refreshed view**, so the rail's statuses follow the save with no
 * second call. Null [view] on a create save, or a refusal.
 */
class WorkflowSaveOutcome(
    val saved: Boolean,
    val unmetTraits: List<String>,
    val item: Map<String, Any?>,
    val view: WorkflowView? = null,
)

/** Reads a `/gedra/workflow/save` `results` map into a [WorkflowSaveOutcome]. */
fun parseSaveOutcome(results: Map<String, Any?>): WorkflowSaveOutcome = WorkflowSaveOutcome(
    saved = results[WSF.saved] == true,
    unmetTraits = results[WSF.unmetTraits].toJsonListOfStrings(),
    item = results[WSF.item].toJsonMapOrEmpty(),
    view = results[WSF.view]?.let { parseWorkflowView(it.toJsonMapOrEmpty()) },
)
