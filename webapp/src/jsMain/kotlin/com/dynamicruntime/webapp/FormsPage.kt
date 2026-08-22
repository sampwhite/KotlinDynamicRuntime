package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useEffectOnce
import react.useRef
import react.useState
import web.cssom.ClassName

/** Coroutine scope for the forms page's suspend calls (the catalog fetch and the list request). */
private val formsScope = MainScope()

/** What identifies a forms-page destination: which form is open. Everything else refines it. */
private val formsIdentity = setOf(HP.gedra)

/**
 * The read side of the form documents (slice 2 of issue #408): a list of the caller's forms, and a read-only
 * view of one. Both are driven by the same catalog the create page uses -- the `formDocs` list endpoint on the
 * caller's client-scoped surface -- so what is listed is exactly what that caller may see, narrowed by the
 * endpoint's own scope rules rather than by anything decided here.
 *
 * Which form is open lives in the URL hash (`g=<id>`), exactly as the Users page keeps its open record: a form
 * opened by a click, a reload, or the create page's "View form" link all resolve the same way. The stored row
 * is rendered through the shared [SchemaForm] in friendly, read-only mode, so its derived system fields drop
 * away and the trait data reads as a filled-in form.
 */
val FormsPage = FC<Props> {
    var catalog by useState<Catalog?>(null)
    var listEndpoint by useState<EndpointInfo?>(null)
    // The form-document rows the list endpoint returned, whole -- the view renders one of these directly rather
    // than fetching it again, the same choice the Users page makes for a record it already holds.
    var rows by useState<List<Map<String, Any?>>>(emptyList())
    var loading by useState(true)
    var error by useState<String?>(null)
    // The gedra id of the form open in the view, or null in the list. Held apart from `rows` so a hash naming a
    // form not (yet) loaded is a state the render can report rather than a crash.
    var viewingId by useState<String?>(null)
    // True once the initial hash restore has run; until then the sync effect stays quiet so it cannot overwrite
    // a `g=` we are about to read (the same gate the Users page uses).
    var restored by useState(false)

    // The rows the once-registered hashchange listener resolves against, read through a ref.
    val rowsRef = useRef<List<Map<String, Any?>>>(emptyList())
    rowsRef.current = rows

    /** Opens whatever form the hash names, or returns to the list. */
    fun applyHash() {
        viewingId = hashParams()[HP.gedra]
    }

    useEffectOnce {
        onHashChange { applyHash() }
    }

    useEffectOnce {
        formsScope.launch {
            try {
                // The caller's own client-scoped surface, so the list is exactly what this caller may see.
                val cat = SchemaCatalogApi.fetchCatalog()
                catalog = cat
                val ep = findFormsListEndpoint(cat.endpoints)
                listEndpoint = ep
                if (ep != null) {
                    val resp = SchemaCatalogApi.invoke(ep, emptyMap())
                    rows = resp[EP.items].toJsonListOrEmpty().map { it.toJsonMapOrEmpty() }
                }
                error = null
            } catch (e: Throwable) {
                error = "Could not load your forms — is `./gradlew :launch:run` running? (${e.message})"
            } finally {
                loading = false
            }
        }
    }

    // Open whatever the hash named once the rows are in -- a reload in the view, or a link from the create page.
    // Gated behind the first load, so it cannot run against an empty list and close a view the URL asked for.
    useEffect(loading) {
        if (!loading && !restored) {
            applyHash()
            restored = true
        }
    }

    // Keep the hash in step with the open form: opening one is a navigation and earns a history entry, so Back
    // returns to the list. A `g=` naming a form we do not hold is corrected in place rather than pushed onto.
    useEffect(viewingId, restored, rows) {
        if (!restored) {
            return@useEffect
        }
        val params = buildList {
            add(HP.page to HMENU.pageForms)
            viewingId?.let { add(HP.gedra to it) }
        }
        val current = hashParams()[HP.gedra]
        val reachable = current == null || rows.any { it[GDF.gedraId] == current }
        applyHashWrite(params, formsIdentity, reachable)
    }

    div {
        className = ClassName("card wide")
        h1 { +"My forms" }

        val cat = catalog
        val ep = listEndpoint
        when {
            loading -> p {
                className = ClassName("subtitle")
                +"Loading…"
            }
            error != null -> p {
                className = ClassName("error-text")
                +error!!
            }
            cat == null || ep == null -> p {
                className = ClassName("subtitle")
                +"This account has no forms to list."
            }
            else -> {
                val payloadType = cat.payloadType(ep)
                val union = entriesUnionOf(payloadType)
                val open = viewingId
                val viewing = open?.let { id -> rows.firstOrNull { it[GDF.gedraId] == id } }
                when {
                    // The list.
                    open == null -> if (rows.isEmpty()) {
                        p {
                            className = ClassName("subtitle")
                            +"You haven't created any forms yet."
                        }
                        div {
                            className = ClassName("row")
                            Button {
                                type = "primary"
                                onClick = { navigateHash(listOf(HP.page to HMENU.pageNewForm)) }
                                +"Create a form"
                            }
                        }
                    } else {
                        FormsTable {
                            forms = rows.map { (it[GDF.gedraId] as? String ?: "") to summarizeForm(it, union) }
                            onSelect = { id -> viewingId = id }
                        }
                    }
                    // A hash naming a form that is not in the list -- an old link, or one belonging to another
                    // caller. Reported rather than shown blank; Back returns to the list.
                    viewing == null -> {
                        backToList { viewingId = null }
                        p {
                            className = ClassName("subtitle")
                            +"That form is not in your list."
                        }
                    }
                    // One form, read-only.
                    else -> {
                        backToList { viewingId = null }
                        val summary = summarizeForm(viewing, union)
                        summary.title?.let { h2 { +it } }
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
                        // The stored document itself, read-only: friendly mode drops the derived system fields
                        // so what remains is the trait data as a filled-in form. Absent only if the payload type
                        // did not resolve, in which case the summary above still stands.
                        payloadType?.let { type ->
                            h2 { +"Details" }
                            SchemaForm {
                                this.type = type
                                values = viewing
                                editable = false
                                friendly = true
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A "back to my forms" link row, used by both the view and the not-found state. */
private fun ChildrenBuilder.backToList(onBack: () -> Unit) {
    div {
        className = ClassName("row")
        Button {
            type = "link"
            onClick = { onBack() }
            +"← Back to my forms"
        }
    }
}
