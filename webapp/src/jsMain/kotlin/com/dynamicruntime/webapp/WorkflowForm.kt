package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.util.toJsonListOfMaps
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.ClassName

private val wfFormScope = MainScope()

/** What Done asks before discarding unsaved edits (issue #716); worded for the button, not for a navigation. */
private const val discardEditsPrompt = "You have unsaved changes. Discard them and stop editing?"

external interface WorkflowFormProps : Props {
    /** The resolved workflow view to render -- a creation workflow, or a survey resolved against a form. */
    var view: WorkflowView

    /** The form a survey edit updates (issue #659); null for a creation workflow, which makes a new form. */
    var gedraId: String?

    /**
     * The client whose copy of the workflow save to post to (issue #714): the form's own, when it is another
     * client's than the caller's and that client has a copy; null posts to the shared path, bound to the
     * caller's own client. The page derives it from the view it fetched (`clientOfResolvedPath`), so the save
     * goes where the view came from.
     */
    var client: String?

    /**
     * Whether a survey edit opens already in edit mode (issue #694): the forms-list status chip's direct-to-edit
     * link sets it; the "View Info" action leaves it unset for the read-only view. Ignored for a creation form,
     * which is always editable.
     */
    var initialEditing: Boolean?

    /**
     * Opens the **raw** editor for this form (issue #694), offered as a "Raw edit" link on the survey's read-only
     * view. The survey is an on-boarding *subset* of traits; the raw editor edits every trait, including ones set
     * by API or other workflows. Null when the caller's surface has no patch endpoint (a control that could only
     * fail is not shown), or for a creation form.
     */
    var onRawEdit: (() -> Unit)?

    /**
     * The task the rail shows (issue #700), for a multi-task survey; ignored (every task renders) for a
     * single-task survey or a creation form. The page chooses it from the URL or the view's earliest task
     * needing action.
     */
    var activeTask: String?

    /** Called when the user picks a task in the rail (issue #700); the page owns the choice, since it rides the
     *  hash. Unset (with a single task) means no rail. */
    var onSelectTask: ((String) -> Unit)?

    /**
     * Told whether any task holds edits not yet saved (issue #700), each time that changes -- what the page arms
     * the leave guard on. Client-side: a working value differing from the last stored one.
     */
    var onDirtyChange: ((Boolean) -> Unit)?
}

/**
 * Renders a resolved workflow and saves it (issues #536, #659). Each trait a task collects is drawn from *its
 * own resolved schema* (carried in the view's `$defs`) through the shared [SchemaForm], in the workflow's order
 * with its `required` flags. It serves two shapes on one component, matching the backend's one view/save
 * endpoint:
 *
 *  - **Creation** (`gedraId == null`): one task, always editable, its `create` save makes the form; on success
 *    the page confirms with the new id.
 *  - **Survey edit** (`gedraId != null`): the form's one-to-three tasks, each seeded from its current entries,
 *    shown **read-only with an Edit toggle** ("View Info"); editing reveals each task's `edit` save, which
 *    updates the form. Saving does not gate on completeness -- an incomplete survey is recorded as state, not
 *    refused -- so a required trait left empty comes back only through the status column, not as a block here.
 *
 * A field the schema rejects is a per-trait validation failure shown against that field. A required trait left
 * empty on a **create** is the workflow's gate: the save returns `saved=false` naming the unmet traits, and each
 * named section says so.
 */
