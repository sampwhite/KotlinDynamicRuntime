package com.dynamicruntime.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Calendar months in UTC (issue #1402), which an API key's extension counts in. In `commonTest`, so the backend and the
 * browser's preview of an extension land on the same moment.
 */
class AddMonthsTest {
    private fun at(iso: String) = Instant.parse(iso)

    @Test
    fun aMonthKeepsTheDayAndTheTimeOfDay() {
        assertEquals(at("2026-04-15T08:30:00Z"), at("2026-01-15T08:30:00Z").addMonths(3))
        // Across a year end.
        assertEquals(at("2027-02-10T00:00:00Z"), at("2026-11-10T00:00:00Z").addMonths(3))
        // Back, for a negative count.
        assertEquals(at("2025-12-15T08:30:00Z"), at("2026-01-15T08:30:00Z").addMonths(-1))
    }

    @Test
    fun aDayTheMonthDoesNotHaveIsItsLastDay() {
        assertEquals(at("2026-02-28T12:00:00Z"), at("2026-01-31T12:00:00Z").addMonths(1))
        // A leap year has a 29th.
        assertEquals(at("2028-02-29T12:00:00Z"), at("2028-01-31T12:00:00Z").addMonths(1))
        assertEquals(at("2026-06-30T12:00:00Z"), at("2026-03-31T12:00:00Z").addMonths(3))
    }

    @Test
    fun twelveMonthsIsAYear() {
        assertEquals(at("2027-10-27T09:00:00Z"), at("2026-10-27T09:00:00Z").addMonths(12))
        // From a leap day, the next year's has none.
        assertEquals(at("2029-02-28T09:00:00Z"), at("2028-02-29T09:00:00Z").addMonths(12))
    }
}
