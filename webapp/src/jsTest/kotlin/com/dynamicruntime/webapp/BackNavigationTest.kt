package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HMENU
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage for the listing → child back navigation (issue #554): where a child's back link goes,
 * given what the hash says about where it was opened from. No browser; the resolution is a map lookup.
 */
class BackNavigationTest {

    @Test
    fun honoursAKnownListingNamedByFrom() {
        // Opened from the forms list, so back goes there even though the child names another fallback.
        assertEquals(HMENU.pageForms, backTarget(from = HMENU.pageForms, fallback = HMENU.pageOperator))
        assertEquals(HMENU.pageOperator, backTarget(from = HMENU.pageOperator, fallback = HMENU.pageForms))
    }

    @Test
    fun fallsBackWhenFromIsAbsent() {
        // Reached from the menu or a bookmark: no `from`, so the child's natural parent.
        assertEquals(HMENU.pageOperator, backTarget(from = null, fallback = HMENU.pageOperator))
    }

    @Test
    fun refusesAFromThatIsNotAListing() {
        // A pasted URL may name anything; a page that is not a listing (or nonsense) is never a back target.
        assertEquals(HMENU.pageOperator, backTarget(from = HMENU.pageProfile, fallback = HMENU.pageOperator))
        assertEquals(HMENU.pageForms, backTarget(from = "javascript:alert(1)", fallback = HMENU.pageForms))
        assertEquals(HMENU.pageForms, backTarget(from = "", fallback = HMENU.pageForms))
    }

    @Test
    fun labelsAListingByItsName() {
        assertEquals("Operator", backLabel(HMENU.pageOperator))
        assertEquals("My forms", backLabel(HMENU.pageForms))
        // A non-listing page has no name here, so the id shows rather than nothing.
        assertEquals(HMENU.pageProfile, backLabel(HMENU.pageProfile))
    }

    @Test
    fun childHrefCarriesTheListingAndExtras() {
        assertEquals("#page=bootChecks&from=operator", childHref(HMENU.pageBootChecks, HMENU.pageOperator))
        assertEquals(
            "#page=editForm&from=forms&g=abc",
            childHref(pageEditForm, HMENU.pageForms, HP.gedra to "abc"),
        )
    }
}
