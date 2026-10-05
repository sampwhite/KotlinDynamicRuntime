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
        hasSandbox: Boolean = false,
        origin: GedraConfigOrigin = GedraConfigOrigin.stored,
        status: ClientStatus = ClientStatus.present,
    ) = parseClientOverview(
        listOf(
            buildMap {
                put(CLD.clientId, id); put(CLD.name, id); put(CLD.status, status.name); put(CLD.origin, origin.name)
                put(CLD.hasSandbox, hasSandbox)
                sandboxOf?.let { put(CLD.sandboxOf, it) }
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
        assertNull(sandboxControl(row("hub", origin = GedraConfigOrigin.source)).add)
        assertTrue("source code" in sandboxControl(row("hub", origin = GedraConfigOrigin.source)).text)
        assertNull(sandboxControl(row("gone", status = ClientStatus.notEnabled)).add)
    }
}
