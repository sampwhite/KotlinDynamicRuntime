package com.dynamicruntime.webapp

import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiRoute
import com.dynamicruntime.common.uiblock.parseUiAction
import com.dynamicruntime.common.util.toJsonListOfMaps
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.nav
import react.useEffect
import react.useMemo
import react.useRef
import react.useState
import web.cssom.ClassName

private val homeCardsScope = MainScope()

/** One link on a home card: its words, and the page it opens. */
class HomeCardLinkView(val label: String, val page: String)

/**
 * One home card as the backend sent it (issue #1636): what Home shows this caller beyond its copy. The backend decided
 * which cards the caller gets; the page draws them in the order given.
 */
class HomeCard(
    val id: String,
    val title: String,
    /** Markdown, drawn as the home page's copy is. */
    val text: String,
    /** The page the primary button opens, with [actionLabel] its words; null for a card with no button. */
    val actionPage: String?,
    val actionLabel: String?,
    /** The button's words while what it opens is offered but not yet on ([HCARD.offerLabel]); null for none. */
    val offerLabel: String?,
    /** The words of a link to the card in the row under Home's title ([HCARD.jumpLabel]); null for none. */
    val jumpLabel: String? = null,
    val links: List<HomeCardLinkView>,
    /** The live body the card draws ([HCARD.kind]); null for a card of words alone. */
    val kind: String?,
    /** The body as `home` copy, `namespace.key` ([HCARD.textCopy]), drawn in place of [text]; null for none. */
    val textCopy: String? = null,
    /** Whether the card is the page's call to action ([HCARD.actionFirst]): its button first, and a button under the title. */
    val actionFirst: Boolean = false,
    /** Whether Home draws the card above its introduction ([HCARD.aboveIntro]). */
    val aboveIntro: Boolean = false,
) {
    /**
     * Whether the row under Home's title draws this card as a button ([homeCardJumpButtons]), not a link: the page's
     * call to action, named in the row, with a page for the button to open.
     */
    val leadsJumpRow: Boolean get() = actionFirst && jumpLabel != null && actionPage != null
}

/**
 * What [card]'s body says (issue #1652): the `home` copy its [HomeCard.textCopy] names, read from [copy] -- blank, and so
 * no body, until that copy arrives or where a deployment emptied it -- else its own [HomeCard.text]. Pure, and covered
 * under `jsNodeTest`.
 */
fun homeCardBody(card: HomeCard, copy: Copy): String {
    val named = card.textCopy ?: return card.text
    val ns = named.substringBefore('.', "")
    val key = named.substringAfter('.', "")
    return if (ns.isEmpty() || key.isEmpty()) "" else copy.opt(ns, key).orEmpty()
}

/**
 * The cards in a home config's `state.cards` (issue #1636). A card with no id is dropped, and so is an action or a link
 * that is not a route, since a card offers somewhere to go, and a link with no words, which would show a page's
 * internal id; anything else missing reads as empty. Pure, and covered under `jsNodeTest`.
 */
fun homeCardsFrom(raw: Any?): List<HomeCard> = raw.toJsonListOfMaps().mapNotNull { card ->
    val id = (card[HFLD.id] as? String)?.ifBlank { null } ?: return@mapNotNull null
    val action = parseUiAction(card[UIB.action]) as? UiRoute
    HomeCard(
        id = id,
        title = card[HCARD.title] as? String ?: "",
        text = card[HCARD.text] as? String ?: "",
        actionPage = action?.page,
        actionLabel = (card[HCARD.actionLabel] as? String)?.ifBlank { null },
        offerLabel = (card[HCARD.offerLabel] as? String)?.ifBlank { null },
        jumpLabel = (card[HCARD.jumpLabel] as? String)?.ifBlank { null },
        links = card[HCARD.links].toJsonListOfMaps().mapNotNull { link ->
            val page = (parseUiAction(link[UIB.action]) as? UiRoute)?.page ?: return@mapNotNull null
            val label = (link[HCARD.linkLabel] as? String)?.ifBlank { null } ?: return@mapNotNull null
            HomeCardLinkView(label, page)
        },
        kind = (card[HCARD.kind] as? String)?.ifBlank { null },
        textCopy = (card[HCARD.textCopy] as? String)?.ifBlank { null },
        actionFirst = card[HCARD.actionFirst] == true,
        aboveIntro = card[HCARD.aboveIntro] == true,
    )
}

