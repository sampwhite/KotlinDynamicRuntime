package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.SchFailure
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

external interface CreationWorkflowFormProps : Props {
    var workflow: WorkflowCreation
}

/**
 * Renders a client's creation workflow and saves it (issue #536): each trait the task collects is drawn from
 * *its own resolved schema* (carried in the view's `$defs`) through the shared [SchemaForm], in the order the
 * workflow gives, with the workflow's `required` flags. The save button carries the save option's label and
 * posts to `/gedra/workflow/save`.
 *
 * Two kinds of "not yet" surface where they belong. A field the schema rejects is a per-trait validation
 * failure, shown against that field the way every form does. A **required trait left empty** is the workflow's
 * gate: the save comes back `saved=false` naming the unmet traits, and each named trait's section says so --
 * the soft-validation seam, on the page.
 *
 * A creation workflow is exactly one task by the backend's rule, so this renders `workflow.task`; the shape is
 * a list so a survey workflow's several tasks drop in without a rewrite.
 */
val CreationWorkflowForm = FC<CreationWorkflowFormProps> { props ->
    val wf = props.workflow
    val task = wf.task

    var valuesByTrait by useState<Map<String, Map<String, Any?>>>(emptyMap())
    var failuresByTrait by useState<Map<String, List<SchFailure>>>(emptyMap())
    var unmetTraits by useState<Set<String>>(emptySet())
    var running by useState(false)
    var runError by useState<DisplayError?>(null)
    var createdItem by useState<Map<String, Any?>?>(null)

    fun valuesOf(traitId: String): Map<String, Any?> = valuesByTrait[traitId] ?: emptyMap()

    fun reset() {
        valuesByTrait = emptyMap()
        failuresByTrait = emptyMap()
        unmetTraits = emptySet()
        runError = null
        createdItem = null
    }

    fun onSave() {
        // Client-side schema check per trait first; a failure keeps the save from leaving.
        val checks = task.traits.associate { it.traitId to checkInput(it.type, valuesOf(it.traitId)) }
        val fails = checks.filterValues { it.failures.isNotEmpty() }.mapValues { it.value.failures }
        failuresByTrait = fails
        if (fails.isNotEmpty()) {
            return
        }
        val entries = workflowSaveEntries(checks.mapValues { it.value.payload ?: emptyMap() })
        val body = workflowSaveBody(wf.workflowId, task.id, task.saves.first().id, entries)
        running = true
        runError = null
        wfFormScope.launch {
            try {
                val outcome = WorkflowApi.save(body)
                // Saved -> confirm; refused -> the workflow's gate names which required traits are still empty.
                if (outcome.saved) createdItem = outcome.item else unmetTraits = outcome.unmetTraits.toSet()
            } catch (e: Throwable) {
                runError = userFacingError(e)
            } finally {
                running = false
            }
        }
    }

    div {
        className = ClassName("card wide")
        h1 { +"New form" }

        val created = createdItem
        if (created != null) {
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
                    onClick = { reset() }
                    +"Create another"
                }
                Button {
                    type = "link"
                    onClick = { navigateHash(listOf(HP.page to HMENU.pageForms)) }
                    +"← Back to my forms"
                }
            }
        } else {
            p {
                className = ClassName("subtitle")
                +task.label.ifBlank { "Fill in the form and create it." }
            }

            task.traits.forEach { trait ->
                div {
                    className = ClassName("wf-trait")
                    h2 { +traitHeading(trait) }
                    if (trait.traitId in unmetTraits) {
                        p {
                            className = ClassName("error-text")
                            +"This is required — please fill it in."
                        }
                    }
                    SchemaForm {
                        type = trait.type
                        this.values = valuesOf(trait.traitId)
                        editable = true
                        friendly = true
                        // The caller's cfacts (issue #569), so a property's g-visibleWhen hides an admin-only
                        // field from an ordinary caller here as it does on the endpoint form.
                        this.cfacts = wf.cfacts
                        this.failures = failuresByTrait[trait.traitId]
                        onChange = { valuesByTrait = valuesByTrait + (trait.traitId to it) }
                        // The server's "required" flag on this trait is stale the moment it is edited.
                        onFieldEdit = { if (trait.traitId in unmetTraits) unmetTraits = unmetTraits - trait.traitId }
                    }
                }
            }

            div {
                className = ClassName("row")
                Button {
                    type = "primary"
                    loading = running
                    onClick = { onSave() }
                    +task.saves.first().label
                }
            }

            if (unmetTraits.isNotEmpty()) {
                p {
                    className = ClassName("form-stale")
                    +"Some required sections are empty — they are marked above."
                }
            }
            runError?.let { errorText("Couldn't create the form.", it) }
        }
    }
}

/** The heading for a trait section: its schema title if it has one, else a humanized trait id. */
private fun traitHeading(trait: WfTraitView): String =
    trait.type.title?.takeIf { it.isNotBlank() } ?: humanizeFieldName(trait.traitId)
