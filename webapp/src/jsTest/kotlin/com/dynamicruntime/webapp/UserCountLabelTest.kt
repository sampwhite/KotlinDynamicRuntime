package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The user-search count line (issue #411): "showing X of Y" only when the cap actually hid some, a plain count
 * otherwise, and the singular when there is exactly one. Pure -- maps three numbers to a string, no React.
 */
class UserCountLabelTest {

    @Test
    fun aCappedResultShowsBothCounts() {
        assertEquals(
            "Showing 500 of 4000 matching users — narrow your search to see the rest.",
            userCountLabel(shown = 500, available = 4000, hasMore = true),
        )
    }

    @Test
    fun anUncappedResultIsOnePlainCount() {
        assertEquals("42 matching users.", userCountLabel(shown = 42, available = 42, hasMore = false))
    }

    @Test
    fun exactlyOneUserIsSingular() {
        assertEquals("1 matching user.", userCountLabel(shown = 1, available = 1, hasMore = false))
    }
}
