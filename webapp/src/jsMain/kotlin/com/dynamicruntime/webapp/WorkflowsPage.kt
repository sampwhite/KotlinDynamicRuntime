package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.workflow.CTKQ
import com.dynamicruntime.common.gedra.workflow.WAGG
import com.dynamicruntime.common.gedra.workflow.WfColumnCategory
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

private val workflowsScope = MainScope()

/**
 * The workflow pages (issue #792): every normal workflow the caller works with -- those being calculated, and any
 * that appears on a form they may see -- each with how many of those forms are **eligible** for it, **engaged**
 * with it and **finished** with it. Each count opens the forms listing drilled into that workflow and state, where
 * a row's View Workflow (or a double-click) opens the workflow on the form.
 *
 * And for each search keyword in use (issue #1600), a pair of counts under the keyword's header ("To review"): the
 * forms whose call to action in the workflow carries it, **anyone's**, and those of them that are **yours** to act on
 * now. Each opens the workflow's engaged forms narrowed the same way. A workflow whose calls to action do not carry the
 * keyword shows no count there.
 *
 * Reached from the Workflows menu item, which a client turns on; the route itself always exists, like the others.
 */
val WorkflowsPage = FC<Props> {
    var entries by useState<List<WorkflowAggregateEntry>?>(null)
    // The search keywords' words (issue #1600), as the caller's client says them, and the cell's "Yours" and "Anyone's".
    var keywordWords by useState<List<CtaKeywordWords>>(emptyList())
    // And as each other client says them, for its workflows' counts across clients.
    var keywordWordsByClient by useState<Map<String, List<CtaKeywordWords>>>(emptyMap())
    var cellWords by useState(WorkflowCellWords())
    var loadError by useState<DisplayError?>(null)
    // Whether the caller sees across clients: then the table names each workflow's client, and a count's drill-down
    // carries it, since the listing's client says whose workflow it is.
    var acrossClients by useState(false)

    useEffectOnce {
        workflowsScope.launch {
            try {
                // Independent, so fetched together: the aggregate -- a pass over every visible form -- need not wait.
                val home = async { apiResult { HomeApi.fetchConfig() }.valueOrNull() }
                val response = Http.getApi(GEP.workflowAggregate)
                val aggregate = parseWorkflowAggregate(response[EP.items].toJsonListOfMaps())
                val config = home.await()
                acrossClients = config?.canSeeAllClients == true
                val summary = response[EP.summary].toJsonMapOrEmpty()
                keywordWords = CtaKeywordWords.listFrom(summary[GDF.ctaKeywords])
                keywordWordsByClient = CtaKeywordWords.byClientFrom(summary[GDF.ctaKeywordsByClient])
                // The cell's words come from the home file a client overlays; copy only decorates the page, so a failure
                // leaves the shipped words rather than failing the counts.
                if (aggregateKeywords(aggregate).isNotEmpty()) {
                    config?.let { cfg ->
                        apiResult { fetchCopyWithRetry(cfg.fragment) { apiResult { HomeApi.fetchConfig().fragment }.valueOrNull() } }
                            .valueOrNull()
                    }?.let { cellWords = WorkflowCellWords.of(it) }
                }
                entries = aggregate
            } catch (e: Throwable) {
                loadError = userFacingError(e)
            }
        }
    }

    val loaded = entries
    if (loaded == null) {
        LoadStateCard {
            title = "Workflows"
            this.loadError = loadError
        }
        return@FC
    }

    // [startsGroup] draws the divider that opens a keyword's pair (issue #1600).
    fun ChildrenBuilder.countLink(count: Int, route: List<Pair<String, String>>, title: String? = null, startsGroup: Boolean = false) {
        td {
            className = ClassName(if (startsGroup) "wf-count wf-count-group" else "wf-count")
            // A count of nothing opens nothing worth seeing, so only a real count is a link.
            if (count == 0) {
                +"0"
            } else {
                a {
                    // The theme's link color, as the forms list's workflow links have -- not the browser's default.
                    className = ClassName("wf-cell-link")
                    href = routeHref(route)
                    title?.let { this.title = it }
                    +count.toString()
                }
            }
        }
    }

    fun ChildrenBuilder.countCell(entry: WorkflowAggregateEntry, state: WfColumnCategory, count: Int) =
        countLink(count, workflowDrillRoute(entry.workflowId, state, entry.client.takeIf { acrossClients }))

    // A keyword's pair of counts (issue #1600): blank for a workflow whose calls to action do not carry it.
    fun ChildrenBuilder.keywordCells(entry: WorkflowAggregateEntry, keyword: String) {
        val counts = entry.keywords.firstOrNull { it.keyword == keyword }
        if (counts == null) {
            td { className = ClassName("wf-count wf-count-group") }
            td { className = ClassName("wf-count") }
            return
        }
        val client = entry.client.takeIf { acrossClients }
        // The column's header is in the caller's words; a count's tooltip in its own workflow's client's.
        val words = keywordWordsByClient[entry.client] ?: keywordWords
        listOf(CTKQ.any to counts.any, CTKQ.mine to counts.mine).forEachIndexed { i, (whose, count) ->
            val route = workflowKeywordDrillRoute(entry.workflowId, keyword, whose, client)
            countLink(count, route, keywordCountWords(keyword, whose, words, cellWords), startsGroup = i == 0)
        }
    }

    div {
        className = ClassName("card wide")
        h1 { +"Workflows" }
        if (loaded.isEmpty()) {
            p {
                className = ClassName("subtitle")
                +"There are no workflows for your forms."
            }
            return@div
        }
        // The keywords some workflow here counts (issue #1600), each a header over its pair of counts.
        val keywords = aggregateKeywords(loaded)
        // With keywords the header is two rows: each keyword's own over its Anyone's and Yours, the rest spanning both.
        val headerRows = if (keywords.isEmpty()) 1 else 2
        div {
            className = ClassName("op-table-scroll")
            table {
                className = ClassName("op-table wf-aggregate")
                thead {
                    tr {
                        th { rowSpan = headerRows; +"Workflow" }
                        // The client's id, headed as one (issue #1438).
                        if (acrossClients) th { rowSpan = headerRows; +"Client ID" }
                        WAGG.drillStates.forEach { state ->
                            th { rowSpan = headerRows; className = ClassName("wf-count"); +workflowStateTitle(state) }
                        }
                        keywords.forEach { keyword ->
                            th {
                                colSpan = 2
                                className = ClassName("wf-count-group wf-keyword-head")
                                +(keywordWords.firstOrNull { it.keyword == keyword }?.columnHeader?.ifBlank { null } ?: keyword)
                            }
                        }
                    }
                    if (keywords.isNotEmpty()) {
                        tr {
                            keywords.forEach { _ ->
                                th { className = ClassName("wf-count wf-count-group"); +cellWords.keywordAny }
                                th { className = ClassName("wf-count"); +cellWords.keywordMine }
                            }
                        }
                    }
                }
                tbody {
                    loaded.forEach { entry ->
                        tr {
                            key = "${entry.client}|${entry.workflowId}".unsafeCast<Key>()
                            td {
                                MarkdownInline { source = entry.label }
                                workflowPhaseText(entry.phase).takeIf { it.isNotEmpty() }?.let { note ->
                                    span {
                                        className = ClassName("wf-cell-cat")
                                        +" $note"
                                    }
                                }
                            }
                            if (acrossClients) td { +entry.client }
                            countCell(entry, WfColumnCategory.eligible, entry.eligible)
                            countCell(entry, WfColumnCategory.engaged, entry.engaged)
                            countCell(entry, WfColumnCategory.finished, entry.finished)
                            keywords.forEach { keywordCells(entry, it) }
                        }
                    }
                }
            }
        }
    }
}
