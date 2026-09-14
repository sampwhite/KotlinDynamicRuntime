package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.home.HMENU
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/**
 * The survey Edit Form's route id (issue #659). Frontend-only, like `pageEditForm` -- it is reached from the
 * forms list's "View Info" action and its status chip, carrying a `g=<gedraId>`, not from the top nav, so it
 * has no `HMENU` entry.
 */
const val pageSurveyEdit = "surveyEdit"

private val surveyEditScope = MainScope()

/**
 * The survey-driven Edit Form (issue #659) -- the **on-boarding** view: the caller's client's survey resolved
 * against a form, seeded from its current entries, showing the survey's *subset* of traits. Reached at
 * `#page=surveyEdit&g=<gedraId>` as the read-only "View Info" view with an Edit toggle, or with `edit=1`
 * (issue #694, the forms-list status chip) already in edit mode. Its read-only view offers the **raw** editor
 * -- every trait, including ones set by API or by other workflows -- when the caller's surface carries the
 * patch endpoint. When the client has no survey (`found=false`) it says so rather than showing an empty form.
 */
val SurveyEditPage = FC<Props> {
    var gedraId by useState(hashParams()[HP.gedra].orEmpty())
    // The task the URL names (issue #700), or null to open on the earliest task needing action.
    var requestedTask by useState(hashParams()[HP.task])
    var view by useState<WorkflowView?>(null)
    var noSurvey by useState(false)
    var loading by useState(true)
    var loadError by useState<DisplayError?>(null)
    // Whether the caller's surface carries the patch endpoint, so the raw-editor link can work (issue #694).
    var rawEditAvailable by useState(false)

    // Keep the open id in step with the hash: App is the router, and a hash-only surveyEdit->surveyEdit move
    // does not remount this page, so without this the first form would stay loaded under the new URL (mirrors
    // EditFormPage, issue #417).
    useEffectOnce {
        onHashChange {
            val h = hashParams()
            gedraId = h[HP.gedra].orEmpty()
            // Back/forward between tasks (issue #700): the rail's own pushes fire no hashchange, so this only
            // runs for history moves and hand-typed URLs, and the state simply follows the hash.
            requestedTask = h[HP.task]
        }
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
                // The survey view and the patch-endpoint check are independent, so they run together (one round
                // trip). The check only gates a link, so its own failure just hides the link rather than failing
                // the page.
                val patchFetch = async {
                    try {
                        val cat = SchemaCatalogApi.fetchEndpoint(HttpMethod.POST.name, GEP.patch, resolveClient = true)
                        findFormPatchEndpoint(cat.endpoints) != null
                    } catch (e: Throwable) {
                        false
                    }
                }
                val v = if (id.isBlank()) null else WorkflowApi.fetchSurveyView(id)
                rawEditAvailable = patchFetch.await()
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
        view != null -> {
            // The raw editor (issue #694): forward the whole current hash -- the listing's `from`, filter and
            // sort -- minus this page's own keys, so the raw edit's back link returns to the same list.
            val rawEdit: (() -> Unit)? = if (!rawEditAvailable) null else {
                {
                    val carried = hashParams().filterKeys { it != HP.page && it != HP.edit }.toList()
                    navigateHash(listOf(HP.page to pageEditForm) + carried)
                }
            }
            WorkflowForm {
                // Keyed on the form, so a hash move to another form remounts with fresh edit state and honours
                // that URL's own edit flag, rather than carrying the previous form's mode across.
                key = gedraId.unsafeCast<Key>()
                this.view = view!!
                this.gedraId = gedraId
                // `edit=1` opens straight in edit mode (the forms-list chip); otherwise the read-only "View Info".
                initialEditing = hashParams()[HP.edit] == "1"
                onRawEdit = rawEdit
                // The rail's task (issue #700): the URL's when it names one of the view's, else the view's earliest
                // task needing action, else the first. Choosing a task is a move between destinations, so it
                // pushes a history entry -- Back returns to the previous task -- without firing hashchange.
                activeTask = initialTaskFor(view!!, requestedTask)
                onSelectTask = { id ->
                    requestedTask = id
                    pushHash(hashParams().filterKeys { it != HP.task }.toList() + (HP.task to id))
                }
            }
        }
    }
}
