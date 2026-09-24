package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.workflow.WfColumnCategory
import com.dynamicruntime.common.util.toJsonListOfMaps
import kotlinx.coroutines.MainScope
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
 * Reached from the Workflows menu item, which a client turns on; the route itself always exists, like the others.
 */
val WorkflowsPage = FC<Props> {
    var entries by useState<List<WorkflowAggregateEntry>?>(null)
    var loadError by useState<DisplayError?>(null)
    // Whether the caller sees across clients: then the table names each workflow's client, and a count's drill-down
    // carries it, since the listing's client says whose workflow it is.
    var acrossClients by useState(false)

    useEffectOnce {
        workflowsScope.launch {
            try {
                acrossClients = runCatching { HomeApi.fetchConfig().canSeeAllClients }.getOrDefault(false)
                entries = parseWorkflowAggregate(Http.getApi(GEP.workflowAggregate)[EP.items].toJsonListOfMaps())
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

    fun ChildrenBuilder.countCell(entry: WorkflowAggregateEntry, state: WfColumnCategory, count: Int) {
        td {
            className = ClassName("wf-count")
            // A count of nothing opens nothing worth seeing, so only a real count is a link.
            if (count == 0) {
                +"0"
            } else {
                a {
                    // The theme's link colour, as the forms list's workflow links have -- not the browser's default.
                    className = ClassName("wf-cell-link")
                    href = hashHref(workflowDrillHash(entry.workflowId, state, entry.client.takeIf { acrossClients }))
                    +count.toString()
                }
            }
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
        div {
            className = ClassName("op-table-scroll")
            table {
                className = ClassName("op-table wf-aggregate")
                thead {
                    tr {
                        th { +"Workflow" }
                        if (acrossClients) th { +"Client" }
                        th { className = ClassName("wf-count"); +"Eligible" }
                        th { className = ClassName("wf-count"); +"Engaged" }
                        th { className = ClassName("wf-count"); +"Finished" }
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
                        }
                    }
                }
            }
        }
    }
}
