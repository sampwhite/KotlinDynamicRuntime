package com.dynamicruntime.webapp

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.main
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useState
import web.cssom.ClassName

/** Coroutine scope for the page's suspend fetch. */
private val cfactScope = MainScope()

/**
 * The cfact reference page (issue #488): it fetches the whole Markdown document the backend assembled -- every
 * cfact the caller's client knows, and whether each is present for this caller right now -- and renders it with
 * [Markdown], the same component the environment-variable reference and the README use. The frontend composes
 * none of the document; it asks for it and shows it.
 *
 * Re-fetched on every refresh generation, so returning to the page (or an idle bump) shows the current present
 * set rather than a snapshot from when it first mounted. It reuses the same `home-shell`/`home-main` chrome as
 * the environment reference so a rendered document looks the same wherever it is shown.
 *
 * The route exists unconditionally (like the environment reference); the backend endpoint is
 * `clientOperator`-gated, so a caller without the role sees the refusal reported here rather than a hidden page.
 * The nav entry that leads here is offered only to a client-scoped operator or admin (the server-built menu
 * decides), and a client may suppress it -- acme does.
 */
val CFactReferencePage = FC<Props> {
    var markdown by useState<String?>(null)
    var error by useState<String?>(null)
    val generation = useRefreshGeneration()

    useEffect(generation) {
        cfactScope.launch {
            try {
                markdown = CFactReferenceApi.fetch()
                error = null
            } catch (e: Throwable) {
                error = "Could not load the cfact reference. (${e.message})"
            }
        }
    }

    div {
        className = ClassName("home-shell")
        main {
            className = ClassName("home-main")
            when {
                error != null -> p {
                    className = ClassName("error-text")
                    +error!!
                }
                markdown == null -> p {
                    className = ClassName("subtitle")
                    +"Loading…"
                }
                else -> Markdown { source = markdown!! }
            }
        }
    }
}
