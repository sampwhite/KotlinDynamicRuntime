package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.page.PageSurface
import com.dynamicruntime.common.page.PageWidgetKind
import com.dynamicruntime.common.page.PageWidgets
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.useMemo
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.nav
import react.dom.html.ReactHTML.p
import web.cssom.ClassName
import web.dom.ElementId

/**
 * What a page's widgets draw from (issue #1794): the shell config ([config], read at refresh generation [configAt]), the
 * page's own copy, the caller's visible home cards and what Home read for them ([reads]), and how the copy's links
 * resolve ([copyLinks]). The renderer passes it to every widget, and knows nothing of what each takes from it.
 */
class PageData(
    val config: HomeConfig,
    val configAt: Int,
    val copy: Copy,
    val cards: List<HomeCard>,
    val reads: HomeLiveReads,
    val copyLinks: (String) -> String,
)

/**
 * The page as planned for this caller (issue #1794): [data] and the [parts] chosen from its document, with what widgets
 * that read the rest of the page need worked out once -- the cards each `cardsBlock` fence draws, a card in one fence
 * only ([pageCardsByFence]), and a jump row's links ([jumpRow]), asked both whether the row shows and to draw it.
 */
class PageScene(val data: PageData, val parts: List<PagePart>) {
    private val cards = pageCardsByFence(parts, data.cards)
    private val rows = HashMap<String, PageJumpRow>()

    /** The cards [part], a `cardsBlock` fence, draws: none that a fence above it draws. */
    fun cardsOf(part: PagePlaced): List<HomeCard> = cards[part.elementId].orEmpty()

    /** The jump row [part], a `jumpLinks` fence, draws ([pageJumpRow]). */
    fun jumpRow(part: PagePlaced): PageJumpRow =
        rows.getOrPut(part.elementId) { pageJumpRow(parts, part, ::cardsOf, data.reads.assistantView) }
}

/**
 * A widget kind as the app draws it (issue #1794): the kernel's [kind], whether a fence of it has something to show from
 * its own data ([shows], asked as the page is planned), whether it still does on the planned page ([showsOnPage], for a
 * widget that reads the rest of the page -- the jump row, a block of cards another block may have drawn), and how it
 * draws ([draw]). A kind with no entry in [appWidgets] is one the app does not draw, and a fence naming it is left out.
 */
class AppWidget(
    val kind: PageWidgetKind,
    val shows: (PageData, PagePlaced) -> Boolean,
    val showsOnPage: (PageScene, PagePlaced) -> Boolean = { _, _ -> true },
    val draw: ChildrenBuilder.(PageScene, PagePlaced) -> Unit,
)

/** The kinds the app draws, by name: one for each core kind whose surfaces include the app. */
val appWidgets: Map<String, AppWidget> = listOf(
    AppWidget(PageWidgets.testAccess, { _, _ -> appConfig().hasTestAccess }) { _, _ -> TestAccessCard {} },
    // The brand mark beside the wordmark. The wordmark is copy, so a deployment that names no brand gets no hero.
    AppWidget(PageWidgets.hero, { data, _ -> data.copy.opt(HFRAG.homeNs, HFRAG.brand) != null }) { scene, _ ->
        val data = scene.data
        div {
            className = ClassName("home-hero")
            img {
                className = ClassName("home-hero-mark")
                // The client's own mark where it has set one (issue #1130), as the bar draws.
                src = markUrlFor(data.config)
                // Decorative: the wordmark beside it carries the name.
                alt = ""
            }
            div {
                className = ClassName("home-hero-name")
                +data.copy.opt(HFRAG.homeNs, HFRAG.brand).orEmpty()
            }
        }
    },
    AppWidget(PageWidgets.pageTitle, { _, part -> pageTitleOf(part) != null }) { _, part -> h1 { +pageTitleOf(part).orEmpty() } },
    // The row reads the rest of the page, so whether it shows is asked of the planned page.
    AppWidget(PageWidgets.jumpLinks, { _, _ -> true }, { scene, part -> !scene.jumpRow(part).isEmpty }) { scene, part ->
        jumpRow(scene, part)
    },
    AppWidget(
        PageWidgets.cardsBlock, { data, part -> pageCardsOf(part.fence, data.cards).isNotEmpty() },
        // A card a block above it draws is not drawn again, so a block may be left with none.
        { scene, part -> scene.cardsOf(part).isNotEmpty() },
    ) { scene, part ->
        HomeCards {
            cards = scene.cardsOf(part)
            reads = scene.data.reads
            copy = scene.data.copy
        }
    },
    AppWidget(PageWidgets.joinableDemos, { data, _ -> data.config.joinOffered && isPublicHome(data.config.user) }) { scene, _ ->
        JoinableDemos {
            config = scene.data.config
            configAt = scene.data.configAt
        }
    },
    AppWidget(PageWidgets.createOwnClient, { data, _ -> isPublicHome(data.config.user) }) { scene, _ ->
        val data = scene.data
        val config = data.config
        CreateOwnClientCard {
            made = config.selfServeClient
            madeAbsent = config.selfServeClientAbsent
            madeName = config.users.firstOrNull { it.client == config.selfServeClient }?.clientName
            workflowsNote = data.copy.opt("publicHome", "createClientNote")
            templates = config.selfServeTemplates
            assistantOffered = ownClientAssistantOffered(config.cards)
        }
    },
    // The documents the home config lists, where the deployment's layout puts them inline (`HCFG.homeInlineLinks`).
    AppWidget(PageWidgets.docLinks, { data, _ -> data.config.layout.inlineLinks }) { scene, _ ->
        inlineDocLinks(scene.data.config.links.filter { it.listed }, scene.data.copy)
    },
).filter { PageSurface.app in it.kind.surfaces }.associateBy { it.kind.name }

