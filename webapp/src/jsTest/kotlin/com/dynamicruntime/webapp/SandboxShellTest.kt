package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HFEAT
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.user.UserChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shell's sandbox pieces (issue #931): when "Open sandbox" is offered, what the chip and its popover say, and
 * where "Back" leads -- read off the shell config the backend serves.
 */
class SandboxShellTest {
    private fun config(features: Map<String, Any?> = emptyMap(), state: Map<String, Any?> = emptyMap()) =
        homeConfigFrom(UiConfig(FragmentRef("home", "b1"), features, emptyMap(), state))

    @Test
    fun theShellConfigCarriesTheOfferAndTheSandboxItIsIn() {
        assertTrue(config(features = mapOf(HFEAT.canOpenSandbox to true)).canOpenSandbox)
        assertFalse(config().canOpenSandbox)
        assertNull(config().sandboxOf)
        val inside = config(state = mapOf(HFLD.sandboxOf to "acme", HFLD.sandboxOfName to "Acme")).sandboxOf!!
        assertEquals("acme" to "Acme", inside.client to inside.name)
        // A name the backend could not supply falls back to the id, so the chip never reads blank.
        assertEquals("acme", config(state = mapOf(HFLD.sandboxOf to "acme")).sandboxOf!!.name)
    }

    @Test
    fun openSandboxIsOfferedOnlyOutsideASandbox() {
        assertTrue(showOpenSandbox(config(features = mapOf(HFEAT.canOpenSandbox to true))))
        assertFalse(showOpenSandbox(config()))
        assertFalse(showOpenSandbox(null))
        assertFalse(
            showOpenSandbox(config(features = mapOf(HFEAT.canOpenSandbox to true), state = mapOf(HFLD.sandboxOf to "acme"))),
        )
    }

    @Test
    fun theChipNamesWhoseSandboxAndItsPopoverSaysWhatItRunsWithoutTheInternalName() {
        val of = SandboxOf("acme", "Acme")
        assertEquals("Acme Sandbox", sandboxChipLabel(of))
        val info = sandboxInfoText(of)
        assertTrue(info.startsWith("You are in Acme's sandbox."))
        assertTrue("published" in info)
        assertFalse("Shadow" in sandboxChipLabel(of) + info)
    }

    @Test
    fun backLeadsToThePersonsUserInTheParentOfTheSamePersona() {
        val of = SandboxOf("acme", "Acme")
        val users = listOf(
            UserChoice(1, "acme", "member"),
            UserChoice(2, "acme", "admin"),
            UserChoice(3, "acme:sandbox", "admin", isCurrent = true),
            UserChoice(4, "globex", "admin"),
        )
        assertEquals(2L, sandboxWayBack(of, users)?.userId)
        // No user of the current persona there: any of theirs in the parent.
        assertEquals(1L, sandboxWayBack(of, users.filter { it.userId != 2L })?.userId)
        assertNull(sandboxWayBack(of, users.filter { it.client != "acme" }))
    }
}
