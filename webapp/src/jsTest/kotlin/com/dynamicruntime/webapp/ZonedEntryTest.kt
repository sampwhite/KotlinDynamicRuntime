package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Pure-logic coverage for date-time entry in the client's zone (issue #1235): a stored moment handed to the picker as
 * its wall clock in the client's zone, and a picked wall clock read back there -- across both daylight-saving changes,
 * where the answer must match `java.time`'s so the browser and the server agree.
 */
class ZonedEntryTest {

    private val chicago = "America/Chicago"

    @Test
    fun aMomentReadsAsItsWallClockInTheZone() {
        assertEquals("2026-10-17T17:00:00", zonedWallClock(Instant.parse("2026-10-17T22:00:00Z"), chicago))
        assertEquals("2026-10-18T03:30:00", zonedWallClock(Instant.parse("2026-10-17T22:00:00Z"), "Asia/Kolkata"))
        // Midnight is 00, not 24: the hour cycle is pinned.
        assertEquals("2026-10-18T00:00:00", zonedWallClock(Instant.parse("2026-10-18T05:00:00Z"), chicago))
    }

    @Test
    fun aWallClockIsReadInTheZone() {
        // 5:00 PM in Chicago is the same moment wherever the browser is.
        assertEquals(Instant.parse("2026-10-17T22:00:00Z"), instantOfWallClock("2026-10-17T17:00:00", chicago))
        assertEquals(Instant.parse("2026-01-15T23:00:00Z"), instantOfWallClock("2026-01-15T17:00:00", chicago))
        assertEquals(Instant.parse("2026-10-17T11:30:00Z"), instantOfWallClock("2026-10-17T17:00:00", "Asia/Kolkata"))
        assertEquals(Instant.parse("2026-10-17T11:15:00Z"), instantOfWallClock("2026-10-17T17:00:00", "Asia/Kathmandu"))
        assertEquals(Instant.parse("2026-10-17T17:00:00Z"), instantOfWallClock("2026-10-17T17:00:00", "UTC"))
        // The deployment's zone, a fixed UTC-8.
        assertEquals(Instant.parse("2026-10-18T01:00:00Z"), instantOfWallClock("2026-10-17T17:00:00", deploymentZone))
    }

    @Test
    fun aTimeTheClocksSkipMovesForwardByTheGap() {
        // Chicago springs forward at 2:00 AM on Mar 8: 2:30 does not exist, and is 3:30 CDT, as java.time has it.
        val skipped = instantOfWallClock("2026-03-08T02:30:00", chicago)
        assertEquals(Instant.parse("2026-03-08T08:30:00Z"), skipped)
        assertEquals("2026-03-08T03:30:00", zonedWallClock(skipped!!, chicago))
        // Either side of the gap reads as itself.
        assertEquals(Instant.parse("2026-03-08T07:30:00Z"), instantOfWallClock("2026-03-08T01:30:00", chicago))
        assertEquals(Instant.parse("2026-03-08T08:30:00Z"), instantOfWallClock("2026-03-08T03:30:00", chicago))
        // East of UTC too: Berlin skips 2:00-3:00 on Mar 29.
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), instantOfWallClock("2026-03-29T02:30:00", "Europe/Berlin"))
    }

    @Test
    fun aTimeTheClocksPassTwiceIsTheEarlierMoment() {
        // Chicago falls back at 2:00 AM on Nov 1: 1:30 happens in CDT and again in CST; java.time takes the first.
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), instantOfWallClock("2026-11-01T01:30:00", chicago))
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), instantOfWallClock("2026-10-25T02:30:00", "Europe/Berlin"))
    }

    @Test
    fun whatIsNotADateAndTimeIsNoMoment() {
        assertNull(instantOfWallClock("whenever", chicago))
        assertNull(pickedIso("", chicago))
        assertNull(pickedIso(null, chicago))
    }

    @Test
    fun aStoredMomentRoundTripsThroughThePicker() {
        val stored = "2026-10-17T22:00:00.000Z"
        val wall = pickerWallOf(stored, chicago)
        assertEquals("2026-10-17T17:00:00", wall)
        assertEquals(stored, pickedIso(wall, chicago))
    }

    @Test
    fun aTimestampWithItsOwnOffsetIsTheMomentItNames() {
        // 17:00 at UTC-5 is 22:00Z, which reads 3:30 AM the next day in Kolkata -- not 17:00 read in the browser.
        assertEquals("2026-10-18T03:30:00", pickerWallOf("2026-10-17T17:00:00-05:00", "Asia/Kolkata"))
        assertEquals("2026-10-17T11:30:00", pickerWallOf("2026-10-17T17:00:00.000+05:30", "UTC"))
    }

    @Test
    fun aStoredMomentRoundTripsThroughTheDayjsBridge() {
        // The picker's own value type: dayjs of the wall clock in, its wall clock read back out -- in whatever zone
        // the machine running the test is in, which is the point.
        for (zone in listOf(chicago, "Asia/Kolkata", "Asia/Kathmandu", "UTC", deploymentZone)) {
            for (stored in listOf("2026-10-17T22:00:00.000Z", "2026-01-15T05:45:30.000Z")) {
                val value = zonedPickerValue(stored, zone)
                assertEquals(stored, zonedPickerIso(value, zone), "$stored in $zone")
            }
        }
        assertNull(zonedPickerValue(null, chicago))
        assertNull(zonedPickerIso(null, chicago))
    }

    @Test
    fun whatIsNotATimestampReachesThePickerAsItIs() {
        assertNull(pickerWallOf(null, chicago))
        assertNull(pickerWallOf(" ", chicago))
        assertEquals("2026-10-17", pickerWallOf("2026-10-17", chicago))
    }

    @Test
    fun anUnknownZoneIsEnteredInTheDeploymentsAsItIsShownThere() {
        assertEquals(deploymentZone, entryZone("Mars/Olympus_Mons"))
        assertEquals(chicago, entryZone(chicago))
    }

    @Test
    fun theKeptNoteAndZoneFollowAChangeOfZone() {
        // Both are kept between renders; a different zone must still get its own answer.
        val first = entryZoneNote(chicago)
        val second = entryZoneNote("Asia/Kolkata")
        assertTrue(first.contains("Chicago") && second.contains("Kolkata"), "$first / $second")
        assertEquals(chicago, entryZone(chicago))
        assertEquals(deploymentZone, entryZone("Mars/Olympus_Mons"))
        assertEquals("Asia/Kolkata", entryZone("Asia/Kolkata"))
    }

    @Test
    fun thePickerNamesTheZoneItsTimesAreIn() {
        val summer = Instant.parse("2026-07-15T12:00:00Z")
        assertEquals("Central Time (Chicago) \u00b7 GMT\u22125", entryZoneNote(chicago, summer))
        // The deployment's offset zone is named by its offset alone: its "place", GMT+8, is POSIX's reversed sign.
        assertEquals("GMT\u22128", entryZoneNote(deploymentZone, summer))
    }
}
