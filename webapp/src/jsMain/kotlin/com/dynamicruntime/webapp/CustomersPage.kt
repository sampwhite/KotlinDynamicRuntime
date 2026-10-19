package com.dynamicruntime.webapp

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.clientLabel
import com.dynamicruntime.common.home.HMENU
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.useEffect
import react.useState
import web.cssom.ClassName

private val customersScope = MainScope()

/**
 * The customer page (issue #1114), grown out of the Clients page. An administrator who sees every client is shown
 * the deployment's customers, each opening to its page (`cu=<id>`); an administrator of a customer's root client is
 * shown their own customer directly, as a client's administrator is shown their client (#1082), with no way back.
 *
 * A customer's page says what the customer is -- its name, plan, root client, and where it is declared -- and lists
 * its clients as the Clients overview's rows: each one's load, counts, plan usage, publish and sandbox state, and
 * issues. Read-only, facts only. Every row links to its client page for an administrator who sees every client; for a
 * root's administrator only their own client does ([customerClientLinked]), the one they edit.
 */
val CustomersPage = FC<Props> {
    var config by useState<HomeConfig?>(null)
    var customers by useState<List<CustomerSummary>?>(null)
    var view by useState<CustomerView?>(null)
    var loadError by useState<DisplayError?>(null)
    // The endpoint's refusal (a 403 or 404), in its words.
    var refusal by useState<String?>(null)
    val generation = useRefreshGeneration()
    val named = hashParams()[HP.customer]

    useEffect(generation, named) {
        customersScope.launch {
            try {
                val loaded = HomeApi.fetchConfig()
                config = loaded
                if (loaded.canSeeAllClients && named == null) {
                    customers = CustomersApi.list()
                    view = null
                } else {
                    view = CustomersApi.overview(named)
                }
                loadError = null
                refusal = null
            } catch (e: Throwable) {
                val status = (e as? ApiError)?.status
                if (status == EXC.notAuthorized || status == EXC.notFound) refusal = e.message else loadError = userFacingError(e)
            }
        }
    }

    val across = config?.canSeeAllClients == true
    div {
        className = ClassName("card wide")
        when {
            refusal != null -> {
                h1 { +"Customer" }
                p {
                    className = ClassName("subtitle")
                    +refusal.orEmpty()
                }
            }
            across && named == null -> customerList(customers, loadError)
            else -> customerDetail(view, loadError, across, config?.user?.client.orEmpty())
        }
    }
}

/** The deployment's customers, each opening to its page. */
private fun ChildrenBuilder.customerList(customers: List<CustomerSummary>?, loadError: DisplayError?) {
    h1 { +"Customers" }
    p {
        className = ClassName("subtitle")
        +"Who owns the deployment's clients. Clients no customer owns -- platform, test, and self-serve ones -- are on the Clients page."
    }
    loadError?.let { errorText("Couldn't load the customers.", it) }
    when {
        customers == null -> if (loadError == null) p {
            className = ClassName("subtitle")
            +"Loading…"
        }
        customers.isEmpty() -> p {
            className = ClassName("subtitle")
            +"This deployment declares no customer."
        }
        else -> div {
            className = ClassName("op-table-scroll")
            table {
                className = ClassName("op-table")
                thead {
                    tr {
                        th { +"Customer" }
                        th { +"Plan" }
                        th { +"Root client" }
                        th { className = ClassName("op-num"); +"Clients" }
                        th { +"Declared in" }
                    }
                }
                tbody {
                    customers.forEach { c ->
                        tr {
                            key = c.customerId.unsafeCast<Key>()
                            td {
                                a {
                                    className = ClassName("wf-cell-link")
                                    href = customerHref(c.customerId)
                                    +clientLabel(c.customerId, c.name)
                                }
                            }
                            td { +planNameText(c.plan) }
                            td { +(c.rootClientId ?: "—") }
                            td { className = ClassName("op-num"); +c.clientCount.toString() }
                            td { +customerOriginText(c) }
                        }
                    }
                }
            }
        }
    }
}

/** One customer: what it is, and its clients as rows of facts. */
private fun ChildrenBuilder.customerDetail(view: CustomerView?, loadError: DisplayError?, across: Boolean, ownClient: String) {
    // A way back only to a list there is: a root's administrator is shown their own customer in its place.
    if (across) backToListing(HMENU.pageCustomers)
    if (view == null) {
        h1 { +"Customer" }
        loadError?.let { errorText("Couldn't load the customer.", it) }
            ?: p {
                className = ClassName("subtitle")
                +"Loading…"
            }
        return
    }
    val customer = view.customer
    h1 { +clientLabel(customer.customerId, customer.name) }
    loadError?.let { errorText("Couldn't refresh the customer; showing what was loaded.", it) }
    customer.description?.let { readOnlyField("Description", it) }
    readOnlyField("Plan", planNameText(customer.plan))
    readOnlyField("Root client", customer.rootClientId ?: "—")
    readOnlyField("Declared in", customerOriginText(customer))
    h2 { +"Clients" }
    if (!across) {
        p {
            className = ClassName("subtitle")
            +"Facts about each of the customer's clients. You change only your own, from its page."
        }
    }
    if (view.clients.isEmpty()) {
        p {
            className = ClassName("subtitle")
            +"No client is filed under this customer."
        }
        return
    }
    div {
        className = ClassName("op-table-scroll")
        table {
            className = ClassName("op-table")
            thead {
                tr {
                    th { +"Client" }
                    th { +"Load" }
                    th { className = ClassName("op-num"); +"Forms" }
                    th { className = ClassName("op-num"); +"Users" }
                    th { +"Plan usage" }
                    th { +"Runs" }
                }
            }
            tbody {
                view.clients.forEach { row ->
                    tr {
                        key = row.clientId.unsafeCast<Key>()
                        td {
                            if (customerClientLinked(row, across, ownClient)) {
                                a {
                                    className = ClassName("wf-cell-link")
                                    href = clientDetailHref(row.clientId)
                                    +clientLabel(row.clientId, row.name)
                                }
                            } else {
                                span { +clientLabel(row.clientId, row.name) }
                            }
                        }
                        td {
                            // The issues themselves, on hover: what a check forgave.
                            if (row.issues.isNotEmpty()) title = row.issues.joinToString("\n")
                            +clientLoadText(row.status, row.issues.size)
                        }
                        val present = row.status == ClientStatus.present.name
                        td { className = ClassName("op-num"); +(if (present) row.forms.toString() else "—") }
                        td { className = ClassName("op-num"); +(if (present) userCountText(row.users, row.unclaimedUsers) else "—") }
                        td { +(row.planUsage?.let(::planUsageText) ?: "—") }
                        td { +clientRunsText(row) }
                    }
                }
            }
        }
    }
}
