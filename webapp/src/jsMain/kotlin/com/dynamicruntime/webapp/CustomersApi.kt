package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * One customer as the customer page shows it (issue #1114): its id -- the prefix of its clients' ids -- its name and
 * note, its plan, its root client, how many clients it owns, and whether it is declared in source code.
 */
class CustomerSummary(
    val customerId: String,
    val name: String,
    val description: String?,
    val plan: String,
    val rootClientId: String?,
    val clientCount: Int,
    val isSource: Boolean,
)

/** One customer's page (issue #1114): what it is, and its clients as the Clients overview's rows. */
class CustomerView(val customer: CustomerSummary, val clients: List<ClientOverview>)

/** A customer summary off the wire; one without an id is not a customer. Pure, and covered under `jsNodeTest`. */
fun parseCustomerSummary(m: Map<String, Any?>): CustomerSummary? {
    val id = m[CUST.customerId].toOptStr() ?: return null
    return CustomerSummary(
        customerId = id,
        name = m[CUST.name].toOptStr() ?: id,
        description = m[CUST.description].toOptStr(),
        plan = m[CUST.plan].toOptStr().orEmpty(),
        rootClientId = m[CUST.rootClientId].toOptStr(),
        clientCount = (m[CUST.clientCount] as? Number)?.toInt() ?: 0,
        isSource = m[CUST.isSource] == true,
    )
}

/** The customer page's item as a [CustomerView], or null without a customer. Pure, and covered under `jsNodeTest`. */
fun parseCustomerView(item: Map<String, Any?>): CustomerView? {
    val customer = parseCustomerSummary(item[CUST.customer].toJsonMapOrEmpty()) ?: return null
    return CustomerView(customer, parseClientOverview(item[CUST.clients].toJsonListOfMaps()))
}

/** One customer's page (issue #1114): the one link to it, from the list of customers. */
fun customerHref(customerId: String): String = hashHref(listOf(HP.page to HMENU.pageCustomers, HP.customer to customerId))

/**
 * Whether the customer page links [row] to its client page (issue #1114): every row for an administrator who sees
 * every client; for a root's administrator only their own client, the one they edit -- the rest are facts.
 */
fun customerClientLinked(row: ClientOverview, acrossClients: Boolean, ownClient: String): Boolean =
    acrossClients || row.clientId == ownClient

/** A plan by its name as a person reads it: "Free", "Unlimited". Pure, and covered under `jsNodeTest`. */
fun planNameText(plan: String): String = plan.replaceFirstChar { it.uppercase() }.ifEmpty { "—" }

/**
 * What a client runs, for the customer page's table (issue #1114): its publish and sandbox state in a few words.
 * Pure, and covered under `jsNodeTest`.
 */
fun clientRunsText(row: ClientOverview): String = when {
    row.status != ClientStatus.present.name -> "—"
    row.staticHere -> "Source only"
    row.hasSandbox -> "Published; has a sandbox"
    row.publishedOnly -> "Published only"
    else -> "Latest"
}

/** Where a customer is declared, as its page says it. */
fun customerOriginText(customer: CustomerSummary): String = if (customer.isSource) "Source code" else "Stored configuration"

/** The customer endpoints (issue #1114), on the scoped surface both kinds of administrator reach. */
object CustomersApi {
    /** The customers this deployment declares, for an administrator who sees every client. */
    suspend fun list(): List<CustomerSummary> =
        Http.getApi(UADEP.customers)[EP.items].toJsonListOfMaps().mapNotNull { parseCustomerSummary(it) }

    /** One customer and its clients: [customerId], or the caller's own when null. */
    suspend fun overview(customerId: String?): CustomerView? {
        val query = customerId?.let { queryString(mapOf(CUST.customer to it)) }.orEmpty()
        return parseCustomerView(Http.getApi(UADEP.customerOverview + query)[EP.item].toJsonMapOrEmpty())
    }
}
