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
private val envScope = MainScope()

/**
 * The operator environment-variable reference page (issue #371): it fetches the whole Markdown document the
 * backend assembled -- every variable this node declares, and the value each resolved to *here* -- and renders
 * it with [Markdown], the same component and renderer the README uses. The frontend composes none of the
 * document; it asks for it and shows it.
 *
 * Re-fetched on every refresh generation, so returning to the page (or an idle bump) shows the node's current
 * values rather than a snapshot from when it first mounted -- which is the whole reason a live view beats a
 * static file. It reuses the README page's `home-shell`/`home-main` chrome so a rendered document looks the
 * same wherever it is shown.
 *
 * The route exists unconditionally (like the Users page); the backend endpoint is operator-gated, so a caller
 * without the role sees the refusal reported here rather than a hidden page. The nav entry that leads here is
 * offered only to operators (the server-built menu decides).
 */
val EnvReferencePage = FC<Props> {
    var markdown by useState<String?>(null)
    var error by useState<String?>(null)
    val generation = useRefreshGeneration()

    useEffect(generation) {
        envScope.launch {
            try {
                markdown = EnvReferenceApi.fetch()
                error = null
            } catch (e: Throwable) {
                error = "Could not load the environment reference. (${e.message})"
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
