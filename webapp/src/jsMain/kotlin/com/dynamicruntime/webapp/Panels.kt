package com.dynamicruntime.webapp

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.awaitCancellation
import org.w3c.dom.Element
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.PropsWithChildren
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.details
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.nav
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.section
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.summary
import react.dom.html.ReactHTML.ul
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName
import web.dom.ElementId
import web.html.HTMLDivElement
import web.html.HTMLElement

/*
 * The building blocks of a page's structure (issue #1477), shared so every page reads the same way: see "Page
 * structure: panels, drafts, and live actions" in the webapp's CLAUDE.md for the rules they serve.
 */

/** The words the building blocks draw, the same on every page. */
@Suppress("ConstPropertyName")
object PNL {
    /** Beside a draft panel's heading while it holds edits nobody has saved. */
    const val unsaved = "Unsaved changes"

    /** Under a live panel's heading: nothing in it waits for a save. */
    const val liveNote = "Changes here take effect immediately."

    /** The summary of a [MoreOptions] disclosure, before what is set in it. */
    const val moreOptions = "More options"
}

/**
 * What a panel holds, which decides how its edits take effect (issue #1477). A closed operational set: each is a
 * different rule for the controls inside it.
 */
@Suppress("EnumEntryName")
enum class PanelKind {
    /** Read, or a section holding editors of its own that each follow the rules (the client page's panels). */
    view,

    /** Edits wait for the panel's one save; its heading says so while any are unsaved. */
    draft,

    /** Each control acts at once; a line under the heading says so. */
    live,

    /** The last panel, holding what cannot be undone, set apart in the danger color. */
    danger,
}

/**
 * A panel of a page (issues #1397, #1477): a keyed `section` set apart from the others, headed by [heading] with its
 * "What is this?" [explanation] (none when blank), its element id [id] what a [panelLink] leads to. Its heading says
 * "Unsaved changes" while [dirty]: a [PanelKind.draft] panel's own, or an editor's inside any panel on a page that keeps
 * its unsaved edits ([PendingEdits], issue #1922). A [PanelKind.live] one says under it that its changes take effect at
 * once. What it holds is told it is in this panel ([PendingPlaceContext]).
 */
fun ChildrenBuilder.panel(
    id: String,
    heading: String,
    explanation: String = "",
    kind: PanelKind = PanelKind.view,
    dirty: Boolean = false,
    body: ChildrenBuilder.() -> Unit,
) {
    section {
        key = id.unsafeCast<Key>()
        this.id = ElementId(id)
        className = ClassName(panelClasses(kind))
        WhatIsThis {
            this.heading = heading
            text = explanation
            // A draft's own changes, or -- on a page that keeps them -- an editor's inside it (issue #1922).
            marker = PNL.unsaved.takeIf { dirty }
            subline = PNL.liveNote.takeIf { kind == PanelKind.live }
        }
        // An editor drawn inside knows which panel it is in, for the page's unsaved edits (issue #1922).
        PendingPlaceContext.Provider {
            value = PendingPlace(id)
            body()
        }
    }
}

/** A panel's CSS classes for its [kind]. Pure, covered under `jsNodeTest`. */
fun panelClasses(kind: PanelKind): String = when (kind) {
    PanelKind.danger -> "panel panel-danger"
    else -> "panel"
}

/**
 * A link to a panel of the page (issue #1397), scrolled to rather than navigated to: the address is the page's, and a
 * hash would be read as one. To the panel's top, which its `scroll-margin-top` keeps clear of the fixed app bar. A side
 * menu marks the link to where the reader is [current] (`aria-current`).
 */
fun ChildrenBuilder.panelLink(id: String, text: String, current: Boolean = false) {
    a {
        className = ClassName("wf-cell-link")
        href = "#"
        if (current) asDynamic()["aria-current"] = "location"
        onClick = { e ->
            e.preventDefault()
            document.getElementById(id)?.asDynamic()?.scrollIntoView(js("({ block: 'start', behavior: 'smooth' })"))
        }
        +text
    }
}

/**
 * The row of links at a page's top, one per panel -- [panels] as `id to heading` -- for a page long enough to want them.
 * A panel long enough to want its own has links to its sections beside its link ([sections], by the panel's id, each
 * `id to heading`; issue #1891), scrolled to the same way.
 */
