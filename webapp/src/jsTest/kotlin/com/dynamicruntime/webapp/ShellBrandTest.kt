package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The app bar's brand (issue #1740 gathered these into one file): the wordmark's transition through loading, and the
// mark's URL. Each class is the file it used to be, kept whole.

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
    fun theTabTitleIsTheSiteTitleInPlainWords() {
        // Issue #1799: set as the copy loads, so a switch to another client's user retitles the tab.
        assertEquals("Riverbend grants", siteTabTitle(Copy(mapOf("site" to mapOf("title" to "**Riverbend** grants")))))
        assertNull(siteTabTitle(Copy(mapOf("site" to mapOf("title" to "  ")))))
        assertNull(siteTabTitle(Copy(mapOf("home" to mapOf("brand" to "KDR")))))
    }

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

/**
 * Pure-logic coverage (issue #161) for the brand-mark URL grammar (issue #529): the `window`-reading getter is
 * a thin wrapper over [brandMarkUrl] so both branches -- appui's content-addressed name, and the dev server's
 * bare fallback -- are testable under Node, where the browser branch nobody opens is the one that breaks the
 * local dev loop if it regresses.
 */
class BrandTest {

    @Test
    fun usesTheBootstrapContentAddressedNameWhenAppuiSuppliedOne() {
        // The whole `name:hash` comes from the backend (versionedName), so the frontend appends no grammar.
        assertEquals("/wa/brand-mark.svg:1a2b3c", brandMarkUrlFrom("/wa", "brand-mark.svg:1a2b3c"))
    }

    @Test
    fun fallsBackToTheBareFileWhenNoBootstrapNameIsPresent() {
        // The dev server injects no bootstrap, so the name is empty and the mark is served bare from the root.
        assertEquals("/brand-mark.svg", brandMarkUrlFrom("", ""))
        // ...and under appui's root but still bare (an unhashed mark), the file is served without a suffix.
        assertEquals("/wa/brand-mark.svg", brandMarkUrlFrom("/wa", ""))
    }
}
