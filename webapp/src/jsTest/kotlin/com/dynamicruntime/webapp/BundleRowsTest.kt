package com.dynamicruntime.webapp

import com.dynamicruntime.common.content.FragmentAudience
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client detail's configuration rows (issue #1001): whether each is live, and what it offers -- and the copy
 * editor's syntax hint.
 */
class BundleRowsTest {
    private fun bundle(version: Int, published: Boolean, publishedVersion: Int? = null) =
        ConfigSummaryView("main", version, published, null, null, 0, publishedVersion)

    private fun client(
        id: String = "acme",
        sandboxOf: String? = null,
        hasSandbox: Boolean = false,
        publishedOnly: Boolean = false,
        staticHere: Boolean = false,
    ) = ClientOverview(
        clientId = id, name = id, status = ClientStatus.present.name, origin = GedraConfigOrigin.stored.name,
        storedConfigs = 1, forms = 0, users = 0, unclaimedUsers = 0, workflowCount = 0, hasSurvey = false,
        issues = emptyList(), copyOverrides = 0, blockOverrides = 0, sandboxOf = sandboxOf, hasSandbox = hasSandbox,
        publishedOnly = publishedOnly, staticHere = staticHere,
    )

    @Test
    fun theWireCarriesThePublishedVersionAndTheClientsTier() {
        val parsed = parseConfigSummaries(listOf(mapOf(CFEP.name to "main", CFEP.version to 3, CFEP.published to false, CFEP.publishedVersion to 2)))
        assertEquals(2, parsed.single().publishedVersion)
        val row = parseClientOverview(listOf(mapOf(CLD.clientId to "acme", CLD.publishedOnly to true, CLD.staticHere to true))).single()
        assertTrue(row.publishedOnly)
        assertTrue(row.staticHere)
    }

    @Test
    fun aRowSaysWhetherItsLatestRevisionIsLiveByTheClientsTier() {
        assertEquals("Live", bundleLiveText(bundle(2, published = true), publishedOnly = true))
        assertEquals("v3 draft; v2 live", bundleLiveText(bundle(3, published = false, publishedVersion = 2), publishedOnly = true))
        assertEquals("Draft; not live", bundleLiveText(bundle(1, published = false), publishedOnly = true))
        assertEquals("Live, unpublished", bundleLiveText(bundle(3, published = false, publishedVersion = 2), publishedOnly = false))
    }

    @Test
    fun publishIsOfferedWhereItMeansSomethingAndExplainedWhereItIsNot() {
        val draft = bundle(2, published = false, publishedVersion = 1)
        // A published revision offers nothing.
        assertFalse(bundleAction(client(), bundle(2, published = true)).publish)
        // A client without a sandbox publishes; on its latest revision it says that changes nothing it runs.
        assertTrue(bundleAction(client(publishedOnly = true), draft).publish)
        assertNull(bundleAction(client(publishedOnly = true), draft).note)
        assertTrue(bundleAction(client(), draft).publish)
        assertTrue("latest revision" in bundleAction(client(), draft).note!!)
        // A client with a sandbox publishes from it, after previewing, and the row points there.
        val withSandbox = bundleAction(client(hasSandbox = true, publishedOnly = true), draft)
        assertFalse(withSandbox.publish)
        assertEquals("acme:sandbox", withSandbox.sandboxClient)
        // A sandbox publishes its parent's, which it runs.
        val inSandbox = bundleAction(client("acme:sandbox", sandboxOf = "acme"), draft)
        assertTrue(inSandbox.publish)
        assertTrue("acme" in inSandbox.note!!)
        // A client static here takes nothing stored: publishing means nothing.
        assertFalse(bundleAction(client(staticHere = true), draft).publish)
    }

    // The copy editor says which template syntax a value takes, by who its file is for (issue #1001).
    @Test
    fun theCopyEditorsHintSaysWhichSyntaxTheFileTakes() {
        val frontend = copySyntaxHint(FragmentAudience.frontend.name)
        assertTrue("\${user.publicName}" in frontend)
        assertTrue("%{...} is refused" in frontend)
        val backend = copySyntaxHint(FragmentAudience.backend.name)
        assertTrue("resolved on the server" in backend)
        assertTrue("%{@t(" in backend)
        assertEquals("Markdown.", copySyntaxHint("somethingNew"))
    }

    @Test
    fun aSandboxListsItsParentsConfiguration() {
        assertEquals(ACEP.bundles + "?client=acme", storedConfigsPath("acme:sandbox", acrossClients = true, ownClient = "hub"))
        // The scoped listing, asked in the sandbox, lists the parent's already.
        assertEquals(CFEP.bundles, storedConfigsPath("acme:sandbox", acrossClients = false, ownClient = "acme:sandbox"))
    }
}
