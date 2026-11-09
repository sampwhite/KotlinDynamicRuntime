package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.util.toJsonStr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Times and zones on the page (issue #1740 gathered these into one file): a moment shown in the client's zone and
// relative to now, a date put on the wire, a date-time entered in the client's zone, and the zone picker. Each class is
// the file it used to be, kept whole with its own helpers.

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
        // Elapsed hours, not calendar days, and the day of the time itself, where the client is (issue #1115).
        assertEquals("2026-10-10", ago(48.hours))
        assertEquals("2026-10-14", ahead(48.hours))
        assertEquals("2025-01-02", formatRelativeTime("2025-01-02T23:59:00.000Z", now, "UTC"))
        assertEquals("2025-01-03", formatRelativeTime("2025-01-02T23:59:00.000Z", now, "Asia/Tokyo"))
    }

    @Test
    fun aValueThatIsNotATimestampIsLeftAlone() {
        assertEquals("whenever", formatRelativeTime("whenever", now))
    }
}

/**
 * What a date looks like when the **frontend** puts it on the wire (issue #255).
 *
 * The backend stamps a timestamp by putting an `Instant` in the response map and letting serialization format
 * it — one place decides the wire format, so no handler can invent its own. The claim worth testing is that
 * the same holds in the other direction; since the validator and the JSON writer are both kernel code, the
 * frontend runs unchanged.
 *
 * Tested **on JS specifically**, not taken on faith from the JVM. `Double.toString` already differs between
 * the two platforms (`1.0` against `1`), so "it is in the kernel" is not by itself proof that both sides agree
 * — and a date format that differed between them would be the same class of bug, in the one place both sides
 * are supposed to agree by construction.
 */
class WireDateTest {

    private fun stampType() = parseSchemaTypes(
        mapOf(
            "core.Stamped" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "at" to mapOf(SCH.type to SCT.string, SCH.format to SFMT.dateTime),
                    "day" to mapOf(SCH.type to SCT.string, SCH.format to SFMT.date),
                ),
            ),
        ),
    ).getValue("core.Stamped")

    // Coercion turns the text a date picker produced into an Instant, and the JSON writer turns it back into
    // text in the system format — so what leaves the browser is normalized rather than whatever was typed.
    @Test
    fun aDateTimeGoesOnTheWireInTheSystemFormat() {
        val result = coerceAndValidate(stampType(), mapOf("at" to "2021-06-01T10:00:00.123456Z"))
        assertTrue(result.failures.isEmpty(), "should coerce cleanly: ${result.failures}")
        val json = result.value.toJsonStr(compact = true)
        // Milliseconds, UTC, trailing Z -- the extra digits the input carried are gone.
        assertTrue(json.contains("\"2021-06-01T10:00:00.123Z\""), "system format expected, got: $json")
    }

    // An offset form is REFUSED rather than normalized -- the parser takes UTC with a trailing `Z` and nothing
    // else. Pinned because it is easy to assume otherwise (this test originally did): a browser's own
    // `toISOString` always produces `Z`, so the frontend never runs into it, and the limit only surfaces for a
    // third-party client sending `+02:00` and getting a message that does not mention the offset.
    @Test
    fun anOffsetFormIsRefusedRatherThanNormalized() {
        val result = coerceAndValidate(stampType(), mapOf("at" to "2021-06-01T12:00:00+02:00"))
        assertEquals(1, result.failures.size, "expected one failure: ${result.failures}")
        assertEquals(SchFailCode.badValue, result.failures.first().code)
    }

    @Test
    fun aDayOnlyValueKeepsItsDayForm() {
        val result = coerceAndValidate(stampType(), mapOf("day" to "2021-06-01"))
        assertTrue(result.failures.isEmpty(), "should coerce cleanly: ${result.failures}")
        assertEquals("""{"day":"2021-06-01"}""", result.value.toJsonStr(compact = true))
    }
}

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

