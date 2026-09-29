package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.GCI
import com.dynamicruntime.common.gedra.UF
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

    private fun acmeDefinition() = parseClientDefinition(
        mapOf(
            CLD.client to mapOf(
                CLD.clientId to "acme", CLD.name to "Acme", CLD.usageType to "production", CLD.audience to "customer",
                CLD.enabledEnvironments to listOf("local", "unit"), CLD.domainPrefix to "acme", CLD.staticConfig to false,
                CLD.userLabels to listOf("reviewer"),
            ),
            CLD.present to true,
            CLD.issues to listOf(mapOf(GCI.message to "Bad thing.", GCI.degradedTo to "Dropped it.", GCI.origin to "stored")),
            CLD.traits to listOf(mapOf(CCT.traitId to "acmeSiteAudit"), mapOf(CCT.typeName to "no id")),
            CLD.usages to listOf(mapOf(UF.label to "Auditor"), mapOf(UF.label to "Year")),
            CLD.workflows to listOf("reviewForm", "auditReview"),
        ),
    )

    @Test
    fun theDefinitionParsesToWhatTheDetailShows() {
        val def = acmeDefinition()
        assertEquals("acme", def.info[CLD.clientId])
        assertEquals(true, def.present)
        assertEquals(listOf("Bad thing."), def.issues.map { it.message })
        assertEquals("stored", def.issues.single().origin)
        assertEquals(listOf("acmeSiteAudit"), def.traitIds)
        assertEquals(listOf("Auditor", "Year"), def.usageLabels)
        assertEquals(listOf("reviewForm", "auditReview"), def.workflowIds)
    }

    @Test
    fun theSummaryRowsNoteThePlatformsFieldsForAScopedAdministrator() {
        val row = ClientOverview("acme", "Acme", ClientStatus.present.name, GedraConfigOrigin.source.name, 1, 3, 5, 2, 4, true, emptyList())
        val across = clientSummaryRows("acme", row, acmeDefinition(), canSeeAllClients = true).toMap()
        assertEquals("acme", across["Client id"])
        assertEquals("Loaded", across["Load"])
        assertEquals("Source + 1 stored", across["Definition"])
        assertEquals("production", across["Usage type"])
        assertEquals("customer", across["Audience"])
        assertEquals("local, unit", across["Enabled in"])
        assertEquals("acme (prefix)", across["Domain"])
        assertEquals("\u2014", across["Extends"])
        assertEquals("acmeSiteAudit", across["Traits"])
        assertEquals("reviewForm, auditReview", across["Workflows"])
        assertEquals("5 (2 unclaimed)", across["Users"])
        // A client's own administrator sees the operator-only fields marked as the platform's.
        val scoped = clientSummaryRows("acme", row, acmeDefinition(), canSeeAllClients = false).toMap()
        assertEquals("production $platformSetNote", scoped["Usage type"])
        assertEquals("customer $platformSetNote", scoped["Audience"])
        assertEquals("local, unit", scoped["Enabled in"])
        // Without a definition (a client this node does not carry), the listing's facts still show.
        val listingOnly = clientSummaryRows("acme", row, null, canSeeAllClients = true).toMap()
        assertEquals("Loaded", listingOnly["Load"])
        assertEquals(null, listingOnly["Traits"])
        // Without a listing row (a deep link before the listing loads), the definition's do.
        val definitionOnly = clientSummaryRows("acme", null, acmeDefinition(), canSeeAllClients = true).toMap()
        assertEquals("acme", definitionOnly["Client id"])
        assertEquals(null, definitionOnly["Load"])
        // Neither yet (a deep link before anything answered): the id the hash named still heads the summary.
        assertEquals("globex", clientSummaryRows("globex", null, null, canSeeAllClients = false).toMap()["Client id"])
    }

    @Test
    fun theStoredConfigurationsParse() {
        val parsed = parseConfigSummaries(
            listOf(
                mapOf(CFEP.name to "main", CFEP.version to 3, CFEP.published to true, CFEP.publishedAt to "2026-09-29T10:00:00Z", CFEP.issues to listOf(mapOf(GCI.message to "m"))),
                mapOf(CFEP.name to "draft"),
                mapOf(CFEP.version to 1),
            ),
        )
        assertEquals(listOf("main", "draft"), parsed.map { it.name })
        assertEquals(3, parsed[0].version)
        assertEquals(true, parsed[0].published)
        assertEquals(1, parsed[0].issueCount)
        assertEquals(false, parsed[1].published)
        assertEquals(null, parsed[1].publishedAt)
    }

    @Test
    fun theCountsReadWithTheirQualifiers() {
        assertEquals("12", userCountText(12, 0))
        assertEquals("12 (3 unclaimed)", userCountText(12, 3))
        assertEquals("4", workflowsText(4, false))
        assertEquals("4, survey", workflowsText(4, true))
    }
}
