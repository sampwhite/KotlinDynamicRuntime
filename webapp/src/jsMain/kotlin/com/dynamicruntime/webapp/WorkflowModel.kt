package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GSRC
import com.dynamicruntime.common.gedra.workflow.SVY
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfPhase
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SLDM
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.SchLayoutField
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
    /** The qualified name [type] was resolved under -- the key the view's `fieldLayouts` are joined on. */
    val typeName: String,
    /**
     * The data type's field layout (issue #585), joined from the view's `fieldLayouts` by [typeName]; null when
     * the type declares none, and the trait then renders from its schema alone.
     */
    val fieldLayout: SchLayout?,
)

/** One save option a task offers: what a button says and what it does (`WfSaveKind.name`, e.g. create/edit). */
class WfSaveView(val id: String, val label: String, val kind: String)

/**
 * One content failure a task's data has (issue #700): which trait, the field [path] within its data, and the
 * wording to show -- the schema author's `userMessage` when the field declares one, else the validator's
 * `message`, the same rule a reported failure is read by anywhere else.
 */
class WfProblem(val traitId: String, val path: String, val message: String)

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
    /**
     * Whether this caller may save the task (issue #856), by its rule for who may -- the rule the save endpoint
     * enforces. True when the view says nothing (creation, survey, or a task with no rule).
     */
    val canSave: Boolean = true,
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
     * What the workflow is called (issue #719), resolved like a task's label -- the page's title over the form.
     * Empty when the definition gives none, and the page then uses its own generic title.
     */
    val label: String = "",
    /**
     * The caller's frontend-delivered cfacts (issue #569), `name -> present`, which each rendered trait's
     * [SchemaForm] evaluates a property's `g-visibleWhen` against — so an admin-only field is hidden from an
     * ordinary caller. The same shape the endpoint catalog delivers.
     */
    val cfacts: Map<String, Boolean>,
    /**
     * The per-type field layouts the view delivered (issue #585), keyed by qualified type name like its `$defs` --
     * the same shape the endpoint catalog carries. Each trait's own is already joined onto [WfTraitView.fieldLayout];
     * this is the whole closure, for a renderer that reaches a nested type by name.
     */
    val fieldLayouts: Map<String, SchLayout> = emptyMap(),
    /**
     * The earliest task still needing action (issue #700) -- the first, in order, whose status is incomplete or
     * invalid -- or null when every task is done. What the rail opens on when the URL names no task.
     */
    val focusTask: String? = null,
    /** A normal workflow's [WfPhase] name (issue #790); null for creation and survey. */
    val phase: String? = null,
    /** For a normal workflow viewed against a form: whether the form is engaged with it (issue #791). */
    val engaged: Boolean? = null,
    /** For a normal workflow the form is not engaged with: whether it passes the eligibility tests (issue #791). */
    val eligible: Boolean? = null,
    /** When it does not: why, resolved -- what the page lists in place of an Engage that would fail. */
    val ineligibleReasons: List<String> = emptyList(),
    /**
     * The form's traits locked for this caller (issue #857), by trait id -- each with every lock on it, since two
     * workflows may lock one trait; drawn read-only and left out of a save.
     */
    val lockedTraits: Map<String, List<TraitLock>> = emptyMap(),
) {
    /** A normal workflow (issue #791): one a form is put into, rather than the creation or the survey. */
    val isNormal: Boolean get() = entry == WfEntry.normal.name

    /** Whether this normal workflow's tasks may be worked on now: engaged, and still calculated (not frozen). */
    val canWork: Boolean get() = !isNormal || (engaged == true && (phase == WfPhase.relevant.name || phase == WfPhase.engageable.name) && tasks.any { it.canSave && it.saves.isNotEmpty() })

    /** Whether the form may be put into this normal workflow from here: not yet engaged, and engagement is open. */
    val canEngage: Boolean get() = isNormal && engaged == false && eligible == true && phase == WfPhase.engageable.name
}

