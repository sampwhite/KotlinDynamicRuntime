package com.dynamicruntime.webapp

import com.dynamicruntime.common.cfact.CFactParser
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.page.PGW
import com.dynamicruntime.common.page.PageFence
import com.dynamicruntime.common.page.PageFenceSource
import com.dynamicruntime.common.page.PageMarkdown
import com.dynamicruntime.common.page.PageWidgetKind
import com.dynamicruntime.common.page.PageWidgets
import com.dynamicruntime.common.page.readPageFence
import com.dynamicruntime.common.page.splitPageDocument
import com.dynamicruntime.common.util.evalTemplate

/** One part of a page as it is drawn (issue #1794): Markdown between fences, or a fence that is drawn. */
sealed interface PagePart

/** Markdown between a page's fences, its copy pulls already resolved. */
class PageText(val markdown: String) : PagePart

/**
 * A fence that is drawn: [fence] as read, its [kind] (null for a fence that draws only its copy), and its copy's text
 * ([copy], pulls resolved; null when it names none, or blank).
 */
class PagePlaced(val fence: PageFence, val kind: PageWidgetKind?, val copy: String?) : PagePart {
    /** The element the fence is drawn in: `page.<id>`, which a link names. */
    val elementId: String get() = PGW.elementIdPrefix + fence.id
}

/**
 * The parts of the page [document] that are drawn (issue #1794), in its order: [parsePage], then [selectParts] for
 * this caller. Pure, covered under `jsNodeTest`.
 */
fun planPage(
    document: String,
    copy: Copy,
    cfacts: Map<String, Boolean>,
    drawable: (PageWidgetKind) -> Boolean,
    shows: (PagePlaced) -> Boolean,
): List<PagePart> = selectParts(parsePage(document, copy), cfacts, drawable, shows)

/**
 * The page [document] read once, before any caller's facts or data apply (issue #1794): its Markdown, and each fence that
 * reads -- closed, an object with an id, its keys text, a kind there is one of -- with its copy's text. A fence with no
 * widget whose copy says nothing is left out here. Markdown between fences, and each fence's copy, get the frontend
 * template pass against [copy] ([pageCopyPass]), so a page pulls a key with `${@t("namespace.key")}`. What it gives
 * depends only on the document and the copy, so a page reads it once per copy rather than on every render. Pure.
 */
fun parsePage(document: String, copy: Copy): List<PagePart> = splitPageDocument(document).mapNotNull { segment ->
    when (segment) {
        is PageMarkdown -> pageCopyPass(segment.text, copy)?.let { PageText(it) }
        is PageFenceSource -> readFence(segment, copy)
    }
}

/** [source] as a fence that reads, with its copy's text, or null when it does not, or would draw nothing. */
private fun readFence(source: PageFenceSource, copy: Copy): PagePlaced? {
    if (!source.closed) return null
    val fence = readPageFence(source).getOrNull() ?: return null
    if (fence.id == null || fence.notText.isNotEmpty()) return null
    val kind = fence.widget?.let { name -> PageWidgets.core.kind(name) ?: return null }
    val text = fence.copy?.let { pageCopyText(it, copy) }
    if (kind == null && text == null) return null
    return PagePlaced(fence, kind, text)
}

/**
 * The [parsed] parts drawn for this caller (issue #1794): a fence only when its kind is one this surface draws
 * ([drawable]), its condition holds on [cfacts], its widget has something to show ([shows], asked of a fence with a
 * widget), and no fence drawn before it has its id. Anything else is left out, never failing the page: the checks at
 * boot and at a client's write are where a wrong fence is reported. Pure.
 */
fun selectParts(
    parsed: List<PagePart>,
    cfacts: Map<String, Boolean>,
    drawable: (PageWidgetKind) -> Boolean,
    shows: (PagePlaced) -> Boolean,
): List<PagePart> {
    val ids = HashSet<String>()
    return parsed.filter { part ->
        when (part) {
            is PageText -> true
            is PagePlaced -> (part.kind == null || drawable(part.kind)) &&
                (part.fence.cfactExpression == null || pageConditionHolds(part.fence.cfactExpression!!, cfacts)) &&
                (part.kind == null || shows(part)) && ids.add(part.fence.id!!)
        }
    }
}

/**
 * Whether a fence's [condition] holds on [cfacts] -- the caller's facts the shell config sends, each name to whether it
 * is present -- with the same kernel parser the backend uses. A condition that does not parse against them does not
 * hold: the fence is left out, as one with any other problem is. Pure.
 */
fun pageConditionHolds(condition: String, cfacts: Map<String, Boolean>): Boolean =
    CFactParser.analyze(condition, cfacts.keys, evaluateWith = cfacts.filterValues { it }.keys).value ?: false

