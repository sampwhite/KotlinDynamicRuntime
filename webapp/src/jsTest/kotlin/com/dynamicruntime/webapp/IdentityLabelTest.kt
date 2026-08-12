package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-logic coverage (issue #161) for the app bar's identity label (issue #276).
 *
 * The case worth protecting is the third one. "Signed out" and "signed in as X" are obvious; **unknown** is
 * what stops the bar claiming signed-out on the first paint, before `/home/ui/config` has answered — which
 * would be wrong for a moment and would flicker for every signed-in user on every load. An edit that collapses
 * this to a two-way `if` would reintroduce exactly that, and these assertions are what would catch it.
 */
class IdentityLabelTest {

    @Test
    fun saysNothingUntilTheConfigHasArrived() {
        // Not yet loaded: silence is correct here, and *only* here.
        assertNull(identityLabel(loaded = false, isLoggedIn = false, displayName = null))
        // Even if stale state claims a login, an unloaded config still says nothing.
        assertNull(identityLabel(loaded = false, isLoggedIn = true, displayName = "Ada"))
    }

    @Test
    fun statesSignedOutOnceItIsKnown() {
        assertEquals(signedOutLabel, identityLabel(loaded = true, isLoggedIn = false, displayName = null))
        // A leftover name must not make a signed-out caller look signed in.
        assertEquals(signedOutLabel, identityLabel(loaded = true, isLoggedIn = false, displayName = "Ada"))
    }

    @Test
    fun namesWhoeverIsSignedIn() {
        assertEquals("Ada", identityLabel(loaded = true, isLoggedIn = true, displayName = "Ada"))
    }

    /** A signed-in caller with no usable name still gets a statement, never silence. */
    @Test
    fun fallsBackToStatingTheStateWhenThereIsNoName() {
        assertEquals(signedInFallback, identityLabel(loaded = true, isLoggedIn = true, displayName = null))
        assertEquals(signedInFallback, identityLabel(loaded = true, isLoggedIn = true, displayName = ""))
        assertEquals(signedInFallback, identityLabel(loaded = true, isLoggedIn = true, displayName = "   "))
    }

    /** The signed-in fallback must not read as its opposite — they are one word apart. */
    @Test
    fun theTwoStatesAreNotConfusable() {
        assertEquals(false, signedInFallback == signedOutLabel)
    }
}
