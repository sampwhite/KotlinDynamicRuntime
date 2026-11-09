package com.dynamicruntime.webapp

import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.uiblock.UIB
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The home cards' pure half (issue #1636): reading them off the shell config, and the rules their drawing follows. */
class HomeCardsTest {
    private val administer = mapOf(
        HFLD.id to HCARD.administer,
        HCARD.title to "Your client",
        HCARD.text to "Set up your client.",
        HCARD.links to listOf(
            mapOf(HCARD.linkLabel to "My client", UIB.action to HMENU.pageClients),
            // A call is no place a card goes: dropped.
            mapOf(HCARD.linkLabel to "Log out", UIB.action to listOf("logout")),
        ),
    )

    @Test
    fun cardsAreReadInOrderAndKeepOnlyWhatACardCanDo() {
        val cards = homeCardsFrom(
            listOf(
                mapOf(
                    HFLD.id to ASST.homeCard, HCARD.title to "Workflow assistant", HCARD.text to "Describe it.",
                    UIB.action to ASST.page, HCARD.actionLabel to "Open the workflow assistant", HCARD.kind to ASST.cardKind,
                ),
                administer,
                // No id: nothing an overlay could name, so not a card.
                mapOf(HCARD.title to "Nameless"),
            ),
        )
        assertEquals(listOf(ASST.homeCard, HCARD.administer), cards.map { it.id })
        val assistant = cards[0]
        assertEquals(ASST.page, assistant.actionPage)
        assertEquals("Open the workflow assistant", assistant.actionLabel)
        assertEquals(ASST.cardKind, assistant.kind)
        val admin = cards[1]
        assertNull(admin.actionPage)
        assertNull(admin.kind)
        assertEquals(listOf("My client" to HMENU.pageClients), admin.links.map { it.label to it.page })
    }

    @Test
    fun theShellConfigCarriesTheCardsAndAnOlderNodeNone() {
        fun configWith(state: Map<String, Any?>) = homeConfigFrom(UiConfig(FragmentRef("frag", "b1"), emptyMap(), emptyMap(), state))
        assertEquals(listOf(HCARD.administer), configWith(mapOf(HCARD.cards to listOf(administer))).cards.map { it.id })
        assertEquals(emptyList(), configWith(emptyMap()).cards)
    }

    @Test
    fun theAssistantsButtonSaysTheCardsOfferOnlyWhileThePersonMaySwitchItOn() {
        val card = homeCardsFrom(
            listOf(
                mapOf(
                    HFLD.id to ASST.homeCard, UIB.action to ASST.page, HCARD.actionLabel to "Open it",
                    HCARD.offerLabel to "Switch it on", HCARD.kind to ASST.cardKind,
                ),
            ),
        ).single()
        assertEquals("Switch it on", assistantCardActionLabel(card, AssistantView.switchOn))
        for (view in AssistantView.entries.filter { it != AssistantView.switchOn } + null) {
            assertEquals("Open it", assistantCardActionLabel(card, view), "$view keeps the card's own words")
        }
        // A card an overlay left without an offer keeps its own words throughout.
        val plain = homeCardsFrom(listOf(mapOf(HFLD.id to ASST.homeCard, UIB.action to ASST.page, HCARD.actionLabel to "Open it"))).single()
        assertEquals("Open it", assistantCardActionLabel(plain, AssistantView.switchOn))
    }

    @Test
    fun aLinkWithNoWordsIsLeftOffRatherThanShowingAPageId() {
        val card = homeCardsFrom(
            listOf(
                mapOf(
                    HFLD.id to "c",
                    HCARD.links to listOf(
                        mapOf(UIB.action to HMENU.pageBootChecks),
                        mapOf(HCARD.linkLabel to " ", UIB.action to HMENU.pageEnv),
                        mapOf(HCARD.linkLabel to "Users", UIB.action to HMENU.pageUsers),
                    ),
                ),
            ),
        ).single()
        assertEquals(listOf("Users"), card.links.map { it.label })
    }

    @Test
    fun aClientMadeFromPublicComesWithTheAssistantWhereItsCardIsSent() {
        val create = homeCardsFrom(listOf(mapOf(HFLD.id to ASST.createHomeCard, HCARD.title to "Design your own forms")))
        assertTrue(ownClientAssistantOffered(create))
        assertFalse(ownClientAssistantOffered(homeCardsFrom(listOf(administer))))
        assertFalse(ownClientAssistantOffered(emptyList()))
    }

    @Test
    fun aCardsElementIdIsUnderHome() {
        assertEquals("home.card.administer", homeCardElementId(HCARD.administer))
    }

    @Test
    fun aCardNamedInTheRowUnderTheTitleSaysWhatItsButtonSays() {
        val cards = homeCardsFrom(
            listOf(
                mapOf(
                    HFLD.id to ASST.homeCard, UIB.action to ASST.page, HCARD.actionLabel to "Open it",
                    HCARD.offerLabel to "Switch on the assistant", HCARD.jumpLabel to "Workflow assistant",
                    HCARD.kind to ASST.cardKind,
                ),
                // Not named in the row: no jump label.
                administer,
            ),
        )
        val assistantId = homeCardElementId(ASST.homeCard)
        assertEquals(listOf("Workflow assistant" to assistantId), homeCardJumpLinks(cards, null))
        assertEquals(listOf("Workflow assistant" to assistantId), homeCardJumpLinks(cards, AssistantView.on))
        // Off, and the person may switch it on: the link says so, as the button does.
        assertEquals(listOf("Switch on the assistant" to assistantId), homeCardJumpLinks(cards, AssistantView.switchOn))
    }

