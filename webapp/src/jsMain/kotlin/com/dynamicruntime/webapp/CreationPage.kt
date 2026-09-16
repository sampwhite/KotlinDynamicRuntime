package com.dynamicruntime.webapp

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.useEffectOnce
import react.useState

private val creationScope = MainScope()

/**
 * The entry point for creating a form (issue #536): it asks the backend for the caller's client's **creation
 * workflow** and renders that when there is one, or falls back to today's trait picker ([NewFormPage]) when
 * there is not — one route, two sources, no client left without a way to create a form. The workflow is the
 * client-configured, guided path; the picker is the generic developer one.
 */
val CreationPage = FC<Props> {
    var workflow by useState<WorkflowView?>(null)
    var noWorkflow by useState(false)
    var loading by useState(true)
    var loadError by useState<DisplayError?>(null)
    // Whether the caller may create for another user (issue #727), handed to the workflow form so its create can
    // offer the picker. Defaults off; a failure to read the home config just leaves the picker out.
    var canManageUsers by useState(false)

    useEffectOnce {
        creationScope.launch {
            try {
                canManageUsers = runCatching { HomeApi.fetchConfig().canManageUsers }.getOrDefault(false)
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
        loading -> LoadStateCard { title = "New form" }
        loadError != null -> LoadStateCard {
            title = "New form"
            this.loadError = loadError
        }
        // No creation workflow: the generic trait picker, unchanged.
        noWorkflow -> NewFormPage {}
        // A creation workflow: the guided form (no gedraId -> the create save makes a new form).
        workflow != null -> WorkflowForm {
            view = workflow!!
            gedraId = null
            allowCreateForUser = canManageUsers
        }
    }
}
