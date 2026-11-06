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
)

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
    )
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
 * card that names a [HomeCard.jumpLabel], in the cards' order. The assistant's reads its offer label while the person
 * may switch it on ([assistantView]), as its button does. Pure, and covered under `jsNodeTest`.
 */
fun homeCardJumpLinks(cards: List<HomeCard>, assistantView: AssistantView?): List<Pair<String, String>> =
    cards.mapNotNull { card ->
        val label = card.jumpLabel ?: return@mapNotNull null
        val shown = if (card.kind == ASST.cardKind && assistantView == AssistantView.switchOn) card.offerLabel ?: label else label
        shown to homeCardElementId(card.id)
    }

/**
 * What the workflow assistant's status says, for its card and the link to it (issue #1636), or null while it is being
 * read, when it cannot be, or when no card in [cards] is the assistant's -- then nothing is read. Read once per card
 * set and refresh, by Home, so the card's button and the link under the title agree.
 */
fun useAssistantCardView(cards: List<HomeCard>): AssistantView? {
    val shown = cards.any { it.kind == ASST.cardKind }
    val generation = useRefreshGeneration()
    var view by useState<AssistantView?>(null)
    useEffect(shown, generation) {
        if (!shown) {
            view = null
            return@useEffect
        }
        homeCardsScope.launch {
            val result = apiResult { AssistantApi.status() }
            result.failureOrNull()?.let { console.warn("$errorLogPrefix could not read the assistant's status for its home card: ${it.message}") }
            view = result.valueOrNull()?.let { assistantViewOf(it, failed = false) }
        }
    }
    return view
}

external interface HomeCardsProps : Props {
    var cards: List<HomeCard>
    /** What the assistant's status says, for its card's button ([useAssistantCardView]); null until read. */
    var assistantView: AssistantView?
    /** What waits on the caller, for the "Waiting on you" card ([useWaitingOnYou]); null until read. */
    var waiting: WaitingRead?
    /** The client the waiting read was confined to, for a caller who sees across clients; null otherwise. */
    var waitingClient: String?
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
            val actionLabel = when (card.kind) {
                ASST.cardKind -> assistantCardActionLabel(card, props.assistantView)
                else -> card.actionLabel
            }
            val waiting = props.waiting
            homeCardPanel(card, copyLinks, actionLabel) {
                // The forms waiting on the caller (issue #1637), under the card's words.
                if (card.kind == HCARD.kindWaitingOnYou && waiting != null) waitingCardBody(waiting, props.waitingClient)
            }
        }
    }
}

/** One card's panel: [card]'s words, what its kind draws ([live]), its links, and its primary button reading [actionLabel]. */
private fun ChildrenBuilder.homeCardPanel(
    card: HomeCard,
    copyLinks: (String) -> String,
    actionLabel: String?,
    live: ChildrenBuilder.() -> Unit = {},
) {
    panel(homeCardElementId(card.id), card.title) {
        if (card.text.isNotBlank()) Markdown { source = card.text; linkResolver = copyLinks }
        live()
        if (card.links.isNotEmpty()) {
            nav {
                className = ClassName("home-card-links")
                // Keyed by place: two links may open the same page, and the list is only ever replaced whole.
                for ((index, link) in card.links.withIndex()) {
                    // An in-app link: a plain click is followed without a reload, a ctrl-click opens a tab.
                    a {
                        key = index.toString().unsafeCast<Key>()
                        href = routeHref(listOf(HP.page to link.page))
                        +link.label
                    }
                }
            }
        }
        val page = card.actionPage
        if (page != null && actionLabel != null) {
            div {
                className = ClassName("home-card-action")
                Button {
                    type = "primary"
                    onClick = { navigate(listOf(HP.page to page)) }
                    +actionLabel
                }
            }
        }
    }
}
