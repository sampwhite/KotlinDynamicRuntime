package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HMENU
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.ul
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName

/** Coroutine scope for the documents page's suspend fetches. */
private val docsScope = MainScope()

/**
 * The documents page (issue #554): `#page=docs` lists the served documents, and `#page=docs&doc=<id>` shows
 * one, with "← Documents" back to the listing. A document is a destination of its own -- it used to be a
 * mode of Home, opened in place with `replaceHash`, so browser Back skipped the listing altogether -- so
 * opening one is an ordinary link navigation, which is what gives it a history entry. A bare `#doc=<id>`
 * (an older bookmark or in-document link) routes here as well.
 *
 * Which document is open is read from the hash on every render rather than held in state: the router's page
 * id is the same for the listing and a document, and a hash navigation bumps the refresh generation, which is
 * what re-renders this page.
 */
val DocsPage = FC<Props> {
    var config by useState<HomeConfig?>(null)
    var copy by useState(Copy.empty)
    var docText by useState<String?>(null)
    // The document fetch has its own error, shown beside the (still-visible) heading, so a failed document does
    // not blank the whole page the way a failed config load does, and does not race the config's `error = null`.
    var docError by useState<DisplayError?>(null)
    var error by useState<DisplayError?>(null)
    val generation = useRefreshGeneration()
    // Monotonic token so an out-of-order document response is dropped rather than overwriting the open one.
    val latestDoc = useRef(0)
    val openId = hashParams()[HP.doc]

    // The same config Home reads (the links, and the fragment carrying the nav copy), re-read per generation.
    useEffect(generation) {
        docsScope.launch {
            try {
                val loaded = HomeApi.fetchConfig()
                config = loaded
                copy = fetchCopyWithRetry(loaded.fragment) {
                    runCatching { HomeApi.fetchConfig().fragment }.getOrNull()
                }
                error = null
            } catch (e: Throwable) {
                error = userFacingError(e)
            }
        }
    }

    // Fetch whichever document the hash names. Clear the old text and error up front, so switching documents
    // never shows the previous body under the new heading; a token drops a slow response that a later switch
    // has superseded (docsScope is module-global, so a launch outlives this effect).
    useEffect(openId, config) {
        val link = config?.links?.firstOrNull { it.id == openId }
        docText = null
        docError = null
        if (link != null) {
            val token = (latestDoc.current ?: 0) + 1
            latestDoc.current = token
            docsScope.launch {
                try {
                    val loaded = HomeApi.fetchDoc(link.docId, link.buildId)
                    if (latestDoc.current == token) docText = loaded
                } catch (e: Throwable) {
                    if (latestDoc.current == token) docError = userFacingError(e)
                }
            }
        }
    }

    div {
        className = ClassName("card wide")
        val current = config
        val links = current?.links ?: emptyList()
        val doc = links.firstOrNull { it.id == openId }
        when {
            error != null -> errorText("Couldn't load the documents.", error!!)
            current == null -> p { className = ClassName("subtitle"); +"Loading…" }
            doc != null -> {
                backToListing(HMENU.pageDocs)
                h1 { +doc.label }
                docError?.let { errorText("Couldn't load this document.", it) }
                docText?.let { text ->
                    Markdown {
                        source = text
                        // Interior repo-relative links become in-app document links or source-repo links as
                        // the document renders (issue #492).
                        linkResolver = docLinkResolver(doc.sourcePath, links, current.sourceRepoBase)
                    }
                }
            }
            else -> {
                h1 { +copy.t("nav", "title", "Documents") }
                if (links.isEmpty()) {
                    p { className = ClassName("type-hint"); +copy.t("nav", "emptyNote", "") }
                } else {
                    // A list of links with a line on each, the shape the Operator overview and the Debug index
                    // use for a listing -- not Home's toolbar of link buttons. The description is served with
                    // the link, so a new document arrives with its own.
                    ul {
                        className = ClassName("index-list")
                        for (link in links) li {
                            a { href = docHref(link.id); +link.label }
                            if (link.description.isNotEmpty()) +" \u2014 ${link.description}"
                        }
                    }
                }
            }
        }
    }
}

/** One link per document, to the document's own page. Home's link presentations use it too. */
fun ChildrenBuilder.docLinks(links: List<HomeLink>) {
    links.forEach { link ->
        a {
            className = ClassName("link-button")
            href = docHref(link.id)
            +link.label
        }
    }
}

/** The href of a document's page. One builder, so Home's links, the listing, and the link resolver agree. */
fun docHref(id: String): String = hashHref(listOf(HP.page to HMENU.pageDocs, HP.doc to id))
