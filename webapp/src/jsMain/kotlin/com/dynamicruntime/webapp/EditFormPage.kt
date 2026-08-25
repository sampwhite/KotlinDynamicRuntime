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
    // The form being edited, from the hash (`g=<id>`), read once on mount.
    var gedraId by useState<String?>(null)
    var patchEndpoint by useState<EndpointInfo?>(null)
    var getEndpoint by useState<EndpointInfo?>(null)
    var catalog by useState<Catalog?>(null)
    var values by useState<Map<String, Any?>>(emptyMap())
    var failures by useState<List<SchFailure>?>(null)
    var revalidate by useState(false)
    var focusRequest by useState(0)
    var running by useState(false)
    var runError by useState<String?>(null)
    // The applied-trait labels the patch reported, set on a successful save; drives the confirmation screen.
    var appliedLabels by useState<List<String>?>(null)
    var loadError by useState<String?>(null)
    // The open form could not be loaded (absent, or not this caller's) -- a deep link to a form that is not theirs.
    var notFound by useState(false)
    // Named to avoid the `Button { loading = running }` collision that loops the render (issues #408, #417).
    var loadingSchema by useState(true)

    useEffectOnce {
        val id = hashParams()[HP.gedra]
        gedraId = id
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
                else loadError = "Could not load the form to edit — is `./gradlew :launch:run` running? (${e.message})"
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
            loadError != null -> p {
                className = ClassName("error-text")
                +loadError!!
            }
            id == null -> p {
                className = ClassName("subtitle")
                +"No form was named to edit."
            }
            notFound -> {
                backToForm(id)
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
                // A save confirms in place with what changed, and offers to return to the (now updated) form.
                backToForm(id)
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
                backToForm(id)
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
                                        runError = e.message
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

                runError?.let {
                    p {
                        className = ClassName("error-text")
                        +"Could not save the form: $it"
                    }
                }
            }
        }
    }
}

/** A "back to the form" link row, returning to the read-only view of the form being edited. */
private fun react.ChildrenBuilder.backToForm(id: String?) {
    div {
        className = ClassName("row")
        Button {
            type = "link"
            onClick = {
                navigateHash(buildList {
                    add(HP.page to HMENU.pageForms)
                    id?.let { add(HP.gedra to it) }
                })
            }
            +"← Back to the form"
        }
    }
}
