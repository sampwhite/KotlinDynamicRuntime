package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.GCI
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.uiblock.UIB
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
                    CLD.copyOverrides to 5, CLD.blockOverrides to 4,
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
        assertEquals(5 to 4, acme.copyOverrides to acme.blockOverrides)
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
        val row = ClientOverview("acme", "Acme", ClientStatus.present.name, GedraConfigOrigin.source.name, 1, 3, 5, 2, 4, true, emptyList(), 0, 0)
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
    fun theStoredConfigurationsAreAskedOfTheListingTheCallerMayUse() {
        // Across clients: the full-scope listing, naming the client -- any client.
        assertEquals("${ACEP.bundles}?${CFEP.client}=globex", storedConfigsPath("globex", acrossClients = true, ownClient = "hub"))
        // Scoped: the caller's own listing, for their own client only...
        assertEquals(CFEP.bundles, storedConfigsPath("acme", acrossClients = false, ownClient = "acme"))
        // ...and nothing for another's, rather than their own under a foreign heading.
        assertEquals(null, storedConfigsPath("globex", acrossClients = false, ownClient = "acme"))
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

    // --- the overrides (issue #917) -------------------------------------------------------------------------

    private fun copyRow(file: String, ns: String, key: String, base: String?, value: String, config: String, origin: String, source: String? = null, orphan: Boolean = false) =
        mapOf(
            COV.fileId to file, COV.namespaceField to ns, COV.key to key, COV.audience to "frontend", COV.baseValue to base,
            COV.value to value, COV.configName to config, COV.origin to origin, COV.sourceValue to source, COV.orphan to orphan,
        )

    private fun field(name: String, base: String?, value: String, config: String = "acmeClient", origin: String = "source") =
        mapOf(COV.field to name, COV.baseValue to base, COV.value to value, COV.configName to config, COV.origin to origin)

    private fun blockRow(block: String, item: String?, added: Boolean, hidden: Boolean, vararg fields: Map<String, Any?>) =
        mapOf(COV.blockId to block, COV.path to "items", COV.itemId to item, COV.added to added, COV.hidden to hidden, COV.fields to fields.toList())

    private fun acmeOverrides() = parseClientOverrides(
        mapOf(
            COV.client to "acme",
            COV.copy to listOf(
                copyRow("home", "home", "brand", "KDR", "Acme Co", "edits", "stored", source = "ACME KDR"),
                copyRow("home", "home", "renamed", null, "Stale", "acmeClient", "source", orphan = true),
                // No key: not an override.
                mapOf(COV.fileId to "home", COV.namespaceField to "home"),
            ),
            COV.blocks to listOf(
                blockRow("homeMenu", "cfactReference", false, true, field(UIB.cfactExpression, null, "#never")),
                blockRow("homeMenu", "workflows", false, false, field(UIB.cfactExpression, "#never", "loggedIn,app")),
                blockRow("sampleNav", "overview", false, false, field("label", "Overview", "Acme overview")),
                blockRow("sampleNav", "siteAudits", true, false, field("label", null, "Site audits"), field(UIB.displayOrder, null, "150")),
                blockRow("homeMenu", null, true, false, field("label", null, "Stray")),
                // No block: not an override.
                mapOf(COV.itemId to "x"),
            ),
        ),
    )

    @Test
    fun theOverridesParseAndDropWhatIsNotOne() {
        val acme = acmeOverrides()
        assertEquals("acme", acme.clientId)
        assertEquals(listOf("home: home.brand", "home: home.renamed"), acme.copy.map { copyKeyText(it) })
        val brand = acme.copy[0]
        assertEquals("KDR" to "Acme Co", brand.baseValue to brand.value)
        assertEquals("ACME KDR", brand.sourceValue)
        assertEquals(false, brand.orphan)
        assertEquals(true, acme.copy[1].orphan)
        assertEquals(null, acme.copy[1].baseValue)
        assertEquals(5, acme.blocks.size)
        assertEquals("homeMenu: cfactReference", blockItemText(acme.blocks[0]))
        assertEquals("homeMenu: (new item)", blockItemText(acme.blocks[4]))
    }

    @Test
    fun aMenuChangeReadsAsWhatTheClientDid() {
        val b = acmeOverrides().blocks
        assertEquals("hidden", menuChangeText(b[0]))
        assertEquals("shown", menuChangeText(b[1]))
        assertEquals("renamed", menuChangeText(b[2]))
        assertEquals("added", menuChangeText(b[3]))
        // Several at once read together; a field with no word of its own is "changed".
        val renamedAndHidden = BlockOverrideView("m", "items", "x", added = false, hidden = true, fields = listOf(
            BlockFieldView("label", "A", "B", "c", "source"), BlockFieldView(UIB.cfactExpression, null, "#never", "c", "source"),
        ))
        assertEquals("hidden, renamed", menuChangeText(renamedAndHidden))
        val other = BlockOverrideView("m", "items", "x", added = false, hidden = false, fields = listOf(BlockFieldView("icon", "a", "b", "c", "source")))
        assertEquals("changed", menuChangeText(other))
        // The client's value: the label when set, the one field's value otherwise, all of them as a last resort.
        assertEquals("Acme overview", blockValueText(b[2]))
        assertEquals("loggedIn,app", blockValueText(b[1]))
        assertEquals("b", blockValueText(other))
        assertEquals("Site audits", blockValueText(b[3]))
        assertEquals("icon: a, ${UIB.displayOrder}: 1", blockValueText(BlockOverrideView("m", "items", "x", added = true, hidden = false, fields = listOf(
            BlockFieldView("icon", null, "a", "c", "source"), BlockFieldView(UIB.displayOrder, null, "1", "c", "source"),
        ))))
        // In a phrase, for the view across clients: the words, and the label when one was set.
        assertEquals("hidden", blockSummaryText(b[0]))
        assertEquals("renamed: Acme overview", blockSummaryText(b[2]))
        assertEquals("added: Site audits", blockSummaryText(b[3]))
    }

    @Test
    fun whoSetAValueReadsWithWhereTheConfigLives() {
        assertEquals("acmeClient (source)", setByText("acmeClient", "source"))
        assertEquals("edits (stored)", setByText("edits", "stored"))
        assertEquals("source", setByText(null, "source"))
        assertEquals("acmeClient", setByText("acmeClient", ""))
        assertEquals("acmeClient (source)", blockSetByText(acmeOverrides().blocks[2]))
    }

    @Test
    fun theCustomizedColumnCountsBothHalvesAndLinksOnlyWhenThereIsSomething() {
        assertEquals("3 copy, 2 menu", customizedText(3, 2))
        assertEquals("3 copy", customizedText(3, 0))
        assertEquals("2 menu", customizedText(0, 2))
        assertEquals("\u2014", customizedText(0, 0))
        assertEquals("#${HP.page}=${HMENU.pageClients}&${HP.client}=acme", clientOverridesHref("acme"))
        assertEquals("#${HP.page}=${HMENU.pageClients}&${HP.overrides}=1", overridesAcrossHref())
    }

    @Test
    fun theViewAcrossClientsGroupsEachKeyWithWhoOverridesIt() {
        val globex = parseClientOverrides(
            mapOf(
                COV.client to "globex",
                COV.copy to listOf(copyRow("mail", "common", "footer", "Sent by KDR.", "Sent by Globex.", "globexClient", "source")),
                COV.blocks to listOf(blockRow("homeMenu", "cfactReference", false, true, field(UIB.cfactExpression, null, "#never"))),
            ),
        )
        val acme = acmeOverrides()
        val keys = overridesAcrossClients(listOf("acme" to acme, "globex" to globex))
        // Grouped by file or block then key, sorted, with the clients in the order given.
        assertEquals(
            listOf("home|home.brand", "home|home.renamed", "homeMenu|(new item)", "homeMenu|cfactReference", "homeMenu|workflows", "mail|common.footer", "sampleNav|overview", "sampleNav|siteAudits"),
            keys.map { "${it.group}|${it.key}" },
        )
        assertEquals(listOf("acme" to "hidden", "globex" to "hidden"), keys.first { it.key == "cfactReference" }.clients)
        assertEquals(listOf("globex" to "Sent by Globex."), keys.first { it.key == "common.footer" }.clients)
        assertEquals(listOf("acme" to "renamed: Acme overview"), keys.first { it.key == "overview" }.clients)
        assertEquals(emptyList<KeyAcrossClients>(), overridesAcrossClients(emptyList()))
    }

    @Test
    fun theCountsReadWithTheirQualifiers() {
        assertEquals("12", userCountText(12, 0))
        assertEquals("12 (3 unclaimed)", userCountText(12, 3))
        assertEquals("4", workflowsText(4, false))
        assertEquals("4, survey", workflowsText(4, true))
    }
}
