package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals

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
