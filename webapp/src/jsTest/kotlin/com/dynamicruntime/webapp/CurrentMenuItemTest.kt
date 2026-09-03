package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.uiblock.UiRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-logic coverage for [currentMenuItem] (issue #554): which menu item, if any, is the page on screen --
 * what the app bar marks with `aria-current`, and what makes a collapsed group open to show where you are.
 */
class CurrentMenuItemTest {

    private val items = listOf(
        MenuItem("catalog", "Endpoint catalog", UiRoute(HMENU.pageCatalog)),
        MenuItem("operator", "Operator", null),
        MenuItem("bootChecks", "Boot checks", UiRoute(HMENU.pageBootChecks), parentId = "operator"),
        MenuItem("cacheState", "Cache state", UiRoute(HMENU.pageCacheState), parentId = "operator"),
        MenuItem("logout", "Log out", null),
    )

    @Test
    fun findsATopLevelItemByItsRoute() {
        assertEquals("catalog", currentMenuItem(items, HMENU.pageCatalog)?.id)
    }

    @Test
    fun findsAChildInsideAGroup() {
        // The child is the current item; its parent is what the render opens and marks as the group.
        val current = currentMenuItem(items, HMENU.pageCacheState)
        assertEquals("cacheState", current?.id)
        assertEquals("operator", current?.parentId)
    }

    @Test
    fun nothingIsCurrentOnAPageTheMenuDoesNotOffer() {
        // Home, a document, a page reached by URL only: no item lights up rather than a wrong one.
        assertNull(currentMenuItem(items, "home"))
        assertNull(currentMenuItem(items, HMENU.pageProfile))
    }

    @Test
    fun aHeaderOrCallItemIsNeverCurrent() {
        // Only a route can be "the page you are on"; a group header and a call have no page.
        assertNull(currentMenuItem(items, "operator"))
        assertNull(currentMenuItem(items, "logout"))
    }
}
