package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The clients page's sandbox pieces (issue #932): the listing's pairing and note, and the detail's section. */
class SandboxClientsTest {
    private fun row(
        id: String,
        sandboxOf: String? = null,
        sandboxOfName: String? = null,
        hasSandbox: Boolean = false,
        origin: GedraConfigOrigin = GedraConfigOrigin.stored,
        status: ClientStatus = ClientStatus.present,
        sandboxInCode: Boolean = false,
        sandboxLocked: String? = null,
    ) = parseClientOverview(
        listOf(
            buildMap {
                put(CLD.clientId, id); put(CLD.name, id); put(CLD.status, status.name); put(CLD.origin, origin.name)
                put(CLD.hasSandbox, hasSandbox)
                if (sandboxInCode) put(CLD.sandboxInCode, true)
                sandboxLocked?.let { put(CLD.sandboxLocked, it) }
                sandboxOf?.let { put(CLD.sandboxOf, it) }
                sandboxOfName?.let { put(CLD.sandboxOfName, it) }
            },
        ),
    ).single()

    @Test
    fun theOverviewCarriesWhoseSandboxARowIsAndWhetherAClientHasOne() {
        assertEquals("acme", row("acme:sandbox", sandboxOf = "acme").sandboxOf)
        assertTrue(row("acme", hasSandbox = true).hasSandbox)
        assertNull(row("globex").sandboxOf)
    }

    @Test
    fun eachSandboxListsRightAfterItsParent() {
        val rows = listOf(row("acme:sandbox", "acme"), row("globex"), row("acme", hasSandbox = true), row("lost:sandbox", "lost"))
        assertEquals(listOf("globex", "acme", "acme:sandbox", "lost:sandbox"), withSandboxesBesideParents(rows).map { it.clientId })
        assertEquals("sandbox of acme", sandboxRowNote(row("acme:sandbox", "acme")))
        assertNull(sandboxRowNote(row("acme")))
        // By its name when the row carries one (issue #1438): the words name the client as its people know it.
        assertEquals("sandbox of Acme", sandboxRowNote(row("acme:sandbox", "acme", sandboxOfName = "Acme")))
        assertTrue(sandboxControl(row("acme:sandbox", "acme", sandboxOfName = "Acme")).text.startsWith("This is the sandbox of Acme:"))
    }

    @Test
    fun theDetailOffersAddingOrRemovingOnlyWhereTheFlagIsStoredHere() {
        assertEquals(true, sandboxControl(row("acme")).add)
        assertEquals(false, sandboxControl(row("acme", hasSandbox = true)).add)
        assertTrue("published" in sandboxControl(row("acme")).text)
        // A sandbox says whose it is; a source-defined client says where its flag is set; an absent one offers nothing.
        val sandbox = sandboxControl(row("acme:sandbox", "acme"))
        assertNull(sandbox.add)
        assertTrue(sandbox.text.startsWith("This is the sandbox of acme"))
        // A client defined in code (issue #1677): its own sandbox is added and removed here, unless its code sets it.
        val code = sandboxControl(row("hub", origin = GedraConfigOrigin.source))
        assertEquals(true, code.add)
        assertTrue("changes to its code" in code.text)
        assertEquals(false, sandboxControl(row("hub", origin = GedraConfigOrigin.source, hasSandbox = true)).add)
        val inCode = sandboxControl(row("acme", origin = GedraConfigOrigin.source, hasSandbox = true, sandboxInCode = true))
        assertNull(inCode.add)
        assertTrue("set in its code" in inCode.text)
        // A visitor of a demo client is told why, and offered nothing.
        val locked = sandboxControl(row("confdemo", origin = GedraConfigOrigin.source, sandboxLocked = "You joined it as a visitor."))
        assertNull(locked.add)
        assertTrue(locked.text.endsWith("You joined it as a visitor."))
        assertNull(sandboxControl(row("gone", status = ClientStatus.notEnabled)).add)
    }

    /**
     * A sandbox's page leads back to its client whatever is published: by switching back, in a sandbox session with a
     * user to go back to, or by the link, for an administrator across clients looking from outside. Never from a
     * client's own page, and never as a switch with no user to switch to.
     */
    @Test
    fun aSandboxsPageLeadsBackToItsClient() {
        val sandbox = row("acme:sandbox", sandboxOf = "acme")
        assertEquals("acme", sandboxWayBackTarget(sandbox, acrossClients = false, inSandbox = true, wayBackUserId = 7))
        assertEquals("acme", sandboxWayBackTarget(sandbox, acrossClients = true, inSandbox = false, wayBackUserId = null))
        assertNull(sandboxWayBackTarget(sandbox, acrossClients = false, inSandbox = true, wayBackUserId = null))
        assertNull(sandboxWayBackTarget(sandbox, acrossClients = false, inSandbox = false, wayBackUserId = null))
        assertNull(sandboxWayBackTarget(row("acme", hasSandbox = true), acrossClients = true, inSandbox = false, wayBackUserId = null))
        assertNull(sandboxWayBackTarget(null, acrossClients = true, inSandbox = true, wayBackUserId = 7))
    }
}
