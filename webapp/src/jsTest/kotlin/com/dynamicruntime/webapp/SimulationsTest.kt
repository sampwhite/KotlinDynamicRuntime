package com.dynamicruntime.webapp

import com.dynamicruntime.common.test.SIM
import com.dynamicruntime.common.test.TEP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Simulations page's pure half (issue #997): reading a report, and turning it into a sign-in and a landing. */
class SimulationsTest {
    private val report = mapOf(
        SIM.clients to listOf("acme", "globex"),
        SIM.users to listOf(
            mapOf(SIM.email to "ada@acme.example", SIM.client to "acme", SIM.level to "admin", SIM.persona to "admin", SIM.purpose to "an acme administrator"),
            mapOf(SIM.email to "oz@acme.example", SIM.client to "acme", SIM.level to "admin", SIM.purpose to "an overseer", SIM.capabilities to listOf("allClients")),
            mapOf(SIM.email to "no-client@acme.example", SIM.purpose to "broken"),
        ),
        SIM.startPage to "#page=reports",
        SIM.summary to "Created 32 forms.",
    )

    @Test
    fun aReportIsReadAndABrokenUserLeftOut() {
        val run = parseSimulationReport(report)
        assertEquals(listOf("acme", "globex"), run.clients)
        assertEquals(listOf("ada@acme.example", "oz@acme.example"), run.users.map { it.email })
        assertEquals("page=reports", run.startPage)
        assertEquals("Created 32 forms.", run.summary)
    }

    @Test
    fun signingInNamesTheUserAndCarriesWhatACreateNeeds() {
        val (ada, oz) = parseSimulationReport(report).users
        assertEquals(
            mapOf(TEP.email to "ada@acme.example", TEP.client to "acme", TEP.level to "admin", TEP.persona to "admin"),
            becomeUserBody(ada),
        )
        assertEquals(listOf("allClients"), becomeUserBody(oz)[TEP.capabilities])
        assertNull(becomeUserBody(oz)[TEP.persona])
    }

    @Test
    fun theStartPageBecomesHashPairsAndNoneMeansHome() {
        assertEquals(listOf("page" to "newForm", "from" to "forms"), startPageHash("page=newForm&from=forms"))
        assertEquals(emptyList(), startPageHash(null))
        assertEquals("design-demo", simulationName("/fixture/simulate/design-demo"))
    }
}