/** A task's [WVF.status] map as a [WfTaskStatus], or null when the task carried none. */
private fun parseTaskStatus(raw: Any?): WfTaskStatus? {
    val s = raw.toJsonMapOrEmpty()
    if (s.isEmpty()) return null
    return WfTaskStatus(
        complete = s[SVY.complete] == true,
        valid = s[SVY.valid] == true,
        missingTraits = s[SVY.missingTraits].toJsonListOfStrings(),
        invalidTraits = s[SVY.invalidTraits].toJsonListOfStrings(),
        // Each problem is the kernel's own failure wire map (`SchFailure.toWireMap`) plus the trait it belongs to,
        // read by the same constants every other reported failure is.
        problems = s[WVF.problems].toJsonListOfMaps().map {
            WfProblem(
                traitId = it[GE.traitId].toOptStr() ?: "",
                path = it[EP.failurePath].toOptStr() ?: "",
                message = it[EP.failureUserMessage].toOptStr() ?: it[EP.failureMessage].toOptStr() ?: "",
            )
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
    val fieldLayouts = parseDeliveredLayouts(results[WVF.fieldLayouts])
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
                WfTraitView(tr[WFD.traitId].toOptStr() ?: "", tr[WFD.required] == true, type, name, fieldLayouts[name])
            },
            saves = t[WFD.saves].toJsonListOfMaps().map { s ->
                WfSaveView(s[WFD.id].toOptStr() ?: "", s[WFD.label].toOptStr() ?: "", s[WFD.kind].toOptStr() ?: "")
            },
            // Present only for a survey view resolved against a form (issue #659) -- the seed for each field.
            entries = t[WVF.entries].toJsonListOfMaps(),
            status = parseTaskStatus(t[WVF.status]),
            canSave = t[WVF.canSave] != false,
        )
    }
    val cfacts = results[WVF.cfacts].toJsonMapOrEmpty().mapValues { it.value == true }
    return WorkflowView(
        workflowId = results[WFD.workflowId].toOptStr() ?: "",
        entry = results[WFD.entry].toOptStr() ?: "",
        showTaskList = results[WVF.showTaskList] == true,
        tasks = tasks,
        label = results[WFD.label].toOptStr().orEmpty(),
        cfacts = cfacts,
        fieldLayouts = fieldLayouts,
        focusTask = results[WVF.focusTask].toOptStr(),
        phase = results[WVF.phase].toOptStr(),
        engaged = results[WVF.engaged] as? Boolean,
        eligible = results[WVF.eligible] as? Boolean,
        lockedTraits = parseTraitLocks(results[WVF.lockedTraits]).groupBy { it.traitId },
        ineligibleReasons = results[WVF.ineligibleReasons].toJsonListOfStrings(),
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
 * the required traits still missing (named by [nameOf] -- the rail passes the same heading the panel uses, so
 * the two agree; the default humanizes the id), each friendly problem, or -- when nothing else applies --
 * "Complete". One line each, in that order.
 */
fun railExplanation(status: WfTaskStatus?, unsaved: Boolean, nameOf: (String) -> String = ::humanizeFieldName): String {
    val lines = buildList {
        if (unsaved) add("Unsaved changes")
        if (status == null || !status.complete) {
            val missing = status?.missingTraits.orEmpty()
            add(if (missing.isEmpty()) "Not started" else "Needs information: " + missing.joinToString(", ") { nameOf(it) })
        }
        status?.problems?.forEach { add(it.message) }
        if (isEmpty()) add("Complete")
    }
    return lines.joinToString("\n")
}

/**
 * Whether a task has edits not yet saved (issue #700): any of its traits' working [values] differ from the last
 * [stored] ones. Client-side only -- the backend has no notion of an unsaved draft -- and orthogonal to the
 * status mark, so the rail overlays it as a badge rather than drawing it as a fourth state. The same answer
 * gates the task's Save (issue #717): a task with nothing unsaved has nothing to save.
 *
 * Compared through [comparableValues] (issue #718): a text box holds what was typed as a string against a
 * stored number, and a cleared box holds `""` against an absent key, so a raw comparison read a value typed
 * back as its original, or a field emptied that was never set, as an edit.
 */
fun taskUnsaved(
    task: WfTaskView,
    values: Map<String, Map<String, Any?>>,
    stored: Map<String, Map<String, Any?>>,
): Boolean = task.traits.any { trait ->
    val working: Map<String, Any?> = values[trait.traitId] ?: emptyMap()
    val kept: Map<String, Any?> = stored[trait.traitId] ?: emptyMap()
    comparableValues(working) != comparableValues(kept)
}

/**
 * A form's values in a shape two copies can be compared in (issue #718), whichever side came from a widget and
 * which from the wire: a null or blank string is dropped (what `emptyIsAbsent` makes of it on the way out), a
 * scalar becomes its text (`"2025"` typed and `2025` stored are one value), and maps and lists are walked. A
 * comparison aid only -- nothing sent is built from this.
 */
fun comparableValues(values: Map<String, Any?>): Map<String, Any?> = buildMap {
    for ((k, v) in values) {
        val c = comparableValue(v) ?: continue
        put(k, c)
    }
}

private fun comparableValue(value: Any?): Any? = when (value) {
    null -> null
    is String -> value.takeIf { it.isNotBlank() }
    is Map<*, *> -> comparableValues(value.toJsonMapOrEmpty())
    is List<*> -> value.map { comparableValue(it) }
    else -> value.toString()
}

/**
 * A task's status computed from its **working** values (issue #718), for the rail while the task holds unsaved
 * edits -- the client's projection of the verdict the server will give once they are saved, by the server's
 * own rule (`SurveyStateDeriver` and its `surveyContentFailures`) so a save does not flip the mark: presence is
 * a required trait having any data; content is the kernel's failures on the data that is there, worded as a
 * reported problem is (the author's `userMessage` first).
 *
 * One reading differs, on purpose. The server sets a `missingRequired` failure aside, because a stored entry
 * passed its save and is present whatever it lacks. Here the data is *unsaved*: a required field emptied is
 * exactly the "needs information" the mark exists to show, so it makes the trait **missing** rather than
 * invalid -- and since the save refuses it, the server never sees that state and the two cannot disagree over
 * a saved form.
 */
fun localTaskStatus(task: WfTaskView, values: Map<String, Map<String, Any?>>): WfTaskStatus {
    val missing = mutableListOf<String>()
    val problems = mutableListOf<WfProblem>()
    for (trait in task.traits) {
        val working = values[trait.traitId] ?: emptyMap()
        if (comparableValues(working).isEmpty()) {
            if (trait.required) missing.add(trait.traitId)
            continue
        }
        val failures = checkInput(trait.type, working).failures
        if (failures.any { it.code == SchFailCode.missingRequired }) missing.add(trait.traitId)
        failures.filter { it.code != SchFailCode.missingRequired }
            .forEach { problems.add(WfProblem(trait.traitId, it.path, it.userMessage ?: it.message)) }
    }
    return WfTaskStatus(
        complete = missing.isEmpty(),
        valid = problems.isEmpty(),
        missingTraits = missing,
        invalidTraits = problems.map { it.traitId }.distinct(),
        problems = problems,
    )
}

/**
 * The failures a task's panel shows for one trait (issue #718): before the trait has been checked as a whole (a
 * Save, or the task being left), only those on fields the user has **committed** -- a first blur must not flag
 * every untouched required field below it; after, all of them. A failure on a field beneath a committed one
 * (an element of a list the user touched) counts as committed.
 */
fun shownFailures(all: List<SchFailure>, committed: Set<String>, wholeTraitChecked: Boolean): List<SchFailure> =
    if (wholeTraitChecked) all else all.filter { f -> committed.any { c -> f.path == c || f.path.startsWith("$c.") || f.path.startsWith("$c[") } }

/**
 * The values to seed a task's fields from, keyed by trait id (issue #659): the inverse of [workflowSaveEntries].
 * A task's [WfTaskView.entries] are `{traitId, data}` maps; this pulls each `data` out under its `traitId`, so a
 * survey edit renders each field pre-filled with what is stored. A creation task has no entries, so this is empty.
 *
 * This is the **raw** read that keeps every entry -- used for a stored item straight off a save, which carries
 * only entered (`source=user`) data. To seed a *resolved view*, whose entries may include supplied defaults,
 * use [seedValuesOf], which splits them by mode.
 */
fun seedValuesFromEntries(entries: List<Map<String, Any?>>): Map<String, Map<String, Any?>> =
    entries.mapNotNull { entry ->
        entry[GE.traitId].toOptStr()?.let { it to entry[GE.data].toJsonMapOrEmpty() }
    }.toMap()

/** Whether a stored entry is a **supplied default** (`source=prefill`, issue #679/#710), not entered data. */
private fun isPrefillEntry(entry: Map<String, Any?>): Boolean = entry[GE.source].toOptStr() == GSRC.prefill

/**
 * How a supplied default for [field] of [trait] is presented (issue #709/#710), from the trait's `g-layout`
 * [SchLayoutField.defaultMode]; [SLDM.filled] when the layout says nothing -- the surface's fallback lives here,
 * so a layout carries the mode only to override it.
 */
fun defaultModeOf(trait: WfTraitView, field: String): String =
    trait.fieldLayout?.fieldFor(field)?.defaultMode ?: SLDM.filled

/**
 * How a resolved view's **supplied defaults** are presented (issue #710). A default is any entry with
 * `source=prefill`; each of its fields is handled by that field's [defaultModeOf]:
 *  - **`filled`** seeds the working value ([working]) -- shown in the control, marked as a default;
 *  - **`offer`** does *not* seed; its value is held in [offered] for a "use it" link, so the control stays empty.
 *
 * [modes] records the mode of every defaulted field, so the form can mark it. Entered (`source=user`) entries go
 * whole into [working], as before -- a real value is never a default.
 */
class PrefillPresentation(
    val working: Map<String, Map<String, Any?>>,
    val offered: Map<String, Map<String, Any?>>,
    val modes: Map<String, Map<String, String>>,
)

/** Splits [view]'s entries into the [PrefillPresentation] the form seeds and marks from (issue #710). */
fun prefillPresentationOf(view: WorkflowView): PrefillPresentation {
    val working = LinkedHashMap<String, Map<String, Any?>>()
    val offered = LinkedHashMap<String, Map<String, Any?>>()
    val modes = LinkedHashMap<String, Map<String, String>>()
    for (task in view.tasks) {
        val traitById = task.traits.associateBy { it.traitId }
        for (entry in task.entries) {
            val traitId = entry[GE.traitId].toOptStr() ?: continue
            val data = entry[GE.data].toJsonMapOrEmpty()
            if (!isPrefillEntry(entry)) {
                working[traitId] = data
                continue
            }
            val trait = traitById[traitId]
            val filled = LinkedHashMap<String, Any?>()
            val offer = LinkedHashMap<String, Any?>()
            val fieldModes = LinkedHashMap<String, String>()
            for ((field, value) in data) {
                val mode = trait?.let { defaultModeOf(it, field) } ?: SLDM.filled
                fieldModes[field] = mode
                if (mode == SLDM.offer) offer[field] = value else filled[field] = value
            }
            if (filled.isNotEmpty()) working[traitId] = filled
            if (offer.isNotEmpty()) offered[traitId] = offer
            modes[traitId] = fieldModes
        }
    }
    return PrefillPresentation(working, offered, modes)
}

/**
 * Every task's seed values in one map, keyed by trait id (trait ids are unique across a workflow's tasks) -- what
 * the form starts from, and what it re-snapshots from the refreshed view a survey edit save returns (issue
 * #700). Supplied defaults are split by mode ([prefillPresentationOf]): a `filled` default seeds, an `offer` one
 * does not (it is offered, not entered), so the seed and the presented form never disagree about "unsaved".
 */
fun seedValuesOf(view: WorkflowView): Map<String, Map<String, Any?>> = prefillPresentationOf(view).working

/** Whether a value reads as empty for a supplied default (absent, or text a user has cleared) -- an unapplied
 *  `offer`, the same reading [SchemaForm] uses to decide whether to draw the "Use it" link (issue #710). */
private fun isBlankPrefillValue(value: Any?): Boolean = value == null || (value is String && value.isBlank())

/**
 * How many of [task]'s supplied defaults are still **pending** (issue #710): a `filled` field the user has not
 * yet touched (still in [suggestedByTrait]), or an `offer` field not yet applied (its value in [valuesByTrait]
 * still empty). This is the count the "N fields filled from your account" line reports, and it falls to zero as
 * the user accepts, edits, or saves them -- so the line disappears once nothing is left to confirm. Pure, so a
 * `jsNodeTest` can pin it; the React form owns the state it reads.
 */
fun pendingDefaultCount(
    task: WfTaskView,
    presentation: PrefillPresentation,
    suggestedByTrait: Map<String, Set<String>>,
    valuesByTrait: Map<String, Map<String, Any?>>,
    resolvedTraits: Set<String> = emptySet(),
): Int {
    var pending = 0
    for (trait in task.traits) {
        if (trait.traitId in resolvedTraits) continue
        val modes = presentation.modes[trait.traitId] ?: continue
        val suggested = suggestedByTrait[trait.traitId] ?: emptySet()
        val values = valuesByTrait[trait.traitId] ?: emptyMap()
        for ((field, mode) in modes) {
            val isPending = if (mode == SLDM.offer) isBlankPrefillValue(values[field]) else field in suggested
            if (isPending) pending++
        }
    }
    return pending
}

/** The fields of [traitId] a form starts marking as suggested (issue #710): its `filled` defaults, shown as
 *  suggestions until the user touches them. An `offer` field is not seeded, so it is not "suggested" -- it is
 *  offered -- and is absent here. Empty when the trait carries no default. */
fun suggestedFilledFields(presentation: PrefillPresentation, traitId: String): Set<String> =
    presentation.modes[traitId]?.filterValues { it == SLDM.filled }?.keys ?: emptySet()

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
    forUserRef: String? = null,
): Map<String, Any?> = buildMap {
    put(GDF.workflowId, workflowId)
    put(GDF.taskId, taskId)
    put(GDF.saveId, saveId)
    put(GDF.entries, entries)
    gedraId?.let { put(GDF.gedraId, it) }
    // Create the form for another user (issue #727): a create save only, so a picked user rides the body just
    // as the plain create sends `user`; an edit save passes null.
    forUserRef?.trim()?.ifEmpty { null }?.let { put(EI.user, it) }
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

/**
 * A trait locked for this caller on a form (issue #857): which workflow locks it ([workflowId], [label]) and whether
 * this caller may override the lock with a reason ([canOverride]).
 */
class TraitLock(val traitId: String, val workflowId: String, val label: String, val canOverride: Boolean)

/** A view's or the locks endpoint's `lockedTraits` (issue #857). Pure, and covered under `jsNodeTest`. */
fun parseTraitLocks(raw: Any?): List<TraitLock> = raw.toJsonListOfMaps().mapNotNull { l ->
    TraitLock(
        traitId = l[WFD.traitId].toOptStr() ?: return@mapNotNull null,
        workflowId = l[WFD.workflowId].toOptStr().orEmpty(),
        label = l[WFD.label].toOptStr().orEmpty(),
        canOverride = l[WVF.canOverride] == true,
    )
}
