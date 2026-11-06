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

external interface HomeCardsProps : Props {
    var cards: List<HomeCard>
}

/**
 * The home cards (issue #1636), under the home page's copy: a panel each, with its words, its links, and its primary
 * button. A card of a [HomeCard.kind] the page knows reads what it needs as it is drawn -- never in the polled shell
 * config -- and may change its button or draw more; one the page does not know is drawn from its words alone.
 */
val HomeCards = FC<HomeCardsProps> { props ->
    // The copy's links, as the home page's intro resolves them: a document, a page, or a section of this page. Read
    // before anything returns, so the hooks this calls are the same however many cards there are.
    val copyLinks = useShellFacts().copyLinkResolver()
    if (props.cards.isEmpty()) return@FC
    div {
        className = ClassName("home-cards")
        for (card in props.cards) {
            when (card.kind) {
                ASST.cardKind -> AssistantHomeCard {
                    key = card.id.unsafeCast<Key>()
                    this.card = card
                }
                else -> homeCardPanel(card, copyLinks, card.actionLabel)
            }
        }
    }
}

/** One card's panel: [card]'s words and links, and its primary button reading [actionLabel]. */
private fun ChildrenBuilder.homeCardPanel(card: HomeCard, copyLinks: (String) -> String, actionLabel: String?) {
    panel(homeCardElementId(card.id), card.title) {
        if (card.text.isNotBlank()) Markdown { source = card.text; linkResolver = copyLinks }
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

private external interface AssistantHomeCardProps : Props {
    var card: HomeCard
}

/**
 * The workflow assistant's card (issue #1636): its words, and a button whose words follow the assistant's status --
 * the card's offer label while it is off and the person may switch it on. Read once as the card is drawn; until it
 * answers, or if it cannot be read, the card's own words stand.
 */
private val AssistantHomeCard = FC<AssistantHomeCardProps> { props ->
    val copyLinks = useShellFacts().copyLinkResolver()
    var view by useState<AssistantView?>(null)
    useEffect(props.card.id) {
        homeCardsScope.launch {
            val result = apiResult { AssistantApi.status() }
            result.failureOrNull()?.let { console.warn("$errorLogPrefix could not read the assistant's status for its home card: ${it.message}") }
            view = assistantViewOf(result.valueOrNull(), failed = result.failureOrNull() != null)
        }
    }
    homeCardPanel(props.card, copyLinks, assistantCardActionLabel(props.card, view))
}
