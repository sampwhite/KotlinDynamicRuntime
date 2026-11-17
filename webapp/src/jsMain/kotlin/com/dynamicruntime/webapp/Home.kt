package com.dynamicruntime.webapp

import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.home.HFRAG
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.aside
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.main
import react.dom.html.ReactHTML.nav
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useMemo
import react.useRef
import react.useState
import web.cssom.ClassName

/** Coroutine scope for firing the suspend home calls from React effects/handlers. */
private val homeScope = MainScope()

/** The home page: the page document `home` (issues #1794, #1796), drawn by [PageScreen] as any page is. */
val Home = FC<Props> {
    PageScreen {
        pageId = HFRAG.pageHome
        docBars = true
    }
}

external interface PageScreenProps : Props {
    /** The page document to draw: a key of the home file's page documents ([HFRAG.pageNs]). */
    var pageId: String

    /**
     * Whether the screen draws the document links as bars beside the page, where the config's layout asks for them --
     * Home's own chrome, so a client's page draws only its document (issue #1796). Absent is false.
     */
    var docBars: Boolean?
}

/**
 * A page document's screen (issue #1796), Home's among them — assembled from data rather than hardcoded. It fetches
 * the shell's UI-config (the "construction manifest") and builds itself from it:
 *  - Its **page** is a document in the `home` Markdown fragment file the config names (`page.<pageId>`, issue #1794):
 *    Markdown with widget fences, drawn in order by the generic [PageView], so what a page shows and in what order is
 *    copy, which a client or a deployment overlays -- or adds, for a page of its own. An id with no document says so;
 *  - Its **layout** comes from the config's feature flags — the document links may be shown as a top menu
 *    bar, a left nav bar, inline in the body (the page's `docLinks` widget), any combination, or not at all. The bars
 *    are Home's alone ([PageScreenProps.docBars]): any other page draws only its document;
 *  - Its **links** come from the config's state, each naming a Markdown document to open.
 *
 * Selecting a link goes to that document's own page ([DocsPage], issue #554) -- Home no longer renders a
 * document in place, so the listing and every document are destinations with history entries of their own.
 */
