package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.workflow.SWF
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Home's "Waiting on you" (issue #1637): reading the listing's answer, when the card shows, and its filter's chip. */
class WaitingOnYouTest {
    /** What Home read, with [waiting] alone answered. */
    private fun readsOf(waiting: WaitingRead?) = HomeLiveReads(
        assistantView = null, waiting = waiting, waitingClient = null, drafts = null,
        checklist = emptyList(), checklistHidden = false, hideChecklist = {},
    )

    private fun step(task: String, text: String) =
        mapOf(WFD.workflowId to "siteReview", WFD.label to "Site review", WFS.ctaTask to task, SWF.actionText to text)

    @Test
    fun theListingsAnswerIsReadAsFormsAndTheirSteps() {
        val read = parseWaitingRead(
            mapOf(
                EP.numAvailable to 7,
                EP.items to listOf(
                    mapOf(GDF.gedraId to "fd:acme:1", GDF.waitingSteps to listOf(step("approve", "Approve"))),
                    // A row with no step (an older node) is not one.
                    mapOf(GDF.gedraId to "fd:acme:2"),
                ),
            ),
            DisplayZone.scriptEnv(),
        )
        assertEquals(7, read.total)
        val form = read.forms.single()
        assertEquals("fd:acme:1", form.gedraId)
        // No display column names it.
        assertEquals("A form", form.heading)
        assertEquals(listOf("approve" to "Approve"), form.steps.map { it.taskId to it.actionText })
    }

    @Test
    fun theCardShowsOnlyOnceSomethingIsKnownToWait() {
        val cards = homeCardsFrom(
            listOf(
                mapOf(HFLD.id to HCARD.waiting, HCARD.title to "Waiting on you", HCARD.kind to HCARD.kindWaitingOnYou),
                mapOf(HFLD.id to HCARD.administer, HCARD.title to "Your client"),
            ),
        )
        assertEquals(listOf(HCARD.administer), visibleHomeCards(cards, readsOf(null)).map { it.id })
        assertEquals(listOf(HCARD.administer), visibleHomeCards(cards, readsOf(WaitingRead(0, emptyList()))).map { it.id })
        assertEquals(
            listOf(HCARD.waiting, HCARD.administer),
            visibleHomeCards(cards, readsOf(WaitingRead(1, listOf(WaitingForm("g", "A form", emptyList()))))).map { it.id },
        )
    }

    @Test
    fun theFormsListingSaysWhenItIsNarrowedToWhatWaits() {
        // The toolbar's toggle says it is on, so the filter takes no chip; its count is Home's card's, whatever is applied.
        waitingToggle(mapOf(GDF.needsMe to "true", "track" to "kotlin"), 3, "Waiting on you").let {
            assertEquals("Waiting on you (3)", it.label)
            assertTrue(it.pressed && it.enabled)
        }
        waitingToggle(emptyMap(), 3, "Waiting on you").let { assertFalse(it.pressed); assertTrue(it.enabled) }
        // At zero it stays, dimmed, unless it is on and must be turned off; and while the count is unknown it has none.
        assertFalse(waitingToggle(emptyMap(), 0, "Waiting on you").enabled)
        assertTrue(waitingToggle(mapOf(GDF.needsMe to "true"), 0, "Waiting on you").enabled)
        assertEquals("Mine to do", waitingToggle(emptyMap(), null, "Mine to do").label)
        // One click flips it, keeping every other filter.
        assertEquals(mapOf("track" to "kotlin", GDF.needsMe to "true"), withWaitingToggled(mapOf("track" to "kotlin")))
        assertEquals(mapOf("track" to "kotlin"), withWaitingToggled(mapOf("track" to "kotlin", GDF.needsMe to "true")))
        assertEquals(listOf(HP.page to HMENU.pageForms, GDF.needsMe to "true"), waitingListingRoute())
        // Confined to a client, for a caller who sees across clients, as the card's read was.
        assertEquals(
            listOf(HP.page to HMENU.pageForms, GDF.needsMe to "true", EI.client to "acme"),
            waitingListingRoute("acme"),
        )
    }
}
