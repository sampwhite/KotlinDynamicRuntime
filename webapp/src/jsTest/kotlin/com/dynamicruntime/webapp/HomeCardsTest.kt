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
}