/**
 * What Home read for the cards whose [HomeCard.kind] draws a live body (issues #1636, #1637, #1638): the workflow
 * assistant's status, what waits on the caller, and the client's unpublished drafts, each null until its read answers
 * (or when it cannot be read, or no card asks for it). **A kind is added here alone**: its read in [useHomeLiveReads],
 * whether it has something to say in [shows], its button's words in [actionLabel], and its body in [drawBody] -- so a
 * new kind cannot be read and then forgotten by the page that draws it.
 */
class HomeLiveReads(
    val assistantView: AssistantView?,
    val waiting: WaitingRead?,
    /** The client the waiting read was confined to, for a caller who sees across clients; null otherwise. */
    val waitingClient: String?,
    val drafts: DraftsRead?,
    /** The new client's checklist's steps, the assistant's among them ([checklistSteps]); empty until read. */
    val checklist: List<ChecklistStep>,
    /** Whether this browser put the checklist away -- hidden, or done ([checklistHiddenHere]) -- or it is no one's here. */
    val checklistHidden: Boolean,
    /** Puts the checklist away on Home in this browser, remembering it ("Hide here"). */
    val hideChecklist: () -> Unit,
) {
    /**
     * Whether [card] has something to show: one whose live body has nothing to say yet -- "Waiting on you" while nothing
     * is known to wait, "Unpublished drafts" while no draft is known, the checklist while no step is left (or once this
     * browser was told to hide it) -- is left out, so neither it nor a link to it shows empty. A card of words alone
     * always shows.
     */
    fun shows(card: HomeCard): Boolean = when (card.kind) {
        HCARD.kindWaitingOnYou -> (waiting?.total ?: 0) > 0
        HCARD.kindUnpublishedDrafts -> (drafts?.count ?: 0) > 0
        HCARD.kindNewClientChecklist -> !checklistHidden && checklist.any { !it.done }
        else -> true
    }

    /** [card]'s primary button's words: the assistant's from its status ([assistantCardActionLabel]), others their own. */
    fun actionLabel(card: HomeCard): String? = when (card.kind) {
        ASST.cardKind -> assistantCardActionLabel(card, assistantView)
        else -> card.actionLabel
    }

    /** What [card]'s kind draws under its words, into [builder]; nothing for a kind of words alone, or before its read. */
    fun drawBody(builder: ChildrenBuilder, card: HomeCard) {
        val waiting = waiting
        val drafts = drafts
        with(builder) {
            when {
                card.kind == HCARD.kindWaitingOnYou && waiting != null -> waitingCardBody(waiting, waitingClient)
                card.kind == HCARD.kindUnpublishedDrafts && drafts != null -> draftsCardBody(drafts)
                card.kind == HCARD.kindNewClientChecklist && checklist.isNotEmpty() -> checklistCardBody(checklist, hideChecklist)
            }
        }
    }
}

/**
 * Everything Home reads for its live cards ([HomeLiveReads]), each read only when a card in [cards] asks for it, and
 * read once here, by Home, so a card and the link to it under the title agree. [waitingClient] confines what waits on
 * the caller to their own client, for one who sees across clients. [assistantOffered], whether the menu offers the
 * workflow assistant, adds its step to the checklist (issue #1639) -- and has its status read for that, even with no
 * card of its own. [ownClient] is the caller's client, the one whose checklist is offered: the card is never offered
 * inside a sandbox, so it is the checklist's own.
 *
 * The checklist is put away in this browser once it is hidden or every step is done, and then is not read at all; what
 * this browser remembers is read once per client, not on every draw. Its steps wait for the assistant's status where the
 * menu offers the assistant, so the list never grows a step after it is drawn, and never looks done before it is.
 *
 * [decidedAt] is the refresh generation Home's config, which holds [cards], was read at ([useCardRead]).
 */
fun useHomeLiveReads(
    cards: List<HomeCard>,
    decidedAt: Int,
    waitingClient: String?,
    assistantOffered: Boolean,
    ownClient: String?,
): HomeLiveReads {
    val hiddenBefore = useMemo(ownClient) { ownClient != null && checklistHiddenHere(ownClient) }
    // What this browser put away since Home was drawn, beside what it remembers from before: a hide redraws at once.
    var hiddenNow by useState(emptySet<String>())
    val checklistHidden = ownClient == null || hiddenBefore || ownClient in hiddenNow
    val wantsChecklist = !checklistHidden && cards.any { it.kind == HCARD.kindNewClientChecklist }
    val assistantView = useAssistantCardView(cards, decidedAt, alsoWanted = wantsChecklist && assistantOffered)
    val checklistRead = useClientChecklist(wantsChecklist, decidedAt)
    val checklist = checklistRead?.let { checklistSteps(it, assistantOffered, assistantView) }.orEmpty()
    fun putAway() {
        if (ownClient == null) return
        localStorageSet(checklistHiddenKey(ownClient), "true", "the checklist's hide choice")
        hiddenNow = hiddenNow + ownClient
    }
    val allDone = checklist.isNotEmpty() && checklist.all { it.done }
    useEffect(allDone, ownClient) { if (allDone) putAway() }
    return HomeLiveReads(
        assistantView = assistantView,
        waiting = useWaitingOnYou(cards, decidedAt, waitingClient),
        waitingClient = waitingClient,
        drafts = useUnpublishedDrafts(cards, decidedAt),
        checklist = checklist,
        checklistHidden = checklistHidden,
        hideChecklist = ::putAway,
    )
}

