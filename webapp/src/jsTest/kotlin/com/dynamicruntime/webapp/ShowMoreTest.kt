package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A growing listing's arithmetic (issue #1315): what the URL asks for, what "Show more" and "Show all" offer, and the
 * count line. Pure -- numbers to numbers and strings, no React. The offers fail silently when wrong (a missing button,
 * or one that grows past the cap), which is why they are spelled out case by case.
 */
class ShowMoreTest {

    @Test
    fun theShownCountIsHeldToTheBatchAndTheCap() {
        assertEquals(listingBatch, shownFromRoute(null))
        assertEquals(listingBatch, shownFromRoute("many"))
        assertEquals(listingBatch, shownFromRoute("7"))
        assertEquals(300, shownFromRoute("300"))
        assertEquals(listingCap, shownFromRoute("100000"))
        // Only past the first batch is it written, so an untouched listing's address stays bare.
        assertEquals(emptyList(), shownRouteParams(listingBatch))
        assertEquals(listOf(HP.shown to "200"), shownRouteParams(200))
    }

    @Test
    fun theWayHomeFromAFormAsksForTheSameRows() {
        // The count is a non-navigation key on a forms address, so every way home carries it as it carries the sort.
        val home = formsListingReturn(mapOf(HP.page to "editForm", HP.gedra to "g1", HP.shown to "300"), "g1")
        assertTrue(HP.shown to "300" in home)
    }

    @Test
    fun everythingOnScreenOffersNothing() {
        val none = showMoreChoice(100, 42)
        assertNull(none.more)
        assertNull(none.all)
    }

    @Test
    fun aFewMoreThanABatchOffersOnlyShowAll() {
        // 132: one more batch would show them all, so "Show 100 more" and "Show all" would be the same click.
        val c = showMoreChoice(100, 132)
        assertNull(c.more)
        assertEquals(132, c.all)
    }

    @Test
    fun underTheCapOffersBoth() {
        val c = showMoreChoice(100, 450)
        assertEquals(200, c.more)
        assertEquals(450, c.all)
    }

    @Test
    fun overTheCapOffersMoreUpToTheCapAndThenNothing() {
        val first = showMoreChoice(100, 812)
        assertEquals(200, first.more)
        assertNull(first.all)
        assertEquals(500, showMoreChoice(450, 812).more)
        val atCap = showMoreChoice(500, 812)
        assertNull(atCap.more)
        assertNull(atCap.all)
    }

    @Test
    fun theCountLineSaysWhereTheListingStands() {
        assertEquals("No forms", listingCountText(0, 0, "form", "forms"))
        assertEquals("1 form", listingCountText(1, 1, "form", "forms"))
        assertEquals("42 forms", listingCountText(42, 42, "form", "forms"))
        assertEquals("Showing 100 of 650", listingCountText(100, 650, "form", "forms"))
        assertEquals(
            "Showing 500 of 812 — narrow the search to see the rest.",
            listingCountText(500, 812, "matching user", "matching users"),
        )
    }
}