/** The text of [key] (`namespace.key`) in [copy], pulls resolved; null when absent or blank, so it draws nothing. */
fun pageCopyText(key: String, copy: Copy): String? {
    val namespace = key.substringBefore('.', "")
    val name = key.substringAfter('.', "")
    return copy.opt(namespace, name)?.let { pageCopyPass(it, copy) }
}

/** The copy whose template pass has failed, so each is said in the console once rather than on every render. */
private val unfilledCopy = HashSet<String>()

/**
 * [text] with the frontend template pass applied against the page's own [copy], so `${@t("namespace.key")}` pulls a key;
 * null when the result is blank. Copy the pass cannot fill in -- a missing key, or a literal `${` written before pages
 * were drawn this way -- is drawn as written, as copy was before (said in the console, once per text): never left out.
 * Never applied to a fence's body, which is cut out before it runs.
 *
 * Theme: #TPLPS.
 */
fun pageCopyPass(text: String, copy: Copy): String? {
    val passed = try {
        text.evalTemplate(emptyMap(), resolver = copy.fragmentResolver())
    } catch (e: Throwable) {
        if (unfilledCopy.add(text)) console.warn("A page's copy could not be filled in, and is drawn as written: ${e.message}")
        text
    }
    return passed.takeIf { it.isNotBlank() }
}

/**
 * The cards a `cardsBlock` fence draws (issue #1794), from [cards], the caller's visible home cards: those of the block
 * the shell config resolves ([HCARD.block]), the fence's `ids` in their order when it names them, less its `except`.
 * Another block draws nothing yet: only the home cards reach the page. Pure, covered under `jsNodeTest`.
 */
fun pageCardsOf(fence: PageFence, cards: List<HomeCard>): List<HomeCard> {
    if (fence.params[PGW.block] != HCARD.block) return emptyList()
    val ids = (fence.params[PGW.ids] as? List<*>)?.mapNotNull { it as? String }
    val except = (fence.params[PGW.except] as? List<*>)?.mapNotNull { it as? String }.orEmpty().toSet()
    val chosen = ids?.mapNotNull { id -> cards.firstOrNull { it.id == id } } ?: cards
    return chosen.filterNot { it.id in except }
}

/**
 * The cards each `cardsBlock` fence of the page [parts] draws (issue #1794), by its element id: its own ([pageCardsOf]),
 * less any a fence before it draws, so a card is drawn once whatever a document names, and its element id is one.
 * Pure, covered under `jsNodeTest`.
 */
fun pageCardsByFence(parts: List<PagePart>, cards: List<HomeCard>): Map<String, List<HomeCard>> {
    val drawn = HashSet<String>()
    return parts.filterIsInstance<PagePlaced>().filter { it.kind == PageWidgets.cardsBlock }
        .associate { part -> part.elementId to pageCardsOf(part.fence, cards).filter { drawn.add(it.id) } }
}

/** The row a `jumpLinks` fence draws: the call-to-action buttons, each label to the page it opens, then the links. */
class PageJumpRow(val buttons: List<Pair<String, String>>, val links: String?) {
    val isEmpty: Boolean get() = buttons.isEmpty() && links == null
}

/**
 * The jump row of the page [parts] that the fence [row] draws (issue #1794). It is led by a button for each drawn card
 * that is the page's call to action ([homeCardJumpButtons]). Then come the fence's copy, links written in Markdown, a
 * link to each other drawn fence with a `jumpLabel`, and one to each drawn card that asks for one
 * ([homeCardJumpLinks]), written in the copy's style ([withCardJumpLinks]). A link to a fence or a card not drawn for
 * this caller is left out ([jumpLinksFor]). [cardsOf] gives a `cardsBlock` fence's cards. Pure, covered under
 * `jsNodeTest`.
 */
fun pageJumpRow(
    parts: List<PagePart>,
    row: PagePlaced,
    cardsOf: (PagePlaced) -> List<HomeCard>,
    assistantView: AssistantView?,
): PageJumpRow {
    val placed = parts.filterIsInstance<PagePlaced>()
    val cards = placed.filter { it.kind == PageWidgets.cardsBlock }.flatMap(cardsOf)
    val fenceLinks = placed.filter { it !== row }.mapNotNull { part ->
        part.fence.jumpLabel?.takeIf { it.isNotBlank() }?.let { it to part.elementId }
    }
    val drawn = placed.map { it.elementId }.toSet() + cards.filterNot { it.leadsJumpRow }.map { homeCardElementId(it.id) }
    val source = withCardJumpLinks(row.copy, fenceLinks + homeCardJumpLinks(cards, assistantView))
    return PageJumpRow(homeCardJumpButtons(cards), jumpLinksFor(source, drawn))
}