/**
 * The cards Home draws (issues #1637, #1638, #1639): those of [cards] that have something to show ([HomeLiveReads.shows]).
 * Pure, and covered under `jsNodeTest`.
 */
fun visibleHomeCards(cards: List<HomeCard>, reads: HomeLiveReads): List<HomeCard> = cards.filter(reads::shows)

/**
 * One live card's read (issues #1636, #1637, #1638): [read]'s answer when [wanted], read again whenever the config that
 * decided [wanted] is read again ([decidedAt]) and whenever [key] changes, and null when not wanted. A failed read
 * leaves what was there and says so ([what] names it). **Only the latest read lands**: one overtaken -- by a refresh, a
 * changed [key], or the card going -- is dropped when it answers, so a slow earlier answer never replaces a newer one.
 *
 * [decidedAt] is the refresh generation that config was read at, never the generation now. A session change bumps the
 * generation before the new caller's config arrives, and a read keyed on the bump would be made as the new caller from
 * the old caller's cards: an administrator's drafts read, made as the editor they just switched to, is refused. Keyed on
 * the config, it waits for the cards that are the new caller's.
 */
fun <T : Any> useCardRead(wanted: Boolean, decidedAt: Int, key: String?, what: String, read: suspend () -> T?): T? {
    var value by useState<T?>(null)
    val latest = useRef(0)
    useEffect(wanted, decidedAt, key) {
        val token = (latest.current ?: 0) + 1
        latest.current = token
        if (!wanted) {
            value = null
            return@useEffect
        }
        homeCardsScope.launch {
            val result = apiResult { read() }
            result.failureOrNull()?.let { console.warn("$errorLogPrefix could not read $what for Home: ${it.message}") }
            if (latest.current == token) value = result.valueOrNull()
        }
    }
    return value
}

/** The element id of card [id], so an address can name it. A dot, as the public home's sections have (`HSEC`). */
fun homeCardElementId(id: String): String = "home.card.$id"

/**
 * The primary button's words for the assistant's [card] (issue #1636), from what the assistant's status says ([view],
 * null until read): while it is off and the person may switch it on, the card's [HomeCard.offerLabel] -- the button
 * still opens the page, whose panel switches it on -- and otherwise the card's own words. Pure, and covered under
 * `jsNodeTest`.
 */
fun assistantCardActionLabel(card: HomeCard, view: AssistantView?): String? =
    if (view == AssistantView.switchOn) card.offerLabel ?: card.actionLabel else card.actionLabel

/**
 * The links to cards in the row under Home's title (issue #1636), as each label to the card's element id: one for each
 * card that names a [HomeCard.jumpLabel], in the cards' order, but for a card drawn there as a button
 * ([homeCardJumpButtons]). The assistant's reads its offer label while the person may switch it on ([assistantView]),
 * as its button does. Pure, and covered under `jsNodeTest`.
 */
fun homeCardJumpLinks(cards: List<HomeCard>, assistantView: AssistantView?): List<Pair<String, String>> =
    cards.filterNot { it.leadsJumpRow }.mapNotNull { card ->
        val label = card.jumpLabel ?: return@mapNotNull null
        val shown = if (card.kind == ASST.cardKind && assistantView == AssistantView.switchOn) card.offerLabel ?: label else label
        shown to homeCardElementId(card.id)
    }

/**
 * The buttons at the head of the row under Home's title (issue #1698), as each label to the page it opens: one for each
 * card that is the page's call to action ([HomeCard.leadsJumpRow]). The button does what the card's own button does --
 * Register opens the Register page -- rather than scroll to the card, which on a window showing the whole page would
 * move nothing and seem to do nothing. Such a card is left out of what the row's links count as drawn, so a link the
 * copy writes to it gives way to the button. Pure, and covered under `jsNodeTest`.
 */
fun homeCardJumpButtons(cards: List<HomeCard>): List<Pair<String, String>> =
    cards.filter { it.leadsJumpRow }.map { card -> card.jumpLabel!! to card.actionPage!! }

