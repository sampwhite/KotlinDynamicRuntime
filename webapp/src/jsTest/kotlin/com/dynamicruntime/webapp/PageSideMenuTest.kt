package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A long page's side menu (issue #1919): its places in the page's order, and which is where the reader is. */
class PageSideMenuTest {
    private val panels = listOf("definition" to "Definition", "formsData" to "Forms and data", "copy" to "Copy")
    private val sections = mapOf("formsData" to listOf("traits" to "Traits", "workflows" to "Workflows"))

    @Test
    fun itsPlacesAreThePanelsEachFollowedByItsSections() {
        val entries = sideMenuEntries(panels, sections)
        assertEquals(listOf("definition", "formsData", "traits", "workflows", "copy"), entries.map { it.id })
        assertEquals(listOf("definition", "formsData", "formsData", "formsData", "copy"), entries.map { it.panelId })
        assertFalse(entries[1].isSection)
        assertTrue(entries[2].isSection)
        // A panel with no sections, and a sections list naming a panel not drawn, add nothing.
        assertEquals(listOf("a"), sideMenuEntries(listOf("a" to "A"), mapOf("gone" to listOf("x" to "X"))).map { it.id })
    }

    @Test
    fun theReaderIsAtTheLastPlaceThatReachedTheLine() {
        val line = 100.0
        val tops = listOf("definition" to -900.0, "formsData" to -300.0, "traits" to 60.0, "workflows" to 700.0, "copy" to 1500.0)
        assertEquals("traits", activeSideMenuEntry(tops, line, atBottom = false, viewportBottom = 800.0))
        // Exactly at the line counts as reached: a link scrolls its place there.
        assertEquals("workflows", activeSideMenuEntry(tops.map { if (it.first == "workflows") it.first to line else it }, line, false, 800.0))
        // Before the first is reached, the first.
        assertEquals("definition", activeSideMenuEntry(listOf("definition" to 300.0, "copy" to 900.0), line, false, 800.0))
        assertNull(activeSideMenuEntry(emptyList(), line, false, 800.0))
    }

    @Test
    fun aCssLengthIsReadInPixels() {
        assertEquals(72.0, cssPixels("72px"))
        assertEquals(84.5, cssPixels(" 84.5px "))
        assertEquals(0.0, cssPixels("auto"))
        assertEquals(0.0, cssPixels(null))
    }

    @Test
    fun atTheBottomTheLastPlaceShowing() {
        // The last, short panels never reach the line: scrolled to the bottom, the last whose top shows counts.
        val tops = listOf("definition" to -900.0, "formsData" to -300.0, "copy" to 400.0, "menu" to 650.0)
        assertEquals("formsData", activeSideMenuEntry(tops, 100.0, atBottom = false, viewportBottom = 800.0))
        assertEquals("menu", activeSideMenuEntry(tops, 100.0, atBottom = true, viewportBottom = 800.0))
        assertEquals("copy", activeSideMenuEntry(tops, 100.0, atBottom = true, viewportBottom = 600.0))
    }
}
