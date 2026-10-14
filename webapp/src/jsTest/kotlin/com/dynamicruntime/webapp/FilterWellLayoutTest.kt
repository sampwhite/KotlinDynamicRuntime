package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The filter well's rows: how many columns a width lays out, and when the Users page's tall date ranges start a row of
 * their own rather than stretching the text boxes beside them -- only when that saves height.
 */
class FilterWellLayoutTest {
    @Test
    fun aWidthLaysOutWhatTheGridFitsAndAtLeastOneColumn() {
        // Inner width after 12px padding each side; each further track needs 300 plus a 24px gap.
        assertEquals(1, filterWellColumns(0.0))
        assertEquals(1, filterWellColumns(500.0))
        assertEquals(2, filterWellColumns(2 * 12.0 + 300 + 24 + 300))
        assertEquals(3, filterWellColumns(1160.0))
        assertEquals(4, filterWellColumns(1580.0))
    }

    /** Measured on the Users page: both shapes, with the well heights each choice gave. */
    @Test
    fun theDatesBreakOnlyWhenThatSavesARow() {
        // Three text boxes in four columns: a date range beside them stretched them (272px); breaking gave 205px.
        assertTrue(breakBeforeTall(shortCount = 3, tallCount = 4, columns = 4))
        // Four in three: the fourth shares a tall row that is there anyway (334px); breaking added a row (396px).
        assertFalse(breakBeforeTall(shortCount = 4, tallCount = 4, columns = 3))
        // A full last row needs no break; neither does a well with nothing tall, or nothing short.
        assertFalse(breakBeforeTall(shortCount = 3, tallCount = 4, columns = 3))
        assertFalse(breakBeforeTall(shortCount = 3, tallCount = 0, columns = 4))
        assertFalse(breakBeforeTall(shortCount = 0, tallCount = 4, columns = 4))
    }
}
