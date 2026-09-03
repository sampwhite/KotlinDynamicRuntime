package com.dynamicruntime.webapp

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.aside
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.main
import react.dom.html.ReactHTML.nav
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useState
import web.cssom.ClassName

/** Coroutine scope for firing the suspend home calls from React effects/handlers. */
private val homeScope = MainScope()

/**
 * The home page — assembled from data rather than hardcoded. It fetches its UI-config (the "construction
 * manifest") and builds itself from it:
 *  - Its **copy** comes from the `home` Markdown fragment file the config names (nothing here is a literal);
 *  - Its **layout** comes from the config's feature flags — the document links may be shown as a top menu
 *    bar, a left nav bar, inline in the body, any combination, or not at all;
 *  - Its **links** come from the config's state, each naming a Markdown document to open.
 *
 * Selecting a link goes to that document's own page ([DocsPage], issue #554) -- Home no longer renders a
 * document in place, so the listing and every document are destinations with history entries of their own.
 */
val Home = FC<Props> {
    var config by useState<HomeConfig?>(null)
    var copy by useState(Copy.empty)
    var error by useState<DisplayError?>(null)

    val generation = useRefreshGeneration()

    // Re-read the home config on every refresh generation (issue #115): on mount, and whenever a navigation or
    // state mutation bumps it.
    useEffect(generation) {
        homeScope.launch {
            try {
                val loaded = HomeApi.fetchConfig()
                config = loaded
                // Recover a stale build id silently (issue #469): a rolling deploy leaves this ref one deploy
                // old, which 404s -- re-fetch the config for a fresh ref and retry once, rather than surfacing
                // the misleading "is the runtime running?" below for a runtime that is running perfectly.
                copy = fetchCopyWithRetry(loaded.fragment) {
                    runCatching { HomeApi.fetchConfig().fragment }.getOrNull()
                }
                error = null
            } catch (e: Throwable) {
                error = userFacingError(e)
            }
        }
    }
    val current = config
    val layout = current?.layout
    val links = current?.links ?: emptyList()

    div {
        className = ClassName("home-shell")

        // Presentation 1: the links as a horizontal menu bar above the content.
        if (layout?.topBar == true && links.isNotEmpty()) {
            nav {
                className = ClassName("home-topbar")
                docLinks(links, openId = null)
            }
        }

        div {
            className = ClassName("home-body")

            // Presentation 2: the links as a left nav bar beside the content.
            if (layout?.leftBar == true && links.isNotEmpty()) {
                aside {
                    className = ClassName("home-leftbar")
                    copy.opt("nav", "title")?.let {
                        h2 { +it }
                    }
                    docLinks(links, openId = null)
                }
            }

            main {
                className = ClassName("home-main")
                when {
                    error != null -> errorText("Couldn't load this page.", error!!)
                    // The welcome page: copy from the fragment file, and optionally the links inline.
                    current != null -> {
                        // The hero: the brand mark beside the wordmark. The wordmark is "copy" like everything
                        // else here, so a deployment that names no brand simply gets no hero.
                        copy.opt("home", "brand")?.let { brandName ->
                            div {
                                className = ClassName("home-hero")
                                img {
                                    className = ClassName("home-hero-mark")
                                    src = brandMarkUrl
                                    // Decorative: the wordmark beside it carries the name.
                                    alt = ""
                                }
                                div {
                                    className = ClassName("home-hero-name")
                                    +brandName
                                }
                            }
                        }
                        copy.opt("home", "title")?.let { h1 { +it } }
                        copy.opt("home", "intro")?.let { Markdown { source = it } }
                        if (layout?.inlineLinks == true) {
                            renderInlineLinks(links, copy)
                        }
                    }
                    else -> p {
                        className = ClassName("subtitle")
                        +"Loading…"
                    }
                }
            }
        }
    }
}

/** The links as inline body content (the third presentation). */
private fun ChildrenBuilder.renderInlineLinks(links: List<HomeLink>, copy: Copy) {
    copy.opt("nav", "title")?.let { h2 { +it } }
    if (links.isEmpty()) {
        p {
            className = ClassName("type-hint")
            +copy.t("nav", "emptyNote", "")
        }
        return
    }
    div {
        className = ClassName("home-inline-links")
        docLinks(links, openId = null)
    }
}
