package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HMENU
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/**
 * The survey Edit Form's route id (issue #659). Frontend-only, like `pageEditForm` -- it is reached from a
 * form's status CTA carrying a `g=<gedraId>`, not from the top nav, so it has no `HMENU` entry.
 */
const val pageSurveyEdit = "surveyEdit"

private val surveyEditScope = MainScope()

/**
 * The survey-driven Edit Form (issue #659): reached at `#page=surveyEdit&g=<gedraId>`, it resolves the caller's
 * client's **survey** against that form (seeded from the form's current entries) and renders it through
 * [WorkflowForm] read-only with an Edit toggle -- the design's "View All Data". When the client has no survey
 * (`found=false`) it says so rather than showing an empty form; the raw data edit path is unaffected.
 */
val SurveyEditPage = FC<Props> {
    var gedraId by useState(hashParams()[HP.gedra].orEmpty())
    var view by useState<WorkflowView?>(null)
    var noSurvey by useState(false)
    var loading by useState(true)
    var loadError by useState<DisplayError?>(null)

    // Keep the open id in step with the hash: App is the router, and a hash-only surveyEdit->surveyEdit move
    // does not remount this page, so without this the first form would stay loaded under the new URL (mirrors
    // EditFormPage, issue #417).
    useEffectOnce {
        onHashChange { gedraId = hashParams()[HP.gedra].orEmpty() }
    }

    // Resolve the survey against the named form, re-running whenever the id changes; drop the previous load's
    // result first so none of it bleeds across.
    useEffect(gedraId) {
        view = null
        noSurvey = false
        loadError = null
        loading = true
        val id = gedraId
        surveyEditScope.launch {
            try {
                val v = if (id.isBlank()) null else WorkflowApi.fetchSurveyView(id)
                if (v == null) noSurvey = true else view = v
            } catch (e: Throwable) {
                loadError = userFacingError(e)
            } finally {
                loading = false
            }
        }
    }

    when {
        loading -> LoadStateCard { title = "Edit form" }
        loadError != null -> LoadStateCard {
            title = "Edit form"
            this.loadError = loadError
        }
        noSurvey -> div {
            className = ClassName("card wide")
            h1 { +"Edit form" }
            p {
                className = ClassName("subtitle")
                +"There is no survey for this form."
            }
            div {
                className = ClassName("row")
                Button {
                    type = "link"
                    onClick = { navigateHash(listOf(HP.page to HMENU.pageForms)) }
                    +"← Back to my forms"
                }
            }
        }
        view != null -> WorkflowForm {
            this.view = view!!
            this.gedraId = gedraId
        }
    }
}
