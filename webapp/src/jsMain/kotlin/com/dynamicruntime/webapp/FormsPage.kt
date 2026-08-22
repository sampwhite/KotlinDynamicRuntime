package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.SchType
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
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/** Coroutine scope for the forms page's suspend calls (the catalog fetch, the list page, and a single fetch). */
private val formsScope = MainScope()

/** What identifies a forms-page destination: which form is open. Everything else refines it. */
private val formsIdentity = setOf(HP.gedra)

/** How many forms a page shows. Small enough to page a long list, large enough that most callers never do. */
private const val formsPageSize = 25

/**
 * The read side of the form documents (slice 2 of issue #408): a paged list of the caller's forms, and a
 * read-only view of one. Both are driven by the same catalog the create page uses -- the client-scoped
 * `formDocs` list and `formDoc` fetch endpoints -- so what is shown is exactly what that caller may see,
 * narrowed by the endpoints' own scope rules rather than by anything decided here.
 *
 * The list pages through `limit`/`offset` and shows the total the scope admits, so a caller with more forms
 * than a page can reach all of them rather than silently seeing only the first (issue #408). The open form
 * lives in the URL hash (`g=<id>`), like the Users page's open record; a form the loaded page does not hold --
 * a bookmark, or a link to one now past the first page -- is fetched by id, so a deep link always resolves.
 * The stored row renders through the shared [SchemaForm] in friendly, read-only mode, so its derived system
 * fields drop away and the trait data reads as a filled-in form.
 */
val FormsPage = FC<Props> {
    var catalog by useState<Catalog?>(null)
    var listEndpoint by useState<EndpointInfo?>(null)
    var getEndpoint by useState<EndpointInfo?>(null)
    var rows by useState<List<Map<String, Any?>>>(emptyList())
    // How many forms the scope admits in all, for "showing X–Y of N" and to know when a next page exists.
    var numAvailable by useState(0)
    // The first index of the page on screen; paging moves it by [formsPageSize].
    var offset by useState(0)
    var loading by useState(true)
    var error by useState<String?>(null)
    // True once the initial hash restore has run; until then the sync effect stays quiet so it cannot overwrite
    // a `g=` we are about to read (the same gate the Users page uses).
    var restored by useState(false)

    // The gedra id of the form open in the view, or null in the list.
    var viewingId by useState<String?>(null)
    // The row being viewed -- from the loaded page when it holds it, otherwise fetched by id. Null while there
    // is nothing to show (list mode, or a fetch in flight).
    var viewRow by useState<Map<String, Any?>?>(null)
    var viewLoading by useState(false)
    // Set when the open id names no form the caller may see -- an old link, or one belonging to someone else.
    var viewMissing by useState(false)

    /** Loads the page at [off] from [ep], replacing the rows and the total. Flips [loading] off when done. */
    fun loadPage(ep: EndpointInfo, off: Int) {
        loading = true
        formsScope.launch {
            try {
                val resp = SchemaCatalogApi.invoke(ep, mapOf(EP.limit to formsPageSize, EP.offset to off))
                rows = resp[EP.items].toJsonListOrEmpty().map { it.toJsonMapOrEmpty() }
                numAvailable = (resp[EP.numAvailable] as? Number)?.toInt() ?: rows.size
                error = null
            } catch (e: Throwable) {
                error = "Could not load your forms — is `./gradlew :launch:run` running? (${e.message})"
            } finally {
                loading = false
            }
        }
    }

    useEffectOnce {
        onHashChange { viewingId = hashParams()[HP.gedra] }
    }

    useEffectOnce {
        formsScope.launch {
            try {
                // The caller's own client-scoped surface, so the list is exactly what this caller may see.
                val cat = SchemaCatalogApi.fetchCatalog()
                catalog = cat
                getEndpoint = findFormGetEndpoint(cat.endpoints)
                val ep = findFormsListEndpoint(cat.endpoints)
                listEndpoint = ep
                if (ep != null) {
                    val resp = SchemaCatalogApi.invoke(ep, mapOf(EP.limit to formsPageSize, EP.offset to 0))
                    rows = resp[EP.items].toJsonListOrEmpty().map { it.toJsonMapOrEmpty() }
                    numAvailable = (resp[EP.numAvailable] as? Number)?.toInt() ?: rows.size
                }
                error = null
            } catch (e: Throwable) {
                error = "Could not load your forms — is `./gradlew :launch:run` running? (${e.message})"
            } finally {
                loading = false
            }
        }
    }

    // Open whatever the hash named once the first load has run -- a reload in the view, or a link from the
    // create page. Gated behind the load so it does not race the initial fetch.
    useEffect(loading) {
        if (!loading && !restored) {
            viewingId = hashParams()[HP.gedra]
            restored = true
        }
    }

    // Resolve the open form: use the loaded page's row when it holds it, otherwise fetch it by id so a deep
    // link to a form past the current page still opens. Keyed on the id and the load, not on `rows`, so paging
    // does not re-fetch a form already on screen.
    useEffect(viewingId, restored) {
        val id = viewingId
        viewMissing = false
        if (id == null || !restored) {
            viewRow = null
            return@useEffect
        }
        val inPage = rows.firstOrNull { it[GDF.gedraId] == id }
        if (inPage != null) {
            viewRow = inPage
            return@useEffect
        }
        val ge = getEndpoint
        if (ge == null) {
            viewRow = null
            viewMissing = true
            return@useEffect
        }
        viewRow = null
        viewLoading = true
        formsScope.launch {
            try {
                val item = SchemaCatalogApi.invoke(ge, mapOf(GDF.gedraId to id))[EP.item].toJsonMapOrEmpty()
                if (item.isEmpty()) viewMissing = true else viewRow = item
            } catch (e: Throwable) {
                // A 404 (absent, or out of the caller's scope) surfaces here as a thrown error, which is the
                // same "not yours to see" the list would express by omission.
                viewMissing = true
            } finally {
                viewLoading = false
            }
        }
    }

    // Keep the hash in step with the open form: opening one is a navigation and earns a history entry, so Back
    // returns to the list. A `g=` naming a form the page does not hold is corrected in place rather than pushed
    // onto -- the fetch still resolves it, but it is not a list row to page back to.
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
            // A form is open (by click, link, or reload): show it, fetching by id if the page did not hold it.
            viewingId != null -> {
                val payloadType = cat.payloadType(ep)
                backToList { viewingId = null }
                when {
                    viewLoading -> p {
                        className = ClassName("subtitle")
                        +"Loading…"
                    }
                    viewRow == null -> p {
                        className = ClassName("subtitle")
                        +(if (viewMissing) "That form is not in your list." else "Loading…")
                    }
                    else -> renderForm(viewRow!!, entriesUnionOf(payloadType), payloadType)
                }
            }
            // The list.
            rows.isEmpty() && offset == 0 -> {
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
            }
            else -> {
                val union = entriesUnionOf(cat.payloadType(ep))
                pagingBar(offset, rows.size, numAvailable) { newOffset ->
                    offset = newOffset
                    loadPage(ep, newOffset)
                }
                FormsTable {
                    forms = rows.map { (it[GDF.gedraId] as? String ?: "") to summarizeForm(it, union) }
                    onSelect = { id -> viewingId = id }
                }
            }
        }
    }
}