    @Test
    fun theCallToActionLeadsTheRowAsAButtonAndNotAsALink() {
        // Issue #1698: the Register card is the page's call to action, so the row draws a button doing what its button
        // does -- opening Register -- not a link to the card, which on a window showing the whole page would do nothing.
        val cards = homeCardsFrom(
            listOf(
                mapOf(
                    HFLD.id to HCARD.register, UIB.action to HMENU.pageRegister, HCARD.actionLabel to "Register",
                    HCARD.jumpLabel to "Register", HCARD.actionFirst to true,
                ),
                mapOf(HFLD.id to ASST.homeCard, HCARD.jumpLabel to "Workflow assistant", HCARD.kind to ASST.cardKind),
                administer,
            ),
        )
        assertEquals(listOf(true, false, false), cards.map { it.actionFirst })
        assertEquals(listOf("Register" to HMENU.pageRegister), homeCardJumpButtons(cards))
        // A call to action with nowhere to go keeps its link: a button must do something.
        val noAction = homeCardsFrom(listOf(mapOf(HFLD.id to "nowhere", HCARD.jumpLabel to "Nowhere", HCARD.actionFirst to true)))
        assertEquals(emptyList(), homeCardJumpButtons(noAction))
        assertEquals(listOf("Nowhere" to homeCardElementId("nowhere")), homeCardJumpLinks(noAction, null))
        assertEquals(listOf("Workflow assistant" to homeCardElementId(ASST.homeCard)), homeCardJumpLinks(cards, null))
        // And the copy's own link to the card is dropped for the button, its card being left out of what is drawn.
        val drawn = cards.filterNot { it.leadsJumpRow }.map { homeCardElementId(it.id) }.toSet()
        assertEquals(
            "- [Start here](/docs/start-here.md)",
            jumpLinksFor("- [Register](#home.card.register)\n- [Start here](/docs/start-here.md)", drawn),
        )
    }

    @Test
    fun aCardMayAskToComeBeforeTheIntroduction() {
        // Issue #1723: a new client's Getting started list, which a long introduction would put below a phone's first screen.
        val cards = homeCardsFrom(
            listOf(
                mapOf(HFLD.id to HCARD.checklist, HCARD.kind to HCARD.kindNewClientChecklist, HCARD.aboveIntro to true),
                administer,
            ),
        )
        assertEquals(listOf(true, false), cards.map { it.aboveIntro })
        val (above, below) = cards.partition { it.aboveIntro }
        assertEquals(listOf(HCARD.checklist) to listOf(HCARD.administer), above.map { it.id } to below.map { it.id })
    }

    @Test
    fun cardLinksJoinTheCopysRowInItsOwnStyle() {
        val links = listOf("Workflow assistant" to "home.card.assistant")
        // After a list, as list items -- the shipped copy, and the reference flavor's "Start here" before it.
        assertEquals(
            "- [Start here](/docs/start-here.md)\n- [Workflow assistant](#home.card.assistant)",
            withCardJumpLinks("- [Start here](/docs/start-here.md)\n", links),
        )
        // After a row written on one line, separated as it is.
        assertEquals(
            "[Start here](/docs/start-here.md) · [Workflow assistant](#home.card.assistant)",
            withCardJumpLinks("[Start here](/docs/start-here.md)", links),
        )
        // With no copy, the links alone; with no links, the copy as it was.
        assertEquals("- [Workflow assistant](#home.card.assistant)", withCardJumpLinks(null, links))
        assertEquals("- [A](#x)", withCardJumpLinks("- [A](#x)", emptyList()))
        // A label's brackets cannot break the link.
        assertEquals("- [Odd \\[1\\]](#home.card.x)", withCardJumpLinks(null, listOf("Odd [1]" to "home.card.x")))
        // And the filter keeps the card's link where the card is drawn, dropping only sections that are not.
        val shipped = "- [Try a demo](#home.demos)\n- [Your own client](#home.own-client)"
        assertEquals(
            "- [Workflow assistant](#home.card.assistant)",
            jumpLinksFor(withCardJumpLinks(shipped, links), setOf("home.card.assistant")),
        )
        // A card the copy already links to is not added again (issue #1652), so the copy can put it first.
        val registerFirst = "- [Register](#home.card.register)\n- [Start here](/docs/start-here.md)"
        assertEquals(registerFirst, withCardJumpLinks(registerFirst, listOf("Register" to "home.card.register")))
    }

    @Test
    fun aCardNamingCopyDrawsTheCopyInPlaceOfItsText() {
        val (ns, key) = HCARD.registerCopy.split('.')
        val copy = Copy(mapOf(ns to mapOf(key to "Register *here*.")))
        val raw = mapOf(HFLD.id to HCARD.register, HCARD.text to "Literal.", HCARD.textCopy to HCARD.registerCopy)
        val card = homeCardsFrom(listOf(raw)).single()
        assertEquals("Register *here*.", homeCardBody(card, copy))
        // Before the copy arrives, or where a deployment emptied it, no body -- never the literal words in its place.
        assertEquals("", homeCardBody(card, Copy.empty))
        assertEquals("", homeCardBody(card, Copy(mapOf(ns to mapOf(key to "")))))
        // A card naming no copy draws its own words.
        assertEquals("Literal.", homeCardBody(homeCardsFrom(listOf(raw - HCARD.textCopy)).single(), copy))
    }
}
