package com.dynamicruntime.edge

import com.dynamicruntime.common.http.request.ContextRoot

/**
 * The context roots a KdrEdge node serves its own surface under (issue #386).
 *
 * They live **here rather than in [ContextRoot]**, which is `base/common`'s vocabulary: an ordinary node has no
 * business naming the edge's roots, and keeping them apart is what makes "no component compile-depends on
 * KdrEdge" hold rather than merely be intended. Nothing in common changes to *use* them --
 * `ACFG.apiContextRoot` and its siblings are already instance config, bound in `RequestService.checkInit`, so
 * an edge instance simply supplies different values.
 *
 * **A shared leading `e`, two characters each.** The `e` is the tell: `k`, `c`, `w` and `s` open the
 * application's roots, so "starts with e" is readable at a glance in a log line without parsing the whole
 * segment. Two characters keeps them clear of third-party path prefixes, which are word-like (`/grafana`,
 * `/opensearch`), so short roots and long prefixes never compete.
 *
 * The mechanical alternative -- `eda`/`ecp`/`ewa`/`est` -- was rejected: `eda` differs from `kda` by one
 * character in the *middle*, and the entire purpose of separate roots is knowing which server answered. The same
 * objection that settled the edge's port at 8010 rather than 7080 (issue #377), and the same failure behind
 * it: a misread does not error, it answers plausibly from the wrong place.
 */
@Suppress("ConstPropertyName")
object EdgeRoot {
    /** The edge's API root -- "edge api". Its own endpoints; never the application's. */
    const val ea = "ea"

    /** The edge's content root -- "edge content". The Env Auth login page is served here. */
    const val ec = "ec"

    /** The edge's app root -- "edge web app". The edge's own front end. */
    const val ew = "ew"

    /** The edge's static root -- "edge static". Immutable assets, and the origin-root browser conventions. */
    const val es = "es"

    /** All four, for the collision check that refuses to boot when one matches an application root. */
    val all: List<String> = listOf(ea, ec, ew, es)
}

/** What an edge shows of itself in the shell (issue #446). */
@Suppress("ConstPropertyName")
object EDGEUI {
    /** The `home` fragment key holding the shell's wordmark, which the app bar and the home hero both read. */
    const val brandKey = "brand"

    /** The wordmark an edge shows: the product, marked as the perimeter rather than the application. */
    const val brand = "KDR Edge"

    /**
     * The id of the env-logout menu item an edge contributes (issue #486). Its own id, distinct from the
     * application's `HMENU.logout`: the two never share a node, but a distinct id keeps the overlay
     * unambiguous about which item it adds, and reads correctly in a menu snapshot.
     */
    const val logoutItem = "envLogout"

    /**
     * Where the env-logout item sorts. Well past the base menu's numbering (its items land at
     * [com.dynamicruntime.common.uiblock.UIB.orderStep]-spaced positions from 100), so signing out sits last
     * as a logout conventionally does -- and an overlay must state its own order, since only a base's items
     * are auto-numbered.
     */
    const val logoutOrder = 1000
}