external interface PageViewProps : Props {
    /** The page's document: Markdown with widget fences. */
    var document: String
    var data: PageData
}

/**
 * The generic page renderer (issue #1794): a page document's parts in order, each Markdown part as copy is drawn now,
 * and each fence in its own element, `page.<id>`, its copy before its widget -- unless the kind draws its copy itself.
 * It knows nothing of which page it draws, nor of any kind: what Home is, its document says, and what each widget
 * shows, its [AppWidget] decides. The document is read once per copy ([parsePage]); what this caller is drawn is chosen
 * from it on each render ([selectParts]), then settled on the planned page ([AppWidget.showsOnPage]).
 */
val PageView = FC<PageViewProps> { props ->
    val data = props.data
    val parsed = useMemo(props.document, data.copy) { parsePage(props.document, data.copy) }
    val planned = selectParts(
        parsed, data.config.cfacts,
        drawable = { it.name in appWidgets },
        shows = { part -> appWidgets.getValue(part.kind!!.name).shows(data, part) },
    )
    val planScene = PageScene(data, planned)
    val scene = PageScene(data, planned.filter { it !is PagePlaced || it.kind == null || widgetOf(it).showsOnPage(planScene, it) })
    for ((index, part) in scene.parts.withIndex()) {
        when (part) {
            is PageText -> div {
                key = "text$index".unsafeCast<Key>()
                Markdown { source = part.markdown; linkResolver = data.copyLinks }
            }
            is PagePlaced -> div {
                key = part.elementId.unsafeCast<Key>()
                id = ElementId(part.elementId)
                className = ClassName(pagePartClasses(part))
                if (part.copy != null && part.kind?.drawsCopy != true) {
                    Markdown { source = part.copy; linkResolver = data.copyLinks }
                }
                if (part.kind != null) widgetOf(part).draw(this, scene, part)
            }
        }
    }
}

/** The app's widget for [part], a fence with a widget the app draws. */
private fun widgetOf(part: PagePlaced): AppWidget = appWidgets.getValue(part.kind!!.name)

/**
 * The classes of a drawn fence's element: `page-part`, `page-part-<id>` (how a page's own parts are styled, the home
 * page's note among them), and `page-widget-<kind>` for a fence with a widget. Pure.
 */
fun pagePartClasses(part: PagePlaced): String =
    listOfNotNull("page-part", "page-part-${part.fence.id}", part.kind?.let { "page-widget-${it.name}" }).joinToString(" ")

/**
 * The row of links under the title (issues #1576, #1794): a button for each card that is the page's call to action,
 * which does what the card's button does (issue #1698), then the links ([pageJumpRow]).
 */
private fun ChildrenBuilder.jumpRow(scene: PageScene, part: PagePlaced) {
    val row = scene.jumpRow(part)
    nav {
        className = ClassName("home-jump-links")
        asDynamic()["aria-label"] = "On this page"
        for ((label, page) in row.buttons) {
            Button {
                key = page.unsafeCast<Key>()
                type = "primary"
                onClick = { navigate(listOf(HP.page to page)) }
                +label
            }
        }
        row.links?.let { Markdown { source = it; linkResolver = scene.data.copyLinks } }
    }
}

/** The listed documents as inline links, under the copy's `nav.title`, or its `nav.emptyNote` when there are none. */
private fun ChildrenBuilder.inlineDocLinks(links: List<HomeLink>, copy: Copy) {
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
