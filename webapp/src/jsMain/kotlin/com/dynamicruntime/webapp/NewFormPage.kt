package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchType
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
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/** Coroutine scope for the new-form page's suspend calls (the catalog fetch and the create request). */
private val formScope = MainScope()

/**
 * The trait suffix a form-document create path always ends with, whichever client it names:
 * `/gedra/formDoc/create` and `/gedra/acme/formDoc/create` share `/formDoc/create` (the client goes after the
 * section, see `clientPath`). Derived from the shared constant rather than written out, so a rename of the path
 * moves this with it.
 */
private val formCreateSuffix: String = GEP.formDocCreate.substringAfter("/gedra")

/**
 * The data field the global `name` trait carries its value in -- a document's title when it has one. The
 * backend names it `GT.name` (`base/common`, so not reachable here as a constant); it matches its own value,
 * like every other schema key, which is what lets this stay a plain literal safely.
 */
private const val nameTraitField = "name"

/**
 * The endpoint that creates a form document, picked out of the caller's own catalog.
 *
 * Matched by its trait suffix rather than the bare `GEP.formDocCreate`, because `/schema/endpoints` answers
 * with the caller's **client-scoped** path (`/gedra/<client>/formDoc/create`), not the shared one -- which is
 * the whole point: a form built from and posted to that path is narrowed to the client by construction (issues
 * #387, #393). Null when this caller's surface carries no such endpoint, which the page reports rather than
 * failing.
 *
 * Pure, and covered under `jsNodeTest`.
 */
fun findFormCreateEndpoint(endpoints: List<EndpointInfo>): EndpointInfo? =
    endpoints.firstOrNull { it.method == HttpMethod.POST.name && it.path.endsWith(formCreateSuffix) }

/**
 * A friendly account of a form that was just created, for the success screen (issue #408). What is worth
 * showing beyond the durable id: the human [title] if the form has one, which [traitLabels] it carries, and
 * when it was made.
 */
class CreatedFormInfo(
    val gedraId: String,
    /** The document's own name, or null when it has none -- see [summarizeCreatedForm]. */
    val title: String?,
    /** Each entry's trait, by the same friendly label the form's trait picker showed. */
    val traitLabels: List<String>,
    /** When it was created, already formatted for reading; null when the row carried no timestamp. */
    val createdAt: String?,
)

/**
 * Summarizes a just-created form document from the stored row the create call returned.
 *
 * A `formDoc` has **no dedicated name field** -- it is a generic bag of trait entries -- so a document's human
 * title, when it has one, comes from the global `name` trait, which exists precisely to hold "what somebody
 * chose to call this document" (see `CoreTraits.GT.name`). It is detected here by an entry carrying a `name`
 * string in its data rather than by a hardcoded trait id, so it also catches a client's own name-bearing trait;
 * a client that supports no such trait simply has an untitled form, which is a legitimate state.
 *
 * [entriesUnion] is the form's entry union, used to label each trait the same way its picker did (title, or a
 * humanized id). Pure, and covered under `jsNodeTest`.
 */
fun summarizeCreatedForm(item: Map<String, Any?>, entriesUnion: SchType?): CreatedFormInfo {
    val entries = item[GDF.entries].toJsonListOfMaps()
    val traitLabels = entries.mapNotNull { entry ->
        (entry[GE.traitId] as? String)?.let { traitId ->
            entriesUnion?.variants?.byValue?.get(traitId)?.title ?: humanizeFieldName(traitId)
        }
    }
    val title = entries.firstNotNullOfOrNull { entry ->
        (entry[GE.data] as? Map<*, *>)?.get(nameTraitField) as? String
    }?.takeIf { it.isNotBlank() }
    return CreatedFormInfo(
        gedraId = item[GDF.gedraId] as? String ?: "(created)",
        title = title,
        traitLabels = traitLabels,
        createdAt = (item[GDF.createdAt] as? String)?.let { formatTimestamp(it) },
    )
}

/**
 * A wire timestamp shown to a person: `2026-08-21T19:49:51.568Z` -> `2026-08-21 19:49 UTC`. Minute precision,
 * which is all a "just created" line needs, and the wire is UTC so it is labeled as such rather than pretending
 * to be local. A value not shaped like an ISO timestamp is returned unchanged rather than sliced into nonsense.
 *
 * Pure, and covered under `jsNodeTest`.
 */
fun formatTimestamp(iso: String): String {
    if (iso.length < 16 || iso[10] != 'T') return iso
    return iso.substring(0, 10) + " " + iso.substring(11, 16) + " UTC"
}

/**
 * A page for **creating a form document**: it fills the endpoint's client-scoped input schema through the same
 * schema-driven [SchemaForm] the endpoint catalog uses, then posts it. Slice 1 of issue #408 -- the read side
 * (list and view) and delete arrive in later slices, so a fresh create confirms in place with a short summary
 * of what was made (see [summarizeCreatedForm]) rather than opening the stored document.
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
    var runError by useState<String?>(null)
    // The stored row the create call returned, kept whole so the success screen can say what was made, not only
    // its id (issue #408).
    var createdItem by useState<Map<String, Any?>?>(null)
    var loadError by useState<String?>(null)
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
                loadError = "Could not load the form schema — is `./gradlew :launch:run` running? (${e.message})"
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
            loadError != null -> p {
                className = ClassName("error-text")
                +loadError!!
            }
            cat == null || ep == null -> p {
                className = ClassName("subtitle")
                +("This account's surface has no form to create. A client defines the traits its forms are " +
                    "built from; yours declares none yet.")
            }
            createdItem != null -> {
                // Slice 1 has no view page to land on yet (that is slice 2), so a create confirms in place --
                // with what was made rather than only its id: the title if the form has one, the traits it
                // carries, and when it was created (issue #408).
                val summary = summarizeCreatedForm(
                    createdItem!!,
                    cat.inputType(ep).properties[GDF.entries]?.valueType?.itemType,
                )
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
                        onClick = {
                            values = emptyMap()
                            failures = null
                            revalidate = false
                            runError = null
                            createdItem = null
                        }
                        +"Create another"
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
                                        runError = e.message
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

                runError?.let {
                    p {
                        className = ClassName("error-text")
                        +"Could not create the form: $it"
                    }
                }
            }
        }
    }
}
