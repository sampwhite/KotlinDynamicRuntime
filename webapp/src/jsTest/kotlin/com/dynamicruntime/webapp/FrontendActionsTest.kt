package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertEquals(emptyList(), FrontendActions(logout = {}).missing())
    }

    @Test
    fun runsTheImplementationAndReportsAnUnknownName() {
        var loggedOut = false
        val actions = FrontendActions(logout = { loggedOut = true })
        assertTrue(actions.run("logout", emptyList()))
        assertTrue(loggedOut)
        // False rather than throwing: a name nothing implements must not break the shell it is rendered in.
        assertFalse(actions.run("noSuchFunction", emptyList()))
    }
}