fun ChildrenBuilder.panelLinks(panels: List<Pair<String, String>>, sections: Map<String, List<Pair<String, String>>> = emptyMap()) {
    nav {
        className = ClassName("panel-links")
        // Named, since a page may have another: a long panel's own section links (issue #1891).
        asDynamic()["aria-label"] = "Page sections"
        panels.forEach { (id, title) ->
            span {
                key = id.unsafeCast<Key>()
                panelLink(id, title)
                sections[id]?.takeIf { it.isNotEmpty() }?.let { subs ->
                    span {
                        className = ClassName("panel-sublinks")
                        sectionLinks("$title sections", subs, parenthesized = true)
                    }
                }
            }
        }
    }
}

/**
 * Links to a panel's sections (issue #1891) -- [sections] as `id to heading` -- scrolled to as a [panelLink] is: the
 * links separated by dots and, when [parenthesized], in parentheses, that punctuation hidden from a screen reader, which
 * reads the links alone. Beside a panel's link at the page's top, a group named [label]; inside a long panel, in a `nav`
 * that carries the name, so none ([label] null).
 */
fun ChildrenBuilder.sectionLinks(label: String?, sections: List<Pair<String, String>>, parenthesized: Boolean = false) {
    span {
        label?.let {
            asDynamic()["role"] = "group"
            asDynamic()["aria-label"] = it
        }
        if (parenthesized) punctuation("(")
        sections.forEachIndexed { i, (id, title) ->
            span {
                key = id.unsafeCast<Key>()
                if (i > 0) punctuation(" · ")
                panelLink(id, title)
            }
        }
        if (parenthesized) punctuation(")")
    }
}

/** [text] that only separates what is around it, so a screen reader skips it. */
private fun ChildrenBuilder.punctuation(text: String) {
    span {
        asDynamic()["aria-hidden"] = true
        +text
    }
}

/** A long page's side menu (issue #1919): where the reader is counts as reached, and when it is checked. */
@Suppress("ConstPropertyName")
object SMENU {
    /**
     * How far past where a link scrolls a place to -- its scroll box's `scroll-padding-top` and its own
     * `scroll-margin-top`, read from the page (`landingOffset`) -- it may be and still count as reached, in pixels.
     */
    const val lineSlack = 16.0

    /** How near the end of its scroll box the page counts as scrolled to the bottom, in pixels. */
    const val bottomSlack = 2.0

    /** The most ancestors looked through for the element that scrolls the page. */
    const val maxDepth = 50

    /** The fewest panels a page has before a side menu is worth its column. */
    const val minPanels = 3

    /** How many screens tall a page is before it has a side menu: a shorter one is read by scrolling. */
    const val minScreens = 2.0

    /** Where a screen has room for the column: `app.css` repeats it in its media query, so change the two together. */
    const val wideQuery = "(min-width: 1200px)"

    /** Room kept above or below the marked entry when the menu's own box scrolls it into view, in pixels. */
    const val keepInViewPad = 8.0
}

/**
 * Whether a page of [panelCount] panels, [pageHeight] tall in a window [viewportHeight] tall, has a side menu (issue
 * #1919): enough panels to need finding, and tall enough to lose the reader. Pure; `jsNodeTest`.
 */
fun pageNeedsSideMenu(panelCount: Int, pageHeight: Double, viewportHeight: Double): Boolean =
    panelCount >= SMENU.minPanels && pageHeight > viewportHeight * SMENU.minScreens

/**
 * How far a scroll box [boxTop] to [boxBottom] scrolls to show an item [itemTop] to [itemBottom] (all against the window),
 * with [pad] to spare: negative up, positive down, 0 when it shows already. Pure; `jsNodeTest`.
 */
fun scrollToShow(boxTop: Double, boxBottom: Double, itemTop: Double, itemBottom: Double, pad: Double): Double = when {
    itemTop < boxTop + pad -> itemTop - boxTop - pad
    itemBottom > boxBottom - pad -> itemBottom - boxBottom + pad
    else -> 0.0
}

/** One place a side menu leads to (issue #1919): a panel, or a section of [panelId] -- a panel is its own. */
class SideMenuEntry(val id: String, val title: String, val panelId: String) {
    val isSection: Boolean get() = id != panelId
}

