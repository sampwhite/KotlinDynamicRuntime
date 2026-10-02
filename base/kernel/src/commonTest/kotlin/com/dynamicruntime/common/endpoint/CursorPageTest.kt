package com.dynamicruntime.common.endpoint

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cutting a page from an ordered list by a cursor (issue #976). Shared source, so it runs on the JVM and in JS. */
class CursorPageTest {
    private val cmp = naturalOrder<String>()

    private fun slice(items: List<String>, after: String?, limit: Int) = cursorSlice(items, after, limit, { it }, cmp)

    @Test
    fun theFirstPageStartsTheListAndSaysMoreRemain() {
        val page = slice(listOf("a", "b", "c", "d", "e"), null, 2)
        assertEquals(listOf("a", "b"), page.items)
        assertTrue(page.hasMore)
        assertEquals(5, page.numAvailable)
    }

    @Test
    fun aPageResumesAfterItsKeyAndTheLastPageSaysNoMore() {
        val items = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("c", "d"), slice(items, "b", 2).items)
        val last = slice(items, "d", 2)
        assertEquals(listOf("e"), last.items)
        assertFalse(last.hasMore)
        // A page that ends exactly on the last item is the last page too.
        assertFalse(slice(items, "c", 2).hasMore)
        // Past the end: nothing, and nothing more.
        val past = slice(items, "e", 2)
        assertEquals(emptyList(), past.items)
        assertFalse(past.hasMore)
    }

    @Test
    fun aKeyThatIsGoneResumesAtTheNextKey() {
        // "b" was the last item of the previous page and has since been removed.
        assertEquals(listOf("c", "d"), slice(listOf("a", "c", "d", "e"), "b", 2).items)
        // A key before everything, and one after everything.
        assertEquals(listOf("a", "c"), slice(listOf("a", "c", "d"), "", 2).items)
        assertEquals(emptyList(), slice(listOf("a", "c", "d"), "z", 2).items)
    }

    @Test
    fun aLimitPastTheRemainderTakesWhatIsLeft() {
        val page = slice(listOf("a", "b", "c"), "a", 50)
        assertEquals(listOf("b", "c"), page.items)
        assertFalse(page.hasMore)
        assertEquals(emptyList(), slice(emptyList(), null, 5).items)
    }

    @Test
    fun aLimitBelowOneIsBadInput() {
        for (limit in listOf(0, -1)) {
            val e = assertFailsWith<KdrException> { slice(listOf("a"), null, limit) }
            assertEquals(EXC.badInput, e.code)
        }
    }

    @Test
    fun theKeyAndItsOrderAreTheCallersOwn() {
        // Items keyed by a number inside them, ordered descending: the comparator, not natural order, decides.
        val items = listOf("x9", "x7", "x4", "x1")
        val page = cursorSlice(items, 7, 2, { it.drop(1).toInt() }, reverseOrder())
        assertEquals(listOf("x4", "x1"), page.items)
    }

    @Test
    fun aWalkVisitsEveryLastingItemOnceWhileTheListChanges() {
        // Ids ascend, as time-sortable ids do; the list is edited between pages.
        val live = (1..9).map { "id0$it" }.toMutableList()
        val seen = mutableListOf<String>()
        var after: String? = null
        var page = 0
        while (true) {
            val slice = slice(live.sorted(), after, 3)
            seen += slice.items
            if (!slice.hasMore) break
            after = slice.items.last()
            page++
            when (page) {
                // After page one: remove the item the cursor sits on, one already seen, and one still ahead; add a
                // new one at the end.
                1 -> { live.remove("id03"); live.remove("id01"); live.remove("id05"); live.add("id10") }
                // After page two: add another, later still.
                2 -> live.add("id11")
            }
        }
        // Everything that existed for the whole walk, exactly once, in order; the removed-ahead item never appears,
        // and the items added during the walk are picked up at the end.
        assertEquals(listOf("id01", "id02", "id03", "id04", "id06", "id07", "id08", "id09", "id10", "id11"), seen)
        assertEquals(seen.size, seen.toSet().size)
    }
}
