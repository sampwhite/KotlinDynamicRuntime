package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.ClientStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The customer page's pure half (issue #1114): what it reads off the wire, where it links, and what a row says. */
class CustomersPageTest {
    private val summary = mapOf(
        CUST.customerId to "acme", CUST.name to "Acme Co", CUST.plan to "unlimited", CUST.rootClientId to "acme",
        CUST.clientCount to 2, CUST.isSource to true,
    )

    private fun row(clientId: String, vararg more: Pair<String, Any?>) = parseClientOverview(
        listOf(mapOf(CLD.clientId to clientId, CLD.status to ClientStatus.present.name) + more),
    ).single()

    @Test
    fun aCustomerAndItsClientsParse() {
        val view = parseCustomerView(mapOf(CUST.customer to summary, CUST.clients to listOf(mapOf(CLD.clientId to "acme"), mapOf("no" to "id"))))!!
        assertEquals("Acme Co", view.customer.name)
        assertEquals(2, view.customer.clientCount)
        assertEquals("Source code", customerOriginText(view.customer))
        assertEquals(listOf("acme"), view.clients.map { it.clientId })
        assertNull(parseCustomerView(mapOf(CUST.clients to emptyList<Any>())))
        assertEquals("Unlimited", planNameText("unlimited"))
    }

    @Test
    fun onlyTheRootsOwnClientIsLinkedForARootsAdministrator() {
        assertTrue(customerClientLinked(row("acme_east"), acrossClients = true, ownClient = "hub"))
        assertTrue(customerClientLinked(row("acme"), acrossClients = false, ownClient = "acme"))
        assertFalse(customerClientLinked(row("acme_east"), acrossClients = false, ownClient = "acme"))
        assertEquals("/customers/acme", customerHref("acme"))
    }

    /** A client's summary links its customer only where the caller may open the customer's page. */
    @Test
    fun aClientsCustomerIsLinkedWhereItsPageWouldOpen() {
        val root = row("acme", CLD.rootOf to "acme")
        val east = row("acme_east")
        // An administrator of every client: from any of the customer's clients.
        assertEquals(customerHref("acme"), clientSummaryHref(summaryCustomerRow, east, acrossClients = true, customerId = "acme"))
        // The root's administrator, on the root's page; nobody from another of its clients.
        assertEquals(customerHref("acme"), clientSummaryHref(summaryCustomerRow, root, acrossClients = false, customerId = "acme"))
        assertNull(clientSummaryHref(summaryCustomerRow, east, acrossClients = false, customerId = "acme"))
        // A client no customer owns has nothing to link.
        assertNull(clientSummaryHref(summaryCustomerRow, east, acrossClients = true, customerId = null))
    }

    @Test
    fun aRowSaysWhatItRuns() {
        assertEquals("Latest", clientRunsText(row("a")))
        assertEquals("Published only", clientRunsText(row("a", CLD.publishedOnly to true)))
        assertEquals("Published; has a sandbox", clientRunsText(row("a", CLD.hasSandbox to true, CLD.publishedOnly to true)))
        assertEquals("Source only", clientRunsText(row("a", CLD.staticHere to true)))
        assertEquals("—", clientRunsText(row("a", CLD.status to ClientStatus.retired.name)))
    }
}
