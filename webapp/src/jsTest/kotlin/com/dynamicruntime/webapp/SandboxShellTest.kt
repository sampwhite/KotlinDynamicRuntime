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
        assertTrue("Acme" in info)
        assertTrue("published" in info)
        assertFalse("Shadow" in sandboxChipLabel(of) + info)
    }

    // Issue #1197: a sandbox user nobody stands behind is said to be one, and the popover says why nothing opens.
    @Test
    fun aStrandedSandboxIsCarriedAndExplained() {
        val state = mapOf(HFLD.sandboxOf to "acme", HFLD.sandboxOfName to "Acme")
        assertFalse(config(state = state).sandboxOf!!.stranded)
        val stranded = config(state = state + (HFLD.sandboxStranded to true)).sandboxOf!!
        assertTrue(stranded.stranded)
        val info = sandboxInfoText(stranded)
        assertTrue("Acme" in info && "ended" in info)
        assertFalse("published" in info)
        // With nowhere listed to go, the exit that remains is named instead.
        assertTrue(sandboxInfoText(stranded, hasWayOut = false) != info)
        // A sandbox somebody stands behind reads as before, whatever the exits.
        assertEquals(sandboxInfoText(SandboxOf("acme", "Acme")), sandboxInfoText(SandboxOf("acme", "Acme"), hasWayOut = false))
    }

    // Issue #1197: every switch from inside a sandbox goes through the leave, which a sandbox user acting with no
    // roles may still make; any other through the gated switch.
    @Test
    fun aSwitchFromInsideASandboxIsALeave() {
        assertTrue(switchesFromSandbox(config(state = mapOf(HFLD.sandboxOf to "acme"))))
        assertFalse(switchesFromSandbox(config()))
        assertFalse(switchesFromSandbox(null))
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

    // Issue #1049: the page is marked only in a sandbox; a loading shell config marks nothing.
    @Test
    fun thePageIsMarkedOnlyInASandbox() {
        assertTrue(pageInSandbox(config(state = mapOf(HFLD.sandboxOf to "acme"))))
        assertFalse(pageInSandbox(config()))
        assertFalse(pageInSandbox(null))
    }

    // Issue #1049: a delete names where it acts, from the thing's own client -- live data, or a sandbox.
    @Test
    fun aDeleteNamesTheClientItActsInAndWhetherItIsLive() {
        assertEquals("acme (live data)", deleteTargetText("acme"))
        assertEquals("acme's sandbox", deleteTargetText("acme:sandbox"))
        // The question names the form's own client, as the delete target says it.
        assertTrue(deleteFormQuestion("gd.fd.acme.e20261007120000000AbCd").contains(deleteTargetText("acme")))
        assertTrue(deleteFormQuestion("gd.fd.acme:sandbox.e20261007120000000AbCd").contains(deleteTargetText("acme:sandbox")))
        // An id that does not parse names nothing rather than guessing.
        assertFalse("live data" in deleteFormQuestion(null) || "sandbox" in deleteFormQuestion(null))
        // Another user's form, which an editor may delete (issue #1452), says whose it is.
        assertTrue("Ada Lovelace" in deleteFormQuestion("gd.fd.acme.e20261007120000000AbCd", "Ada Lovelace"))
        assertTrue("Ada Lovelace" in deleteRowQuestion("gd.fd.acme.e20261007120000000AbCd", "Ada Lovelace"))
        assertEquals("Ada Lovelace", otherOwnerLabel(8, 7, "Ada Lovelace", "ada@x.com"))
        assertEquals("ada@x.com", otherOwnerLabel(8, 7, " ", "ada@x.com"))
        assertEquals(null, otherOwnerLabel(7, 7, "Me", "me@x.com"))
        assertEquals(null, otherOwnerLabel(null, 7, "Ada", "ada@x.com"))
        // A cross-client listing's row names its own form's client, not the page's.
        assertTrue(deleteRowQuestion("gd.fd.globex.e20261007120000000AbCd").contains(deleteTargetText("globex")))
        assertFalse("live data" in deleteRowQuestion("not an id"))
        assertTrue(deleteUserQuestion("acme:sandbox").contains(deleteTargetText("acme:sandbox")))
    }
}
