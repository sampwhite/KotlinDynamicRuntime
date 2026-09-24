package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.clearedAt
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useEffectOnce
import react.useRef
import react.useState
import web.cssom.ClassName

/** Coroutine scope for the edit page's suspend calls (the endpoint fetches, the form fetch, and the patch). */
private val editScope = MainScope()

/**
 * The edit page's route id (issue #417). A frontend-only page id -- edit is reached from the read-only view,
 * not the server-built nav, so it has no [HMENU] entry -- shared here so [App]'s router and the view's Edit
 * button name the same string.
 */
const val pageEditForm = "editForm"

/**
 * A page for **editing a stored form document** (issue #417): it loads the form by id, seeds the client-scoped
 * **patch** endpoint's edit union from the form's current entries, and renders that through the same
 * schema-driven [SchemaForm] the create page uses -- so each entry becomes an editable section with a real
 * sub-form for its data. Saving sends the edits as a patch and reports what changed.
 *
 * The one genuinely new capability of the forms redesign. Its own route off the read-only view for now (reached
 * by an Edit button there); a later slice folds create/view/edit into a list-centric hub. The gedra-form helpers
 * it shares -- endpoint discovery, the patch shaping, [summarizeForm] -- live in `GedraForms.kt`.
 *
 * Editing works in the endpoint's own terms (the edit union): each section carries an **action** (replace the
 * entry, merge into it, or delete it) beside its data, seeded to "replace" so opening and saving is a faithful
 * round-trip. Adding a section adds a trait; switching one to delete removes it.
 */
