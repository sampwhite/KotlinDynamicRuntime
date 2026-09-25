package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.GEP
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
 *
 * With `wf=<workflowId>` (issue #791) it opens that **normal workflow** against the form instead -- where the forms
 * list's workflow column links. The same form component draws it; the view says whether the form is engaged and
 * where the workflow stands in its windows, so a form not yet in it is offered Engage, and a closed one is shown
 * read-only.
 */
val SurveyEditPage = FC<Props> {
    var gedraId by useState(hashParams()[HP.gedra].orEmpty())
    // The task the URL names (issue #700), or null to open on the earliest task needing action.
    var requestedTask by useState(hashParams()[HP.task])
    // The normal workflow the URL names (issue #791), or null for the form's survey.
    var workflowId by useState(hashParams()[HP.workflow])
    // Bumped to reload the view in place -- after engaging, which changes what the view says.
    var reloads by useState(0)
    var engageError by useState<DisplayError?>(null)
    var view by useState<WorkflowView?>(null)
    var noSurvey by useState(false)
    var loading by useState(true)
    var loadError by useState<DisplayError?>(null)
    // Whether the caller's surface carries the patch endpoint, so the raw-editor link can work (issue #694).
    var rawEditAvailable by useState(false)
    // The client whose copy of the workflow endpoints the view came from (issue #714), handed to the form so its
    // save posts to the same copy; null when the shared endpoint answered.
    var workflowClient by useState<String?>(null)
    // Whether the form holds edits not yet saved (issue #700), as WorkflowForm reports it.
    var dirty by useState(false)

    // The leave guard (issue #700): armed while there are unsaved edits, so leaving the page -- in-app (the
    // router asks before switching) or by reload / closed tab (the browser's own prompt) -- warns first.
    // Switching tasks never warns: the working copy survives a switch, and only a leave drops it. Disarmed the
    // moment the edits are saved or reverted, so a clean page never nags.
    useEffect(dirty) {
        if (dirty) {
            // "Still here" is this page AND this form: a task switch stays; another form's survey -- the same
            // page, but a keyed remount that would drop the edits -- is a leave.
            val form = gedraId
            val wf = workflowId
            LeaveGuard.arm({ h -> h[HP.page] == pageSurveyEdit && h[HP.gedra] == form && h[HP.workflow] == wf }) {
                LeaveGuard.confirmLeave("You have unsaved changes on this form. Leave the page and lose them?")
            }
        } else {
            LeaveGuard.disarm()
        }
    }

    // Keep the open id in step with the hash: App is the router, and a hash-only surveyEdit->surveyEdit move
    // does not remount this page, so without this the first form would stay loaded under the new URL (mirrors
    // EditFormPage, issue #417).
    useEffectOnce {
        onHashChange {
            val h = hashParams()
            // Only a hash that still names THIS page drives its state (the lesson FormsPage learned in #694, and
            // what the leave guard depends on): a vetoed leave shows this listener the other page's hash for a
            // moment before the router puts the address back, and reading `g=` off it here would blank the form.
            if (h[HP.page] != pageSurveyEdit) return@onHashChange
            gedraId = h[HP.gedra].orEmpty()
            workflowId = h[HP.workflow]
            // Back/forward between tasks (issue #700): the rail's own pushes fire no hashchange, so this only
            // runs for history moves and hand-typed URLs, and the state simply follows the hash.
            requestedTask = h[HP.task]
        }
    }

    // Resolve the survey against the named form, re-running whenever the id changes; drop the previous load's
    // result first so none of it bleeds across.
    useEffect(gedraId, workflowId, reloads) {
        view = null
        engageError = null
        noSurvey = false
        loadError = null
        workflowClient = null
        loading = true
        val id = gedraId
        val wfId = workflowId
        surveyEditScope.launch {
            try {
                // The form's own client (from its id, issue #714): the survey view, its save and the raw-edit
                // link's patch endpoint are all resolved on *that* client's surface, so an `allClients` admin
                // opening another client's form gets that client's survey -- resolved on their own it reads "no
                // survey", since the survey is the form's client's. Null for an unparseable id, which resolves on
                // the caller's own surface (an ordinary caller, whose client is the form's).
                val formClient = formClientOf(id)
                // The patch-endpoint check and the view lookup are independent, so they run together (one round
                // trip). The check only gates a link, so its own failure just hides the link rather than failing
                // the page.
                val patchFetch = async {
                    try {
                        findFormPatchEndpoint(fetchFormEndpoint(HttpMethod.POST.name, GEP.patch, formClient).endpoints) != null
                    } catch (e: Throwable) {
                        false
                    }
                }
                // Which copy of the workflow endpoints to call, decided by the backend's resolution rather than
                // by forming the client path here: a client that varies nothing has only the shared endpoint
                // (bound to the caller's own client -- the same client, for an ordinary caller), and asking for
                // its copy by exact path would find nothing (the #714 review's regression).
                val viewPath = if (id.isBlank()) null else
                    fetchFormEndpoint(HttpMethod.GET.name, GEP.workflowView, formClient).endpoints.firstOrNull()?.path
                val surfaceClient = clientOfResolvedPath(viewPath, GEP.workflowView, formClient)
                val v = when {
                    id.isBlank() -> null
                    wfId != null -> WorkflowApi.fetchWorkflowView(wfId, id, surfaceClient)
                    else -> WorkflowApi.fetchSurveyView(id, surfaceClient)
                }
                rawEditAvailable = patchFetch.await()
                workflowClient = surfaceClient
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
                +(if (workflowId != null) "There is no such workflow for this form." else "There is no survey for this form.")
            }
            // The shared way back (issue #714 review): carries the listing's chosen client, filter and sort home,
            // as the survey's own header and the raw editor do -- a bare `page=forms` dropped them all.
            formsBackToListing()
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
            // The raw read-only view (issue #726): the listing with this form open in place, carrying the
            // listing's search and sort. Needs only the get the listing surface already has, so it is always
            // offered where the raw editor may not be.
            val rawView: () -> Unit = { navigateHash(formsRawViewHash(hashParams(), gedraId)) }
            engageError?.let { errorText("Couldn't put the form into this workflow.", it) }
            WorkflowForm {
                // Keyed on the form (and the workflow), so a hash move to another form remounts with fresh edit
                // state and honours that URL's own edit flag, rather than carrying the previous form's mode across.
                key = "$gedraId|${workflowId.orEmpty()}|$reloads".unsafeCast<Key>()
                this.view = view!!
                this.gedraId = gedraId
                // The client whose copy of the save to post to -- where the view came from (issue #714).
                this.client = workflowClient
                // `edit=1` opens straight in edit mode (the forms-list chip); otherwise the read-only "View Info".
                initialEditing = hashParams()[HP.edit] == "1"
                onRawEdit = rawEdit
                onRawView = rawView
                // The rail's task (issue #700): the URL's when it names one of the view's, else the view's earliest
                // task needing action, else the first. Choosing a task is a move between destinations, so it
                // pushes a history entry -- Back returns to the previous task -- without firing hashchange.
                activeTask = initialTaskFor(view!!, requestedTask)
                onSelectTask = { id ->
                    requestedTask = id
                    pushHash(hashParams().filterKeys { it != HP.task }.toList() + (HP.task to id))
                }
                onDirtyChange = { dirty = it }
                // Approved (issue #832): the form's state moved -- the step now reads approved, and the next one may be
                // the call to action -- so the view is read again, as after an engage. It stays on the approved step:
                // with every step done the view names no call to action, and the page would open on the first.
                onApproved = { taskId ->
                    requestedTask = taskId
                    pushHash(hashParams().filterKeys { it != HP.task }.toList() + (HP.task to taskId))
                    reloads += 1
                }
                // Engage (issue #791): put the form into the workflow, then reload the view, which now says it is
                // engaged and opens the tasks. A refusal -- not eligible after all -- is shown with its reasons.
                val engageClient = workflowClient
                onEngage = workflowId?.let { wfId ->
                    {
                        surveyEditScope.launch {
                            try {
                                WorkflowApi.engage(gedraId, wfId, engageClient)
                                reloads += 1
                            } catch (e: Throwable) {
                                engageError = userFacingError(e)
                            }
                        }
                    }
                }
            }
        }
    }
}
