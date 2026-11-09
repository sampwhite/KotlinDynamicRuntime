package com.dynamicruntime.webapp

import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.home.HFRAG
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
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
import react.useRef
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
    // What Home reads for its live cards (issues #1636-#1639): the workflow assistant's status, what waits on the caller,
    // the client's unpublished drafts, and a new client's checklist -- read here, once, so a card and its link under the
    // title say the same thing, and a card whose read finds nothing is not drawn. What waits is confined to the caller's
    // own client when they see across clients, so Home never reads the whole deployment.
    val allCards = current?.cards.orEmpty()
    val reads = useHomeLiveReads(
        allCards,
        decidedAt = configAt,
        waitingClient = current?.takeIf { it.canSeeAllClients }?.user?.client,
        // The checklist's step for the workflow assistant (issue #1639), only where the menu offers it.
        assistantOffered = current?.menu.orEmpty().any { it.id == ASST.menuItem },
        ownClient = current?.user?.client,
    )
    val cards = visibleHomeCards(allCards, reads)

    // An address naming a section (`/wa#home.own-client`, after a jump link and a reload, say) scrolls to it once the
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

    div {
        className = ClassName("home-shell")

        // Presentation 1: the links as a horizontal menu bar above the content.
        if (layout?.topBar == true && links.isNotEmpty()) {
            nav {
                className = ClassName("home-topbar")
                docLinks(links)
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
                    docLinks(links)
                }
            }

            main {
                className = ClassName("home-main")
                when {
                    error != null -> errorText("Couldn't load this page.", error!!)
                    // The welcome page: copy from the fragment file, and optionally the links inline.
                    current != null -> {
                        // On a test instance, for a caller with test access (issue #1183): becoming someone first.
                        if (appConfig().hasTestAccess) TestAccessCard {}
                        // The hero: the brand mark beside the wordmark. The wordmark is "copy" like everything
                        // else here, so a deployment that names no brand simply gets no hero.
                        copy.opt(HFRAG.homeNs, HFRAG.brand)?.let { brandName ->
                            div {
                                className = ClassName("home-hero")
                                img {
                                    className = ClassName("home-hero-mark")
                                    // The client's own mark where it has set one (issue #1130), as the bar draws.
                                    src = markUrlFor(config)
                                    // Decorative: the wordmark beside it carries the name.
                                    alt = ""
                                }
                                div {
                                    className = ClassName("home-hero-name")
                                    +brandName
                                }
                            }
                        }
                        copy.opt(HFRAG.homeNs, HFRAG.title)?.let { h1 { +it } }
                        // Jump links to what this page offers further down (issue #1576), from the copy, keeping only
                        // those whose section is drawn for this caller -- none outside the public home page.
                        // And to the cards that ask to be named there (issue #1636): the assistant's, which may sit below
                        // a long introduction, beside "Start here".
                        // A card that is the page's call to action leads the row as its own button (issue #1698), and is
                        // left out of what the links count as drawn, so a link the copy writes to it gives way to the button.
                        val jumpButtons = homeCardJumpButtons(cards)
                        val drawn = (if (isPublicHome(current.user)) publicHomeSections(current) else emptySet()) +
                            cards.filterNot { it.leadsJumpRow }.map { homeCardElementId(it.id) }
                        // Named apart from the Markdown components' `source` below, which a local `source` would shadow.
                        val jumpSource =
                            withCardJumpLinks(copy.opt(HFRAG.homeNs, HFRAG.jumpLinks), homeCardJumpLinks(cards, reads.assistantView))
                        val jumpLinks = jumpLinksFor(jumpSource, drawn)
                        if (jumpButtons.isNotEmpty() || jumpLinks != null) {
                            nav {
                                className = ClassName("home-jump-links")
                                asDynamic()["aria-label"] = "On this page"
                                for ((label, page) in jumpButtons) {
                                    Button {
                                        key = page.unsafeCast<Key>()
                                        type = "primary"
                                        onClick = { navigate(listOf(HP.page to page)) }
                                        +label
                                    }
                                }
                                jumpLinks?.let { Markdown { source = it; linkResolver = copyLinks } }
                            }
                        }
                        // A card that is what the caller came to do comes before the introduction (issue #1723), the rest
                        // after it; one list split in two, so a card is drawn in exactly one place.
                        val (aboveIntro, belowIntro) = cards.partition { it.aboveIntro }
                        HomeCards {
                            this.cards = aboveIntro
                            this.reads = reads
                            this.copy = copy
                        }
                        copy.opt(HFRAG.homeNs, HFRAG.intro)?.let { Markdown { source = it; linkResolver = copyLinks } }
                        // A note beneath the intro (issue #1221), empty as shipped: room for a component's own word on
                        // a deployment -- a flavor's, say -- without its competing with whoever rewords the intro.
                        copy.opt(HFRAG.homeNs, HFRAG.note)?.takeIf { it.isNotBlank() }?.let {
                            div {
                                className = ClassName("home-note")
                                Markdown { source = it; linkResolver = copyLinks }
                            }
                        }
                        // The home cards (issue #1636): what this caller can do next, as the backend decided for them.
                        HomeCards {
                            this.cards = belowIntro
                            this.reads = reads
                            this.copy = copy
                        }
                        // The public home page (issue #1324): for someone signed in to `public` -- never a visitor who
                        // has not signed in, though they act in `public` too -- what they can do next.
                        if (isPublicHome(current.user)) {
                            PublicHome {
                                // Qualified: Home's own `config` and `copy` are state, and a bare name here would set
                                // them -- during render, which loops.
                                this.config = current
                                this.configAt = configAt
                                this.copy = copy
                            }
                        }
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
        docLinks(links)
    }
}