/**
 * A side menu's places (issue #1919), in the page's order: each of [panels] (`id to heading`), then its [sections],
 * from the same lists the links at the page's top draw ([panelLinks]), so the two cannot disagree. Pure; `jsNodeTest`.
 */
fun sideMenuEntries(panels: List<Pair<String, String>>, sections: Map<String, List<Pair<String, String>>>): List<SideMenuEntry> =
    panels.flatMap { (id, title) ->
        listOf(SideMenuEntry(id, title, id)) + sections[id].orEmpty().map { (sub, subTitle) -> SideMenuEntry(sub, subTitle, id) }
    }

/**
 * Where the reader is (issue #1919): of the places drawn, in the page's order with each one's top against the window
 * ([tops]), the last to have reached [line]. Scrolled to the bottom ([atBottom]), where the last ones may never reach
 * it, the last whose top shows above [viewportBottom]. Before the first is reached, the first. Pure; `jsNodeTest`.
 */
fun activeSideMenuEntry(tops: List<Pair<String, Double>>, line: Double, atBottom: Boolean, viewportBottom: Double): String? {
    if (tops.isEmpty()) return null
    val reached = if (atBottom) tops.lastOrNull { it.second < viewportBottom } else tops.lastOrNull { it.second <= line }
    return (reached ?: tops.first()).first
}

/** A CSS length in pixels, as `getComputedStyle` gives one (`72px`); 0 for anything else (`auto`). */
fun cssPixels(value: String?): Double = value?.trim()?.removeSuffix("px")?.toDoubleOrNull() ?: 0.0

/**
 * How far below its scroll box's top a link scrolls [target] to: the box's `scroll-padding-top` and the target's
 * `scroll-margin-top` together, since both apply. Read from the page, so a change to either rule moves the line with it.
 */
fun landingOffset(scroller: dynamic, target: dynamic): Double =
    cssPixels(window.getComputedStyle(scroller.unsafeCast<Element>()).asDynamic().scrollPaddingTop as String?) +
        cssPixels(window.getComputedStyle(target.unsafeCast<Element>()).asDynamic().scrollMarginTop as String?)

/** The element that scrolls the page holding [start]: its nearest ancestor that scrolls, else the document's own. */
fun pageScroller(start: dynamic): dynamic {
    var at = start?.parentElement
    var depth = 0
    while (at != null && depth < SMENU.maxDepth) {
        val overflow = window.getComputedStyle(at.unsafeCast<Element>()).overflowY
        if ((overflow == "auto" || overflow == "scroll") && (at.scrollHeight as Int) > (at.clientHeight as Int)) return at
        at = at.parentElement
        depth++
    }
    return document.asDynamic().scrollingElement ?: document.documentElement
}

external interface PageSideMenuProps : Props {
    /** The page's panels, `id to heading`, as [panelLinks] takes them. */
    var panels: List<Pair<String, String>>

    /** The sections of a panel that has them, by the panel's id, as [panelLinks] takes them. */
    var sections: Map<String, List<Pair<String, String>>>

    /** The page's unsaved edits (issue #1922), marked beside their panels and sections and listed; null for none kept. */
    var pending: PendingEdits?
}

/**
 * A long page's side menu (issue #1919): its panels and their sections, beside the page and in view however far it
 * scrolls, marking where the reader is ([activeSideMenuEntry]) -- the section, and the panel it is in. A click scrolls
 * as the links at the page's top do. Drawn only on a screen wide enough for a column (`app.css`) beside a page long
 * enough to need it ([pageNeedsSideMenu], measured on the page drawn just before it), where it takes the place of those
 * links; otherwise they stay. A page draws it with [panelPage], not by hand.
 *
 * Where the reader is is measured on scroll, once a frame: an `IntersectionObserver` hears a place cross a line, but
 * not the page reaching its bottom, where the last, short panels never reach the line at all. Also when the page
 * holding the places changes size -- content loaded or opened moves them without a scroll -- and never while the menu
 * is hidden. What does not change with a scroll (the element scrolling the page, where a link lands a place) is read
 * once, and again on a resize.
 */