/**
 * Pure-logic coverage for the time-zone picker (issue #1257): the labels `Intl` writes, the catalog of zones it
 * offers, and the groups it shows for a search. Run on Node, whose `Intl` is the same engine as Chrome's; the dates are
 * pinned to a January and a July so the offsets do not depend on the day the test runs.
 */
class TimeZonePickerTest {

    private val winter = Instant.parse("2026-01-15T12:00:00Z")
    private val summer = Instant.parse("2026-07-15T12:00:00Z")

    // A browser's list in miniature: an alias of a common zone, a zone off the short list, and an Etc/ offset.
    private val listed = listOf("US/Central", "America/Indiana/Knox", "Asia/Kathmandu", "Etc/GMT+5", "America/Denver")
    private val catalog = timeZoneCatalog(listed, summer)

    private fun titles(groups: List<TimeZoneGroup>) = groups.map { it.title }
    private fun zones(group: TimeZoneGroup) = group.choices.map { it.zone }

    @Test
    fun labelsNameTheZoneAndPlaceAndOffsetForTheDate() {
        assertEquals("Central Time (Chicago) \u00b7 GMT\u22126", timeZoneChoice("America/Chicago", winter)?.label)
        assertEquals("Central Time (Chicago) \u00b7 GMT\u22125", timeZoneChoice("America/Chicago", summer)?.label)
        // The place tells apart zones that share a name.
        assertEquals("Central Time (Knox) \u00b7 GMT\u22125", timeZoneChoice("America/Indiana/Knox", summer)?.label)
        assertEquals("Newfoundland Time (St Johns) \u00b7 GMT\u22123:30", timeZoneChoice("America/St_Johns", winter)?.label)
        // UTC's generic name is only "GMT", so its long name stands in.
        assertEquals("Coordinated Universal Time (UTC) \u00b7 GMT", timeZoneChoice("UTC", winter)?.label)
    }

    @Test
    fun aLabelSaysThePlaceOnceOnly() {
        assertEquals("Central Time (Chicago) \u00b7 GMT\u22125", zoneLabel("Central Time", "Chicago", "GMT\u22125"))
        // Chrome's own name for some zones carries the place already.
        assertEquals("Western European Time (Casablanca) \u00b7 GMT+1", zoneLabel("Western European Time (Casablanca)", "Casablanca", "GMT+1"))
        assertEquals("Moscow Standard Time \u00b7 GMT+3", zoneLabel("Moscow Standard Time", "Moscow", "GMT+3"))
        assertEquals("Almaty \u00b7 GMT+5", zoneLabel(null, "Almaty", "GMT+5"))
        assertEquals("Central Time (Chicago)", zoneLabel("Central Time", "Chicago", ""))
    }

    @Test
    fun aZoneIntlDoesNotKnowHasNoChoice() {
        assertNull(timeZoneChoice("Mars/Olympus_Mons", winter))
    }

    @Test
    fun twoSpellingsOfOneZoneShareAKey() {
        // Which spelling an engine resolves to differs (Chrome says Asia/Calcutta); that the two agree does not.
        assertEquals(timeZoneChoice("Asia/Kolkata", winter)?.key, timeZoneChoice("Asia/Calcutta", winter)?.key)
        assertEquals(timeZoneChoice("America/Chicago", winter)?.key, timeZoneChoice("US/Central", winter)?.key)
    }

    @Test
    fun theCatalogOffersEachZoneOnceTheCommonOnesFirstUnderTheirOwnSpelling() {
        val names = catalog.map { it.zone }
        assertEquals(commonTimeZones, names.take(commonTimeZones.size))
        // US/Central is Chicago, already offered; Denver is on the short list already; the Etc/ offset is refused.
        assertEquals(listOf("America/Indiana/Knox", "Asia/Kathmandu"), names.drop(commonTimeZones.size))
        assertEquals(names.size, catalog.map { it.key }.toSet().size)
        assertTrue("UTC" in names, "UTC is offered though Intl does not list it")
    }

