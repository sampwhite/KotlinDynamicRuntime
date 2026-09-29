package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.GCI
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.user.USF
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Clients page's pure half (issue #905): reading the overview's rows, and how each fact reads in the table. */
class ClientsPageTest {
    @Test
    fun theOverviewParsesAndDropsWhatIsNotAClient() {
        val parsed = parseClientOverview(
            listOf(
                mapOf(
                    CLD.clientId to "acme", CLD.name to "Acme", CLD.status to ClientStatus.present.name,
                    CLD.origin to GedraConfigOrigin.source.name, CLD.storedConfigs to 1, CLD.forms to 3, CLD.users to 5,
                    CLD.unclaimedUsers to 2, CLD.workflowCount to 4, CLD.hasSurvey to true,
                    CLD.issues to listOf(mapOf(GCI.message to "m", GCI.degradedTo to "d"), mapOf(GCI.degradedTo to "no message")),
                ),
                // Keys missing: defaults, not a dropped row.
                mapOf(CLD.clientId to "bare"),
                // No id: not a client.
                mapOf(CLD.name to "stray"),
            ),
        )
        assertEquals(listOf("acme", "bare"), parsed.map { it.clientId })
        val acme = parsed[0]
        assertEquals(listOf(1, 3, 5, 2, 4), listOf(acme.storedConfigs, acme.forms, acme.users, acme.unclaimedUsers, acme.workflowCount))
        assertEquals(true, acme.hasSurvey)
        // The messages, so the listing can say what was forgiven; an issue with none is not a message.
        assertEquals(listOf("m"), acme.issues)
        val bare = parsed[1]
        assertEquals("", bare.name)
        assertEquals(0, bare.forms + bare.users + bare.issues.size)
        assertEquals(false, bare.hasSurvey)
    }

    @Test
    fun aClientsLoadReadsAsLoadedOrAsWhyNot() {
        assertEquals("Loaded", clientLoadText(ClientStatus.present.name, 0))
        assertEquals("Loaded, 1 issue forgiven", clientLoadText(ClientStatus.present.name, 1))
        assertEquals("Loaded, 2 issues forgiven", clientLoadText(ClientStatus.present.name, 2))
        assertEquals("Not enabled here", clientLoadText(ClientStatus.notEnabled.name, 0))
        assertEquals("Dropped by a check \u2014 1 issue", clientLoadText(ClientStatus.dropped.name, 1))
        assertEquals("Stored config only", clientLoadText(ClientStatus.storedOnly.name, 0))
        // A status this build does not know still says something.
        assertEquals("odd", clientLoadText("odd", 0))
    }

    @Test
    fun aDefinitionsOriginCountsOverlaysButNeverItself() {
        assertEquals("Source", clientOriginText(GedraConfigOrigin.source.name, 0))
        assertEquals("Source + 2 stored", clientOriginText(GedraConfigOrigin.source.name, 2))
        // A stored definition's own config is among the loaded ones, so one is just "Stored".
        assertEquals("Stored", clientOriginText(GedraConfigOrigin.stored.name, 1))
        assertEquals("Stored", clientOriginText(GedraConfigOrigin.stored.name, 0))
        assertEquals("Stored (3 configs)", clientOriginText(GedraConfigOrigin.stored.name, 3))
    }

    @Test
    fun theCountsLeadToTheListingsBehindThem() {
        // Across clients the link chooses the client; a scoped administrator's listing is their own already.
        assertEquals("#${HP.page}=${HMENU.pageForms}&${EI.client}=acme", clientFormsHref("acme", acrossClients = true))
        assertEquals("#${HP.page}=${HMENU.pageForms}", clientFormsHref("acme", acrossClients = false))
        assertEquals("#${HP.page}=${HMENU.pageUsers}&${USF.client}=acme", clientUsersHref("acme", acrossClients = true))
        assertEquals("#${HP.page}=${HMENU.pageUsers}", clientUsersHref("acme", acrossClients = false))
    }

    @Test
    fun theCountsReadWithTheirQualifiers() {
        assertEquals("12", userCountText(12, 0))
        assertEquals("12 (3 unclaimed)", userCountText(12, 3))
        assertEquals("4", workflowsText(4, false))
        assertEquals("4, survey", workflowsText(4, true))
    }
}