// Sibling of the create page (NewFormPage): the same shape of state block and schema-driven form, so the two
// read alike. Inherent to two components doing the same job; the duplicated-fragment inspection is suppressed
// rather than dedup'd into a worse-reading abstraction (issue #671 follow-up).
@Suppress("DuplicatedCode")
val EditFormPage = FC<Props> {
    // The form being edited, from the hash (`g=<id>`): initialized from the hash and kept in step with it, so
    // navigating to another edit URL reloads and re-seeds rather than leaving the previous form on screen --
    // where Save would patch the stale gedra (issue #417).
    var gedraId by useState(hashParams()[HP.gedra])
    var patchEndpoint by useState<EndpointInfo?>(null)
    // The get endpoint, kept so a save can re-read the form and re-seed the edits in place (issue #726).
    var getEndpoint by useState<EndpointInfo?>(null)
    var catalog by useState<Catalog?>(null)
    // The last save landed (issue #726): the "✓ Saved." note beside Done. Dropped again by the next edit, so the
    // note never stands beside changes it does not cover.
    var saved by useState(false)
    var values by useState<Map<String, Any?>>(emptyMap())
    var failures by useState<List<SchFailure>?>(null)
    var revalidate by useState(false)
    var focusRequest by useState(0)
    var running by useState(false)
    var runError by useState<DisplayError?>(null)
    var loadError by useState<DisplayError?>(null)
    // The open form could not be loaded (absent, or not this caller's) -- a deep link to a form that is not theirs.
    var notFound by useState(false)
    // Named to avoid the `Button { loading = running }` collision that loops the render (issues #408, #417).
    var loadingSchema by useState(true)
    // Whether this caller can see across clients (issue #667): a precondition for free-form trait entry, which is
    // offered only to an `allClients` admin on the shared/global surface. Defaults off, so an ordinary caller
    // (and any failure to read the home config) leaves the trait picker a closed choice.
    var canSeeAllClients by useState(false)
    // Whether the form's client has a survey (issue #726), so a "View info" link beside Done can lead back to the
    // survey's read-only view. Decided the way the survey page decides it -- by resolving and probing the
    // workflow view on the form's own client -- since a control that could only dead-end is not shown.
    var hasSurvey by useState(false)
    // The last seeded snapshot of the edits -- the load, or the re-read after a save -- so "unsaved" is the working
    // values differing from it (issue #726 review), the same measure the survey editor arms its leave guard on.
    var seeded by useState<Map<String, Any?>>(emptyMap())
    // The form's traits locked for this caller (issue #857): drawn as a notice, and a changed section of one is sent
    // only as an explicit override -- by someone the lock allows -- with a reason.
    var locks by useState<List<TraitLock>>(emptyList())
    var overriding by useState(false)
    var overrideReason by useState("")
    var lockError by useState<String?>(null)
    // Whether the user has typed since the current save was sent (issue #726 review). A ref rather than state:
    // it is read inside the save's coroutine after the round trip, where a state value would be the stale
    // closure's. If they have, the re-read must not overwrite their newer keystrokes, nor "Saved." stand beside
    // them.
    val editedSinceSave = useRef(false)

    // Keep the open id in step with the hash, so a navigation to another edit URL re-runs the load below. App is
    // the router; a hash-only editForm->editForm move does not remount this page, so without this the first form
    // would stay loaded under the new URL (issue #417).
    useEffectOnce {
        onHashChange { gedraId = hashParams()[HP.gedra] }
    }

    // Load the endpoints and the named form, re-running whenever the id changes. Re-fetching the endpoints on a
    // reload is a rare, cheap cost (edit->edit happens only by a hand-edited URL); what matters is that the
    // form is re-seeded from the id now in the hash, never left as the previous one.
    useEffect(gedraId) {
        // A new form to edit: drop the previous load's seed, validation, and any success screen so none of it
        // bleeds across the reload.
        values = emptyMap()
        failures = null
        revalidate = false
        runError = null
        notFound = false
        saved = false
        seeded = emptyMap()
        hasSurvey = false
        locks = emptyList()
        overriding = false
        overrideReason = ""
        lockError = null
        loadingSchema = true
        val id = gedraId
        editScope.launch {
            try {
                // Just the two endpoints this page uses (issue #552): the **patch** endpoint, whose schema this
                // page renders (already narrowed to what the client supports, so a section cannot offer a trait
                // the client removed), and the **get** endpoint, which it only *invokes* to load the form's
                // current entries. Fetched in isolation -- each carries only its own `$defs` closure plus the
                // page's per-client cfacts and layouts -- rather than the whole catalog once scanned to find
                // these by suffix. Run together, so two small fetches cost one round trip.
                //
                // Resolved on the **form's own** client's surface (from its id, issue #714), not the caller's
                // own: an `allClients` admin editing another client's form must edit it in that client's rules --
                // its copy of the endpoint, in its `$defs`. For an ordinary caller the form's client is their
                // own, so this is the endpoint `resolveClient` alone resolved to; and a client that varies
                // nothing falls back to the shared endpoint on the backend, which is why the page asks by bare
                // path and never forms a client path itself (the #714 review's regression).
                val formClient = formClientOf(id)
                val patchFetch = async { fetchFormEndpoint(HttpMethod.POST.name, GEP.patch, formClient) }
                val getFetch = async { fetchFormEndpoint(HttpMethod.GET.name, GEP.formDoc, formClient) }
                // The home config carries `canSeeAllClients` (issue #667). Read defensively: a failure here must
                // not block editing, only leave free-form trait entry off.
                val homeFetch = async { runCatching { HomeApi.fetchConfig().canSeeAllClients }.getOrDefault(false) }
                // Does the form's client have a survey (issue #726)? The same resolution the survey page makes:
                // the workflow view's copy on the form's client, or the shared endpoint for a client that varies
                // nothing, then the view itself -- `null` is "no survey". Its own coroutine, never awaited here
                // (issue #726 review): it only decides whether a link is drawn, so neither its failure nor a
                // stall may hold the editor on "Loading…". The link simply appears when the answer lands.
                if (id != null) {
                    // The locks (issue #857), likewise never awaited: they only decorate the editor, and the backend
                    // refuses a locked change whatever is drawn.
                    editScope.launch {
                        locks = runCatching { WorkflowApi.fetchLocks(id) }.getOrDefault(emptyList())
                    }
                    editScope.launch {
                        hasSurvey = runCatching {
                            val viewPath = fetchFormEndpoint(HttpMethod.GET.name, GEP.workflowView, formClient).endpoints.firstOrNull()?.path
                            WorkflowApi.fetchSurveyView(id, clientOfResolvedPath(viewPath, GEP.workflowView, formClient)) != null
                        }.getOrDefault(false)
                    }
                }
                val cat = patchFetch.await()
                catalog = cat
                val patchEp = findFormPatchEndpoint(cat.endpoints)
                val getEp = findFormGetEndpoint(getFetch.await().endpoints)
                patchEndpoint = patchEp
                getEndpoint = getEp
                canSeeAllClients = homeFetch.await()
                if (id == null || patchEp == null || getEp == null) {
                    loadError = null
                } else {
                    // Load the form and seed the edits from its current entries, so editing opens on what the
                    // form holds now.
                    val item = SchemaCatalogApi.invoke(getEp, mapOf(GDF.gedraId to id))[EP.item].toJsonMapOrEmpty()
                    if (item.isEmpty()) {
                        notFound = true
                    } else {
                        val seed = mapOf(GDF.gedraId to id, GPF.edits to seededEdits(item))
                        values = seed
                        seeded = seed
                    }
                }
                loadError = null
            } catch (e: Throwable) {
                // A 404 (absent, or out of the caller's scope) is the "not yours" case, shown as not-found; any
                // other failure is the "is the server up?" case.
                if (e is ApiError && e.status == 404) notFound = true
                else loadError = userFacingError(e)
            } finally {
                loadingSchema = false
            }
        }
    }

    useFocusOnFailure(focusRequest, failures)

    // The leave guard (issue #726 review): Done and View info are navigations, so unsaved edits are asked about
    // exactly as the survey editor's are -- one save model, one answer to a dirty Done -- and a reload or closed
    // tab gets the browser's own prompt. Dirty is the working values differing from the last seeded snapshot;
    // it clears the moment a save re-seeds, so a clean page never nags. "Still here" is this page AND this form:
    // another form's editor is a keyed reload that would drop the edits, so it counts as a leave.
    val dirty = values != seeded
    useEffect(dirty, gedraId) {
        if (dirty) {
            val form = gedraId
            LeaveGuard.arm({ h -> h[HP.page] == pageEditForm && h[HP.gedra] == form }) {
                LeaveGuard.confirmLeave("You have unsaved changes on this form. Leave the page and lose them?")
            }
        } else {
            LeaveGuard.disarm()
        }
    }

    div {
        className = ClassName("card wide")

        val cat = catalog
        val patchEp = patchEndpoint
        val id = gedraId
        val targetType = if (cat != null && patchEp != null) formDocPatchTargetType(cat.inputType(patchEp)) else null
        // The shared editor header (issue #726): back link, title, and -- once the form is up -- Done and the
        // saved note, the same line the survey editor draws. Done is the one way home: the listing, with this
        // form flashed, carrying the listing's search and sort. The back link is the same target without the
        // flash. Every arm gets the back link from here, so none draws its own.
        val formUp = !loadingSchema && loadError == null && id != null && !notFound && targetType != null
        formsEditorHeader(title = { +"Edit form" }) {
            if (formUp) {
                Button {
                    onClick = { navigateHash(formsListingReturn(hashParams(), id)) }
                    +"Done"
                }
                // Back to the survey's read-only view (issue #726): an alternate view of the same form, so a
                // link beside the Done button; offered only where the survey exists.
                if (hasSurvey) {
                    Button {
                        type = "link"
                        onClick = { navigateHash(formsSurveyViewHash(hashParams(), id)) }
                        +"View info"
                    }
                }
                if (saved) {
                    p {
                        className = ClassName("form-ok")
                        +"✓ Saved."
                    }
                }
            }
        }
        // Free-form trait entry (issue #667): offered only to an `allClients` admin editing on the shared/global
        // surface (the endpoint resolved to no client copy), whose union lists only the global traits. On a
        // per-client copy the picker stays a closed choice of that client's full trait set.
        val openTraitEntry = patchEp != null &&
            freeformTraitEntry(canSeeAllClients, clientOfResolvedPath(patchEp.path, GEP.patch, formClientOf(id)))
        when {
            loadingSchema -> p {
                className = ClassName("subtitle")
                +"Loading…"
            }
            loadError != null -> errorText("Couldn't load the form to edit.", loadError!!)
            id == null -> p {
                className = ClassName("subtitle")
                +"No form was named to edit."
            }
            notFound -> p {
                className = ClassName("subtitle")
                +"That form is not one you can edit."
            }
            cat == null || patchEp == null || targetType == null -> p {
                className = ClassName("subtitle")
                +"This account's surface has no way to edit forms."
            }
            else -> {
                p {
                    className = ClassName("subtitle")
                    +("Change an entry's fields, add a section for a new trait, or switch a section to delete. " +
                        "Save sends only the sections you leave in place; Done returns to your forms.")
                }

                // The traits locked for this caller (issue #857): named, with the workflow locking each, so a refused
                // save is never a surprise. Each locked section is marked too, and the override -- for someone a
                // lock allows -- sits by Save, where the refusal it answers appears.
                val overridable = locks.any { it.canOverride }
                if (locks.isNotEmpty()) {
                    div {
                        className = ClassName("lock-notice")
                        p {
                            +("Locked for you: " + locks.joinToString("; ") { "${humanizeFieldName(it.traitId)} (by ${it.label})" } +
                                ". Changes to ${if (locks.size == 1) "it" else "them"} can't be saved" +
                                if (overridable) " unless you override the lock, by Save." else ".")
                        }
                    }
                }

                SchemaForm {
                    type = targetType
                    this.values = values
                    editable = true
                    friendly = true
                    // Promote a keyed trait's primary key out of `data`, up beside the trait choice (issue #642).
                    promoteKeys = true
                    // The caller's delivered cfacts (issue #564), so a g-visibleWhen field this caller should
                    // not see is hidden here. The backend enforces the condition regardless of what is drawn.
                    cfacts = cat.cfacts
                    // The per-type layouts (issue #586), joined by type name inside the form -- the same copy the
                    // create form shows, since both render the same trait data types.
                    fieldLayouts = cat.fieldLayouts
                    // The gedra id is the form being edited, not something to retype; it is seeded and hidden.
                    omit = listOf(GDF.gedraId)
                    // A locked section says so on itself (issue #857), where the person editing it is looking.
                    elementNote = { _, element -> lockNoteFor(locks, element[GE.traitId].toOptStr()) }
                    // Let the trait be typed, not only chosen, when this is the cross-client admin surface (#667).
                    this.openTraitEntry = openTraitEntry
                    this.failures = failures
                    onChange = { values = it; saved = false; editedSinceSave.current = true }
                    onFieldEdit = { path ->
                        failures?.let { current ->
                            failures = current.clearedAt(path).ifEmpty { null }
                            revalidate = true
                        }
                    }
                }

                div {
                    className = ClassName("row")
                    Button {
                        type = "primary"
                        loading = running
                        onClick = {
                            val check = checkInput(targetType, values)
                            // An addOrReplace must carry complete data; checkInput does not demand it (the edit's
                            // data is an optionalContents fragment), so surface that completeness inline rather
                            // than as the server's form-level "carries no data" (issue #662).
                            val completeness = editDataCompletenessFailures(targetType, values)
                            val allFailures = check.failures + completeness
                            failures = allFailures.ifEmpty { null }
                            revalidate = false
                            val checked = if (completeness.isEmpty()) check.payload else null
                            // Locked sections (issue #857): an untouched one is left out -- the backend would pass it
                            // unchanged anyway, but there is no reason to send it -- and a changed one goes only as an
                            // override, by someone every such lock allows, with a reason, so a refusal is said here.
                            val split = checked?.let { splitLockedEdits(it, seeded, locks.map { l -> l.traitId }.toSet()) }
                            val reason = overrideReason.trim().ifBlank { null }
                            val changedLocks = locks.filter { it.traitId in split?.changedLocked.orEmpty() }
                            // A local, not the state: a state set in this click still reads as its old value here.
                            val lockProblem = when {
                                changedLocks.isEmpty() -> null
                                !overriding -> "${changedLocks.joinToString(", ") { humanizeFieldName(it.traitId) }} " +
                                    "is locked; undo the change" + (if (changedLocks.all { it.canOverride }) ", or override the lock." else ".")
                                reason == null -> "Give a reason for overriding the lock."
                                !changedLocks.all { it.canOverride } -> "You may not override the lock on " +
                                    changedLocks.filterNot { it.canOverride }.joinToString(", ") { humanizeFieldName(it.traitId) } + "."
                                else -> null
                            }
                            lockError = lockProblem
                            val payload = split?.target?.takeIf { lockProblem == null }
                            val sendReason = reason?.takeIf { changedLocks.isNotEmpty() }
                            if (lockProblem != null) {
                                // Nothing to focus: the notice above the form says what to do.
                            } else if (payload == null) {
                                focusRequest += 1
                            } else {
                                running = true
                                runError = null
                                editedSinceSave.current = false
                                editScope.launch {
                                    try {
                                        // A free-form trait the client's union does not know needs the escape
                                        // hatch, or the backend refuses it (issue #667); sent only when one was
                                        // entered on the cross-client admin surface.
                                        val allowAdditional = openTraitEntry && patchNamesUnknownTrait(targetType, payload)
                                        SchemaCatalogApi.invoke(patchEp, formDocPatchBody(payload, allowAdditional, sendReason))
                                        // An override is one write's: the next change asks again.
                                        if (sendReason != null) {
                                            overriding = false
                                            overrideReason = ""
                                        }
                                        // Save stays on the page (issue #726), as the survey editor's does, so
                                        // several saves in a row work without re-entering: re-read the form and
                                        // re-seed the edits from what is now stored -- the patch answers with
                                        // per-edit outcomes, not the document -- and note the save beside Done.
                                        // Done is the way back to the listing, where the form flashes.
                                        getEndpoint?.let { getEp ->
                                            val item = SchemaCatalogApi.invoke(getEp, mapOf(GDF.gedraId to id))[EP.item].toJsonMapOrEmpty()
                                            if (item.isNotEmpty()) {
                                                val stored = mapOf(GDF.gedraId to id, GPF.edits to seededEdits(item))
                                                // What is now stored is the new clean point either way; the fields
                                                // follow it only if the user has not typed since the save went out
                                                // -- their newer keystrokes are not overwritten (issue #726 review),
                                                // and they read as unsaved against the re-seeded snapshot.
                                                seeded = stored
                                                if (editedSinceSave.current != true) values = stored
                                            }
                                        }
                                        // "Saved." only beside a form that still shows what was saved.
                                        if (editedSinceSave.current != true) saved = true
                                    } catch (e: Throwable) {
                                        runError = userFacingError(e)
                                    } finally {
                                        // The page stays either way now, so the button re-enables here.
                                        running = false
                                        // The locks may have moved with the save, or beside it -- a review finished,
                                        // a form engaged (issue #857 review) -- so they are asked again, never awaited.
                                        val before = locks
                                        editScope.launch {
                                            locks = id?.let { runCatching { WorkflowApi.fetchLocks(it) }.getOrNull() } ?: before
                                        }
                                    }
                                }
                            }
                        }
                        +"Save changes"
                    }
                }

                if (revalidate) {
                    p {
                        className = ClassName("form-stale")
                        +"Edited since the last check — choose Save changes to check it again."
                    }
                }

                // The failure summary (issue #641): off debug, a single prompt -- the fields are already marked
                // inline -- and the internal list only when the frontend is in debug. Both strings default here
                // and are overridable through the edited type's layout.
                failures?.let { fs ->
                    formFailureSummary(
                        fs, appConfig().envAuthDebug, formTraitLayouts(targetType, cat.fieldLayouts),
                        defaultSummary = "Please fix these before saving",
                        defaultHint = "Fix highlighted errors in entered data before saving.",
                    )
                }

                // The override (issue #857), by Save: the refusal it answers is drawn right here, so the two are
                // never a long form apart.
                if (overridable) {
                    div {
                        className = ClassName("lock-override")
                        Checkbox {
                            checked = overriding
                            onChange = { e -> overriding = e.target.checked; lockError = null }
                            +"Override the lock"
                        }
                        if (overriding) {
                            Input {
                                placeholder = "Why? Recorded with the change."
                                value = overrideReason
                                onChange = { event -> overrideReason = event.target.value as? String ?: ""; lockError = null }
                            }
                        }
                    }
                }
                lockError?.let {
                    p {
                        className = ClassName("error-text")
                        +it
                    }
                }
                runError?.let { errorText("Couldn't save the form.", it) }
            }
        }
    }
}

