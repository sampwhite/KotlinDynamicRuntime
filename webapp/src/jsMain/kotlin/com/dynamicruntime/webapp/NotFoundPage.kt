package com.dynamicruntime.webapp

import kotlinx.coroutines.awaitCancellation
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.useEffectOnce
import web.cssom.ClassName

/**
 * What a path the app has no route for shows (issue #1125). The server answers every path that is not a file with
 * the app, so this page is the one thing that can say there is no such page -- to a person, and to a crawler, which
 * the `noindex` it writes into the head while it is on screen tells not to index the address.
 */
val NotFoundPage = FC<Props> {
    useEffectOnce {
        val meta: dynamic = js("document.createElement('meta')")
        meta.name = "robots"
        meta.content = "noindex"
        js("document.head.appendChild(meta)")
        try {
            awaitCancellation()
        } finally {
            meta.remove()
        }
    }
    div {
        className = ClassName("card wide")
        h1 { +"Page not found" }
        p {
            className = ClassName("subtitle")
            +"There is no page at this address. The link may be mistyped, or the page may have moved."
        }
        p {
            a {
                href = routeHref(emptyList())
                +"Go to the home page"
            }
        }
    }
}
