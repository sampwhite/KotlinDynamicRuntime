package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.USF
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The users console's filter chips (issue #683): the filters in force said in words while the panel is closed,
 * built from the shared spec so a chip can never disagree with the count; the sort as its own chip; and the
 * shared chip vocabulary both listing pages draw on. Pure -- no React, no location -- like the hash round-trip
 * beside it.
 */
class UserFilterChipsTest {

    @Test
    fun nothingInForceGivesNoChips() {
        assertEquals(emptyList(), userFilterChips(emptyMap(), emptyMap()))
        // A whitespace-only term is kept while it is typed but filters nothing, so it is not a chip either.
        assertEquals(emptyList(), userFilterChips(mapOf(USF.email to "  "), emptyMap()))
        // A bound present but blank (a hand-edited link) is no bound.
        assertEquals(emptyList(), userFilterChips(emptyMap(), mapOf(USF.lastEdited.at to DateRange("", null))))
    }

    @Test
    fun eachFilterKindReadsInItsOwnWordsAndDatesOnTheTablesClock() {
        val chips = userFilterChips(
            texts = mapOf(USF.email to "ada", USF.name to "Lovelace", USF.client to "acme"),
            ranges = mapOf(
                USF.lastEdited.at to DateRange("2026-01-01T00:00:00.000Z", "2026-06-01T00:00:00.000Z"),
                USF.lastLoggedIn.at to DateRange("2026-08-01T10:00:00.000Z", null),
                USF.activated.at to DateRange(null, "2025-12-31T00:00:00.000Z"),
            ),
        )
        // A date bound reads exactly as the table renders the column it filters: UTC, to the minute.
        assertEquals(
            listOf(
                "Email contains \"ada\"",
                "Name contains \"Lovelace\"",
                "Client is \"acme\"",
                "Edited 2026-01-01 00:00 UTC – 2026-06-01 00:00 UTC",
                "Last login ≥ 2026-08-01 10:00 UTC",
                "Activated ≤ 2025-12-31 00:00 UTC",
            ),
            chips,
        )
    }

    @Test
    fun aClientTermIsAChipWhoeverTheCallerIs() {
        // A shared link may carry a client term the caller's own well does not offer. It still counts as a
        // filter (Clear resets it, the count line reports it), so it must be visible somewhere -- and the chip is
        // the only somewhere.
        assertEquals(listOf("Client is \"acme\""), userFilterChips(mapOf(USF.client to "acme"), emptyMap()))
    }

    @Test
    fun theSortIsItsOwnChipAndOnlyWhenNotTheDefault() {
        // The default -- newest edit first -- is not a chip.
        assertNull(userSortChip(USF.lastEdited.at, descending = true))
        // The default field the other way round, and a date field's words are about time.
        assertEquals("Sorted by Edited, oldest first", userSortChip(USF.lastEdited.at, descending = false))
        assertEquals("Sorted by Last login, newest first", userSortChip(USF.lastLoggedIn.at, descending = true))
        // A text field's words are alphabetical.
        assertEquals("Sorted by Name, A–Z", userSortChip(USF.name, descending = false))
        assertEquals("Sorted by Email, Z–A", userSortChip(USF.email, descending = true))
    }

    @Test
    fun theSharedChipWordsDropABlankValue() {
        assertNull(textChip("Name", "  ", contains = true))
        assertEquals("Name is \"x\"", textChip("Name", "x", contains = false))
        assertNull(rangeChip("Year", " ", null))
        assertEquals("Year ≥ 2020", rangeChip("Year", "2020", " "))
        assertEquals("Year ≤ 2025", rangeChip("Year", null, "2025"))
    }

    @Test
    fun theToggleReportsTheCountWhileClosed() {
        assertEquals("Filters", filterToggleLabel(open = false, filterCount = 0))
        assertEquals("Filters (2)", filterToggleLabel(open = false, filterCount = 2))
        assertEquals("Hide filters", filterToggleLabel(open = true, filterCount = 2))
    }
}
