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

    // --- recent runs (issue #1099) ---------------------------------------------------------------------

    private fun saved(name: String, dataId: String?, at: String) =
        SavedSimulationRun(name, dataId, at, parseSimulationReport(report))

    @Test
    fun aRememberedRunGoesFirstAndTheListKeepsTheMostRecent() {
        val older = (1..recentRunsLimit).map { saved("run$it", "d1", "2026-10-09T10:0$it:00Z") }
        val next = rememberRun(older, saved("newest", "d1", "2026-10-09T11:00:00Z"))
        assertEquals(recentRunsLimit, next.size)
        assertEquals("newest", next.first().simulation)
        // The oldest falls off the end.
        assertEquals("run${recentRunsLimit - 1}", next.last().simulation)
    }

    @Test
    fun runsFromOtherDataAreStaleAndANodeWithNoDataIdCannotSay() {
        val runs = listOf(saved("now", "d2", "2026-10-09T11:00:00Z"), saved("before", "d1", "2026-10-09T10:00:00Z"))
        val split = recentRunsFor(runs, "d2")
        assertEquals(listOf("now"), split.current.map { it.simulation })
        assertEquals(listOf("before"), split.stale.map { it.simulation })
        val unknown = recentRunsFor(runs, null)
        assertEquals(2, unknown.current.size)
        assertEquals(0, unknown.stale.size)
    }

    @Test
    fun savedRunsRoundTripAndAnUnreadableStoreIsForgotten() {
        val runs = listOf(saved("report-demo", "d1", "2026-10-09T11:00:00Z"))
        val back = decodeSavedRuns(encodeSavedRuns(runs)).single()
        assertEquals("report-demo", back.simulation)
        assertEquals("d1", back.dataId)
        assertEquals(listOf("acme", "globex"), back.run.clients)
        // The users as the report had them -- the broken one was already left out when it was first read.
        assertEquals(listOf("ada@acme.example", "oz@acme.example"), back.run.users.map { it.email })
        assertEquals("admin", back.run.users.first().persona)
        assertEquals(listOf("allClients"), back.run.users[1].capabilities)
        assertEquals("page=reports", back.run.startPage)
        // Nothing stored, text that does not parse, and an entry without its simulation all read as no runs.
        assertEquals(emptyList(), decodeSavedRuns(null))
        assertEquals(emptyList(), decodeSavedRuns("not json"))
        assertEquals(emptyList(), decodeSavedRuns("[{\"ranAt\": \"2026-10-09T11:00:00Z\"}]"))
    }

    @Test
    fun aRememberedRunIsHeadedBySimulationClientsAndTime() {
        assertEquals("report-demo · acme, globex · 2026-10-09 11:00 UTC", recentRunHeading(saved("report-demo", "d1", "2026-10-09T11:00:00Z")))
    }
}
