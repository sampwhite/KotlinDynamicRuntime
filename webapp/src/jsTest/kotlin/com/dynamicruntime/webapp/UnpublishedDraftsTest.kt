package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Home's "Unpublished drafts" (issue #1638): reading the drafts' answer, when the card shows, and where it links. */
class UnpublishedDraftsTest {
    /** What Home read, with [drafts] alone answered. */
    private fun readsOf(drafts: DraftsRead?) = HomeLiveReads(assistantView = null, waiting = null, waitingClient = null, drafts = drafts)

    @Test
    fun theAnswerIsReadAsTheClientsDrafts() {
        val read = parseDraftsRead(
            mapOf(CFEP.client to "acme", CFEP.count to 2, CFEP.names to listOf("acmeForms", "acmeMenu"), CFEP.inSandbox to true),
        )!!
        assertEquals("acme", read.client)
        assertEquals(2, read.count)
        assertEquals(listOf("acmeForms", "acmeMenu"), read.names)
        assertEquals(true, read.inSandbox)
        // No count: as many as it names. No client: not an answer.
        assertEquals(1, parseDraftsRead(mapOf(CFEP.client to "acme", CFEP.names to listOf("acmeForms")))?.count)
        assertNull(parseDraftsRead(mapOf(CFEP.count to 1)))
    }

    @Test
    fun theCardShowsOnlyOnceADraftIsKnown() {
        val cards = homeCardsFrom(
            listOf(
                mapOf(HFLD.id to HCARD.drafts, HCARD.title to "Unpublished drafts", HCARD.kind to HCARD.kindUnpublishedDrafts),
                mapOf(HFLD.id to HCARD.administer, HCARD.title to "Your client"),
            ),
        )
        assertEquals(listOf(HCARD.administer), visibleHomeCards(cards, readsOf(null)).map { it.id })
        assertEquals(listOf(HCARD.administer), visibleHomeCards(cards, readsOf(DraftsRead("acme", 0, emptyList(), false))).map { it.id })
        assertEquals(
            listOf(HCARD.drafts, HCARD.administer),
            visibleHomeCards(cards, readsOf(DraftsRead("acme", 1, listOf("acmeForms"), false))).map { it.id },
        )
    }

    @Test
    fun itLinksToThePublishingPanelOfThePageThePersonIsOn() {
        val outside = DraftsRead("acme", 1, listOf("acmeForms"), inSandbox = false)
        assertEquals(
            listOf(HP.page to HMENU.pageClients, HP.client to "acme", HP.section to panelPublishing),
            draftsPublishRoute(outside),
        )
        // From inside the sandbox, the sandbox's own page, where the drafts are tried and published.
        assertEquals(sandboxOf("acme"), draftsPublishRoute(DraftsRead("acme", 1, listOf("acmeForms"), inSandbox = true))[1].second)
        assertEquals("1 configuration has unpublished changes:", draftsCountText(1))
        assertEquals("3 configurations have unpublished changes:", draftsCountText(3))
    }
}
