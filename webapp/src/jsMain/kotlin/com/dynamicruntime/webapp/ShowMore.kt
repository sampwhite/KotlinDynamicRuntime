package com.dynamicruntime.webapp

import react.ChildrenBuilder
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/*
 * A growing listing (issue #1315): the forms listing, the Users page and a report's run show a first batch, then grow
 * on request -- **Show 100 more**, or **Show all (N)** when N fits -- up to a cap. Search and sort are how a reader finds
 * a row in a long list, so none of them pages deeply; past the cap they say so and point at the search.
 *
 * Growing re-queries with the larger limit rather than splicing rows in by offset: one query is one consistent
 * snapshot of the current sort, so a form created or deleted between clicks never duplicates or skips a row, and the
 * page keeps one number -- how many rows are shown -- which rides the hash ([HP.shown]), so coming back from a form
 * asks for the same rows again.
 */

/** The rows a growing listing shows first, and adds per "Show more". */
const val listingBatch = 100

/** The most rows a growing listing shows; past it the reader narrows the search. */
const val listingCap = 500

/**
 * How many rows the hash asks a listing to show: [value] as a whole number, held to [listingBatch]..[listingCap];
 * absent, unreadable, or out of range reads as the nearest bound. Pure, covered under `jsNodeTest`.
 */
fun shownFromHash(value: String?): Int = (value?.trim()?.toIntOrNull() ?: listingBatch).coerceIn(listingBatch, listingCap)

/** The hash pair for [shown] rows: nothing for the first batch, so an untouched listing's address stays bare. Pure. */
fun shownHashParams(shown: Int): List<Pair<String, String>> =
    if (shown <= listingBatch) emptyList() else listOf(HP.shown to shown.toString())

/** What a growing listing offers next: the count "Show more" would grow to, and the count "Show all" would, each null when not offered. */
class ShowMoreChoice(val more: Int?, val all: Int?)

/**
 * What a growing listing offers next, given [shown] rows asked for and [available] in all. "Show all" is offered while
 * everything fits under the cap; when one batch covers the rest, "Show more" would be the same click, so only "Show
 * all" is offered. Pure, covered under `jsNodeTest`.
 */
fun showMoreChoice(shown: Int, available: Int): ShowMoreChoice {
    if (available <= shown || shown >= listingCap) return ShowMoreChoice(null, null)
    val all = available.takeIf { it <= listingCap }
    val more = (shown + listingBatch).coerceAtMost(listingCap).takeIf { all == null || it < available }
    return ShowMoreChoice(more, all)
}

/**
 * The count line of a growing listing: `132 forms` when every row is on screen, `Showing 100 of 650` while more can
 * be shown, and `Showing 500 of 812 — narrow the search to see the rest.` once the cap is reached. [one] and [many]
 * are the noun. Pure, covered under `jsNodeTest`.
 */
fun listingCountText(onScreen: Int, available: Int, one: String, many: String): String = when {
    available == 0 -> "No $many"
    available <= onScreen -> if (available == 1) "1 $one" else "$available $many"
    onScreen >= listingCap -> "Showing $onScreen of $available — narrow the search to see the rest."
    else -> "Showing $onScreen of $available"
}

/**
 * The bar under a growing listing: its count line ([listingCountText]) and the ways to grow it ([showMoreChoice]),
 * link-styled since they go further down the same list rather than change anything. [grow] is called with the new
 * count to show.
 */
fun ChildrenBuilder.showMoreBar(
    onScreen: Int,
    shown: Int,
    available: Int,
    one: String,
    many: String,
    busy: Boolean,
    grow: (Int) -> Unit,
) {
    val choice = showMoreChoice(shown, available)
    div {
        className = ClassName("row show-more")
        span {
            className = ClassName("type-hint")
            +listingCountText(onScreen, available, one, many)
        }
        choice.more?.let { n ->
            Button {
                type = "link"
                disabled = busy
                onClick = { grow(n) }
                +"Show ${n - shown} more"
            }
        }
        choice.all?.let { n ->
            Button {
                type = "link"
                disabled = busy
                onClick = { grow(n) }
                +"Show all ($n)"
            }
        }
    }
}
