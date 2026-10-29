package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.ComponentImageDef
import com.dynamicruntime.common.content.MarkdownDocDef
import com.dynamicruntime.common.content.componentImage

/**
 * The walk-through (issue #1429): a page for a person signed in to a reference deployment that takes them through the
 * site one step at a time, with what to choose and what to type -- the conference demo first. Its links go to pages
 * of the app, so it is linked from the signed-in visitor's home page alone (the `reference` flavor's `walkThrough`
 * copy) and not offered on the Documents list. On GitHub it reads as a page like any other.
 *
 * The tables of what the reader types are entered by `PublicWalkThroughTest`, through the endpoints the pages use, so
 * the page cannot fall behind the demo without a failing build.
 */
@Suppress("ConstPropertyName")
object WalkThrough {
    /** Its doc id: the build copies `docs/walk-through.md` onto the classpath under its own name. */
    const val docId = "walk-through"
    const val sourcePath = "docs/walk-through.md"

    val doc = MarkdownDocDef(
        docId, "A walk-through", sourcePath,
        "Try the site one step at a time: the conference demo, as a speaker and as its committee, and then a client of your own.",
        listed = false,
    )

    /** How many parts the page has, each opening with its banner. */
    const val partCount = 3

    /**
     * Each part's banner (issue #1463): kept in the repository at `docs/images/walk-part<n>.svg`, which the page names
     * it by, so it draws on GitHub from the file and in the app from the image served from it. The build copies the files
     * onto the classpath.
     */
    val images: List<ComponentImageDef> = (1..partCount).map {
        componentImage("kdr:walkPart$it", "walk-part$it.svg", sourcePath = "docs/images/walk-part$it.svg")
    }
}
