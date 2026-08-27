package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for the app bar's brand transition (issue #469) -- the companion to
 * [IdentityLabelTest] one element to the left, and for the same reason: a blank wordmark must not be *ambiguous*
 * between "still loading" and "the fetch failed".
 *
 * The cases worth protecting are the ones a browser can barely reach: **loaded-but-empty** (a deployment that
 * names no brand -- a valid ready state showing the mark alone, not a failure), and the two failure directions
 * that must differ -- a **first-load** failure downgrades to Failed, while a failure **after** a wordmark has
 * shown keeps it ("the previous wordmark beats no wordmark"), which is what stops a refresh from flickering.
 */
class ShellBrandTest {

    @Test
    fun namesTheBrandOnLoad() {
        val state = brandAfterLoad(loaded = true, brand = "Acme", everLoaded = false)
        assertTrue(state is ShellBrand.Ready)
        assertEquals("Acme", state.label)
    }

    /** Loaded but the deployment names no brand: a valid ready state, mark alone -- NOT a failure or a spinner. */
    @Test
    fun readyWithNoBrandShowsTheMarkAlone() {
        for (blank in listOf(null, "", "   ")) {
            val state = brandAfterLoad(loaded = true, brand = blank, everLoaded = true)
            assertTrue(state is ShellBrand.Ready, "blank brand '$blank' should still be Ready")
            assertNull(state.label, "blank brand '$blank' should carry no label")
        }
    }

    /** A failure with nothing loaded yet is a definite Failed -- the endless spinner this exists to prevent. */
    @Test
    fun firstLoadFailureIsFailed() {
        assertEquals(ShellBrand.Failed, brandAfterLoad(loaded = false, brand = null, everLoaded = false))
    }

    /** A failure after a wordmark has shown keeps it (null = no change), so a transient refresh blip never blanks it. */
    @Test
    fun failureAfterLoadKeepsThePreviousWordmark() {
        assertNull(brandAfterLoad(loaded = false, brand = null, everLoaded = true))
        assertNull(brandAfterLoad(loaded = false, brand = "ignored", everLoaded = true))
    }

    /** A stale build id (404) is recoverable and distinct from a genuine failure; nothing else is stale. */
    @Test
    fun onlyA404IsAStaleFragment() {
        assertTrue(isStaleFragment(404))
        assertEquals(false, isStaleFragment(500))
        assertEquals(false, isStaleFragment(200))
        assertEquals(false, isStaleFragment(null))
    }
}
