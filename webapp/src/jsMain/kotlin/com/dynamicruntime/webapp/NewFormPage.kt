package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.clearedAt
import com.dynamicruntime.common.util.toJsonMapOrEmpty
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

/** Coroutine scope for the new-form page's suspend calls (the endpoint fetch and the create request). */
private val formScope = MainScope()

/**
 * A page for **creating a form document**: it fills the endpoint's client-scoped input schema through the same
 * schema-driven [SchemaForm] the endpoint catalog uses, then posts it (issue #408). The gedra-form helpers it
 * shares with the list page -- endpoint discovery -- live in `GedraForms.kt`.
 *
 * A successful create returns to the My forms listing with the new row flashed (issue #663), the same
 * confirmation the edit form's save gives, rather than a separate in-place screen.
 *
 * Where the catalog is a developer tool over *every* endpoint, this is one endpoint with the scaffolding
 * removed: no method/path heading, no raw-schema views, no request-JSON editor. What stays is the part a person
 * filling in a form needs -- the fields, the validation, and the jump-to-failure.
 */
// The create and edit pages are siblings -- both a card of state, a schema-driven form, and a save -- so their
// state block and SchemaForm setup read alike. That resemblance is inherent to two React components doing the
// same shape of thing; deduping it would need a state holder that reads worse than the likeness, so the
// duplicated-fragment inspection is suppressed rather than chased (issue #671 follow-up).
@Suppress("DuplicatedCode")
val NewFormPage = FC<Props> {
    var endpoint by useState<EndpointInfo?>(null)
    var catalog by useState<Catalog?>(null)
    var values by useState<Map<String, Any?>>(emptyMap())
    var failures by useState<List<SchFailure>?>(null)
    // Set when a field is edited after a check, so the page can say what is on screen predates the last check --
    // the same three-state honesty the catalog keeps (never / checked-clean / checked-then-edited).
    var revalidate by useState(false)
    // Bumped by a check that found something, to send focus to the first failing field. A counter rather than an
    // effect on `failures`, so clearing a failure by editing does not steal focus out of the field being fixed.
    var focusRequest by useState(0)
    var running by useState(false)
    var runError by useState<DisplayError?>(null)
    var loadError by useState<DisplayError?>(null)
    // Named to avoid colliding with the `Button { loading = running }` prop below: an unqualified `loading`
    // inside that builder resolves to this local and fires its setter on every render — an infinite loop.
    var loadingSchema by useState(true)

    useEffectOnce {
        formScope.launch {
            try {
                // Just this one endpoint's closure, resolved to the caller's own client-scoped copy of the bare
                // create path (issue #552) -- the schema is already narrowed to what this client supports (a
                // control cannot offer a trait the client removed), and the page fetches only what it renders
                // rather than the whole catalog to discover its path. The finder still validates the one result.
                val fetched = SchemaCatalogApi.fetchEndpoint(HttpMethod.POST.name, GEP.formDocCreate, resolveClient = true)
                catalog = fetched
                endpoint = findFormCreateEndpoint(fetched.endpoints)
                loadError = null
            } catch (e: Throwable) {
                loadError = userFacingError(e)
            } finally {
                loadingSchema = false
            }
        }
    }

    useFocusOnFailure(focusRequest, failures)

    div {
        className = ClassName("card wide")
        h1 { +"New form" }

        val cat = catalog
        val ep = endpoint
        when {
            loadingSchema -> p {
                className = ClassName("subtitle")
                +"Loading…"
            }
            loadError != null -> errorText("Couldn't load the form.", loadError!!)
            cat == null || ep == null -> {
                formsBackToListing()
                p {
                    className = ClassName("subtitle")
                    +("This account's surface has no form to create. A client defines the traits its forms are " +
                        "built from; yours declares none yet.")
                }
            }
            else -> {
                formsBackToListing()
                val inputType = cat.inputType(ep)
                p {
                    className = ClassName("subtitle")
                    +"Add a section for each trait this form should carry, fill it in, and create the form."
                }

                SchemaForm {
                    type = inputType
                    this.values = values
                    editable = true
                    // Friendly data-entry presentation: fields labeled by title/humanized key, and the derived
                    // system fields (entryId, source, the audit stamps) hidden rather than shown read-only.
                    friendly = true
                    // The caller's delivered cfacts (issue #564), so a g-visibleWhen field this caller should
                    // not see is hidden here. The backend enforces the condition regardless of what is drawn.
                    cfacts = cat.cfacts
                    // The per-type layouts (issue #586): a field's label/description come from the layout for
                    // its type, cascading over the schema's title/description. Joined by type name inside the form.
                    layouts = cat.layouts
                    // `allowAdditionalTraits` is a power flag (write traits the client does not support), not
                    // something an end-user form should offer; it defaults false when omitted.
                    omit = listOf(GDF.allowAdditionalTraits)
                    this.failures = failures
                    onChange = { values = it }
                    // Clearing on edit rather than re-checking: a field being corrected must not keep showing the
                    // complaint about what it used to hold, and dropping the last failure returns to null (not an
                    // empty list), because empty draws the ✓ that an unchecked edit has not earned.
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
                            val check = checkInput(inputType, values)
                            failures = check.failures
                            revalidate = false
                            val payload = check.payload
                            if (payload == null) {
                                // Something failed: show it, and send focus to the first problem rather than
                                // making it be hunted for.
                                focusRequest += 1
                            } else {
                                running = true
                                runError = null
                                formScope.launch {
                                    try {
                                        val response = SchemaCatalogApi.invoke(ep, payload)
                                        // Back to the listing (issue #663), flashing the new row -- the same
                                        // confirmation the edit form's save gives (issue #592) -- rather than an
                                        // in-place screen. No running=false here: this navigation unmounts the page.
                                        val newId = response[EP.item].toJsonMapOrEmpty()[GDF.gedraId] as? String
                                        // Return to the *same* listing the create was launched from (issue #669):
                                        // the originating search and sort ride in the hash (the "New form" button
                                        // put them there), so this lands on the filtered, sorted list rather than the
                                        // default one -- the same round-trip the edit form's save makes. A new row the
                                        // active filter excludes simply is not flashed; the filter is the user's view.
                                        val search = formsSearchHashParams(formsSearchFromHash(hashParams()))
                                        val flag = newId?.let { listOf(HP.highlight to it) } ?: emptyList()
                                        navigateHash(listOf(HP.page to HMENU.pageForms) + search + flag)
                                    } catch (e: Throwable) {
                                        // Only the failure path stays on the page, so re-enable the button here
                                        // rather than in a finally that would run after a create has navigated away.
                                        runError = userFacingError(e)
                                        running = false
                                    }
                                }
                            }
                        }
                        +"Create form"
                    }
                }

                // The one state the form cannot vouch for: edited since the last check, so neither the ✓ nor the
                // failures on screen are current. Announced, because a blank result reads as "nothing wrong".
                if (revalidate) {
                    p {
                        className = ClassName("form-stale")
                        +"Edited since the last check — choose Create form to check it again."
                    }
                }

                // The failure summary (issue #641): off debug, a single prompt -- the fields are already marked
                // inline -- and the internal list only when the frontend is in debug. Both strings default here
                // and are overridable through the created type's layout.
                failures?.let { fs ->
                    formFailureSummary(
                        fs, appConfig().envAuthDebug, formTraitLayouts(inputType, cat.layouts),
                        defaultSummary = "Please fix these before creating the form",
                        defaultHint = "Fix highlighted errors in entered data before creating the form.",
                    )
                }

                runError?.let { errorText("Couldn't create the form.", it) }
            }
        }
    }
}