val PageScreen = FC<PageScreenProps> { props ->
    val pageId = props.pageId
    var config by useState<HomeConfig?>(null)
    var copy by useState(Copy.empty)
    var error by useState<DisplayError?>(null)
    // The refresh generation [config] was read at: what a read the config decides -- a live card's, the public home's
    // demos -- keys on, rather than the generation now, so after a session change it waits for the new caller's config
    // instead of reading as them from the old caller's ([useCardRead]). -1 until a config has loaded.
    var configAt by useState(-1)
    // A token per read, so one overtaken -- by a later generation or poll -- is dropped when it answers.
    val latestRead = useRef(0)

    val generation = useRefreshGeneration()
    // The copy's links, as a document's are (issue #1510): `/docs/...` to the in-app document, `/wa/...` under the live
    // app root, so the intro can point to "Start here" as the public home's sections point to the walk-through.
    val copyLinks = useShellFacts().copyLinkResolver()

    // Re-read the home config on every refresh generation (issue #115): on mount, and whenever a navigation or
    // state mutation bumps it.
    // And on every idle poll: it reads the home config, a polled endpoint, and static copy alone (issue #1372).
    val polls = useIdlePolls()
    useEffect(generation, polls) {
        val token = (latestRead.current ?: 0) + 1
        latestRead.current = token
        homeScope.launch {
            try {
                val loaded = HomeApi.fetchConfig()
                // An answer from before a session change would hand the new caller the old caller's cards.
                if (latestRead.current != token) return@launch
                config = loaded
                configAt = generation
                // Recover a stale build id silently (issue #469): a rolling deploy leaves this ref one deploy
                // old, which 404s -- re-fetch the config for a fresh ref and retry once, rather than surfacing
                // the misleading "is the runtime running?" below for a runtime that is running perfectly.
                val loadedCopy = fetchCopyWithRetry(loaded.fragment) {
                    apiResult { HomeApi.fetchConfig().fragment }.valueOrNull()
                }
                if (latestRead.current != token) return@launch
                copy = loadedCopy
                error = null
            } catch (e: Throwable) {
                if (latestRead.current == token) error = userFacingError(e)
            }
        }
    }
    val current = config
    val layout = current?.layout
    // What the page reads for its live cards (issues #1636-#1639): the configuration assistant's status, what waits on the
    // caller, the client's unpublished drafts, and a new client's checklist -- read here, once, so a card and its link
    // under the title say the same thing, and a card whose read finds nothing is not drawn. What waits is confined to the
    // caller's own client when they see across clients, so Home never reads the whole deployment. Only for the cards the
    // page's document can draw (issue #1796): a page with one card, or none, reads nothing for the rest.
    val pageDocument = copy.opt(HFRAG.pageNs, pageId)
    val configCards = current?.cards
    val allCards = useMemo(pageDocument, configCards) { pageDocument?.let { pageDrawableCards(it, configCards.orEmpty()) }.orEmpty() }
    val reads = useHomeLiveReads(
        allCards,
        decidedAt = configAt,
        waitingClient = current?.takeIf { it.canSeeAllClients }?.user?.client,
        // The checklist's step for the configuration assistant (issue #1639), only where the menu offers it.
        assistantOffered = current?.menu.orEmpty().any { it.id == ASST.menuItem },
        ownClient = current?.user?.client,
    )
    val cards = visibleHomeCards(allCards, reads)

    // An address naming a part of the page (`/wa#page.ownClient`, after a jump link and a reload, say) scrolls to it once the
    // page is drawn (issue #1576): the browser looks for it as the page loads, before Home has fetched what it draws.
    // Drawn means the copy too, which arrives after the config and adds the intro above the sections.
    val hashScrolled = useRef(false)
    val drawnWithCopy = current != null && copy !== Copy.empty
    useEffect(drawnWithCopy) {
        if (!drawnWithCopy || hashScrolled.current == true) return@useEffect
        hashScrolled.current = true
        val id = window.location.hash.removePrefix("#")
        if (id.isNotEmpty()) document.getElementById(id)?.scrollIntoView()
    }
    // Only the documents the listing offers; a suite's inner pages are reached by links (issue #1136).
    val links = current?.links?.filter { it.listed } ?: emptyList()
    val barLinks = if (props.docBars == true) links else emptyList()

    div {
        className = ClassName("home-shell")

        // Presentation 1: the links as a horizontal menu bar above the content.
        if (layout?.topBar == true && barLinks.isNotEmpty()) {
            nav {
                className = ClassName("home-topbar")
                docLinks(barLinks)
            }
        }

        div {
            className = ClassName("home-body")

            // Presentation 2: the links as a left nav bar beside the content.
            if (layout?.leftBar == true && barLinks.isNotEmpty()) {
                aside {
                    className = ClassName("home-leftbar")
                    copy.opt("nav", "title")?.let {
                        h2 { +it }
                    }
                    docLinks(barLinks)
                }
            }

            main {
                className = ClassName("home-main")
                when {
                    error != null -> errorText("Couldn't load this page.", error!!)
                    // The page document (issue #1794): Home is the page `home`, drawn by the generic renderer, which
                    // knows nothing of Home -- its order is its document's, and what each part shows, its widget's.
                    current != null && pageDocument != null -> PageView {
                        // Qualified: a bare `document` is the browser's, and this screen's own `config` and `copy` are
                        // state, which a bare name here would set -- during render, which loops.
                        this.document = pageDocument
                        this.data = PageData(current, configAt, copy, cards, reads, copyLinks)
                    }
                    // The copy has come, and holds no such page: said, with the way home, rather than left loading.
                    current != null && copy !== Copy.empty -> p {
                        +"There is no page named '$pageId' here. "
                        a {
                            href = routeHref(emptyList())
                            +"Go to the home page"
                        }
                        +"."
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
