package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That the frontend implements every function the kernel declares (issue #483).
 *
 * **This is the half the backend cannot check.** Its boot refuses a UiBlock naming an *undeclared* function;
 * nothing on that side can see whether this side actually implements a declared one. A function declared, named
 * in a menu, and never implemented is a click that silently does nothing -- so the coverage assertion lives
 *  here and runs in CI rather than being discovered by somebody using the app.
 */
class FrontendActionsTest {

    @Test
    fun everyDeclaredActionIsImplemented() {
        assertEquals(emptyList(), FrontendActions(logout = {}, envLogout = {}, openPath = {}).missing())
    }

    @Test
    fun runsTheImplementationAndReportsAnUnknownName() {
        var loggedOut = false
        val actions = FrontendActions(logout = { loggedOut = true }, envLogout = {}, openPath = {})
        assertTrue(actions.run("logout", emptyList()))
        assertTrue(loggedOut)
        // False rather than throwing: a name nothing implements must not break the shell it is rendered in.
        assertFalse(actions.run("noSuchFunction", emptyList()))
    }

    @Test
    fun envLogoutReceivesItsCallArguments() {
        // The two URLs the edge supplies must reach the implementation untouched -- it is pure mechanism over
        // them (issue #486).
        var received: List<String>? = null
        val actions = FrontendActions(logout = {}, envLogout = { received = it }, openPath = {})
        assertTrue(actions.run("envLogout", listOf("/ea/auth/env/logout", "/ew")))
        assertEquals(listOf("/ea/auth/env/logout", "/ew"), received)
    }

    @Test
    fun openPathNavigatesOnlyToAGuardedSameOriginPath() {
        // A menu item's action is data a client may overlay, so the one function that turns it into a
        // navigation drops anything that is not a same-origin path rather than following it (issue #493).
        var navigated: String? = null
        val actions = FrontendActions(logout = {}, envLogout = {}, openPath = { navigated = it })
        assertTrue(actions.run("openPath", listOf("/wa")))
        assertEquals("/wa", navigated)
        // An off-site protocol-relative target runs (the name is implemented) but navigates nowhere.
        navigated = null
        assertTrue(actions.run("openPath", listOf("//evil.example.com")))
        assertNull(navigated)
    }

    @Test
    fun sameOriginPathRefusesWhatAnOpenRedirectWouldNeed() {
        // Directly on the guard, since it is the security boundary (issue #493).
        assertEquals("/wa", FrontendActions.sameOriginPath("/wa"))
        assertEquals("/ec/login?next=%2Fwa", FrontendActions.sameOriginPath("/ec/login?next=%2Fwa"))
        assertNull(FrontendActions.sameOriginPath("//host"))          // protocol-relative
        assertNull(FrontendActions.sameOriginPath("/\\host"))         // backslash smuggling
        assertNull(FrontendActions.sameOriginPath("https://host"))    // a scheme
        assertNull(FrontendActions.sameOriginPath("javascript:alert(1)"))
        assertNull(FrontendActions.sameOriginPath("wa"))              // not absolute
        assertNull(FrontendActions.sameOriginPath(""))
    }
}
