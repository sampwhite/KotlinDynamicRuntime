package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.clientLabel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.useEffect
import react.useState
import web.cssom.ClassName

private val clientsScope = MainScope()

/**
 * The Clients page (issue #905): the clients an administrator oversees, each with where it stands on this node,
 * where its definition comes from, and what it holds. An `allClients` administrator sees every client this node
 * knows of -- present or not, with why not; a client-scoped one sees their own. One table for both: the second
 * simply has one row.
 *
 * Denied honestly, in two layers as Users is: the shell's `canManageUsers` says whether to ask at all, and the
 * endpoint's own refusal -- a `public` self-administrator, who administers only their own users -- is shown as it
 * came. The detail view (issue #906) and, later, editing a client and designing its workflows (#903) open from here.
 */
val ClientsPage = FC<Props> {
    var config by useState<HomeConfig?>(null)
    var rows by useState<List<ClientOverview>?>(null)
    var loadError by useState<DisplayError?>(null)
    val generation = useRefreshGeneration()

    useEffect(generation) {
        clientsScope.launch {
            try {
                val loaded = HomeApi.fetchConfig()
                config = loaded
                // Not asked of a caller the shell says may not administer: the panel below says so instead.
                if (loaded.canManageUsers) rows = ClientsApi.listOverview()
                loadError = null
            } catch (e: Throwable) {
                loadError = userFacingError(e)
            }
        }
    }

    val current = config
    when {
        loadError != null -> LoadStateCard {
            title = "Clients"
            this.loadError = loadError
            errorLead = "Couldn't load the clients."
        }
        current == null -> LoadStateCard { title = "Clients" }
        !current.canManageUsers -> div {
            className = ClassName("card wide")
            h1 { +"Clients" }
            p {
                className = ClassName("subtitle")
                +"You do not have permission to see clients."
            }
        }
        else -> clientsListing(rows, current.canSeeAllClients)
    }
}

/** The listing card: the heading, a line saying whose clients these are, and the table -- or the empty state. */
private fun react.ChildrenBuilder.clientsListing(rows: List<ClientOverview>?, acrossClients: Boolean) {
    div {
        className = ClassName("card wide")
        h1 { +"Clients" }
        p {
            className = ClassName("subtitle")
            +(if (acrossClients) "Every client this node knows of, present or not." else "Your client, as this node carries it.")
        }
        when {
            rows == null -> p {
                className = ClassName("subtitle")
                +"Loading…"
            }
            rows.isEmpty() -> p {
                className = ClassName("subtitle")
                +"No client is present on this node for you."
            }
            else -> div {
                className = ClassName("op-table-scroll")
                table {
                    className = ClassName("op-table")
                    thead {
                        tr {
                            th { +"Client" }
                            th { +"Load" }
                            th { +"Definition" }
                            th { className = ClassName("wf-count"); +"Forms" }
                            th { className = ClassName("wf-count"); +"Users" }
                            th { className = ClassName("wf-count"); +"Workflows" }
                        }
                    }
                    tbody {
                        rows.forEach { c ->
                            tr {
                                key = c.clientId.unsafeCast<Key>()
                                td { +clientLabel(c.clientId, c.name) }
                                td { +clientLoadText(c.status, c.issueCount) }
                                td { +clientOriginText(c.origin, c.storedConfigs) }
                                td { className = ClassName("wf-count"); +c.forms.toString() }
                                td { className = ClassName("wf-count"); +userCountText(c.users, c.unclaimedUsers) }
                                td { className = ClassName("wf-count"); +workflowsText(c.workflowCount, c.hasSurvey) }
                            }
                        }
                    }
                }
            }
        }
    }
}
