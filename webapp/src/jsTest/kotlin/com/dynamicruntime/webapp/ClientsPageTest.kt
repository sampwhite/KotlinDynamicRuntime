package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.MNU
import com.dynamicruntime.common.gedra.GCI
import com.dynamicruntime.common.gedra.UF
import com.dynamicruntime.common.gedra.ClientPresentationFields
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.home.HFLD
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

    private fun copyRow(file: String, ns: String, key: String, base: String?, value: String, config: String, origin: String, source: String? = null, orphan: Boolean = false, shownOn: String? = "the home page") =
        mapOf(
            COV.fileId to file, COV.namespaceField to ns, COV.key to key, COV.audience to "frontend", COV.baseValue to base,
            COV.value to value, COV.configName to config, COV.origin to origin, COV.sourceValue to source, COV.orphan to orphan,
            COV.shownOn to shownOn,
        )

    private fun field(name: String, base: String?, value: String, config: String = "acmeClient", origin: String = "source") =
        mapOf(COV.field to name, COV.baseValue to base, COV.value to value, COV.configName to config, COV.origin to origin)

    private fun blockRow(block: String, item: String?, added: Boolean, hidden: Boolean, vararg fields: Map<String, Any?>, baseLabel: String? = null) =
        mapOf(COV.blockId to block, COV.path to "items", COV.itemId to item, COV.added to added, COV.hidden to hidden, COV.baseLabel to baseLabel, COV.fields to fields.toList())

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
                blockRow("homeMenu", "cfactReference", false, true, field(UIB.cfactExpression, null, "#never"), baseLabel = "Client facts"),
                blockRow("homeMenu", "workflows", false, false, field(UIB.cfactExpression, "#never", "kdr:loggedIn,kdr:app"), baseLabel = "Workflows"),
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
        assertEquals("Client facts", acme.blocks[0].baseLabel)
        assertEquals(null, acme.blocks[3].baseLabel)
        assertEquals("homeMenu: cfactReference", blockItemText(acme.blocks[0]))
        assertEquals("homeMenu: (new item)", blockItemText(acme.blocks[4]))
        assertEquals("(block)", blockItemName(BlockOverrideView("m", "", null, added = false, hidden = false, baseLabel = null, fields = emptyList())))
        assertEquals("m: nav", blockItemText(BlockOverrideView("m", "nav", null, added = false, hidden = false, baseLabel = null, fields = emptyList())))
    }

    @Test
    fun aMenuChangeReadsAsWhatTheClientDid() {
        val b = acmeOverrides().blocks
        assertEquals("hidden", menuChangeText(b[0]))
        assertEquals("shown", menuChangeText(b[1]))
        assertEquals("renamed", menuChangeText(b[2]))
        assertEquals("added", menuChangeText(b[3]))
        // Several at once read together; a field with no word of its own is "changed".
        val renamedAndHidden = BlockOverrideView("m", "items", "x", added = false, hidden = true, baseLabel = "A", fields = listOf(
            BlockFieldView(HFLD.label, "A", "B", "c", "source"), BlockFieldView(UIB.cfactExpression, null, "#never", "c", "source"),
        ))
        assertEquals("hidden, renamed", menuChangeText(renamedAndHidden))
        val other = BlockOverrideView("m", "items", "x", added = false, hidden = false, baseLabel = "A", fields = listOf(BlockFieldView("icon", "a", "b", "c", "source")))
        assertEquals("changed", menuChangeText(other))
        // A condition that neither hides nor un-hides -- narrowed, or `#never` on an item the base already withdraws
        // (which #916 does not call hidden) -- is a change to the condition, not "shown".
        fun condition(base: String?, value: String) = BlockOverrideView("m", "items", "x", added = false, hidden = false, baseLabel = "A",
            fields = listOf(BlockFieldView(UIB.cfactExpression, base, value, "c", "source")))
        assertEquals("condition changed", menuChangeText(condition("kdr:loggedIn", "kdr:hasAdminLevel")))
        assertEquals("condition changed", menuChangeText(condition("#never", "#never")))
        assertEquals("shown", menuChangeText(condition("#never", "#always")))
        // The client's value: the label when set; nothing for a hide or a show (the condition is not a value anybody
        // reads); the one field's value otherwise, all of them as a last resort.
        assertEquals("Acme overview", blockValueText(b[2]))
        assertEquals("", blockValueText(b[0]))
        assertEquals("", blockValueText(b[1]))
        assertEquals("kdr:hasAdminLevel", blockValueText(condition("kdr:loggedIn", "kdr:hasAdminLevel")))
        assertEquals("b", blockValueText(other))
        assertEquals("Site audits", blockValueText(b[3]))
        assertEquals("icon: a, ${UIB.displayOrder}: 1", blockValueText(BlockOverrideView("m", "items", "x", added = true, hidden = false, baseLabel = null, fields = listOf(
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
        // A value inherited from the template the client extends (issue #945) names the template, not the origin.
        assertEquals("base (template starter)", setByText("base", "source", "starter"))
        assertEquals("template starter", setByText(null, "source", "starter"))
        val inherited = parseClientOverrides(
            mapOf(
                COV.client to "kid",
                COV.copy to listOf(
                    mapOf(COV.fileId to "home", COV.namespaceField to "home", COV.key to "brand", COV.value to "TPL",
                        COV.configName to "base", COV.origin to "source", COV.template to "starter"),
                ),
                COV.blocks to listOf(
                    mapOf(COV.blockId to "m", COV.path to "items", COV.itemId to "x", COV.fields to listOf(
                        mapOf(COV.field to HFLD.label, COV.value to "X", COV.configName to "base", COV.origin to "source",
                            COV.template to "starter"),
                    )),
                ),
            ),
        )
        assertEquals("starter", inherited.copy.single().template)
        assertEquals("base (template starter)", blockSetByText(inherited.blocks.single()))
        assertEquals("acmeClient (source)", blockSetByText(acmeOverrides().blocks[2]))
        // A row two configs set names both, the stored one first: applied last, its values are the ones that win.
        val mixed = BlockOverrideView("m", "items", "x", added = false, hidden = false, baseLabel = "A", fields = listOf(
            BlockFieldView(HFLD.label, "A", "B", "edits", "stored"), BlockFieldView(UIB.cfactExpression, null, "kdr:loggedIn", "acmeClient", "source"),
            BlockFieldView(UIB.displayOrder, null, "5", "acmeClient", "source"),
        ))
        assertEquals("edits (stored), acmeClient (source)", blockSetByText(mixed))
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

    // --- editing the copy (issue #918) ---------------------------------------------------------------------

    @Test
    fun theCopyKeysParseAndTheAddablesLeaveOutWhatIsAlreadyOverridden() {
        val keys = parseCopyKeys(
            listOf(
                mapOf(COV.fileId to "sampleContent", COV.namespaceField to "welcome", COV.key to "title", COV.audience to "frontend", COV.value to "Welcome"),
                mapOf(COV.fileId to "home", COV.namespaceField to "home", COV.key to "brand", COV.audience to "frontend", COV.value to "ACME KDR", COV.shownOn to "the app bar"),
                mapOf(COV.fileId to "home", COV.namespaceField to "home", COV.key to "title", COV.audience to "frontend", COV.value to "Welcome", COV.shownOn to "the app bar"),
                mapOf(COV.fileId to "mail", COV.namespaceField to "common", COV.key to "footer", COV.audience to "backend", COV.value to "Sent by KDR.", COV.shownOn to "the mails"),
                // No key: not a key.
                mapOf(COV.fileId to "home", COV.namespaceField to "home"),
            ),
        )
        assertEquals(listOf("sampleContent:welcome.title", "home:home.brand", "home:home.title", "mail:common.footer"), keys.map { "${it.fileId}:${it.namespace}.${it.key}" })
        assertEquals("backend", keys[3].audience)
        assertEquals(null, keys[0].shownOn)
        // acme already overrides home.brand: the picker offers the rest.
        val addable = addableCopyKeys(keys, acmeOverrides().copy)
        assertEquals(listOf("sampleContent:welcome.title", "home:home.title", "mail:common.footer"), addable.map { "${it.fileId}:${it.namespace}.${it.key}" })
        // The files the picker offers: shown ones first, each saying where; the fixture last, with nothing (issue #933).
        val files = copyFileChoices(addable)
        assertEquals(listOf("home", "mail", "sampleContent"), files.map { it.fileId })
        assertEquals(listOf("home \u2014 the app bar", "mail \u2014 the mails", "sampleContent"), files.map { copyFileLabel(it) })
        assertEquals(null, files[2].shownOn)
    }

    @Test
    fun aSetOrResetRequestCarriesTheAddressAndTheValueOnlyWhenSetting() {
        assertEquals(
            mapOf(COV.client to "acme", COV.fileId to "home", COV.namespaceField to "home", COV.key to "brand", COV.value to "Acme Co"),
            copyEditRequest("acme", "home", "home", "brand", "Acme Co"),
        )
        // An empty value is a value; only null means "no value" (a reset).
        assertEquals("", copyEditRequest("acme", "home", "home", "brand", "")[COV.value])
        assertEquals(false, copyEditRequest("acme", "home", "home", "brand", null).containsKey(COV.value))
    }

    @Test
    fun onlyAStoredValueOffersAReset() {
        val rows = acmeOverrides().copy
        assertEquals(true, copyRowResettable(rows[0]))   // stored over source
        assertEquals(false, copyRowResettable(rows[1]))  // set in source: overridable, not removable
    }

    @Test
    fun theEditResultParses() {
        val result = parseCopyEditResult(
            mapOf(COV.configName to "copy", COV.value to "Acme Co", CPY.stored to true, CPY.issues to listOf(mapOf(GCI.message to "m"))),
        )
        assertEquals("copy", result.configName)
        assertEquals("Acme Co", result.value)
        assertEquals(true, result.stored)
        assertEquals(listOf("m"), result.issues)
        val reset = parseCopyEditResult(mapOf(COV.configName to "copy", CPY.stored to false))
        assertEquals(null, reset.value)
        assertEquals(false, reset.stored)
        // How the save took effect (issue #930): live unless the result says it stayed a draft.
        assertEquals(EDM.live, reset.mode)
        assertEquals(EDM.draft, parseCopyEditResult(mapOf(COV.configName to "copy", CPY.mode to EDM.draft)).mode)
        assertEquals(EDM.draft, parseMenuEditResult(mapOf(COV.configName to "copy", CPY.mode to EDM.draft)).mode)
    }

    // A draft says where it can be seen and how it goes live; a live save says only what changed (issue #930).
    @Test
    fun aDraftSaveSaysWhereItShowsAndHowItGoesLive() {
        assertEquals("Saved x.", savedNote("Saved x.", EDM.live))
        assertEquals(
            "Saved x. Saved as a draft: the client's sandbox shows it, and it goes live once published.",
            savedNote("Saved x.", EDM.draft),
        )
    }

    // --- editing the definition (issue #1026) ----------------------------------------------------------------

    private val storedInfo = mapOf(
        CLD.clientId to "globex", CLD.name to "Globex", CLD.description to "A note.", CLD.domainPrefix to "globex",
        CLD.userLabels to listOf("reviewer", "auditor"),
    )

    @Test
    fun theDraftOpensOnTheStoredDefinitionAndTheRequestCarriesOnlyWhatChanged() {
        val draft = definitionDraftOf(storedInfo)
        assertEquals(ClientPresentationFields.names, draft.keys.toList())
        assertEquals("Globex", draft[CLD.name])
        assertEquals("", draft[CLD.customDomain])
        assertEquals("reviewer, auditor", draft[CLD.userLabels])
        // Untouched: nothing but the client.
        val same = definitionEditRequest("globex", storedInfo, draft)
        assertEquals(mapOf(CLD.client to "globex"), same)
        assertEquals(false, definitionEditChanges(same))
        // A rename trimmed, the note cleared (sent blank, so the backend clears it), the labels re-read as a list --
        // each once and trimmed -- and the fields left alone not sent.
        val edited = draft + mapOf(CLD.name to " Globex Corp ", CLD.description to "  ", CLD.userLabels to "auditor, reviewer ,auditor,,")
        val request = definitionEditRequest("globex", storedInfo, edited)
        assertEquals(true, definitionEditChanges(request))
        assertEquals(
            mapOf(CLD.client to "globex", CLD.name to "Globex Corp", CLD.description to "", CLD.userLabels to listOf("auditor", "reviewer")),
            request,
        )
        // A label line that only re-spells the same list is not a change.
        assertEquals(listOf("reviewer", "auditor"), labelsOfText(" reviewer,auditor , reviewer"))
        assertEquals(false, definitionEditChanges(definitionEditRequest("globex", storedInfo, draft + (CLD.userLabels to " reviewer,auditor "))))
    }

    /** The retrieve's stored definition (issue #1026) is the editor's baseline; a source-defined client has none. */
    @Test
    fun theStoredDefinitionParsesWhenPresent() {
        assertEquals(null, acmeDefinition().stored)
        val withStored = parseClientDefinition(mapOf(CLD.client to storedInfo, CLD.storedDefinition to mapOf(CLD.name to "Draft"), CLD.storedDefinitionConfig to "main", CLD.present to true))
        assertEquals("Draft", withStored.stored?.get(CLD.name))
        assertEquals("main", withStored.storedConfig)
        assertEquals("Globex", withStored.info[CLD.name])
    }

    @Test
    fun theEditorIsOfferedOnlyWhereASaveCouldLand() {
        fun row(status: String = ClientStatus.present.name, origin: String = GedraConfigOrigin.stored.name, sandboxOf: String? = null, staticHere: Boolean = false) =
            ClientOverview("globex", "Globex", status, origin, 1, 0, 0, 0, 0, false, emptyList(), 0, 0, sandboxOf = sandboxOf, staticHere = staticHere)
        assertEquals(true, definitionEditable(row()))
        // A source definition is edited in source; a sandbox's is its parent's; a static client takes nothing stored;
        // a client this node does not carry has nothing to reload; and before the listing answers there is no row.
        assertEquals(false, definitionEditable(row(origin = GedraConfigOrigin.source.name)))
        assertEquals(false, definitionEditable(row(sandboxOf = "globex")))
        assertEquals(false, definitionEditable(row(staticHere = true)))
        assertEquals(false, definitionEditable(row(status = ClientStatus.dropped.name)))
        assertEquals(false, definitionEditable(null))
    }

    /** The note before the attempt (issue #1026): only when the definition's config is unpublished and saves would publish. */
    @Test
    fun theEditorIsHeldBackWhileTheDefinitionsConfigHasUnpublishedChanges() {
        fun config(name: String, published: Boolean) = ConfigSummaryView(name, 2, published, null, null, 0)
        val drafted = listOf(config("main", published = false), config("copy", published = true))
        val held = definitionEditOffer("main", drafted, sandbox = false)
        assertEquals(false, held.editor)
        assertEquals(true, held.note?.contains("'main'") == true && held.note.contains("Publish it first"))
        // Published, held in another config, or a definition asking for a sandbox (its saves are drafts): the editor.
        for (offer in listOf(
            definitionEditOffer("main", listOf(config("main", published = true)), sandbox = false),
            definitionEditOffer("copy", drafted, sandbox = false),
            definitionEditOffer("main", drafted, sandbox = true),
        )) {
            assertEquals(true, offer.editor)
            assertEquals(null, offer.note)
        }
        // Not yet known -- the configs still loading, or no stored definition: neither, so an editor is never opened
        // and then replaced by the note.
        for (offer in listOf(definitionEditOffer("main", null, sandbox = false), definitionEditOffer(null, drafted, sandbox = false))) {
            assertEquals(false, offer.editor)
            assertEquals(null, offer.note)
        }
        // The row the note sends people to is the one offered Publish.
        val row = ClientOverview("globex", "Globex", ClientStatus.present.name, GedraConfigOrigin.stored.name, 1, 0, 0, 0, 0, false, emptyList(), 0, 0)
        assertEquals(true, configRowNeedsPublish(row, config("main", published = false)))
        assertEquals(false, configRowNeedsPublish(row, config("main", published = true)))
    }

    @Test
    fun theDefinitionEditResultParses() {
        val result = parseDefinitionEditResult(
            mapOf(
                COV.configName to "main", CLD.definition to mapOf(CLD.name to "Globex Corp"), CPY.mode to EDM.draft,
                CPY.issues to listOf(mapOf(GCI.message to "Old problem."), mapOf("other" to 1)),
            ),
        )
        assertEquals("main", result.configName)
        assertEquals("Globex Corp", result.info[CLD.name])
        assertEquals(EDM.draft, result.mode)
        assertEquals(listOf("Old problem."), result.issues)
        assertEquals(EDM.live, parseDefinitionEditResult(emptyMap()).mode)
    }

    // --- editing the menu (issue #919) ---------------------------------------------------------------------

    private fun menuRow(id: String, base: String?, label: String?, baseCond: String?, cond: String?, stored: Boolean = false, parent: String? = null) =
        mapOf(COV.itemId to id, MNU.parentId to parent, COV.baseLabel to base, MNU.label to label, MNU.baseCondition to baseCond, MNU.condition to cond, CPY.stored to stored)

    private fun sampleMenu() = parseMenuItems(
        listOf(
            menuRow("account", "Account", "Account", "kdr:app", "kdr:app"),
            menuRow("profile", "Profile", "My account", "kdr:loggedIn,kdr:app", "kdr:loggedIn,kdr:app", stored = true, parent = "account"),
            menuRow("catalog", "Endpoint catalog", "Endpoint catalog", null, null),
            menuRow("docs", "Documents", "Documents", "kdr:app", "#never", stored = true),
            menuRow("workflows", "Workflows", "Workflows", "#never", "kdr:loggedIn,kdr:app"),
            mapOf(MNU.label to "no id"),
        ),
    )

    @Test
    fun theMenuItemsParseAndSayHowEachIsOffered() {
        val items = sampleMenu()
        assertEquals(listOf("account", "profile", "catalog", "docs", "workflows"), items.map { it.itemId })
        assertEquals("account", items[1].parentId)
        assertEquals(true, items[1].stored)
        assertEquals("kdr:app", menuVisibilityText(items[0]))
        assertEquals("everyone", menuVisibilityText(items[2]))
        assertEquals("hidden (shipped: kdr:app)", menuVisibilityText(items[3]))
        assertEquals("kdr:loggedIn,kdr:app (shipped: hidden)", menuVisibilityText(items[4]))
        assertEquals(listOf(false, false, false, true, false), items.map { menuItemHidden(it) })
        // The audiences a client may show an item to: everyone, then each condition the shipped menu draws for, once.
        assertEquals(listOf("#always", "kdr:app", "kdr:loggedIn,kdr:app"), menuAudiences(items))
        // The groups: what some other item sits under; those get no Hide.
        assertEquals(setOf("account"), menuGroups(items))
    }

    @Test
    fun aMenuEditRequestCarriesOnlyWhatIsAsked() {
        assertEquals(mapOf(COV.client to "acme", COV.itemId to "profile", MNU.label to "Me"), menuEditRequest("acme", "profile", "Me", null, null))
        assertEquals(mapOf(COV.client to "acme", COV.itemId to "docs", MNU.visibility to MNU.hide), menuEditRequest("acme", "docs", null, MNU.hide, null))
        assertEquals(
            mapOf(COV.client to "acme", COV.itemId to "workflows", MNU.visibility to MNU.show, MNU.condition to "kdr:loggedIn,kdr:app"),
            menuEditRequest("acme", "workflows", null, MNU.show, "kdr:loggedIn,kdr:app"),
        )
        val result = parseMenuEditResult(mapOf(COV.configName to "copy", MNU.label to "Me", MNU.condition to "#never", CPY.stored to true))
        assertEquals("copy" to "Me", result.configName to result.label)
        assertEquals("#never", result.condition)
        assertEquals(true, result.stored)
    }

    @Test
    fun theCountsReadWithTheirQualifiers() {
        assertEquals("12", userCountText(12, 0))
        assertEquals("12 (3 unclaimed)", userCountText(12, 3))
        assertEquals("4", workflowsText(4, false))
        assertEquals("4, survey", workflowsText(4, true))
    }
}
