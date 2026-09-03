package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HMENU
import react.ChildrenBuilder
import react.dom.html.ReactHTML.a
import web.cssom.ClassName

/**
 * Back navigation from a child page to the listing it was opened from (issue #554).
 *
 * A listing that opens a child writes [HP.from] naming itself, and the child renders [backToListing], which
 * returns there -- or, when the child was reached some other way (the menu, a bookmark, a pasted link), to
 * the child's *natural parent*, the [fallback] it names. One mechanism in one place, so every listing/child
 * pair is correct by construction rather than one more hand-coded link per page that knows nothing of where
 * you came from -- and so a child that had no way back at all (the operator tool pages) gets one for free.
 *
 * Only a known [backListings] page is honoured as a `from`: a URL may say anything, and a back link must never
 * send someone to an arbitrary page a pasted address happened to name. That check is [backTarget], pure and
 * covered under `jsNodeTest`.
 */

/** A listing page a child may be opened from, and how its back link names it. */
class BackListing(val page: String, val label: String)

/** The listings a child may return to, by page id. Documents joins this in a later slice of #554. */
val backListings: Map<String, BackListing> = listOf(
    BackListing(HMENU.pageOperator, "Operator"),
    BackListing(HMENU.pageForms, "My forms"),
    BackListing(HMENU.pageUsers, "Users"),
    BackListing(HMENU.pageCatalog, "Endpoint catalog"),
    BackListing(HMENU.pageDebug, "Debug"),
).associateBy { it.page }

/**
 * The page a child's back link goes to: [from] when it names a known listing, else [fallback], the child's
 * natural parent. An unknown or absent `from` -- a menu-reached page, a bookmark, a tampered URL -- always
 * lands on the fallback.
 */
fun backTarget(from: String?, fallback: String): String =
    if (from != null && from in backListings) from else fallback

/** The label a back link shows for [page]: the listing's own name, or the page id when it is not a listing. */
fun backLabel(page: String): String = backListings[page]?.label ?: page

/** The href that opens [childPage] from [listing], carrying [HP.from] so the child can find its way back. */
fun childHref(childPage: String, listing: String, vararg extra: Pair<String, String>): String =
    hashHref(listOf(HP.page to childPage, HP.from to listing) + extra)

/** The `← Listing` link atop a child page: to the listing it was opened from, or to [fallback]. */
fun ChildrenBuilder.backToListing(fallback: String) {
    val target = backTarget(hashParams()[HP.from], fallback)
    a {
        className = ClassName("back-link")
        href = hashHref(listOf(HP.page to target))
        +"← ${backLabel(target)}"
    }
}