/** The read-only view of one stored form: its summary, then the document itself through [SchemaForm]. */
private fun ChildrenBuilder.renderForm(
    row: Map<String, Any?>,
    union: SchType?,
    payloadType: SchType?,
) {
    val summary = summarizeForm(row, union)
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
    // Read-only, friendly: the derived system fields drop away so what remains is the trait data as a
    // filled-in form. Absent only if the payload type did not resolve, in which case the summary still stands.
    payloadType?.let { type ->
        h2 { +"Details" }
        SchemaForm {
            this.type = type
            values = row
            editable = false
            friendly = true
        }
    }
}

/**
 * The "showing X–Y of N" line and its page controls. Drawn only when there is more than one page, so a short
 * list carries no controls; the count itself is always worth showing once a page is on screen.
 */
private fun ChildrenBuilder.pagingBar(offset: Int, pageCount: Int, numAvailable: Int, goTo: (Int) -> Unit) {
    val first = if (pageCount == 0) 0 else offset + 1
    val last = offset + pageCount
    val hasPrev = offset > 0
    val hasNext = last < numAvailable
    div {
        className = ClassName("row")
        span {
            className = ClassName("type-hint")
            +(if (numAvailable <= pageCount && offset == 0) {
                if (numAvailable == 1) "1 form" else "$numAvailable forms"
            } else {
                "Showing $first–$last of $numAvailable"
            })
        }
        if (hasPrev || hasNext) {
            Button {
                type = "link"
                disabled = !hasPrev
                onClick = { goTo((offset - formsPageSize).coerceAtLeast(0)) }
                +"← Newer"
            }
            Button {
                type = "link"
                disabled = !hasNext
                onClick = { goTo(offset + formsPageSize) }
                +"Older →"
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
