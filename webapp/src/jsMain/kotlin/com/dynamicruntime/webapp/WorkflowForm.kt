package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.util.toJsonListOfMaps
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.useState
import web.cssom.ClassName

private val wfFormScope = MainScope()

external interface WorkflowFormProps : Props {
    /** The resolved workflow view to render -- a creation workflow, or a survey resolved against a form. */
    var view: WorkflowView

    /** The form a survey edit updates (issue #659); null for a creation workflow, which makes a new form. */
    var gedraId: String?
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
 *    shown **read-only with an Edit toggle** ("View All Data"); editing reveals each task's `edit` save, which
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
    val seeded: Map<String, Map<String, Any?>> = wf.tasks.flatMap { seedValuesFromEntries(it.entries).entries }
        .associate { it.key to it.value }

    // A creation form is always editable; a survey edit starts read-only (the "View All Data" view).
    var editing by useState(!isEdit)
    var valuesByTrait by useState(seeded)
    // The last-stored values, refreshed on each successful save. "Done" reverts the fields to this -- not the
    // first-render `seeded` snapshot -- so after a save it shows what was saved, not the pre-save values.
    var stored by useState(seeded)
    var failuresByTrait by useState<Map<String, List<SchFailure>>>(emptyMap())
    var unmetTraits by useState<Set<String>>(emptySet())
    var savingTask by useState<String?>(null)
    var runError by useState<DisplayError?>(null)
    var savedItem by useState<Map<String, Any?>?>(null)

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
                val outcome = WorkflowApi.save(body)
                if (outcome.saved) {
                    savedItem = outcome.item
                    // A survey edit stays on the form and in edit mode -- a multi-task survey is saved one task
                    // at a time, so exiting or re-seeding the whole form here would discard the other tasks'
                    // in-progress edits. Refresh the stored snapshot from the whole updated form, then push only
                    // *this* task's (possibly server-canonicalized) values into the fields; other tasks keep
                    // what the user has typed. "Done" returns to read-only showing `stored`.
                    if (isEdit) {
                        val storedNow = seedValuesFromEntries(outcome.item[GDF.entries].toJsonListOfMaps())
                        stored = storedNow
                        val savedTraitIds = task.traits.map { it.traitId }.toSet()
                        valuesByTrait = valuesByTrait + storedNow.filterKeys { it in savedTraitIds }
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
            // A way back to the listing while filling out a create form (issue #671, carried through the rename
            // of CreationWorkflowForm to this shared component): the create success screen has its own, but the
            // fill-out form otherwise had none. The survey edit uses the Edit / Done header row below instead.
            if (!isEdit) formsBackToListing()
            // The header row: the survey edit offers an Edit / Done toggle over the read-only "View All Data".
            if (isEdit) {
                div {
                    className = ClassName("row")
                    if (editing) {
                        Button {
                            onClick = { valuesByTrait = stored; failuresByTrait = emptyMap(); unmetTraits = emptySet(); editing = false }
                            +"Done"
                        }
                    } else {
                        Button {
                            type = "primary"
                            onClick = { editing = true }
                            +"Edit"
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

            wf.tasks.forEach { task ->
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
                    // The save is per task (each task's own entries), shown only while editing.
                    if (editing) {
                        div {
                            className = ClassName("row")
                            Button {
                                type = "primary"
                                loading = savingTask == task.id
                                onClick = { onSave(task) }
                                +saveFor(task).label
                            }
                        }
                    }
                }
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
 * the page's [title] and either "Loading…" or the load error. Keeps the two pages from each hand-rolling the
 * same scaffolding that differs only in the heading text.
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