/**
 * What the workflow assistant's status says, for its card and the link to it (issue #1636), or null while it is being
 * read, or when no card in [cards] is the assistant's and nothing else wants it ([alsoWanted]: the new client checklist's
 * step, issue #1639) -- then nothing is read ([useCardRead], which says what [decidedAt] is). [AssistantView.failed] when
 * it cannot be read.
 */
fun useAssistantCardView(cards: List<HomeCard>, decidedAt: Int, alsoWanted: Boolean): AssistantView? =
    useCardRead(alsoWanted || cards.any { it.kind == ASST.cardKind }, decidedAt, null, "the assistant's status") {
        // A failure is an answer too ([AssistantView.failed]), so what waits for the status -- the checklist -- is not
        // kept waiting by one that will not come.
        val status = apiResult { AssistantApi.status() }
        status.failureOrNull()?.let { console.warn("$errorLogPrefix could not read the assistant's status for Home: ${it.message}") }
        assistantViewOf(status.valueOrNull(), failed = status.failureOrNull() != null)
    }

external interface HomeCardsProps : Props {
    var cards: List<HomeCard>
    /** What Home read for the live cards ([useHomeLiveReads]). */
    var reads: HomeLiveReads
    /** The `home` copy, for a card whose body is copy ([homeCardBody]). */
    var copy: Copy
}

/**
 * The home cards (issue #1636), under the home page's copy: a panel each, with its words, its links, and its primary
 * button. A card of a [HomeCard.kind] the page knows may change its button from what Home read for it -- never from the
 * polled shell config; one the page does not know is drawn from its words alone.
 */
val HomeCards = FC<HomeCardsProps> { props ->
    // The copy's links, as the home page's intro resolves them: a document, a page, or a section of this page. Read
    // before anything returns, so the hooks this calls are the same however many cards there are.
    val copyLinks = useShellFacts().copyLinkResolver()
    if (props.cards.isEmpty()) return@FC
    div {
        className = ClassName("home-cards")
        for (card in props.cards) {
            // What the card's kind reads, under its words, and its button's words from it.
            homeCardPanel(card, homeCardBody(card, props.copy), copyLinks, props.reads.actionLabel(card)) {
                props.reads.drawBody(this, card)
            }
        }
    }
}

/**
 * One card's panel: [card]'s title, its [body] ([homeCardBody]), what its kind draws ([live]), its links, and its primary
 * button reading [actionLabel] -- the button and links first, before the words, for the page's call to action
 * ([HomeCard.actionFirst], issue #1698).
 */
private fun ChildrenBuilder.homeCardPanel(
    card: HomeCard,
    body: String,
    copyLinks: (String) -> String,
    actionLabel: String?,
    live: ChildrenBuilder.() -> Unit = {},
) {
    panel(homeCardElementId(card.id), card.title) {
        if (card.actionFirst) homeCardActions(card, actionLabel, first = true)
        if (body.isNotBlank()) Markdown { source = body; linkResolver = copyLinks }
        live()
        if (!card.actionFirst) homeCardActions(card, actionLabel, first = false)
    }
}

/**
 * [card]'s links and its primary button reading [actionLabel]. After its words ([first] false), the links and then the
 * button beneath them, as cards have always drawn. Before its words, one row led by the button, the links beside it
 * (`home-card-lead`), so the card's purpose is the first thing in it.
 */
private fun ChildrenBuilder.homeCardActions(card: HomeCard, actionLabel: String?, first: Boolean) {
    val page = card.actionPage
    val button: ChildrenBuilder.() -> Unit = {
        if (page != null && actionLabel != null) {
            Button {
                type = "primary"
                onClick = { navigate(listOf(HP.page to page)) }
                +actionLabel
            }
        }
    }
    // An in-app link each: a plain click is followed without a reload, a ctrl-click opens a tab. Keyed by place: two links
    // may open the same page, and the list is only ever replaced whole.
    val links: ChildrenBuilder.() -> Unit = {
        for ((index, link) in card.links.withIndex()) {
            a {
                key = index.toString().unsafeCast<Key>()
                href = routeHref(listOf(HP.page to link.page))
                +link.label
            }
        }
    }
    if (first) {
        div {
            className = ClassName("home-card-lead")
            button()
            links()
        }
        return
    }
    if (card.links.isNotEmpty()) {
        nav {
            className = ClassName("home-card-links")
            links()
        }
    }
    if (page != null && actionLabel != null) {
        div {
            className = ClassName("home-card-action")
            button()
        }
    }
}
