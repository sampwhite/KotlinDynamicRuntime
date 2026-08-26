package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.USF
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shareable user-search URL (issue #411): the search encodes to hash params and reads back to the same
 * query, only the non-default parts appear (so a plain search is a clean URL), and a hand-edited or stale link
 * cannot ask for a sort field the endpoint would reject. The hash keys are the endpoint's own arg names (from
 * the shared spec), so this exercises the spec-driven encoding too. Pure -- no React, no location.
 */
class UserSearchHashTest {

    private fun roundTrip(q: UserSearchQuery): UserSearchQuery =
        searchQueryFromHash(searchHashParams(q).toMap())

    @Test
    fun aDefaultSearchEmitsNoParams() {
        assertTrue(searchHashParams(UserSearchQuery()).isEmpty())
    }

    @Test
    fun onlyNonDefaultPartsAreEmittedUnderTheWireKeys() {
        val params = searchHashParams(
            UserSearchQuery(textTerms = mapOf(USF.email to "ada"), sortBy = USF.name, descending = false),
        ).toMap()
        assertEquals("ada", params[USF.email])
        assertEquals(USF.name, params[USF.sortBy])
        assertEquals("false", params[USF.descending])
        // Untouched fields carry nothing.
        assertEquals(null, params[USF.client])
    }

    @Test
    fun aFullSearchRoundTrips() {
        val q = UserSearchQuery(
            textTerms = mapOf(USF.email to "a@b", USF.name to "Ada", USF.client to "acme"),
            ranges = mapOf(USF.lastEdited.at to DateRange("2026-01-01T00:00:00.000Z", "2026-06-01T00:00:00.000Z")),
            sortBy = USF.client, descending = false,
        )
        val back = roundTrip(q)
        assertEquals("a@b", back.textTerms[USF.email])
        assertEquals("Ada", back.textTerms[USF.name])
        assertEquals("acme", back.textTerms[USF.client])
        assertEquals("2026-01-01T00:00:00.000Z", back.ranges[USF.lastEdited.at]?.after)
        assertEquals("2026-06-01T00:00:00.000Z", back.ranges[USF.lastEdited.at]?.before)
        assertEquals(USF.client, back.sortBy)
        assertEquals(false, back.descending)
    }

    @Test
    fun anEmptyHashIsTheDefaultSearch() {
        val q = searchQueryFromHash(emptyMap())
        assertTrue(q.textTerms.isEmpty())
        assertTrue(q.ranges.isEmpty())
        assertEquals(USF.lastEdited.at, q.sortBy)
        assertEquals(true, q.descending)
    }

    @Test
    fun anUnknownSortKeyFallsBackToTheDefault() {
        // A hand-edited or stale link must not send a sort the endpoint would 400 on.
        assertEquals(USF.lastEdited.at, searchQueryFromHash(mapOf(USF.sortBy to "bogusField")).sortBy)
        // publicName is a backend axis but not a console column, so it is not an accepted hash sort either.
        assertEquals(USF.lastEdited.at, searchQueryFromHash(mapOf(USF.sortBy to USF.publicName)).sortBy)
    }
}
