package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.clearedAt
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
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/** Coroutine scope for the new-form page's suspend calls (the catalog fetch and the create request). */
private val formScope = MainScope()

/**
 * A page for **creating a form document**: it fills the endpoint's client-scoped input schema through the same
 * schema-driven [SchemaForm] the endpoint catalog uses, then posts it (issue #408). The gedra-form helpers it
 * shares with the list page -- endpoint discovery, [summarizeForm] -- live in `GedraForms.kt`.
 *
 * A successful create confirms in place with a short summary and offers to open the new form in the list.
 *
 * Where the catalog is a developer tool over *every* endpoint, this is one endpoint with the scaffolding
 * removed: no method/path heading, no raw-schema views, no request-JSON editor. What stays is the part a person
 * filling in a form needs -- the fields, the validation, and the jump-to-failure.
 */
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
    // The stored row the create call returned, kept whole so the success screen can say what was made, not only
    // its id (issue #408).
    var createdItem by useState<Map<String, Any?>?>(null)
    var loadError by useState<DisplayError?>(null)
    // Named to avoid colliding with the `Button { loading = running }` prop below: an unqualified `loading`
    // inside that builder resolves to this local and fires its setter on every render — an infinite loop.
    var loadingSchema by useState(true)

    useEffectOnce {
        formScope.launch {
            try {
                // The caller's own client-scoped surface (no `client` arg), so the schema is already narrowed to
                // what this client supports and a control cannot offer a trait the client removed.
                val fetched = SchemaCatalogApi.fetchCatalog()
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

    // Send focus to the first failure once the render carrying it has committed. It has to be an effect: the row
    // is addressed by a DOM id, which does not exist until React has drawn the failures the check just produced.
    useEffect(focusRequest) {
        if (focusRequest > 0) {
            failures?.firstOrNull()?.let { focusField(it.path) }
        }
    }

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
            cat == null || ep == null -> p {
                className = ClassName("subtitle")
                +("This account's surface has no form to create. A client defines the traits its forms are " +
                    "built from; yours declares none yet.")
            }
            createdItem != null -> {
                // A create confirms in place with what was made -- the title if the form has one, the traits it
                // carries, and when it was created -- and offers to open it in the list (issue #408).
                val summary = summarizeForm(createdItem!!, entriesUnionOf(cat.inputType(ep)))
                p {
                    className = ClassName("form-ok")
                    +"✓ Form created."
                }
                // A named form leads with its name; an unnamed one is not given a fake title -- what it *is*
                // (the traits below) carries the identity instead.
                summary.title?.let {
                    h2 { +it }
                }
                if (summary.traitLabels.isNotEmpty()) {
                    p {
                        className = ClassName("subtitle")
                        +("Contains: " + summary.traitLabels.joinToString(", "))
                    }
                }
                summary.createdAt?.let {
                    p {
                        className = ClassName("subtitle")
                        +"Created $it"
                    }
                }
                p {
                    className = ClassName("type-hint")
                    +"Reference id"
                }
                p {
                    className = ClassName("code")
                    +summary.gedraId
                }
                div {
                    className = ClassName("row")
                    Button {
                        type = "primary"
                        // Opens the just-created form in the list page, addressed by its id (issue #408).
                        onClick = { navigateHash(listOf(HP.page to HMENU.pageForms, HP.gedra to summary.gedraId)) }
                        +"View form"
                    }
                    Button {
                        onClick = {
                            values = emptyMap()
                            failures = null
                            revalidate = false
                            runError = null
                            createdItem = null
                        }
                        +"Create another"
                    }
                    // The list is the hub, and it is the only way here (the nav item is gone since #417), so the
                    // success screen offers a link straight back to it rather than only into the new form.
                    Button {
                        type = "link"
                        onClick = { navigateHash(listOf(HP.page to HMENU.pageForms)) }
                        +"← Back to my forms"
                    }
                }
            }
            else -> {
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
                                        createdItem = response[EP.item].toJsonMapOrEmpty()
                                    } catch (e: Throwable) {
                                        runError = userFacingError(e)
                                    } finally {
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

                failures?.let { fs ->
                    if (fs.isNotEmpty()) {
                        h2 { +"Please fix these before creating the form" }
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

                runError?.let { errorText("Couldn't create the form.", it) }
            }
        }
    }
}