val PageSideMenu = FC<PageSideMenuProps> { props ->
    val entries = sideMenuEntries(props.panels, props.sections)
    var active by useState<String?>(null)
    // Whether the page beside it is long enough for it ([pageNeedsSideMenu]): measured, so no page has to say.
    var long by useState(false)
    val menu = useRef<HTMLElement>(null)
    val box = useRef<HTMLDivElement>(null)
    val ids = entries.joinToString(" ") { it.id }
    useEffect(ids) {
        if (entries.isEmpty()) return@useEffect
        var frame = 0
        // What does not change as the page scrolls -- the element that scrolls it, and where a link lands a place --
        // read once, and again on a resize; null until the menu shows.
        var scroller: dynamic = null
        var landing = 0.0
        fun measure() {
            // The page it sits beside, drawn just before it ([panelPage]): long enough for a menu, or not.
            val page = menu.current?.asDynamic()?.previousElementSibling
            val pageHeight = (page?.getBoundingClientRect()?.height as Double?) ?: 0.0
            val isLong = pageNeedsSideMenu(props.panels.size, pageHeight, window.innerHeight.toDouble())
            long = isLong
            // Hidden -- a short page, or a narrow screen: nothing to mark, and nothing more read.
            if (!isLong || !window.matchMedia(SMENU.wideQuery).matches) {
                scroller = null
                return
            }
            val first = document.getElementById(entries.first().id) ?: return
            if (scroller == null) {
                scroller = pageScroller(first)
                landing = landingOffset(scroller, first)
            }
            val box = scroller
            val ownScroller = box == document.asDynamic().scrollingElement || box == document.documentElement
            val boxTop = if (ownScroller) 0.0 else (box.getBoundingClientRect().top as Double)
            val atBottom = (box.scrollTop as Double) + (box.clientHeight as Int) >= (box.scrollHeight as Int) - SMENU.bottomSlack
            val tops = entries.mapNotNull { e -> document.getElementById(e.id)?.getBoundingClientRect()?.top?.let { e.id to it } }
            active = activeSideMenuEntry(tops, boxTop + landing + SMENU.lineSlack, atBottom, window.innerHeight.toDouble())
        }
        fun schedule() {
            if (frame == 0) {
                frame = window.requestAnimationFrame {
                    frame = 0
                    measure()
                }
            }
        }
        val onScroll: (dynamic) -> Unit = { schedule() }
        val onResize: (dynamic) -> Unit = {
            scroller = null
            schedule()
        }
        // Capturing on the document hears whichever element scrolls the page, since a scroll does not bubble.
        val options = js("({ capture: true, passive: true })")
        document.asDynamic().addEventListener("scroll", onScroll, options)
        window.asDynamic().addEventListener("resize", onResize, options)
        // Content that loads or opens moves the places without a scroll, and may make the page long enough for the
        // menu: the row holding the menu and the page beside it is watched for it -- there from the start, unlike a
        // panel, which may draw only once its read answers.
        val watched = menu.current?.parentElement
        val observer: dynamic = watched?.let {
            val made = resizeObserver { schedule() }
            made.observe(it)
            made
        }
        measure()
        try {
            awaitCancellation()
        } finally {
            document.asDynamic().removeEventListener("scroll", onScroll, options)
            window.asDynamic().removeEventListener("resize", onResize, options)
            observer?.disconnect()
            if (frame != 0) window.cancelAnimationFrame(frame)
        }
    }
    // The marked entry kept in view when the menu's own box scrolls (a short window, a long list). Its box is moved,
    // never the page: a scrollIntoView would move every box around it, the page among them.
    useEffect(active) {
        val shown = box.current ?: return@useEffect
        // The first marked, the box goes back to its top, heading and all.
        if (active != null && active == entries.firstOrNull()?.id) {
            shown.scrollTop = 0.0
            return@useEffect
        }
        val link = shown.querySelector("[aria-current]") ?: return@useEffect
        val outer = shown.getBoundingClientRect()
        val item = link.getBoundingClientRect()
        val by = scrollToShow(outer.top, outer.bottom, item.top, item.bottom, SMENU.keepInViewPad)
        if (by != 0.0) shown.scrollTop += by
    }
    val activePanel = entries.firstOrNull { it.id == active }?.panelId
    nav {
        ref = menu
        className = ClassName(if (long) "page-side-menu page-long" else "page-side-menu")
        asDynamic()["aria-label"] = "Page sections"
        div {
            ref = box
            className = ClassName("page-side-menu-box")
            // Seen, not heard: the nav's own name says it to a screen reader.
            div {
                className = ClassName("side-menu-title")
                asDynamic()["aria-hidden"] = true
                +"On this page"
            }
            // The page's unsaved edits (issue #1922), each a way to the editor holding it.
            props.pending?.all?.takeIf { it.isNotEmpty() }?.let { edits ->
                div {
                    className = ClassName("side-menu-pending")
                    div {
                        className = ClassName("side-menu-pending-title")
                        +pendingCountText(edits.size)
                    }
                    ul {
                        edits.forEach { edit ->
                            li {
                                key = edit.key.unsafeCast<Key>()
                                goToEdit(edit)
                            }
                        }
                    }
                }
            }
            ul {
                entries.filter { !it.isSection }.forEach { panelEntry ->
                    li {
                        key = panelEntry.id.unsafeCast<Key>()
                        className = ClassName(if (panelEntry.id == activePanel) "side-menu-panel side-menu-in" else "side-menu-panel")
                        panelLink(panelEntry.id, panelEntry.title, current = panelEntry.id == active)
                        if (props.pending?.inPanel(panelEntry.id) == true) unsavedMark()
                        val subs = entries.filter { it.isSection && it.panelId == panelEntry.id }
                        if (subs.isNotEmpty()) {
                            ul {
                                subs.forEach { sub ->
                                    li {
                                        key = sub.id.unsafeCast<Key>()
                                        val here = sub.id == active
                                        className = ClassName(if (here) "side-menu-section side-menu-in" else "side-menu-section")
                                        panelLink(sub.id, sub.title, current = here)
                                    if (props.pending?.inSection(sub.id) == true) unsavedMark()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Beside a side menu's entry: a dot for the eye, and words for a screen reader, that it holds unsaved changes. */
private fun ChildrenBuilder.unsavedMark() {
    span {
        className = ClassName("side-menu-unsaved")
        asDynamic()["aria-hidden"] = true
        asDynamic()["title"] = PNL.unsaved
        +"●"
    }
    span {
        className = ClassName("visually-hidden")
        +" (${PNL.unsaved.lowercase()})"
    }
}

/**
 * A link to [edit]'s editor (issue #1922), scrolled to the middle of the window -- the edit, not its panel's top -- and
 * any section folded around it opened first.
 */
private fun ChildrenBuilder.goToEdit(edit: PendingEdit) {
    a {
        className = ClassName("wf-cell-link")
        href = "#"
        onClick = { e ->
            e.preventDefault()
            val target = document.getElementById(edit.anchorId)
            // An edit folded inside a closed section is opened to first: the section's `details`, each that holds it.
            var at = target?.asDynamic()?.parentElement
            var depth = 0
            while (at != null && depth < SMENU.maxDepth) {
                if ((at.tagName as String).equals("details", ignoreCase = true) && at.open != true) at.open = true
                at = at.parentElement
                depth++
            }
            target?.asDynamic()?.scrollIntoView(js("({ block: 'center', behavior: 'smooth' })"))
        }
        +edit.label
    }
}

external interface PendingEditsBarProps : Props {
    var pending: PendingEdits
}

/**
 * A page's unsaved edits at the window's foot (issue #1922), where its side menu is not shown -- a narrow screen, a
 * short page -- since nothing else then says they are there once their editors are out of sight: how many, and a way to
 * each. `app.css` hides it where the menu shows, which lists them itself.
 */
val PendingEditsBar = FC<PendingEditsBarProps> { props ->
    val edits = props.pending.all
    if (edits.isEmpty()) return@FC
    div {
        className = ClassName("pending-edits-bar")
        asDynamic()["role"] = "status"
        span {
            className = ClassName("pending-edits-count")
            +"${pendingCountText(edits.size)}:"
        }
        edits.forEach { edit ->
            span {
                key = edit.key.unsafeCast<Key>()
                goToEdit(edit)
            }
        }
    }
}

external interface PendingEditsGuardProps : Props {
    var pending: PendingEdits

    /** Whether an address (as `routeParams` reads it) is still this page, which a move to is not a leave. */
    var stays: (Map<String, String>) -> Boolean
}

/**
 * Leaving a page with unsaved edits asks first (issue #1922): [LeaveGuard] armed while any is in the register, for every
 * editor of the page together -- its one slot is the page's, not an editor's -- and disarmed once none is.
 */
val PendingEditsGuard = FC<PendingEditsGuardProps> { props ->
    val anyPending = props.pending.any()
    useEffect(anyPending) {
        if (!anyPending) return@useEffect
        val token = LeaveGuard.arm(props.stays) { LeaveGuard.confirmLeave(pendingLeavePrompt(props.pending.all)) }
        try {
            awaitCancellation()
        } finally {
            // Its own arm only: cancelled late, as an effect's coroutine is, it must not clear the next page's.
            LeaveGuard.disarm(token)
        }
    }
}

/**
 * A page of panels in one card (issue #1919): the card ([cardClass], keyed [cardKey]) holding [body], and then its
 * [PageSideMenu] of [panels] and [sections] -- the lists the card's own top links ([panelLinks]) take. After the card in
 * the page's order, so a keyboard or a screen reader reaches the page first, and placed beside it on the left by
 * `app.css`. The menu shows only on a screen wide enough for it and a page long enough to need it
 * ([pageNeedsSideMenu]), where it takes the place of the top links; otherwise the page reads as it always did.
 *
 * A page that keeps its editors' unsaved edits passes its register ([pending], issue #1922): the card's editors put
 * themselves in it, the menu marks and lists them, a bar at the window's foot says so where the menu is not shown, and
 * leaving -- for an address [stays] does not call this page -- asks first.
 *
 * Drawn into the page's centered row (`.app-content`), as a page's card is: the two must be its items, side by side.
 */
fun ChildrenBuilder.panelPage(
    panels: List<Pair<String, String>>,
    sections: Map<String, List<Pair<String, String>>> = emptyMap(),
    cardKey: String = "page",
    cardClass: String = "card wide",
    pending: PendingEdits? = null,
    stays: (Map<String, String>) -> Boolean = { false },
    body: ChildrenBuilder.() -> Unit,
) {
    div {
        key = cardKey.unsafeCast<Key>()
        className = ClassName(cardClass)
        if (pending == null) {
            body()
        } else {
            PendingEditsContext.Provider {
                value = pending
                body()
            }
        }
    }
    PageSideMenu {
        key = "$cardKey-sideMenu".unsafeCast<Key>()
        this.panels = panels
        this.sections = sections
        this.pending = pending
    }
    if (pending != null) {
        PendingEditsBar {
            key = "$cardKey-pendingBar".unsafeCast<Key>()
            this.pending = pending
        }
        PendingEditsGuard {
            key = "$cardKey-pendingGuard".unsafeCast<Key>()
            this.pending = pending
            this.stays = stays
        }
    }
}

external interface DisclosureProps : PropsWithChildren {
    /** The line the closed disclosure shows, which opens it. */
    var summaryText: String

    /**
     * The summary as a heading of this level (3 or 4; issue #1891), for a disclosure that holds a section of the page, so
     * the page's outline keeps it; plain text when unset.
     */
    var summaryLevel: Int?

    /** Open to start, as one just added is (issue #1460); closed when unset. */
    var initiallyOpen: Boolean?

    /**
     * Opens it whenever this turns true (issue #1477), and never closes it: what is in force inside must not be out of
     * sight, but a reader who opened it keeps it open.
     */
    var openWhen: Boolean?
}

/**
 * A section of the page shown on request (issue #1397), its contents drawn only once opened -- and kept once they have
 * been, folded away rather than dropped, so an editor inside keeps its unsaved work (issue #1922): the menu's editor,
 * whose table is the page's longest and rarely changed, reads nothing until somebody asks for it. Closed to start
 * unless [DisclosureProps.initiallyOpen], and opened by [DisclosureProps.openWhen] as [MoreOptions] uses it.
 */
val Disclosure = FC<DisclosureProps> { props ->
    var expanded by useState(props.initiallyOpen == true || props.openWhen == true)
    // Once opened, its contents stay drawn, folded away by the `details` element (issue #1922): an editor inside keeps
    // its unsaved work when the section is folded, rather than losing it unasked.
    var opened by useState(expanded)
    useEffect(props.openWhen) {
        if (props.openWhen == true) expanded = true
    }
    if (expanded && !opened) opened = true
    details {
        className = ClassName("disclosure")
        this.open = expanded
        onToggle = { e -> expanded = e.currentTarget.asDynamic().open == true }
        summary {
            when (props.summaryLevel) {
                3 -> h3 { +props.summaryText }
                4 -> h4 { +props.summaryText }
                else -> +props.summaryText
            }
        }
        if (expanded || opened) +props.children
    }
}

external interface MoreOptionsProps : PropsWithChildren {
    /**
     * The record the options belong to (a user's id, say). Another record starts the disclosure afresh -- open when its
     * own options are set -- rather than keeping how the reader left the last one, which could hide a setting in effect.
     */
    var recordKey: String

    /**
     * What is set inside, each said shortly ("Editor", "labels: reviewer"); empty when everything is at its default.
     * It is the summary line, and it opens the disclosure: one list, so the two cannot disagree.
     */
    var inForce: List<String>
}

/**
 * A draft panel's less common fields (issue #1477, rule 5): closed while every one is at its default, open -- and its
 * summary naming them -- once any is not, so a setting in effect is never out of sight. Each option inside carries a
 * one-line hint beginning "Use this when".
 */
val MoreOptions = FC<MoreOptionsProps> { props ->
    Disclosure {
        key = props.recordKey.unsafeCast<Key>()
        summaryText = moreOptionsSummary(props.inForce)
        openWhen = moreOptionsOpens(props.inForce)
        +props.children
    }
}

/** A [MoreOptions] summary: `More options`, or `More options · Editor, labels: reviewer`. Pure, under `jsNodeTest`. */
fun moreOptionsSummary(inForce: List<String>): String =
    if (inForce.isEmpty()) PNL.moreOptions else "${PNL.moreOptions} · ${inForce.joinToString(", ")}"

/** Whether a [MoreOptions] opens itself: when anything inside is set. Pure, covered under `jsNodeTest`. */
fun moreOptionsOpens(inForce: List<String>): Boolean = inForce.isNotEmpty()

external interface SaveBarProps : Props {
    /** What is pending, said before the buttons ("2 keys changed."); none for a footer whose panel says it. */
    var pendingText: String?

    /** The primary verb, naming what it saves ("Save user", "Save name"). Never "OK". */
    var saveText: String

    /**
     * The way back: "Cancel" (the default) or "Discard". Link-styled, as a back link is: it drops the draft -- leaving
     * a form that makes something new, or taking an existing record's fields back to what is stored.
     */
    var discardText: String?

    /** How a save takes effect, in the style of `saveEffectText`, said with the buttons. */
    var effectText: String?

    var saving: Boolean

    /**
     * Whether there is anything to save. Save is disabled unless this is true, so a footer that forgets it shows a Save
     * that cannot be pressed -- noticed at once -- rather than one pressable before anything changed.
     */
    var canSave: Boolean?

    /**
     * The save's failure, drawn here, where the save was asked for. Not `error`: a page's own `error` state would win
     * over the prop inside the builder, and setting it there sets state during the render.
     */
    var failure: DisplayError?

    /** Leads the failure ("Couldn't save the copy."). */
    var failureLead: String?

    /**
     * In view at the window's bottom while the page scrolls -- for a draft too long for its footer to stay in sight, as
     * a copy file is. Default false.
     */
    var sticky: Boolean?

    var onSave: () -> Unit
    var onDiscard: () -> Unit
}

/**
 * A draft's one save (issue #1477, rule 2): its primary verb, the way back, how the save takes effect, and its
 * failure. A footer at the bottom of the panel it covers; [SaveBarProps.sticky] for a draft spanning several panels,
 * as the copy editor's does.
 */
val SaveBar = FC<SaveBarProps> { props ->
    div {
        className = ClassName(if (props.sticky == true) "save-bar save-bar-sticky" else "save-bar")
        props.pendingText?.let { span { +it } }
        Button {
            type = "primary"
            loading = props.saving
            disabled = props.canSave != true
            onClick = { props.onSave() }
            +props.saveText
        }
        Button {
            type = "link"
            disabled = props.saving
            onClick = { props.onDiscard() }
            +(props.discardText ?: "Cancel")
        }
        props.effectText?.let {
            p {
                className = ClassName("type-hint")
                +it
            }
        }
        props.failure?.let { failure -> props.failureLead?.let { errorText(it, failure) } ?: errorText(failure) }
    }
}

/**
 * A panel's own result line (issue #1477, rule 3): its failure, led by [errorLead] when given, else its [note] -- drawn
 * inside the panel whose action produced it, not in one line shared by the page.
 */
fun ChildrenBuilder.panelNote(error: DisplayError?, note: String?, errorLead: String? = null) {
    if (error != null) {
        if (errorLead != null) errorText(errorLead, error) else errorText(error)
    } else {
        note?.let {
            p {
                className = ClassName("form-ok")
                +it
            }
        }
    }
}

external interface DangerConfirmProps : Props {
    /**
     * The record it acts on (a user's id, say). Armed for one record, it is not armed for the next: a page that swaps
     * the record in place shows the first press again, never a confirm left waiting by the last one.
     */
    var recordKey: String

    /** The danger button that arms it, ending in an ellipsis since it asks first ("Delete user…"). */
    var actionText: String

    /** The question asked once armed, naming where it acts (`deleteTargetText`, issue #1049). */
    var question: String

    /** The button that does it ("Confirm permanent delete"). */
    var confirmText: String

    /** The way back ("Keep user"). */
    var keepText: String

    /** While the delete runs: the confirm spins, and nothing can be pressed. Not `busy`, for the reason of [SaveBarProps.failure]. */
    var inProgress: Boolean?

    /**
     * While another act on the page runs (issue #1787): nothing here can be pressed, and the confirm does not spin --
     * this act is not the one running.
     */
    var blocked: Boolean?

    /** Called on the confirm, unless the act asks for a reason ([askReason]), whose own `onConfirm` is called instead. */
    var onConfirm: () -> Unit

    /** When set, the act needs a reason (issue #1787): see [DangerReason]. */
    var askReason: DangerReason?
}

/**
 * The reason an act behind [DangerConfirm] needs (issue #1787): once armed, a text area under [label] taking at most
 * [maxLength] characters, the confirm disabled until something is written, and [onConfirm] called with it, trimmed, in
 * place of [DangerConfirmProps.onConfirm]. One value, so a reason cannot be asked for with nothing to receive it.
 */
class DangerReason(val label: String, val maxLength: Int, val onConfirm: (String) -> Unit)

/**
 * The one confirmation for what cannot be undone (issue #1477, rule 6): a danger button that, pressed, is replaced in
 * place by the question, the danger confirm, and the way back -- two deliberate presses, the second beside the words
 * saying what it does. Armed only for the record it was pressed for ([DangerConfirmProps.recordKey]). An act that
 * needs a reason ([DangerConfirmProps.askReason], issue #1787) asks for it between the question and the confirm.
 */
val DangerConfirm = FC<DangerConfirmProps> { props ->
    // Which record it was armed for, so another record reads as unarmed from its first render -- not after an effect
    // has caught up, a frame in which a click would delete the wrong one.
    var armedFor by useState<String?>(null)
    // The reason being written, and the record it is for: another record starts with none.
    var reason by useState("" to "")
    val armed = armedFor == props.recordKey
    val written = reason.second.takeIf { reason.first == props.recordKey }.orEmpty().trim()
    val ask = props.askReason
    // Nothing can be pressed while this act runs, or another on the page does.
    val held = props.inProgress == true || props.blocked == true
    if (!armed) {
        div {
            className = ClassName("row")
            Button {
                danger = true
                disabled = held
                onClick = { armedFor = props.recordKey; reason = props.recordKey to "" }
                +props.actionText
            }
        }
    } else {
        p {
            className = ClassName("subtitle")
            +props.question
        }
        ask?.let {
            choiceRow(it.label) {
                TextArea {
                    value = reason.second
                    disabled = held
                    maxLength = it.maxLength
                    asDynamic()["rows"] = 2
                    onChange = { e -> reason = props.recordKey to (e.target.value as String) }
                }
            }
        }
        div {
            className = ClassName("row")
            Button {
                danger = true
                loading = props.inProgress == true
                disabled = props.blocked == true || (ask != null && written.isEmpty())
                onClick = { if (ask != null) ask.onConfirm(written) else props.onConfirm() }
                +props.confirmText
            }
            Button {
                type = "link"
                disabled = held
                onClick = { armedFor = null }
                +props.keepText
            }
        }
    }
}
