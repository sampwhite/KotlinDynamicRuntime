package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.clearedAt
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useEffectOnce
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
val EditFormPage = FC<Props> {
    // The form being edited, from the hash (`g=<id>`): initialized from the hash and kept in step with it, so
    // navigating to another edit URL reloads and re-seeds rather than leaving the previous form on screen --
    // where Save would patch the stale gedra (issue #417).
    var gedraId by useState<String?>(hashParams()[HP.gedra])
    var patchEndpoint by useState<EndpointInfo?>(null)
    var catalog by useState<Catalog?>(null)
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
        loadingSchema = true
        val id = gedraId
        editScope.launch {
            try {
                // Just the two endpoints this page uses, each resolved to the caller's own client-scoped copy of
                // its bare path (issue #552): the **patch** endpoint, whose schema this page renders (already
                // narrowed to what this client supports, so a section cannot offer a trait the client removed),
                // and the **get** endpoint, which it only *invokes* to load the form's current entries. Fetched
                // in isolation -- each carries only its own `$defs` closure plus the page's per-client cfacts and
                // layouts -- rather than the whole catalog once scanned to find these by suffix. Run together, so
                // two small fetches cost one round trip.
                val patchFetch = async {
                    SchemaCatalogApi.fetchEndpoint(HttpMethod.POST.name, GEP.patch, resolveClient = true)
                }
                val getFetch = async {
                    SchemaCatalogApi.fetchEndpoint(HttpMethod.GET.name, GEP.formDoc, resolveClient = true)
                }
                val cat = patchFetch.await()
                catalog = cat
                val patchEp = findFormPatchEndpoint(cat.endpoints)
                val getEp = findFormGetEndpoint(getFetch.await().endpoints)
                patchEndpoint = patchEp
                if (id == null || patchEp == null || getEp == null) {
                    loadError = null
                } else {
                    // Load the form and seed the edits from its current entries, so editing opens on what the
                    // form holds now.
                    val item = SchemaCatalogApi.invoke(getEp, mapOf(GDF.gedraId to id))[EP.item].toJsonMapOrEmpty()
                    if (item.isEmpty()) {
                        notFound = true
                    } else {
                        values = mapOf(GDF.gedraId to id, GPF.edits to seededEdits(item))
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

    useEffect(focusRequest) {
        if (focusRequest > 0) {
            failures?.firstOrNull()?.let { focusField(it.path) }
        }
    }

    div {
        className = ClassName("card wide")
        h1 { +"Edit form" }

        val cat = catalog
        val patchEp = patchEndpoint
        val id = gedraId
        val targetType = if (cat != null && patchEp != null) formDocPatchTargetType(cat.inputType(patchEp)) else null
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
            notFound -> {
                editNav()
                p {
                    className = ClassName("subtitle")
                    +"That form is not one you can edit."
                }
            }
            cat == null || patchEp == null || targetType == null -> p {
                className = ClassName("subtitle")
                +"This account's surface has no way to edit forms."
            }
            else -> {
                editNav()
                p {
                    className = ClassName("subtitle")
                    +("Change an entry's fields, add a section for a new trait, or switch a section to delete. " +
                        "Save sends only the sections you leave in place.")
                }

                SchemaForm {
                    type = targetType
                    this.values = values
                    editable = true
                    friendly = true
                    // The caller's delivered cfacts (issue #564), so a g-visibleWhen field this caller should
                    // not see is hidden here. The backend enforces the condition regardless of what is drawn.
                    cfacts = cat.cfacts
                    // The per-type layouts (issue #586), joined by type name inside the form -- the same copy the
                    // create form shows, since both render the same trait data types.
                    layouts = cat.layouts
                    // The gedra id is the form being edited, not something to retype; it is seeded and hidden.
                    omit = listOf(GDF.gedraId)
                    this.failures = failures
                    onChange = { values = it }
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
                            failures = check.failures
                            revalidate = false
                            val payload = check.payload
                            if (payload == null) {
                                focusRequest += 1
                            } else {
                                running = true
                                runError = null
                                editScope.launch {
                                    try {
                                        SchemaCatalogApi.invoke(patchEp, formDocPatchBody(payload))
                                        // Back to the listing (issue #592): filtered as it was, and with the
                                        // just-saved form flagged so the list flashes it -- "here is the form
                                        // you saved", the confirmation, not a screen to click away from. Every
                                        // successful save flashes, deliberately, even one that changed nothing.
                                        // The backend now reports per-entry `applied` honestly (#626), so a
                                        // no-op could be told apart -- but flashing the form the caller was just
                                        // editing is the confirmation they asked for either way, whether or not
                                        // the bytes moved.
                                        val search = formsSearchHashParams(formsSearchFromHash(hashParams()))
                                        val flag = id?.let { listOf(HP.highlight to it) } ?: emptyList()
                                        navigateHash(listOf(HP.page to HMENU.pageForms) + search + flag)
                                    } catch (e: Throwable) {
                                        // Only the failure path stays on the page, so re-enable the button here
                                        // rather than in a `finally` that would run after a successful save has
                                        // already navigated away and unmounted this page.
                                        runError = userFacingError(e)
                                        running = false
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
                        fs, appConfig().envAuthDebug, targetType.name?.let { cat.layouts[it] },
                        defaultSummary = "Please fix these before saving",
                        defaultHint = "Fix highlighted errors in entered data before saving.",
                    )
                }

                runError?.let { errorText("Couldn't save the form.", it) }
            }
        }
    }
}

/**
 * The edit page's navigation row: a link back to **My forms** (the listing, issue #417), carrying the search
 * the caller was filtering by (issue #592) so returning lands on the same filtered list. A successful save
 * navigates there on its own (see the Save handler), so this is the way out *before* saving -- while editing,
 * and on the not-found branch.
 */
private fun react.ChildrenBuilder.editNav() {
    val search = formsSearchHashParams(formsSearchFromHash(hashParams()))
    div {
        className = ClassName("row")
        backToListing(HMENU.pageForms, search)
    }
}
