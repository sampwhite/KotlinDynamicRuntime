package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.home.PFRAG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The client page's layout (issue #1397): its header, the definition's groups, and the Menu panel's filter. */
class ClientDetailLayoutTest {
    private fun row(sandboxOf: String? = null, hasSandbox: Boolean = false, staticHere: Boolean = false) = ClientOverview(
        "acme", "Acme", ClientStatus.present.name, GedraConfigOrigin.stored.name, 1, 3, 5, 2, 4, true, emptyList(), 0, 0,
        sandboxOf = sandboxOf, hasSandbox = hasSandbox, publishedOnly = hasSandbox || sandboxOf != null, staticHere = staticHere,
    )

    private fun definition() = parseClientDefinition(
        mapOf(
            CLD.client to mapOf(CLD.clientId to "acme", CLD.name to "Acme", CLD.usageType to "production", CLD.audience to "customer"),
            CLD.present to true,
            CLD.traits to listOf(mapOf(CCT.traitId to "acmeSiteAudit")),
        ),
    )

    @Test
    fun theDefinitionIsGroupedAsItsEditorIs() {
        val groups = clientSummaryGroups("acme", row(), definition(), canSeeAllClients = true)
        assertEquals(listOf("About", "How it behaves", "Platform", runsGroupHeading), groups.map { it.heading })
        assertEquals(listOf(PFRAG.aboutNs, PFRAG.behaviorNs, PFRAG.platformNs, PFRAG.runsNs), groups.map { it.explanationNs })
        assertEquals(listOf("Client id", "Name", "Description"), groups[0].rows.map { it.first })
        assertEquals("customer", groups[2].rows.toMap()["Audience"])
        assertEquals("Stored", groups[3].rows.toMap()["Definition"])
        // The traits are listed only where their own section is not drawn.
        assertEquals("acmeSiteAudit", groups[1].rows.toMap()["Traits"])
        val withPanel = clientSummaryGroups("acme", row(), definition(), canSeeAllClients = true, traitsShownBelow = true)
        assertNull(withPanel[1].rows.toMap()["Traits"])
        // With no definition (a client this node does not carry), only what the listing knows; an empty group goes.
        assertEquals(listOf("About", runsGroupHeading), clientSummaryGroups("acme", row(), null, canSeeAllClients = true).map { it.heading })
        assertEquals(listOf("About"), clientSummaryGroups("acme", null, null, canSeeAllClients = true).map { it.heading })
    }

    @Test
    fun theHeaderSaysWhatTheClientIsAndHowASaveTakesEffect() {
        assertEquals(listOf("Loaded"), clientStatusChips(row(), 0))
        assertEquals(listOf("Loaded, 2 issues forgiven", "Has a sandbox"), clientStatusChips(row(hasSandbox = true), 2))
        assertEquals(listOf("Loaded", "Sandbox of acme"), clientStatusChips(row(sandboxOf = "acme"), 0))
        assertEquals(emptyList(), clientStatusChips(null, 0))

        assertEquals("Changes made here are live for the client's people at once.", clientSaveEffectLine(row(), savesDraft = false))
        assertEquals(true, clientSaveEffectLine(row(hasSandbox = true), savesDraft = true)!!.startsWith("Changes made here are drafts"))
        assertEquals(true, clientSaveEffectLine(row(sandboxOf = "acme"), savesDraft = true)!!.contains("acme's configuration"))
        // A client that takes nothing stored here saves nothing from this page.
        assertNull(clientSaveEffectLine(row(staticHere = true), savesDraft = false))
    }

    @Test
    fun theBannerCountsWhatCouldBePublished() {
        fun config(name: String, published: Boolean) = ConfigSummaryView(name, 2, published, null, null, 0, publishedVersion = 1.takeIf { published })
        assertNull(unpublishedBannerText(row(), null))
        assertNull(unpublishedBannerText(row(), listOf(config("main", true))))
        assertEquals("1 configuration has unpublished changes.", unpublishedBannerText(row(), listOf(config("main", false), config("copy", true))))
        assertEquals("2 configurations have unpublished changes.", unpublishedBannerText(row(), listOf(config("main", false), config("copy", false))))
        // A client that takes nothing stored here has nothing to publish.
        assertNull(unpublishedBannerText(row(staticHere = true), listOf(config("main", false))))
    }

    @Test
    fun theMenuListsWhatTheClientsPeopleMayBeOffered() {
        fun item(id: String, parentId: String? = null, deploymentOnly: Boolean = false, stored: Boolean = false) =
            MenuItemView(id, parentId, id, id, null, null, stored = stored, deploymentOnly = deploymentOnly)
        val items = listOf(
            item("account"),
            item("profile", parentId = "account"),
            item("becomeUser", parentId = "account", deploymentOnly = true),
            item("operator", deploymentOnly = true),
            item("systemInfo", parentId = "operator", deploymentOnly = true),
            item("envReference", parentId = "operator", deploymentOnly = true, stored = true),
            item("users"),
        )
        // The deployment's own items go -- except one the client changed, which keeps its group with it.
        assertEquals(listOf("account", "profile", "operator", "envReference", "users"), menuItemsForPeople(items).map { it.itemId })
        assertEquals(emptyList(), menuItemsForPeople(emptyList()))

        assertEquals("Shipped as is", menuPanelSummary(0))
        assertEquals("1 item changed by this client", menuPanelSummary(1))
        assertEquals("3 items changed by this client", menuPanelSummary(3))
    }
}