    @Test
    fun beforeAnythingIsTypedTheCurrentAndBrowserZonesArePinnedAboveTheCommonOnes() {
        val groups = timeZoneOptions(catalog, "", browserZone = "America/Chicago", currentZone = "America/Indiana/Knox", summer)
        assertEquals(listOf("Current", "This browser's", "Common zones"), titles(groups))
        assertEquals(listOf("America/Indiana/Knox"), zones(groups[0]))
        assertEquals(listOf("America/Chicago"), zones(groups[1]))
        assertTrue("America/Chicago" !in zones(groups[2]), "a pinned zone is not repeated below")
        assertEquals(commonTimeZones.size - 1, groups[2].choices.size)
    }

    @Test
    fun oneZoneBothCurrentAndTheBrowsersIsPinnedOnceUnderTheStoredSpelling() {
        val groups = timeZoneOptions(catalog, "", browserZone = "US/Central", currentZone = "America/Chicago", summer)
        assertEquals(listOf("Current, and this browser's", "Common zones"), titles(groups))
        assertEquals(listOf("America/Chicago"), zones(groups[0]))
    }

    @Test
    fun theStoredSpellingIsKeptSoAnUntouchedDraftIsNoChange() {
        // A client stored under the old name keeps it, labeled as the zone it is.
        val groups = timeZoneOptions(catalog, "", browserZone = null, currentZone = "Asia/Calcutta", summer)
        assertEquals(listOf("Asia/Calcutta"), zones(groups[0]))
        assertEquals(timeZoneChoice("Asia/Kolkata", summer)?.label, groups[0].choices.single().label)
        assertTrue("Asia/Kolkata" !in zones(groups[1]), "the same zone is not offered again under its other name")
    }

    @Test
    fun theBrowsersOldSpellingIsOfferedUnderTheModernOne() {
        val groups = timeZoneOptions(catalog, "", browserZone = "Asia/Calcutta", currentZone = null, summer)
        assertEquals(listOf("This browser's", "Common zones"), titles(groups))
        assertEquals(listOf("Asia/Kolkata"), zones(groups[0]))
    }

    @Test
    fun aCurrentZoneIntlDoesNotKnowIsStillShownAsItIs() {
        val groups = timeZoneOptions(catalog, "", browserZone = null, currentZone = "Mars/Olympus_Mons", summer)
        assertEquals("Mars/Olympus_Mons", groups[0].choices.single().label)
    }

    @Test
    fun noCurrentZoneMeansNoCurrentPin() {
        assertEquals(listOf("Common zones"), titles(timeZoneOptions(catalog, "", browserZone = null, currentZone = "", summer)))
    }

    @Test
    fun typingSearchesEveryZoneByPlaceNameOrId() {
        val knox = timeZoneOptions(catalog, "knox", browserZone = null, currentZone = null, summer)
        assertEquals(listOf("America/Indiana/Knox"), zones(knox.single()))
        // A name matches every zone carrying it; `_` in an id reads as a space.
        assertTrue(zones(timeZoneOptions(catalog, "central time", null, null, summer).single())
            .containsAll(listOf("America/Chicago", "America/Indiana/Knox")))
        assertEquals(listOf("America/St_Johns"), zones(timeZoneOptions(catalog, "st johns", null, null, summer).single()))
        assertEquals("Matching zones", timeZoneOptions(catalog, "knox", null, null, summer).single().title)
    }

    @Test
    fun anOffsetMatchesTheZonesAtExactlyThatOffset() {
        val minusFive = zones(timeZoneOptions(catalog, "-5", null, null, summer).single())
        assertTrue("America/Chicago" in minusFive && "America/Indiana/Knox" in minusFive)
        assertTrue("America/Denver" !in minusFive, "GMT-6 is not GMT-5")
        assertEquals(minusFive, zones(timeZoneOptions(catalog, "GMT\u22125", null, null, summer).single()))
        assertEquals(listOf("Asia/Kolkata"), zones(timeZoneOptions(catalog, "+5:30", null, null, summer).single()))
        // An hour is that hour exactly: +5 is Karachi, not India's +5:30 or Nepal's +5:45.
        assertEquals(listOf("Asia/Karachi"), zones(timeZoneOptions(catalog, "+5", null, null, summer).single()))
        assertEquals(listOf("Asia/Kathmandu"), zones(timeZoneOptions(catalog, "utc+05:45", null, null, summer).single()))
    }

