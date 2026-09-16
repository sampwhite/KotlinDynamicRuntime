package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.LAYSTR
import com.dynamicruntime.common.schema.SLDM
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.util.evalTemplate
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
     * Opens the raw **read-only** view of this form (issue #726), offered as "View raw" beside "Raw edit" on the
     * survey's read-only view: every trait, looked at rather than edited. Null for a creation form, which has no
     * stored form to view yet.
     */
    var onRawView: (() -> Unit)?

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
    val presentation = prefillPresentationOf(wf)
    val seeded: Map<String, Map<String, Any?>> = presentation.working

    // A creation form is always editable; a survey edit starts read-only (the "View Info" view) unless the URL
    // asked for edit mode (issue #694, the forms-list chip's direct-to-edit link).
    var editing by useState(if (isEdit) props.initialEditing == true else true)
    var valuesByTrait by useState(seeded)
    // Which supplied `filled` defaults are still suggestions (issue #710), by trait: seeded from the view's
    // filled defaults, a field leaving the set the first time it is touched (below) and a "reset" putting it
    // back. `offer` defaults are not seeded here -- they are not shown until applied, so they are never
    // "suggested". Empty for a form that carries no prefill, which is every form but the demo today.
    var suggestedByTrait by useState(wf.tasks.flatMap { it.traits }.associate { t ->
        t.traitId to suggestedFilledFields(presentation, t.traitId)
    }.filterValues { it.isNotEmpty() })
    // Traits whose defaults are settled: once a task saves, its fields are stored as the user's own, so they
    // drop every default affordance (chip, "Use it", "reset") and read as ordinary fields (issue #710). The
    // `presentation` here is from the first view and does not re-derive per save, so this records what it can no
    // longer tell. A fresh load returns those entries as `source=user`, carrying nothing to present anyway.
    var resolvedTraits by useState(emptySet<String>())
    // The last-stored values, refreshed on each successful save -- what "unsaved" is measured against, so after a
    // save the saved task reads as clean rather than as differing from the first-render `seeded` snapshot.
    var stored by useState(seeded)
    // Each task's status for the rail (issue #700), refreshed from the view a survey edit save returns -- so
    // saving one task can move another's mark (completing one can flip the earliest-actionable pointer).
    var statuses by useState(wf.tasks.associate { it.id to it.status })
    var failuresByTrait by useState<Map<String, List<SchFailure>>>(emptyMap())
    // Commit-time validation (issue #718): per trait, the fields the user has committed (blurred, or picked), and
    // the traits checked as a whole -- by a Save, or by leaving the task. `failuresByTrait` always holds a trait's
    // full check; these two decide how much of it the panel shows, so a first blur does not flag every untouched
    // required field beneath it.
    var committedByTrait by useState<Map<String, Set<String>>>(emptyMap())
    var wholeChecked by useState<Set<String>>(emptySet())
    var unmetTraits by useState<Set<String>>(emptySet())
    var savingTask by useState<String?>(null)
    var runError by useState<DisplayError?>(null)
    var savedItem by useState<Map<String, Any?>?>(null)

    // Whether any task holds unsaved edits (issue #700): reported to the page when it changes, so the page can
    // arm the leave guard while there is something to lose and disarm it once saved or reverted.
    val anyUnsaved = isEdit && wf.tasks.any { taskUnsaved(it, valuesByTrait, stored) }
    useEffect(anyUnsaved) { props.onDirtyChange?.invoke(anyUnsaved) }

    fun valuesOf(traitId: String): Map<String, Any?> = valuesByTrait[traitId] ?: emptyMap()

    // The per-field supplied-default descriptors for a trait's form (issue #710): each defaulted field's mode, an
    // `offer`'s value for its "Use it" link, whether a `filled` default is still a suggestion, and -- once the
    // user has touched it -- a reset that restores the value and the mark. Applying an offer needs no callback:
    // it runs through the field's ordinary edit path in SchemaForm, so only the reset is wired here.
    fun prefillFor(traitId: String): Map<String, FieldPrefill> {
        if (traitId in resolvedTraits) return emptyMap()
        val modes = presentation.modes[traitId] ?: return emptyMap()
        val suggested = suggestedByTrait[traitId] ?: emptySet()
        val filledValues = presentation.working[traitId] ?: emptyMap()
        val offered = presentation.offered[traitId] ?: emptyMap()
        return modes.mapValues { (field, mode) ->
            if (mode == SLDM.offer) {
                FieldPrefill(mode = SLDM.offer, offeredValue = offered[field])
            } else {
                val isSuggested = field in suggested
                FieldPrefill(
                    mode = SLDM.filled,
                    suggested = isSuggested,
                    // Only a touched field offers a reset -- an untouched one already shows its suggestion.
                    onReset = if (isSuggested) null else ({
                        valuesByTrait = valuesByTrait +
                            (traitId to (valuesOf(traitId) + (field to filledValues[field])))
                        suggestedByTrait = suggestedByTrait +
                            (traitId to ((suggestedByTrait[traitId] ?: emptySet()) + field))
                    }),
                )
            }
        }
    }

    // The line shown when a task still holds supplied defaults (issue #710): the wording is overridable per
    // client through a task trait's layout `strings` (LAYSTR.prefillSummary), else the frontend's own copy;
    // both template over `${'$'}{count}`. Fail-safe: a broken override renders as written rather than blanking.
    fun prefillSummaryText(task: WfTaskView, count: Int): String {
        val override = task.traits.firstNotNullOfOrNull { it.layout?.strings?.get(LAYSTR.prefillSummary) }
        if (override != null) {
            return try { override.evalTemplate(mapOf("count" to count)) } catch (_: Throwable) { override }
        }
        val lead = if (count == 1) "1 field was" else "$count fields were"
        return "$lead filled in from your account — review and save to keep them."
    }

    // The save a task offers for the current mode: the edit save when editing an existing form, else the create
    // save. Each task carries exactly one today; picking by kind keeps this honest if that ever grows.
    fun saveFor(task: WfTaskView): WfSaveView {
        val wanted = if (isEdit) WfSaveKind.edit.name else WfSaveKind.create.name
        return task.saves.firstOrNull { it.kind == wanted } ?: task.saves.first()
    }

    fun onSave(task: WfTaskView) {
        // Client-side schema check per trait first; a failure keeps the save from leaving. A Save checks the
        // task's traits as a whole, so every failure shows from here on (issue #718), and a clean check clears
        // what an earlier one left.
        val checks = task.traits.associate { it.traitId to checkInput(it.type, valuesOf(it.traitId)) }
        failuresByTrait = failuresByTrait + checks.mapValues { it.value.failures }
        wholeChecked = wholeChecked + checks.keys
        if (checks.values.any { it.failures.isNotEmpty() }) return

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
                        // presented shape the seed used (filled prefill defaults seeded, offer ones held aside;
                        // issues #679/#710) -- not the raw stored item, or a prefilled task the user never touched
                        // would read as unsaved from here on. The item is the fallback only for a save that
                        // carried no view.
                        val storedNow = outcome.view?.let { v -> seedValuesOf(v) }
                            ?: seedValuesFromEntries(outcome.item[GDF.entries].toJsonListOfMaps())
                        stored = storedNow
                        val savedTraitIds = task.traits.map { it.traitId }.toSet()
                        valuesByTrait = valuesByTrait + storedNow.filterKeys { it in savedTraitIds }
                        // The saved task's fields are now the user's own stored data, not pending defaults, so
                        // drop their default affordances and summary (issue #710): out of the suggested set, and
                        // into the resolved set so `prefillFor` and the count stop treating them as defaults.
                        suggestedByTrait = suggestedByTrait.filterKeys { it !in savedTraitIds }
                        resolvedTraits = resolvedTraits + savedTraitIds
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

    // A field committed (issue #718): remember it, and re-check its whole trait from the working values -- the
    // panel shows the committed fields' failures now and the rest once the trait is checked as a whole.
    fun onFieldCommit(trait: WfTraitView, path: String) {
        committedByTrait = committedByTrait + (trait.traitId to (committedByTrait[trait.traitId].orEmpty() + path))
        failuresByTrait = failuresByTrait + (trait.traitId to checkInput(trait.type, valuesOf(trait.traitId)).failures)
    }

    // Leaving a task in the rail checks it as a whole (issue #718): the user is done with it for now, so what
    // it still needs shows when they come back, and the mark reflects the check straight away.
    fun checkWholeTask(task: WfTaskView) {
        failuresByTrait = failuresByTrait + task.traits.associate { it.traitId to checkInput(it.type, valuesOf(it.traitId)).failures }
        wholeChecked = wholeChecked + task.traits.map { it.traitId }
    }

    // The status a rail entry draws (issue #718): while the task holds unsaved edits, the client's projection
    // from the working values -- what the server will say once they are saved, by the same rule -- so clearing
    // a required field drops the check at once; otherwise the server's verdict from the last view or save,
    // which stays the authority for anything the kernel check cannot see.
    fun statusFor(task: WfTaskView, unsaved: Boolean): WfTaskStatus? =
        if (unsaved) localTaskStatus(task, valuesByTrait) else statuses[task.id]

    // One task's body -- the "TaskPanel" (issue #700): its label (when something else does not already name the
    // task, issue #719), each trait's form, and its Save while editing. The rail layout shows one of these at a
    // time; the single-panel layout shows each task's in turn.
    fun ChildrenBuilder.taskPanel(task: WfTaskView, showLabel: Boolean = true) {
        div {
            className = ClassName("wf-task")
            if (showLabel && wf.showTaskList && task.label.isNotBlank()) {
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
                        this.failures = shownFailures(
                            failuresByTrait[trait.traitId].orEmpty(), committedByTrait[trait.traitId].orEmpty(),
                            trait.traitId in wholeChecked,
                        )
                        this.prefill = prefillFor(trait.traitId)
                        onChange = { valuesByTrait = valuesByTrait + (trait.traitId to it) }
                        onFieldEdit = { field ->
                            if (trait.traitId in unmetTraits) unmetTraits = unmetTraits - trait.traitId
                            // First touch of a suggested default makes it the user's (issue #710); a reset can
                            // bring it back. A nested field's path never matches a root default's name, so only
                            // the top-level default it belongs to is un-suggested.
                            val suggested = suggestedByTrait[trait.traitId]
                            if (suggested != null && field in suggested) {
                                suggestedByTrait = suggestedByTrait + (trait.traitId to (suggested - field))
                            }
                        }
                        onFieldCommit = { path -> onFieldCommit(trait, path) }
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
                // When defaults are the only thing left to do, say so above the Save (issue #710): the count is
                // the task's still-pending defaults, and it disappears as they are accepted, edited, or saved.
                val pendingDefaults =
                    pendingDefaultCount(task, presentation, suggestedByTrait, valuesByTrait, resolvedTraits)
                if (pendingDefaults > 0) {
                    p {
                        className = ClassName("form-prefill-summary")
                        +prefillSummaryText(task, pendingDefaults)
                    }
                }
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
        val unsaved = taskUnsaved(task, valuesByTrait, stored)
        val status = statusFor(task, unsaved)
        val mark = railMark(status)
        // Name a missing trait the way the panel heads it (its schema title), so tooltip and heading agree.
        val explanation = railExplanation(status, unsaved) { id ->
            task.traits.firstOrNull { it.traitId == id }?.let(::traitHeading) ?: humanizeFieldName(id)
        }
        button {
            className = ClassName(if (active) "wf-rail-item active" else "wf-rail-item")
            title = explanation
            asDynamic()["aria-current"] = if (active) "true" else "false"
            asDynamic()["aria-label"] = "${task.label}: $explanation"
            onClick = {
                if (!active) {
                    // The task being left gets its whole check before the switch (issue #718).
                    if (editing) wf.tasks.firstOrNull { it.id == props.activeTask }?.let { checkWholeTask(it) }
                    props.onSelectTask?.invoke(task.id)
                }
            }
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
        // The page's title (issue #719): the workflow's own label when its definition gives one, else the
        // generic title. Inline markdown, the phrase renderer a task label's copy already goes through.
        val title = wf.label.ifBlank { if (isEdit) "Edit form" else "New form" }

        val created = savedItem
        // Creation confirmation: only for a create (a survey edit stays on the form after saving).
        if (created != null && !isEdit) {
            h1 { MarkdownInline { source = title } }
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
                        committedByTrait = emptyMap(); wholeChecked = emptySet()
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
            // One header line, shared with the raw editor (issues #719, #726): the back link, the title beside
            // it, and the actions right-aligned. The back link (issue #671 for the create fill-out, #694 for the
            // survey's views, including an arrival straight into edit mode from the status chip) is the shared
            // forms link, so it returns to the same filtered, sorted listing. The survey edit's actions are Edit
            // over the read-only "View Info" (with the raw editor beside it) and Done while editing, with the
            // saved note beside either.
            formsEditorHeader(title = { MarkdownInline { source = title } }) {
                if (isEdit) {
                    if (editing) {
                        Button {
                            // Done is the one way home from an edit (issue #726): back to the listing, with this
                            // form flashed, carrying the listing's search and sort -- the same return the raw
                            // editor's Done makes. It is a navigation, so the leave guard the page armed on
                            // unsaved edits asks through the router (issue #716); nothing to ask here, and asking
                            // here too would prompt twice. A clean Done just leaves.
                            onClick = { gedraId?.let { navigateHash(formsListingReturn(hashParams(), it)) } }
                            +"Done"
                        }
                    } else {
                        Button {
                            type = "primary"
                            onClick = { editing = true }
                            +"Edit"
                        }
                        // The raw read-only view (issue #726): every trait, including the ones the survey does
                        // not show, without entering the editor -- the look-before-editing counterpart of the
                        // raw editor beside it.
                        props.onRawView?.let { rawView ->
                            Button {
                                type = "link"
                                onClick = { rawView() }
                                +"View raw"
                            }
                        }
                        // The raw editor, for the traits the survey does not show (issue #694): offered only
                        // when the caller's surface carries the patch endpoint (the page decides; null hides it).
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
                        // The rail entry the user just clicked is the task's label; the panel does not repeat it
                        // (issue #719) and opens on the trait heading.
                        taskPanel(wf.tasks.firstOrNull { it.id == active } ?: wf.tasks.first(), showLabel = false)
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
