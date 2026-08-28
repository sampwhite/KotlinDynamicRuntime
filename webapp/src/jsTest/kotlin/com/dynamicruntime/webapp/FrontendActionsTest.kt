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
    fun envLogoutReceivesItsCallArgumentsWhenBothAreSameOrigin() {
        // The two URLs the edge supplies reach the implementation when they are same-origin paths (issue #486).
        var received: List<String>? = null
        val actions = FrontendActions(logout = {}, envLogout = { received = it }, openPath = {})
        assertTrue(actions.run("envLogout", listOf("/ea/auth/env/logout", "/ew")))
        assertEquals(listOf("/ea/auth/env/logout", "/ew"), received)
    }

    @Test
    fun envLogoutIsInertWhenEitherUrlIsNotSameOrigin() {
        // The landing URL reaches a full-window navigation, so an overlaid off-site value would be an open
        // redirect. Authority is not trust: the edge supplies these, but they are still guarded (issue #498).
        var received: List<String>? = null
        val actions = FrontendActions(logout = {}, envLogout = { received = it }, openPath = {})

        // The name is implemented, so run() reports true -- but nothing is handed on.
        assertTrue(actions.run("envLogout", listOf("/ea/auth/env/logout", "//evil.example.com")))
        assertNull(received)

        // ...and the same for the logout path, which reaches an API call.
        assertTrue(actions.run("envLogout", listOf("https://evil.example.com", "/ew")))
        assertNull(received)

        // A missing argument is not a URL either.
        assertTrue(actions.run("envLogout", listOf("/ea/auth/env/logout")))
        assertNull(received)
    }

    @Test
    fun openPathNavigatesOnlyToAGuardedSameOriginPath() {
        // A menu item's action is data a client may overlay, so every argument that becomes a navigation is
        // dropped unless it is a same-origin path, rather than being followed (issue #493).
        var navigated: String? = null
        val actions = FrontendActions(logout = {}, envLogout = {}, openPath = { navigated = it })
        assertTrue(actions.run("openPath", listOf("/wa")))
        assertEquals("/wa", navigated)
        // An off-site protocol-relative target runs (the name is implemented) but navigates nowhere.
        navigated = null
        assertTrue(actions.run("openPath", listOf("//evil.example.com")))
        assertNull(navigated)
    }
}
