package com.dynamicruntime.webapp

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.clientLabel
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
 * endpoint's own refusal -- a `public` self-administrator, who administers only their own users -- is shown as the
 * denial it is, in the endpoint's words, not as a failed load. A failed *refresh* keeps the listing already on
 * screen and says so above it: the page re-reads on every refresh generation, and a blip must not take away what
 * was being read. The detail view (issue #906) and, later, editing a client and designing its workflows (#903) open
 * from here.
 */
val ClientsPage = FC<Props> {
    var config by useState<HomeConfig?>(null)
    var rows by useState<List<ClientOverview>?>(null)
    var loadError by useState<DisplayError?>(null)
    // The endpoint's refusal (a 403), in its words: a designed answer, drawn as the permission panel.
    var refusal by useState<String?>(null)
    val generation = useRefreshGeneration()

    useEffect(generation) {
        clientsScope.launch {
            try {
                val loaded = HomeApi.fetchConfig()
                config = loaded
                // Not asked of a caller the shell says may not administer: the panel below says so instead.
                if (loaded.canManageUsers) rows = ClientsApi.listOverview()
                loadError = null
                refusal = null
            } catch (e: Throwable) {
                if ((e as? ApiError)?.status == EXC.notAuthorized) refusal = e.message else loadError = userFacingError(e)
            }
        }
    }

    val current = config
    when {
        refusal != null -> deniedCard(refusal!!)
        // Nothing loaded yet: the load state, or the failure that kept it from loading.
        current == null -> LoadStateCard {
            title = "Clients"
            this.loadError = loadError
            errorLead = "Couldn't load the clients."
        }
        !current.canManageUsers -> deniedCard("You do not have permission to see clients.")
        else -> clientsListing(rows, current.canSeeAllClients, loadError)
    }
}

/** The page as a denial: the heading and why, whichever layer said so. */
private fun ChildrenBuilder.deniedCard(why: String) {
    div {
        className = ClassName("card wide")
        h1 { +"Clients" }
        p {
            className = ClassName("subtitle")
            +why
        }
    }
}

/**
 * The listing card: the heading, a line saying whose clients these are, and the table -- or the empty state. A
 * [loadError] with rows on screen is a failed refresh: said above the rows, which stay.
 */
private fun ChildrenBuilder.clientsListing(rows: List<ClientOverview>?, acrossClients: Boolean, loadError: DisplayError?) {
    div {
        className = ClassName("card wide")
        h1 { +"Clients" }
        p {
            className = ClassName("subtitle")
            +(if (acrossClients) "Every client this node knows of, present or not." else "Your client, as this node carries it.")
        }
        loadError?.let { errorText(if (rows == null) "Couldn't load the clients." else "Couldn't refresh the clients; showing what was loaded.", it) }
        when {
            rows == null -> if (loadError == null) p {
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
                            th { className = ClassName("op-num"); +"Forms" }
                            th { className = ClassName("op-num"); +"Users" }
                            th { className = ClassName("op-num"); +"Workflows" }
                        }
                    }
                    tbody {
                        rows.forEach { c ->
                            tr {
                                key = c.clientId.unsafeCast<Key>()
                                td { +clientLabel(c.clientId, c.name) }
                                td {
                                    // The issues themselves, on hover: what a check forgave, or why the client was dropped.
                                    if (c.issues.isNotEmpty()) title = c.issues.joinToString("\n")
                                    +clientLoadText(c.status, c.issues.size)
                                }
                                td { +clientOriginText(c.origin, c.storedConfigs) }
                                // The counts open the listings behind them (issue #905) -- for a present client only:
                                // the forms and users pages work in a client this node carries.
                                val present = c.status == ClientStatus.present.name
                                td {
                                    className = ClassName("op-num")
                                    countCell(c.forms.toString(), clientFormsHref(c.clientId, acrossClients).takeIf { present })
                                }
                                td {
                                    className = ClassName("op-num")
                                    countCell(userCountText(c.users, c.unclaimedUsers), clientUsersHref(c.clientId, acrossClients).takeIf { present })
                                }
                                td { className = ClassName("op-num"); +workflowsText(c.workflowCount, c.hasSurvey) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A count as a link to the listing behind it, or plain text when there is nowhere to go. */
private fun ChildrenBuilder.countCell(text: String, href: String?) {
    if (href == null) {
        +text
    } else {
        a {
            className = ClassName("wf-cell-link")
            this.href = href
            +text
        }
    }
}
