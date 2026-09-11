package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.USF
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The users console's filter chips (issue #683): the filters in force said in words while the panel is closed,
 * built from the shared spec so a chip can never disagree with the count, plus a chip for a non-default sort.
 * Pure -- no React, no location -- like the hash round-trip beside it.
 */
class UserFilterChipsTest {

    @Test
    fun nothingInForceGivesNoChips() {
        assertEquals(emptyList(), userFilterChips(emptyMap(), emptyMap(), USF.lastEdited.at, true, showClient = true))
        // A whitespace-only term is kept while it is typed but filters nothing, so it is not a chip either.
        assertEquals(emptyList(), userFilterChips(mapOf(USF.email to "  "), emptyMap(), USF.lastEdited.at, true, showClient = true))
    }

    @Test
    fun eachFilterKindReadsInItsOwnWords() {
        val chips = userFilterChips(
            texts = mapOf(USF.email to "ada", USF.name to "Lovelace", USF.client to "acme"),
            ranges = mapOf(
                USF.lastEdited.at to DateRange("2026-01-01T00:00:00.000Z", "2026-06-01T00:00:00.000Z"),
                USF.lastLoggedIn.at to DateRange("2026-08-01T10:00:00.000Z", null),
                USF.activated.at to DateRange(null, "2025-12-31T00:00:00.000Z"),
            ),
            sortBy = USF.lastEdited.at,
            descending = true,
            showClient = true,
        )
        assertEquals(
            listOf(
                "Email contains \"ada\"",
                "Name contains \"Lovelace\"",
                "Client is \"acme\"",
                "Edited 2026-01-01T00:00:00.000Z – 2026-06-01T00:00:00.000Z",
                "Last login ≥ 2026-08-01T10:00:00.000Z",
                "Activated ≤ 2025-12-31T00:00:00.000Z",
            ),
            chips,
        )
    }

    @Test
    fun aBoundIsRenderedThroughTheGivenFormatter() {
        val chips = userFilterChips(
            emptyMap(), mapOf(USF.lastEdited.at to DateRange("2026-01-01T00:00:00.000Z", null)),
            USF.lastEdited.at, true, showClient = true, fmtInstant = { it.substring(0, 10) },
        )
        assertEquals(listOf("Edited ≥ 2026-01-01"), chips)
    }

    @Test
    fun theClientChipIsOnlyForACallerWhoSeesTheClientField() {
        // A shared link may carry a client term the caller's own view does not offer; it filters nothing they
        // can see and gets no chip, matching the field it would sit beside.
        val texts = mapOf(USF.client to "acme")
        assertEquals(emptyList(), userFilterChips(texts, emptyMap(), USF.lastEdited.at, true, showClient = false))
        assertEquals(listOf("Client is \"acme\""), userFilterChips(texts, emptyMap(), USF.lastEdited.at, true, showClient = true))
    }

    @Test
    fun aNonDefaultSortIsAChipTooSinceClearResetsIt() {
        fun sortChips(sortBy: String, descending: Boolean) =
            userFilterChips(emptyMap(), emptyMap(), sortBy, descending, showClient = true)
        // The default -- newest edit first -- is not a chip.
        assertEquals(emptyList(), sortChips(USF.lastEdited.at, true))
        // The default field the other way round, and a date field's words are about time.
        assertEquals(listOf("Sorted by Edited, oldest first"), sortChips(USF.lastEdited.at, false))
        assertEquals(listOf("Sorted by Last login, newest first"), sortChips(USF.lastLoggedIn.at, true))
        // A text field's words are alphabetical.
        assertEquals(listOf("Sorted by Name, A–Z"), sortChips(USF.name, false))
        assertEquals(listOf("Sorted by Email, Z–A"), sortChips(USF.email, true))
    }

    @Test
    fun theToggleReportsTheCountWhileClosed() {
        assertEquals("Filters", filterToggleLabel(open = false, chipCount = 0))
        assertEquals("Filters (2)", filterToggleLabel(open = false, chipCount = 2))
        assertEquals("Hide filters", filterToggleLabel(open = true, chipCount = 2))
    }
}
