package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for [nextTraceId]: the format contract and the uniqueness property. The
 * counter is a random-seeded module global, so these assert the shape and that successive ids differ, rather
 * than a fixed counter value.
 */
class TraceIdTest {

    /** `f` (frontend marker) + compact UTC timestamp (digits) + a two-digit counter. */
    private val traceIdFormat = Regex("^f\\d+\\d{2}$")

    @Test
    fun leadsWithFrontendPrefixAndEndsWithTwoDigitCounter() {
        val id = nextTraceId()
        assertTrue(id.startsWith("f"), "trace id should lead with the frontend marker 'f': $id")
        assertTrue(traceIdFormat.matches(id), "trace id should be f + timestamp digits + 2-digit counter: $id")
    }

    @Test
    fun successiveIdsAreUnique() {
        // Stay at or under the counter's 99-wide range: within one cycle the counter value is distinct on
        // every call, so the ids are unique no matter how they fall across millisecond boundaries. Minting
        // more than 99 in a single millisecond could legitimately repeat a counter, which is not a uniqueness
        // guarantee the design makes.
        val ids = (1..90).map { nextTraceId() }
        assertEquals(ids.size, ids.toSet().size, "every minted trace id should be distinct")
    }
}
