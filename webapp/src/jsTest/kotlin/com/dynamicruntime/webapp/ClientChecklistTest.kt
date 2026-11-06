package com.dynamicruntime.webapp

import com.dynamicruntime.common.assistant.ASST
import com.dynamicruntime.common.gedra.CHKL
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HMENU
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Home's new client checklist (issue #1639): reading the steps, the assistant's step beside them, and when the card shows. */
class ClientChecklistTest {
    private val read = ChecklistRead(
        "free_acme", listOf(CHKL.newForm to true, CHKL.workflow to false, CHKL.invite to false, CHKL.firstForm to false),
    )

    private fun readsOf(steps: List<ChecklistStep>, hidden: Boolean = false) = HomeLiveReads(
        assistantView = null, waiting = null, waitingClient = null, drafts = null,
        checklist = steps, checklistHidden = hidden, hideChecklist = {},
    )

    @Test
    fun theAnswerIsReadAsStepsInOrder() {
        val parsed = parseChecklistRead(
            mapOf(
                CLD.client to "free_acme",
                CHKL.steps to listOf(
                    mapOf(CHKL.id to CHKL.newForm, CHKL.done to true),
                    mapOf(CHKL.id to CHKL.invite, CHKL.done to false),
                    // No id: not a step.
                    mapOf(CHKL.done to true),
                ),
            ),
        )!!
        assertEquals("free_acme", parsed.client)
        assertEquals(listOf(CHKL.newForm to true, CHKL.invite to false), parsed.steps)
        assertNull(parseChecklistRead(mapOf(CHKL.steps to emptyList<Any>())))
    }

    @Test
    fun theAssistantsStepLeadsWhereTheMenuOffersItAndThePersonMaySwitchItOn() {
        // Offered and off: a step to take, first, going to the assistant's page.
        val off = checklistSteps(read, assistantOffered = true, assistantView = AssistantView.switchOn)
        assertEquals(listOf(CHKL.assistant, CHKL.newForm, CHKL.workflow, CHKL.invite, CHKL.firstForm), off.map { it.id })
        assertEquals(false, off.first().done)
        assertEquals(listOf(HP.page to ASST.page), off.first().route)
        // On: done.
        assertEquals(true, checklistSteps(read, assistantOffered = true, assistantView = AssistantView.on).first().done)
        // Not offered, not yet read, or only an operator's to switch on: no step for it.
        for ((offered, view) in listOf(false to AssistantView.switchOn, true to null, true to AssistantView.off)) {
            assertEquals(CHKL.newForm, checklistSteps(read, offered, view).first().id)
        }
    }

    @Test
    fun eachStepGoesWhereItIsDoneAndAnUnknownOneIsLeftOut() {
        val steps = checklistSteps(ChecklistRead("free_acme", read.steps + ("somethingNew" to false)), false, null)
        assertEquals(listOf(CHKL.newForm, CHKL.workflow, CHKL.invite, CHKL.firstForm), steps.map { it.id })
        val workflows = listOf(HP.page to HMENU.pageClients, HP.client to "free_acme", HP.section to panelFormsData)
        assertEquals(workflows, steps[0].route)
        assertEquals(workflows, steps[1].route)
        assertEquals(listOf(HP.page to HMENU.pageUsers), steps[2].route)
        assertEquals(listOf(HP.page to HMENU.pageNewForm), steps[3].route)
    }

    @Test
    fun theCardShowsWhileAStepIsLeftAndUntilItIsHidden() {
        val cards = homeCardsFrom(
            listOf(
                mapOf(HFLD.id to HCARD.checklist, HCARD.title to "Set up your client", HCARD.kind to HCARD.kindNewClientChecklist),
                mapOf(HFLD.id to HCARD.administer, HCARD.title to "Your client"),
            ),
        )
        val left = checklistSteps(read, false, null)
        val allDone = checklistSteps(ChecklistRead("free_acme", read.steps.map { it.first to true }), false, null)
        assertEquals(listOf(HCARD.checklist, HCARD.administer), visibleHomeCards(cards, readsOf(left)).map { it.id })
        assertEquals(listOf(HCARD.administer), visibleHomeCards(cards, readsOf(allDone)).map { it.id })
        assertEquals(listOf(HCARD.administer), visibleHomeCards(cards, readsOf(left, hidden = true)).map { it.id })
        // Before its read answers.
        assertEquals(listOf(HCARD.administer), visibleHomeCards(cards, readsOf(emptyList())).map { it.id })
    }
}