val WorkflowForm = FC<WorkflowFormProps> { props ->
    val wf = props.view
    val gedraId = props.gedraId
    val isEdit = gedraId != null

    // Seed every task's fields from its current entries (empty for a creation view). Trait ids are unique across
    // a workflow's tasks, so one map serves them all.
    val seeded: Map<String, Map<String, Any?>> = seedValuesOf(wf)

    // A creation form is always editable; a survey edit starts read-only (the "View Info" view) unless the URL
    // asked for edit mode (issue #694, the forms-list chip's direct-to-edit link).
    var editing by useState(if (isEdit) props.initialEditing == true else true)
    var valuesByTrait by useState(seeded)
    // The last-stored values, refreshed on each successful save. "Done" reverts the fields to this -- not the
    // first-render `seeded` snapshot -- so after a save it shows what was saved, not the pre-save values.
    var stored by useState(seeded)
    // Each task's status for the rail (issue #700), refreshed from the view a survey edit save returns -- so
    // saving one task can move another's mark (completing one can flip the earliest-actionable pointer).
    var statuses by useState(wf.tasks.associate { it.id to it.status })
    var failuresByTrait by useState<Map<String, List<SchFailure>>>(emptyMap())
    var unmetTraits by useState<Set<String>>(emptySet())
    var savingTask by useState<String?>(null)
    var runError by useState<DisplayError?>(null)
    var savedItem by useState<Map<String, Any?>?>(null)

    // Whether any task holds unsaved edits (issue #700): reported to the page when it changes, so the page can
    // arm the leave guard while there is something to lose and disarm it once saved or reverted.
    val anyUnsaved = isEdit && wf.tasks.any { taskUnsaved(it, valuesByTrait, stored) }
    useEffect(anyUnsaved) { props.onDirtyChange?.invoke(anyUnsaved) }

    fun valuesOf(traitId: String): Map<String, Any?> = valuesByTrait[traitId] ?: emptyMap()

    // The save a task offers for the current mode: the edit save when editing an existing form, else the create
    // save. Each task carries exactly one today; picking by kind keeps this honest if that ever grows.
    fun saveFor(task: WfTaskView): WfSaveView {
        val wanted = if (isEdit) WfSaveKind.edit.name else WfSaveKind.create.name
        return task.saves.firstOrNull { it.kind == wanted } ?: task.saves.first()
    }

    fun onSave(task: WfTaskView) {
        // Client-side schema check per trait first; a failure keeps the save from leaving.
        val checks = task.traits.associate { it.traitId to checkInput(it.type, valuesOf(it.traitId)) }
        val fails = checks.filterValues { it.failures.isNotEmpty() }.mapValues { it.value.failures }
        failuresByTrait = failuresByTrait + fails
        if (fails.isNotEmpty()) return

        val entries = workflowSaveEntries(checks.mapValues { it.value.payload ?: emptyMap() })
        val body = workflowSaveBody(wf.workflowId, task.id, saveFor(task).id, entries, gedraId)
        savingTask = task.id
        runError = null
        wfFormScope.launch {
            try {
                val outcome = WorkflowApi.save(body, props.client)
                if (outcome.saved) {
                    savedItem = outcome.item
                    // A survey edit stays on the form and in edit mode -- a multi-task survey is saved one task
                    // at a time, so exiting or re-seeding the whole form here would discard the other tasks'
                    // in-progress edits. Refresh the stored snapshot from the whole updated form, then push only
                    // *this* task's (possibly server-canonicalized) values into the fields; other tasks keep
                    // what the user has typed. "Done" returns to read-only showing `stored`.
                    if (isEdit) {
                        // The refreshed snapshot comes from the returned VIEW's per-task entries -- the same
                        // presented shape the seed used, prefill defaults included (issue #679) -- not the raw
                        // stored item, or a prefilled task the user never touched would read as unsaved from here
                        // on. The item is the fallback only for a save that carried no view.
                        val storedNow = outcome.view?.let { v -> seedValuesOf(v) }
                            ?: seedValuesFromEntries(outcome.item[GDF.entries].toJsonListOfMaps())
                        stored = storedNow
                        val savedTraitIds = task.traits.map { it.traitId }.toSet()
                        valuesByTrait = valuesByTrait + storedNow.filterKeys { it in savedTraitIds }
                        // The save is the refresh (issue #700): every task's status follows from the returned view.
                        outcome.view?.let { v -> statuses = v.tasks.associate { it.id to it.status } }
                    }
                } else {
                    // The create gate: the required traits still empty.
                    unmetTraits = outcome.unmetTraits.toSet()
                }
            } catch (e: Throwable) {
                runError = userFacingError(e)
            } finally {
                savingTask = null
            }
        }
    }

    // One task's body -- the "TaskPanel" (issue #700): its label, each trait's form, and its Save while editing.
    // The rail layout shows one of these at a time; the single-panel layout shows each task's in turn.
    fun ChildrenBuilder.taskPanel(task: WfTaskView) {
        div {
            className = ClassName("wf-task")
            if (wf.showTaskList && task.label.isNotBlank()) {
                Markdown { source = task.label; inlineUi = true }
            }
            task.traits.forEach { trait ->
                div {
                    className = ClassName("wf-trait")
                    trait.layout?.label?.let { Markdown { source = it; inlineUi = true } } ?: h2 { +traitHeading(trait) }
                    if (trait.traitId in unmetTraits) {
                        p {
                            className = ClassName("error-text")
                            +"This is required — please fill it in."
                        }
                    }
                    SchemaForm {
                        type = trait.type
                        this.values = valuesOf(trait.traitId)
                        editable = editing
                        friendly = true
                        this.cfacts = wf.cfacts
                        this.layouts = wf.layouts
                        this.failures = failuresByTrait[trait.traitId]
                        onChange = { valuesByTrait = valuesByTrait + (trait.traitId to it) }
                        onFieldEdit = { if (trait.traitId in unmetTraits) unmetTraits = unmetTraits - trait.traitId }
                    }
                }
            }
            // The save is per task (each task's own entries), shown only while editing. Disabled while there is
            // nothing to save (issue #717) -- the working values match the stored snapshot, the same comparison
            // that drives the rail's unsaved badge, so a prefilled task the user never touched offers no Save
            // and a saved task's Save disables itself once the snapshot refreshes -- and while ANY task's save
            // is in flight: `loading` blocks only the button being saved, and the single-panel layout shows
            // every task's. Disabled means "nothing to do", never "you did it wrong": a task with known
            // validation failures keeps its Save, so clicking it shows the errors rather than a dead button.
            if (editing) {
                div {
                    className = ClassName("row")
                    Button {
                        type = "primary"
                        loading = savingTask == task.id
                        disabled = !taskUnsaved(task, valuesByTrait, stored) || savingTask != null
                        onClick = { onSave(task) }
                        +saveFor(task).label
                    }
                }
            }
        }
    }

    // One rail entry (issue #700): the task's label, its status mark, and an unsaved badge when its working values
    // differ from the stored ones. A real button, so it is keyboard reachable; `aria-current` names the open
    // task, and the title / aria-label carry what the mark means -- a hover tooltip alone is not enough for
    // touch or a screen reader.
    fun ChildrenBuilder.railItem(task: WfTaskView, active: Boolean) {
        val status = statuses[task.id]
        val mark = railMark(status)
        val unsaved = taskUnsaved(task, valuesByTrait, stored)
        // Name a missing trait the way the panel heads it (its schema title), so tooltip and heading agree.
        val explanation = railExplanation(status, unsaved) { id ->
            task.traits.firstOrNull { it.traitId == id }?.let(::traitHeading) ?: humanizeFieldName(id)
        }
        button {
            className = ClassName(if (active) "wf-rail-item active" else "wf-rail-item")
            title = explanation
            asDynamic()["aria-current"] = if (active) "true" else "false"
            asDynamic()["aria-label"] = "${task.label}: $explanation"
            onClick = { props.onSelectTask?.invoke(task.id) }
            span {
                className = ClassName("wf-mark wf-mark-${mark.name}")
                +(if (mark == RailMark.incomplete) "○" else "✓")
            }
            span {
                className = ClassName("wf-rail-label")
                +task.label
            }
            if (unsaved) {
                span {
                    className = ClassName("wf-unsaved")
                    +"●"
                }
            }
        }
    }

    div {
        className = ClassName("card wide")
        h1 { +if (isEdit) "Edit form" else "New form" }

        val created = savedItem
        // Creation confirmation: only for a create (a survey edit stays on the form after saving).
        if (created != null && !isEdit) {
            val id = created[GDF.gedraId] as? String ?: ""
            p {
                className = ClassName("form-ok")
                +"✓ Form created."
            }
            p {
                className = ClassName("type-hint")
                +"Reference id"
            }
            p {
                className = ClassName("code")
                +id
            }
            div {
                className = ClassName("row")
                Button {
                    type = "primary"
                    onClick = { navigateHash(listOf(HP.page to HMENU.pageForms, HP.gedra to id)) }
                    +"View form"
                }
                Button {
                    onClick = {
                        valuesByTrait = emptyMap(); failuresByTrait = emptyMap()
                        unmetTraits = emptySet(); runError = null; savedItem = null
                    }
                    +"Create another"
                }
                Button {
                    type = "link"
                    onClick = { navigateHash(listOf(HP.page to HMENU.pageForms)) }
                    +"← Back to my forms"
                }
            }
        } else {
            // A way back to the listing from the form (issue #671 for the create fill-out; extended to the survey
            // in #694): the create success screen has its own, but the fill-out form, and the survey's read-only
            // and edit views -- including an arrival straight into edit mode from the status chip -- otherwise
            // had none. The shared forms back link, so it returns to the same filtered, sorted listing.
            formsBackToListing()
            // The header row: the survey edit offers an Edit / Done toggle over the read-only "View All Data".
            if (isEdit) {
                div {
                    className = ClassName("row")
                    if (editing) {
                        Button {
                            // Done reverts to the stored values -- a discard, when there are unsaved edits. The
                            // leave guard lives in the router and only ever sees a navigation, so a button that
                            // drops the same edits in place has to ask for itself (issue #716), with the guard's
                            // own dialog. A clean Done stays silent: there is nothing to lose.
                            onClick = {
                                if (!anyUnsaved || LeaveGuard.confirmLeave(discardEditsPrompt)) {
                                    valuesByTrait = stored; failuresByTrait = emptyMap(); unmetTraits = emptySet(); editing = false
                                }
                            }
                            +"Done"
                        }
                    } else {
                        Button {
                            type = "primary"
                            onClick = { editing = true }
                            +"Edit"
                        }
                        // The raw editor, for the traits the survey does not show (issue #694): offered only when
                        // the caller's surface carries the patch endpoint (the page decides; null hides it).
                        props.onRawEdit?.let { rawEdit ->
                            Button {
                                type = "link"
                                onClick = { rawEdit() }
                                +"Raw edit"
                            }
                        }
                    }
                    savedItem?.let {
                        p {
                            className = ClassName("form-ok")
                            +"✓ Saved."
                        }
                    }
                }
            }

            // A multi-task survey shows ONE task at a time, picked from the rail (issue #700) -- so there is one
            // Save in view, and the other tasks' working values stay in state across the switch. A single-task
            // survey and the creation form keep the single panel with their one task.
            val active = props.activeTask
            if (isEdit && wf.tasks.size > 1 && props.onSelectTask != null) {
                div {
                    className = ClassName("wf-layout")
                    div {
                        className = ClassName("wf-rail")
                        wf.tasks.forEach { railItem(it, active = it.id == active) }
                    }
                    div {
                        className = ClassName("wf-panel")
                        taskPanel(wf.tasks.firstOrNull { it.id == active } ?: wf.tasks.first())
                    }
                }
            } else {
                wf.tasks.forEach { taskPanel(it) }
            }

            if (unmetTraits.isNotEmpty()) {
                p {
                    className = ClassName("form-stale")
                    +"Some required sections are empty — they are marked above."
                }
            }
            runError?.let { errorText(if (isEdit) "Couldn't save the form." else "Couldn't create the form.", it) }
        }
    }
}

/** The heading for a trait section: its schema title if it has one, else a humanized trait id. */
private fun traitHeading(trait: WfTraitView): String =
    trait.type.title?.takeIf { it.isNotBlank() } ?: humanizeFieldName(trait.traitId)

external interface LoadStateCardProps : Props {
    /** The page heading, shown while it loads or fails ("New form", "Edit form"). */
    var title: String

    /** The load failure to show; null renders the "Loading…" state instead. */
    var loadError: DisplayError?
}

/**
 * The load-state chrome shared by the workflow pages ([CreationPage], [SurveyEditPage]): a `card wide` carrying
 * the page's [LoadStateCardProps.title] and either "Loading…" or the load error. Keeps the two pages from each
 * hand-rolling the same scaffolding that differs only in the heading text.
 */
val LoadStateCard = FC<LoadStateCardProps> { props ->
    div {
        className = ClassName("card wide")
        h1 { +props.title }
        val err = props.loadError
        if (err == null) {
            p {
                className = ClassName("subtitle")
                +"Loading…"
            }
        } else {
            errorText("Couldn't load the form.", err)
        }
    }
}
