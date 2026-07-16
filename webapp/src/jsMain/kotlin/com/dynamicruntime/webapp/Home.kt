package com.dynamicruntime.webapp

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.aside
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.main
import react.dom.html.ReactHTML.nav
import react.dom.html.ReactHTML.p
import react.useEffect
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

/** Coroutine scope for firing the suspend home calls from React effects/handlers. */
private val homeScope = MainScope()

/** The hash key naming the open document, so a doc page survives a refresh and can be linked to. */
private const val docParam = "doc"

/**
 * The home page — assembled from data rather than hardcoded. It fetches its UI-config (the "construction
 * manifest") and builds itself from it:
 *  - its **copy** comes from the `home` Markdown fragment file the config names (nothing here is a literal);
 *  - its **layout** comes from the config's feature flags — the document links may be shown as a top menu
 *    bar, a left nav bar, inline in the body, any combination, or not at all;
 *  - its **links** come from the config's state, each naming a Markdown document to open.
 *
 * Selecting a link opens that document (rendered by [Markdown]) and records it in the URL hash, so a document
 * page can be refreshed or shared.
 */
val Home = FC<Props> {
    var config by useState<HomeConfig?>(null)
    var copy by useState(Copy.empty)
    var openDoc by useState<String?>(hashParams()[docParam])
    var docText by useState<String?>(null)
    var error by useState<String?>(null)

    useEffectOnce {
        homeScope.launch {
            try {
                val loaded = HomeApi.fetchConfig()
                config = loaded
                copy = fetchCopy(loaded.fragment)
                error = null
            } catch (e: Throwable) {
                error = "Could not load the home page — is the runtime running? (${e.message})"
            }
        }
        // The app bar's brand (and back/forward) can clear the hash from outside this component; re-derive the
        // open document when that happens. Our own navigation uses replaceHash, which does not fire this.
        onHashChange { openDoc = hashParams()[docParam] }
    }

    // Fetch whichever document the selection names (and drop the old text when nothing is open). Keyed on the
    // selection and the config, so it also runs for a doc named by the hash on first load.
    useEffect(openDoc, config) {
        val link = config?.links?.firstOrNull { it.id == openDoc }
        if (link == null) {
            docText = null
        } else {
            homeScope.launch {
                try {
                    docText = HomeApi.fetchDoc(link.docId, link.buildId)
                    error = null
                } catch (e: Throwable) {
                    error = "Could not load '${link.label}'. (${e.message})"
                }
            }
        }
    }

    /** Opens [link] (or the welcome copy when null), recording it in the hash so it survives a refresh. */
    fun show(link: HomeLink?) {
        openDoc = link?.id
        replaceHash(if (link == null) emptyList() else listOf(docParam to link.id))
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
                linkButtons(links, openDoc) { show(it) }
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
                    linkButtons(links, openDoc) { show(it) }
                }
            }

            main {
                className = ClassName("home-main")
                val doc = links.firstOrNull { it.id == openDoc }
                when {
                    error != null -> p {
                        className = ClassName("todo-error")
                        +error!!
                    }
                    // A document page: its rendered Markdown, plus a way back to the welcome copy.
                    doc != null -> {
                        button {
                            className = ClassName("link-button")
                            onClick = { show(null) }
                            +"← ${copy.t("nav", "homeLabel", "Home")}"
                        }
                        h1 { +doc.label }
                        docText?.let { Markdown { source = it } }
                    }
                    // The welcome page: copy from the fragment file, and optionally the links inline.
                    current != null -> {
                        // The hero: the brand mark beside the wordmark. The wordmark is copy like everything
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
                            renderInlineLinks(links, copy) { show(it) }
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
private fun ChildrenBuilder.renderInlineLinks(
    links: List<HomeLink>,
    copy: Copy,
    onSelect: (HomeLink) -> Unit,
) {
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
        linkButtons(links, openId = null, onSelect = onSelect)
    }
}

/** One button per link, marking the open one. Shared by all three presentations. */
private fun ChildrenBuilder.linkButtons(links: List<HomeLink>, openId: String?, onSelect: (HomeLink) -> Unit) {
    links.forEach { link ->
        button {
            className = ClassName(if (link.id == openId) "link-button open" else "link-button")
            onClick = { onSelect(link) }
            +link.label
        }
    }
}
