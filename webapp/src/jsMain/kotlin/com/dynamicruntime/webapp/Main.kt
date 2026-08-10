package com.dynamicruntime.webapp

import react.create
import react.dom.client.createRoot
import web.dom.ElementId
import web.dom.document

/**
 * Browser entry point. The Kotlin/JS bundle's `main` runs as soon as the
 * `<script>` in index.html loads, mounts the React tree onto the `#root`
 * element, and hands control to the [App] component.
 */
fun main() {
    initLogging() // frontend logging via the shared KdrLogger -> browser console (issue #79)
    // Before the tree is mounted, so a failure during that first render is reported rather than lost. Covers
    // what an error boundary structurally cannot -- handlers, effects, rejected promises (issue #223).
    installGlobalErrorHandlers()
    val container = document.getElementById(ElementId("root"))
        ?: error("Couldn't find the #root container in index.html")
    createRoot(container).render(App.create())
}