    @Test
    fun zeroIsAnOffsetToo() {
        // Intl writes a zero offset as a bare "GMT", which a typed +0, GMT or UTC must still reach.
        // In January, when London and Lisbon are on GMT too.
        val january = timeZoneCatalog(listed, winter)
        val zero = listOf("+0", "-0", "GMT", "utc", "GMT+0", "UTC+00:00").map { zones(timeZoneOptions(january, it, null, null, winter).single()) }
        assertTrue(zero.all { it == zero[0] }, "every spelling of zero finds the same zones: $zero")
        assertTrue(zero[0].containsAll(listOf("Europe/London", "Europe/Lisbon", "UTC")))
        assertTrue("Europe/Paris" !in zero[0], "GMT+1 is not GMT")
    }

    @Test
    fun zeroMatchesHoweverTheEngineSpellsIt() {
        // Node writes a zero offset "GMT", Chrome "GMT+0": a typed zero must find both.
        val node = TimeZoneChoice("Africa/Accra", "Accra", "GMT", "Africa/Accra")
        val chrome = TimeZoneChoice("Africa/Abidjan", "Abidjan", "GMT+0", "Africa/Abidjan")
        val plusOne = TimeZoneChoice("Europe/London", "London", "GMT+1", "Europe/London")
        for (query in listOf("+0", "gmt", "UTC+00:00")) {
            val matches = timeZoneMatcher(query)
            assertEquals(listOf(true, true, false), listOf(node, chrome, plusOne).map(matches), "for '$query'")
        }
        assertTrue(timeZoneMatcher("+1")(plusOne))
    }

    @Test
    fun pinsAreLabeledWhenOnlyTheShortListIsLoaded() {
        // Before a search the picker holds the short list alone: a pin off it is labeled by itself, not shown as an id.
        val short = timeZoneCatalog(emptyList(), summer)
        val groups = timeZoneOptions(short, "", browserZone = "Asia/Calcutta", currentZone = "America/Indiana/Knox", summer)
        assertEquals(listOf("Current", "This browser's", "Common zones"), titles(groups))
        assertEquals("Central Time (Knox) \u00b7 GMT\u22125", groups[0].choices.single().label)
        assertEquals(listOf("Asia/Kolkata"), zones(groups[1]))
    }

    @Test
    fun aBrowserReportingAnOffsetIsNotPinned() {
        assertEquals(listOf("Common zones"), titles(timeZoneOptions(catalog, "", "Etc/GMT+5", null, summer)))
    }

    @Test
    fun aSearchKeepsOnlyThePinsThatMatchIt() {
        val groups = timeZoneOptions(catalog, "knox", browserZone = "America/Chicago", currentZone = "America/Indiana/Knox", summer)
        assertEquals(listOf("Current"), titles(groups))
        assertEquals(emptyList(), timeZoneOptions(catalog, "nowhere at all", "America/Chicago", "America/Indiana/Knox", summer))
    }

    @Test
    fun aReadOnlyRowShowsTheLabelAndTheStoredId() {
        assertEquals("Central Time (Chicago) \u00b7 GMT\u22125 \u00b7 America/Chicago", timeZoneText("America/Chicago", summer))
        assertEquals("Mars/Olympus_Mons", timeZoneText("Mars/Olympus_Mons", summer))
        assertEquals("None: the deployment's", timeZoneText(null, summer))
        assertEquals("None: the deployment's", timeZoneText(" ", summer))
    }
}
