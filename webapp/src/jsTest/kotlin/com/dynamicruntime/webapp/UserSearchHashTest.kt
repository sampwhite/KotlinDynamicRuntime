package com.dynamicruntime.webapp

import com.dynamicruntime.common.user.USF
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shareable user-search URL (issue #411): the search encodes to hash params and reads back to the same
 * query, only the non-default parts appear (so a plain search is a clean URL), and a hand-edited or stale link
 * cannot ask for a sort field the endpoint would reject. Pure -- no React, no location.
 */
class UserSearchHashTest {

    private fun roundTrip(q: UserSearchQuery): UserSearchQuery =
        searchQueryFromHash(searchHashParams(q).toMap())

    @Test
    fun aDefaultSearchEmitsNoParams() {
        assertTrue(searchHashParams(UserSearchQuery()).isEmpty())
    }

    @Test
    fun onlyNonDefaultPartsAreEmitted() {
        val params = searchHashParams(
            UserSearchQuery(email = "ada", sortBy = USF.name, descending = false),
        ).toMap()
        assertEquals("ada", params[HP.qEmail])
        assertEquals(USF.name, params[HP.qSort])
        assertEquals("0", params[HP.qDesc])
        // Untouched fields carry nothing.
        assertEquals(null, params[HP.qName])
        assertEquals(null, params[HP.qClient])
    }

    @Test
    fun aFullSearchRoundTrips() {
        val q = UserSearchQuery(
            email = "a@b", name = "Ada", client = "acme",
            updatedAfter = "2026-01-01T00:00:00.000Z", updatedBefore = "2026-06-01T00:00:00.000Z",
            sortBy = USF.client, descending = false,
        )
        val back = roundTrip(q)
        assertEquals("a@b", back.email)
        assertEquals("Ada", back.name)
        assertEquals("acme", back.client)
        assertEquals("2026-01-01T00:00:00.000Z", back.updatedAfter)
        assertEquals("2026-06-01T00:00:00.000Z", back.updatedBefore)
        assertEquals(USF.client, back.sortBy)
        assertEquals(false, back.descending)
    }

    @Test
    fun anEmptyHashIsTheDefaultSearch() {
        val q = searchQueryFromHash(emptyMap())
        assertEquals("", q.email)
        assertEquals(USF.updatedAt, q.sortBy)
        assertEquals(true, q.descending)
    }

    @Test
    fun anUnknownSortKeyFallsBackToTheDefault() {
        // A hand-edited or stale link must not send a sort the endpoint would 400 on.
        val q = searchQueryFromHash(mapOf(HP.qSort to "bogusField"))
        assertEquals(USF.updatedAt, q.sortBy)
        // publicName is a backend axis but not a console column, so it is not an accepted hash sort either.
        assertEquals(USF.updatedAt, searchQueryFromHash(mapOf(HP.qSort to USF.publicName)).sortBy)
    }
}
