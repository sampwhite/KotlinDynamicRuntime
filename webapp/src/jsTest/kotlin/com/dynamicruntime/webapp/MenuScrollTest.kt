package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage for [menuScrollDelta] (issue #1303): how far the navigation menu scrolls to bring an item,
 * or a group's header and children, into view. The menu's visible part runs 100..400 in every case.
 */
class MenuScrollTest {

    @Test
    fun aRunAlreadyInViewDoesNotMove() {
        assertEquals(0.0, menuScrollDelta(100.0, 400.0, 150.0, 300.0))
        assertEquals(0.0, menuScrollDelta(100.0, 400.0, 100.0, 400.0))
    }

    @Test
    fun aRunBelowTheEdgeScrollsJustFarEnoughToShowItsBottom() {
        // An item at 420..460, or a short group expanded near the bottom: its last line comes up to the edge.
        assertEquals(60.0, menuScrollDelta(100.0, 400.0, 420.0, 460.0))
        assertEquals(50.0, menuScrollDelta(100.0, 400.0, 300.0, 450.0))
    }

    @Test
    fun aRunTallerThanTheMenuShowsItsTop() {
        // A group's header at 350 with children to 800: the header comes to the top, as many children as fit.
        assertEquals(250.0, menuScrollDelta(100.0, 400.0, 350.0, 800.0))
    }

    @Test
    fun aRunAboveTheTopScrollsUpToIt() {
        assertEquals(-40.0, menuScrollDelta(100.0, 400.0, 60.0, 90.0))
        assertEquals(-40.0, menuScrollDelta(100.0, 400.0, 60.0, 900.0))
    }
}
