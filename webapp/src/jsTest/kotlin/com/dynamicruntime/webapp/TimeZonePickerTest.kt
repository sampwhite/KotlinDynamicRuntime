package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

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
        val groups = timeZoneOptions(catalog, "", browserZone = "America/Chicago", currentZone = "America/Indiana/Knox")
        assertEquals(listOf("Current", "This browser's", "Common zones"), titles(groups))
        assertEquals(listOf("America/Indiana/Knox"), zones(groups[0]))
        assertEquals(listOf("America/Chicago"), zones(groups[1]))
        assertTrue("America/Chicago" !in zones(groups[2]), "a pinned zone is not repeated below")
        assertEquals(commonTimeZones.size - 1, groups[2].choices.size)
    }

    @Test
    fun oneZoneBothCurrentAndTheBrowsersIsPinnedOnceUnderTheStoredSpelling() {
        val groups = timeZoneOptions(catalog, "", browserZone = "US/Central", currentZone = "America/Chicago")
        assertEquals(listOf("Current, and this browser's", "Common zones"), titles(groups))
        assertEquals(listOf("America/Chicago"), zones(groups[0]))
    }

    @Test
    fun theStoredSpellingIsKeptSoAnUntouchedDraftIsNoChange() {
        // A client stored under the old name keeps it, labeled as the zone it is.
        val groups = timeZoneOptions(catalog, "", browserZone = null, currentZone = "Asia/Calcutta")
        assertEquals(listOf("Asia/Calcutta"), zones(groups[0]))
        assertEquals(timeZoneChoice("Asia/Kolkata", summer)?.label, groups[0].choices.single().label)
        assertTrue("Asia/Kolkata" !in zones(groups[1]), "the same zone is not offered again under its other name")
    }

    @Test
    fun theBrowsersOldSpellingIsOfferedUnderTheModernOne() {
        val groups = timeZoneOptions(catalog, "", browserZone = "Asia/Calcutta", currentZone = null)
        assertEquals(listOf("This browser's", "Common zones"), titles(groups))
        assertEquals(listOf("Asia/Kolkata"), zones(groups[0]))
    }

    @Test
    fun aCurrentZoneIntlDoesNotKnowIsStillShownAsItIs() {
        val groups = timeZoneOptions(catalog, "", browserZone = null, currentZone = "Mars/Olympus_Mons")
        assertEquals("Mars/Olympus_Mons", groups[0].choices.single().label)
    }

    @Test
    fun noCurrentZoneMeansNoCurrentPin() {
        assertEquals(listOf("Common zones"), titles(timeZoneOptions(catalog, "", browserZone = null, currentZone = "")))
    }

    @Test
    fun typingSearchesEveryZoneByPlaceNameOrId() {
        val knox = timeZoneOptions(catalog, "knox", browserZone = null, currentZone = null)
        assertEquals(listOf("America/Indiana/Knox"), zones(knox.single()))
        // A name matches every zone carrying it; `_` in an id reads as a space.
        assertTrue(zones(timeZoneOptions(catalog, "central time", null, null).single())
            .containsAll(listOf("America/Chicago", "America/Indiana/Knox")))
        assertEquals(listOf("America/St_Johns"), zones(timeZoneOptions(catalog, "st johns", null, null).single()))
        assertEquals("Matching zones", timeZoneOptions(catalog, "knox", null, null).single().title)
    }

    @Test
    fun anOffsetMatchesTheZonesAtExactlyThatOffset() {
        val minusFive = zones(timeZoneOptions(catalog, "-5", null, null).single())
        assertTrue("America/Chicago" in minusFive && "America/Indiana/Knox" in minusFive)
        assertTrue("America/Denver" !in minusFive, "GMT-6 is not GMT-5")
        assertEquals(minusFive, zones(timeZoneOptions(catalog, "GMT\u22125", null, null).single()))
        assertEquals(listOf("Asia/Kolkata"), zones(timeZoneOptions(catalog, "+5:30", null, null).single()))
        // An hour is that hour exactly: +5 is Karachi, not India's +5:30 or Nepal's +5:45.
        assertEquals(listOf("Asia/Karachi"), zones(timeZoneOptions(catalog, "+5", null, null).single()))
        assertEquals(listOf("Asia/Kathmandu"), zones(timeZoneOptions(catalog, "utc+05:45", null, null).single()))
    }

    @Test
    fun aSearchKeepsOnlyThePinsThatMatchIt() {
        val groups = timeZoneOptions(catalog, "knox", browserZone = "America/Chicago", currentZone = "America/Indiana/Knox")
        assertEquals(listOf("Current"), titles(groups))
        assertEquals(emptyList(), timeZoneOptions(catalog, "nowhere at all", "America/Chicago", "America/Indiana/Knox"))
    }

    @Test
    fun aReadOnlyRowShowsTheLabelAndTheStoredId() {
        assertEquals("Central Time (Chicago) \u00b7 GMT\u22125 \u00b7 America/Chicago", timeZoneText("America/Chicago", summer))
        assertEquals("Mars/Olympus_Mons", timeZoneText("Mars/Olympus_Mons", summer))
        assertEquals("None: the deployment's", timeZoneText(null, summer))
        assertEquals("None: the deployment's", timeZoneText(" ", summer))
    }
}
