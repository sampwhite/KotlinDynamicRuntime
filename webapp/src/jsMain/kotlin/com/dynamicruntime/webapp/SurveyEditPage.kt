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
    val gedraId = hashParams()[HP.gedra].orEmpty()
    var view by useState<WorkflowView?>(null)
    var noSurvey by useState(false)
    var loading by useState(true)
    var loadError by useState<DisplayError?>(null)

    useEffectOnce {
        surveyEditScope.launch {
            try {
                val v = if (gedraId.isBlank()) null else WorkflowApi.fetchSurveyView(gedraId)
                if (v == null) noSurvey = true else view = v
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
            h1 { +"Edit form" }
            p {
                className = ClassName("subtitle")
                +"Loading…"
            }
        }
        loadError != null -> div {
            className = ClassName("card wide")
            h1 { +"Edit form" }
            errorText("Couldn't load the form.", loadError!!)
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
                    onClick = { navigateHash(listOf(HP.page to com.dynamicruntime.common.home.HMENU.pageForms)) }
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
