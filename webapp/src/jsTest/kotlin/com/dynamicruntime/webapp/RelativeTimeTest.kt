package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A table's relative times (issue #1141): `just now`, then two units, then the day alone -- the same rules whichever
 * way the time lies, since nothing stops a future one arriving, though nothing produces one yet.
 */
class RelativeTimeTest {
    private val now = Instant.parse("2026-10-12T15:30:00.000Z")

    private fun ago(d: Duration) = formatRelativeTime((now - d).toString(), now)
    private fun ahead(d: Duration) = formatRelativeTime((now + d).toString(), now)

    @Test
    fun underAMinuteIsJustNowEitherWay() {
        assertEquals("just now", ago(Duration.ZERO))
        assertEquals("just now", ago(59.seconds))
        // A clock a little ahead of the browser's must not read as the future.
        assertEquals("just now", ahead(59.seconds))
        assertEquals("1m ago", ago(60.seconds))
        assertEquals("in 1m", ahead(60.seconds))
    }

    @Test
    fun minutesThenHoursAndMinutes() {
        assertEquals("59m ago", ago(59.minutes + 59.seconds))
        assertEquals("1h ago", ago(60.minutes))
        assertEquals("1h 23m ago", ago(83.minutes))
        assertEquals("in 1h 23m", ahead(83.minutes))
        assertEquals("23h 59m ago", ago(24.hours - 1.seconds))
    }

    @Test
    fun daysAndHoursUntilTwoDaysThenTheDayAlone() {
        assertEquals("1d ago", ago(24.hours))
        assertEquals("1d 2h ago", ago(26.hours))
        assertEquals("in 1d 2h", ahead(26.hours))
        assertEquals("1d 23h ago", ago(48.hours - 1.seconds))
        // Elapsed hours, not calendar days, and the UTC day of the time itself.
        assertEquals("2026-10-10", ago(48.hours))
        assertEquals("2026-10-14", ahead(48.hours))
        assertEquals("2025-01-02", formatRelativeTime("2025-01-02T23:59:00.000Z", now))
    }

    @Test
    fun aValueThatIsNotATimestampIsLeftAlone() {
        assertEquals("whenever", formatRelativeTime("whenever", now))
    }
}
