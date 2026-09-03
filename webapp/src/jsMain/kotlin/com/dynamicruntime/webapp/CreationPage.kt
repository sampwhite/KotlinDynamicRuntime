package com.dynamicruntime.webapp

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

private val creationScope = MainScope()

/**
 * The entry point for creating a form (issue #536): it asks the backend for the caller's client's **creation
 * workflow** and renders that when there is one, or falls back to today's trait picker ([NewFormPage]) when
 * there is not — one route, two sources, no client left without a way to create a form. The workflow is the
 * client-configured, guided path; the picker is the generic developer one.
 */
val CreationPage = FC<Props> {
    var workflow by useState<WorkflowCreation?>(null)
    var noWorkflow by useState(false)
    var loading by useState(true)
    var loadError by useState<DisplayError?>(null)

    useEffectOnce {
        creationScope.launch {
            try {
                val wf = WorkflowApi.fetchCreationView()
                if (wf == null) noWorkflow = true else workflow = wf
            } catch (e: Throwable) {
                loadError = userFacingError(e)
            } finally {
                loading = false
            }
        }
    }

    when {
        loading -> div {
            className = ClassName("card wide")
            h1 { +"New form" }
            p {
                className = ClassName("subtitle")
                +"Loading…"
            }
        }
        loadError != null -> div {
            className = ClassName("card wide")
            h1 { +"New form" }
            errorText("Couldn't load the form.", loadError!!)
        }
        // No creation workflow: the generic trait picker, unchanged.
        noWorkflow -> NewFormPage {}
        // A creation workflow: the guided form.
        workflow != null -> CreationWorkflowForm { this.workflow = workflow!! }
    }
}
