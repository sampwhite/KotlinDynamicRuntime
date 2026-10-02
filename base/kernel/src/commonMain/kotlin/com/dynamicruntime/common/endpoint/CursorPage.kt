package com.dynamicruntime.common.endpoint

import com.dynamicruntime.common.exception.KdrException

/**
 * One page cut from an ordered list by a cursor (issue #976): the page's [items], whether more remain after it
 * ([hasMore]), and how many the whole list holds ([numAvailable]).
 */
class CursorSlice<T>(val items: List<T>, val hasMore: Boolean, val numAvailable: Int)

/**
 * The page of [sorted] that follows [afterKey] -- the first [limit] items whose key is **greater than** it under
 * [cmp], or the first [limit] of all when [afterKey] is null (issue #976).
 *
 * [sorted] must already be in [cmp] order of [keyOf], with no two items sharing a key: the order has to be total, or
 * a page boundary falling between two equal keys would drop the second. The resume point is found by binary search,
 * so a page costs a search and a copy however far into the list it starts.
 *
 * ### Why "greater than the key" rather than "after the item"
 *
 * A cursor names a key, never a position, and that is what makes a walk survive a changing list. The item the last
 * page ended on may be gone by the next request -- deleted, or no longer matching a filter -- and the walk still
 * resumes at the next key, having skipped nothing and repeated nothing. An offset has neither property: an item
 * removed before it shifts every later page by one.
 *
 * It holds only for a key that **never changes** for an item. Ordering by something an edit moves (a last-updated
 * date, a count) lets an item cross the cursor between pages, to be skipped or returned twice, so a cursor-paged
 * listing orders by an immutable key -- an id.
 *
 * A [limit] below one is refused as bad input: a page with no last item has no key to continue after.
 */
fun <T, K> cursorSlice(
    sorted: List<T>,
    afterKey: K?,
    limit: Int,
    keyOf: (T) -> K,
    cmp: Comparator<in K>,
): CursorSlice<T> {
    if (limit < 1) {
        throw KdrException.mkInput("A cursor-paged listing needs a limit of at least 1; got $limit.")
    }
    val start = if (afterKey == null) 0 else firstAfter(sorted, afterKey, keyOf, cmp)
    val end = minOf(sorted.size, start + limit)
    return CursorSlice(sorted.subList(start, end).toList(), hasMore = end < sorted.size, numAvailable = sorted.size)
}

/** The index of the first item of [sorted] whose key is greater than [afterKey]; `sorted.size` when none is. */
private fun <T, K> firstAfter(sorted: List<T>, afterKey: K, keyOf: (T) -> K, cmp: Comparator<in K>): Int {
    var low = 0
    var high = sorted.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (cmp.compare(keyOf(sorted[mid]), afterKey) <= 0) low = mid + 1 else high = mid
    }
    return low
}
