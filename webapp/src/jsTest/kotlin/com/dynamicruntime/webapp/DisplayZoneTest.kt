package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Moments shown in the client's zone (issue #1115), labeled with it; days never shift. */
class DisplayZoneTest {
    @Test
    fun aTimestampIsShownInTheZoneAndLabeled() {
        assertEquals("Oct 17, 2026, 5:00 PM CT", formatTimestamp("2026-10-17T22:00:00.000Z", "America/Chicago"))
        assertEquals("Aug 21, 2026, 7:49 PM GMT", formatTimestamp("2026-08-21T19:49:51.568Z", "UTC"))
        // A client naming no zone is shown in the deployment's, the kernel's fixed UTC-8.
        assertEquals("Etc/GMT+8", deploymentZone)
        assertEquals("Aug 21, 2026, 11:49 AM GMT-8", formatTimestamp("2026-08-21T19:49:51.568Z", deploymentZone))
    }

    @Test
    fun aDayAndANonTimestampAreLeftAlone() {
        assertEquals("2026-10-17", formatTimestamp("2026-10-17", "Asia/Tokyo"))
        assertEquals("whenever", formatTimestamp("whenever", "UTC"))
    }

    @Test
    fun aZoneIntlDoesNotKnowIsShownInTheDeploymentsZone() {
        assertEquals("Aug 21, 2026, 11:49 AM GMT-8", formatTimestamp("2026-08-21T19:49:51.568Z", "Mars/Olympus"))
    }

    @Test
    fun theDayAMomentFallsOnIsTheZonesDay() {
        val lateUtc = Instant.parse("2026-10-17T23:30:00Z")
        assertEquals("2026-10-17", zonedDay(lateUtc, "UTC"))
        assertEquals("2026-10-18", zonedDay(lateUtc, "Asia/Tokyo"))
        assertEquals("2026-10-17", zonedDay(lateUtc, "America/Chicago"))
    }
}
