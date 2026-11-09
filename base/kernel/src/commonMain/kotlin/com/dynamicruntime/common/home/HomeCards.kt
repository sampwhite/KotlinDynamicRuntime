package com.dynamicruntime.common.home

import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.uiblock.UiBlockItemsBuilder
import com.dynamicruntime.common.uiblock.UiRoute

/**
 * The home cards (issue #1636): what Home shows a caller beyond the copy every caller of the client reads -- a card
 * per thing this caller can do next. A registered UiBlock ([block]), as the home menu is, so the cards a caller gets are
 * decided by the backend from cfacts, a component adds its own with an overlay, and a client hides or rewords one the
 * way it does a menu item. Its words are literal, as the menu's labels are, except a body that names copy in the
 * shell's `home` file ([textCopy], issue #1652), which a deployment, a flavor, or a client rewords as copy.
 */
@Suppress("ConstPropertyName")
object HCARD {
    /** The UiBlock the cards are registered as; its [cards] merge by [HFLD.id]. */
    const val block = "homeCards"

    /** The block's array of cards, and the home config's `state` entry holding this caller's. */
    const val cards = "cards"

    /** A card's heading. */
    const val title = "title"

    /** A card's body: Markdown, its links resolved as the home page's copy resolves them. */
    const val text = "text"

    /**
     * A card's body as copy (issue #1652): `namespace.key` of the shell's `home` fragment file, whose value the page
     * draws in place of [text]. So a card's words can be reworded where copy is -- by a deployment flavor, which can
     * reword copy but cannot overlay a block -- and a blank value draws no body, as blank copy removes anything else.
     */
    const val textCopy = "textCopy"

    /** The words on a card's primary button, beside its [UIB.action] (a route). */
    const val actionLabel = "actionLabel"

    /**
     * The words on a card's primary button while what it opens is offered but not yet on -- the workflow assistant's
     * "Switch on the assistant" -- read by a card whose [kind] knows that state. In the card, beside [actionLabel], so a
     * client's overlay rewords it with the rest of the card.
     */
    const val offerLabel = "offerLabel"

    /**
     * The words of a link to the card in the row of links under Home's title (`home.jumpLinks`, issue #1636), for a card
     * worth finding without scrolling to it; absent for a card the row does not name. While what the card opens is
     * offered but not yet on, the link reads its [offerLabel] instead, as its button does.
     */
    const val jumpLabel = "jumpLabel"

    /**
     * Whether the card is the page's call to action (issue #1698): its button and links come before its words, and the
     * row under Home's title draws, in place of a link to it ([jumpLabel]), a button that does what its button does. For
     * a card whose reason to be read is to be acted on -- the Register card, which on a phone otherwise puts its button
     * several screens down. Absent is false.
     */
    const val actionFirst = "actionFirst"

    /** A card's further links, each a [linkLabel] and a [UIB.action] (a route), drawn under its text. */
    const val links = "links"

    /** A link's words. */
    const val linkLabel = "label"

    /**
     * The live body a card draws (issue #1636): a name the webapp knows, whose body reads what it shows when the card is
     * drawn -- never in the polled config -- and may draw nothing when it has nothing to say. A kind the webapp does
     * not know draws the card's own words alone.
     */
    const val kind = "kind"

    /**
     * The live body of [waiting] (issue #1637): the forms with a step waiting on the caller, read from the forms
     * listing's `needsMe`, and the card drawn only when there is one.
     */
    const val kindWaitingOnYou = "waitingOnYou"

    /**
     * The live body of [drafts] (issue #1638): how many of the client's configurations have unpublished changes, read
     * from `/clientAdmin/config/drafts`, and the card drawn only when one has.
     */
    const val kindUnpublishedDrafts = "unpublishedDrafts"

    /**
     * The live body of [checklist] (issue #1639): the new client's steps, read from `/clientAdmin/client/checklist`
     * with the webapp's own step for the workflow assistant beside them, and the card drawn only while one is left.
     */
    const val kindNewClientChecklist = "newClientChecklist"

