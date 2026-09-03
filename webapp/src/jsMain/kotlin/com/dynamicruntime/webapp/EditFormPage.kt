package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.clearedAt
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/** Coroutine scope for the edit page's suspend calls (the catalog fetch, the form fetch, and the patch). */
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
    var getEndpoint by useState<EndpointInfo?>(null)
    var catalog by useState<Catalog?>(null)
    var values by useState<Map<String, Any?>>(emptyMap())
    var failures by useState<List<SchFailure>?>(null)
    var revalidate by useState(false)
    var focusRequest by useState(0)
    var running by useState(false)
    var runError by useState<DisplayError?>(null)
    // The applied-trait labels the patch reported, set on a successful save; drives the confirmation screen.
    var appliedLabels by useState<List<String>?>(null)
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

    // Load the catalog and the named form, re-running whenever the id changes. Re-fetching the catalog on a
    // reload is a rare, cheap cost (edit->edit happens only by a hand-edited URL); what matters is that the
    // form is re-seeded from the id now in the hash, never left as the previous one.
    useEffect(gedraId) {
        // A new form to edit: drop the previous load's seed, validation, and any success screen so none of it
        // bleeds across the reload.
        values = emptyMap()
        failures = null
        revalidate = false
        runError = null
        appliedLabels = null
        notFound = false
        loadingSchema = true
        val id = gedraId
        editScope.launch {
            try {
                // The caller's own client-scoped surface, so the patch schema is already narrowed to what this
                // client supports and a section cannot offer a trait the client removed.
                val cat = SchemaCatalogApi.fetchCatalog()
                catalog = cat
                val patchEp = findFormPatchEndpoint(cat.endpoints)
                val getEp = findFormGetEndpoint(cat.endpoints)
                patchEndpoint = patchEp
                getEndpoint = getEp
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
                // The form is not viewable either, so only the list link is offered here.
                editNav(id, toForm = false)
                p {
                    className = ClassName("subtitle")
                    +"That form is not one you can edit."
                }
            }
            cat == null || patchEp == null || targetType == null -> p {
                className = ClassName("subtitle")
                +"This account's surface has no way to edit forms."
            }
            appliedLabels != null -> {
                // A save confirms in place with what changed, and offers to return to the (now updated) form to
                // keep editing or to go back to the listing (issue #417).
                editNav(id)
                val labels = appliedLabels!!
                p {
                    className = ClassName("form-ok")
                    +(if (labels.isEmpty()) "✓ Saved — no changes to apply." else "✓ Saved.")
                }
                if (labels.isNotEmpty()) {
                    p {
                        className = ClassName("subtitle")
                        +("Updated: " + labels.joinToString(", "))
                    }
                }
            }
            else -> {
                editNav(id)
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
                                        val body = formDocPatchBody(payload)
                                        val patched = SchemaCatalogApi.invoke(patchEp, body)[EP.items].toJsonListOfMaps()
                                        val union = entriesUnionOf(getEndpoint?.let { cat.payloadType(it) })
                                        appliedLabels = appliedTraitLabels(patched, union)
                                    } catch (e: Throwable) {
                                        runError = userFacingError(e)
                                    } finally {
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

                failures?.let { fs ->
                    if (fs.isNotEmpty()) {
                        h2 { +"Please fix these before saving" }
                        fs.forEach { f ->
                            p {
                                className = ClassName("error-text")
                                val text = "${f.path.ifEmpty { "(whole form)" }}: ${f.message}${choicesSuffix(f)}"
                                if (f.path.isEmpty()) {
                                    +text
                                } else {
                                    button {
                                        className = ClassName("failure-jump")
                                        asDynamic()["type"] = "button"
                                        onClick = { focusField(f.path) }
                                        +text
                                    }
                                }
                            }
                        }
                    }
                }

                runError?.let { errorText("Couldn't save the form.", it) }
            }
        }
    }
}

/**
 * The edit page's navigation row. It always offers a link back to **My forms** (the listing) -- the way out that
 * was missing after a save (issue #417) -- and, when [toForm] and the form is viewable, a link back to that
 * form's read-only view to keep editing it. On the not-found branch the form cannot be viewed, so only the list
 * link is shown.
 */
private fun react.ChildrenBuilder.editNav(id: String?, toForm: Boolean = true) {
    div {
        className = ClassName("row")
        if (toForm && id != null) {
            Button {
                type = "link"
                onClick = { navigateHash(listOf(HP.page to HMENU.pageForms, HP.gedra to id)) }
                +"← Back to the form"
            }
        }
        // The listing back (issue #554): to the forms list, or to whichever listing opened this form.
        backToListing(HMENU.pageForms)
    }
}