    // Core's card ids.
    /**
     * An invitation to register (issue #1652), for a visitor who has not signed in, wherever anyone may: what the
     * walk-through and the demos start from. Its body is the `home` file's [registerCopy].
     */
    const val register = "register"

    /** The `home` file's key holding the [register] card's body ([textCopy]). */
    const val registerCopy = "registerCard.text"

    /** The forms with a step waiting on the caller (issue #1637), first, since it is what to do next. */
    const val waiting = "waiting"

    /** Configuration saved as a draft and not yet published (issue #1638), for a client with a sandbox. */
    const val drafts = "drafts"

    /** A new self-serve client's checklist (issue #1639): the steps that make it useful, each checked off as done. */
    const val checklist = "checklist"

    /** Administering one client: its page, its users, its reports. */
    const val administer = "administer"

    /** Administering every client: the clients, the customers, the users. */
    const val oversee = "oversee"

    /** An editor's: the client's forms, and its user directory. */
    const val edit = "edit"

    /** A deployment operator's: the operator pages. */
    const val operate = "operate"
}

/** One link on a card: [label], going to the frontend page [action] names. */
class HomeCardLink(val label: String, val action: UiRoute)

/**
 * One card inside the home cards block (issue #1636), its shape written as parameters as [menuItem]'s is, so a
 * misspelled key cannot compile. **Everything but [id] is optional**, since an overlay names only what it changes.
 * A card's actions are routes: a card offers somewhere to go, never a call.
 */
fun UiBlockItemsBuilder.homeCard(
    id: String,
    title: String? = null,
    /** Markdown, drawn with the home page's link resolution, so a link to a document or an anchor works. */
    text: String? = null,
    /** The body as `home` copy, `namespace.key`, drawn in place of [text] ([HCARD.textCopy]). */
    textCopy: String? = null,
    /** The page the card's primary button opens; [actionLabel] says what it reads. */
    action: UiRoute? = null,
    actionLabel: String? = null,
    /** The button's words while what it opens is offered but not yet on ([HCARD.offerLabel]), for a card whose kind knows. */
    offerLabel: String? = null,
    /** The words of a link to the card in the row under Home's title ([HCARD.jumpLabel]); absent for none. */
    jumpLabel: String? = null,
    /** Whether the card is the page's call to action ([HCARD.actionFirst]): its button first, its link a button. */
    actionFirst: Boolean? = null,
    /** Further links, drawn under the text; replaced whole by an overlay that names them. */
    links: List<HomeCardLink>? = null,
    /** The live body the card draws ([HCARD.kind]). */
    kind: String? = null,
    /** The cfact expression deciding whether this caller is shown the card; absent means always. */
    cfactExpression: String? = null,
    /** Where it sorts. Absent lets a base's cards take their written order, and an overlay must state it. */
    displayOrder: Int? = null,
) {
    item {
        set(HFLD.id, id)
        if (title != null) set(HCARD.title, title)
        if (text != null) set(HCARD.text, text)
        if (textCopy != null) set(HCARD.textCopy, textCopy)
        if (action != null) set(UIB.action, action.toJson())
        if (actionLabel != null) set(HCARD.actionLabel, actionLabel)
        if (offerLabel != null) set(HCARD.offerLabel, offerLabel)
        if (jumpLabel != null) set(HCARD.jumpLabel, jumpLabel)
        if (actionFirst != null) set(HCARD.actionFirst, actionFirst)
        if (links != null) {
            set(HCARD.links, links.map { mapOf(HCARD.linkLabel to it.label, UIB.action to it.action.toJson()) })
        }
        if (kind != null) set(HCARD.kind, kind)
        if (cfactExpression != null) set(UIB.cfactExpression, cfactExpression)
        if (displayOrder != null) set(UIB.displayOrder, displayOrder)
    }
}
