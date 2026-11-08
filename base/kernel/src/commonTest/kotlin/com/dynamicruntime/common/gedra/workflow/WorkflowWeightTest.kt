package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.gedra.GE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The classification (issue #1597), on the JVM and under Kotlin/JS alike: each of a form's workflows weighed by what it
 * asks of the viewer, from the form's state and whether the viewer may act on a call to action, and the keyword search
 * read from it.
 */
class WorkflowWeightTest {
    private fun state(id: String, vararg fields: Pair<String, Any?>) =
        mapOf(GE.traitId to WFS.workflowState, GE.data to mapOf(WFD.workflowId to id, *fields))

    private fun engagement(id: String) =
        mapOf(GE.traitId to WFS.workflowEngagement, GE.data to mapOf(WFD.workflowId to id, WFS.engaged to true))

    private fun weights(states: List<Map<String, Any?>>, phases: Map<String, WfPhase>, mayAct: Set<String>) =
        formWorkflowWeights(states, { phases[it] }) { workflow, _ -> workflow.workflowId in mayAct }

    @Test
    fun eachStateHasItsWeightAndTheCellListsThemStrongestFirst() {
        val states = listOf(
            state("closedOut", WFS.eligible to false, WFS.eligibilityFailures to listOf(mapOf(WFD.id to "ready"))),
            state("done", WFS.tasksDone to true),
            state("open", WFS.eligible to true),
            state("theirs", WFS.ctaTask to "approve", WFS.ctaKeyword to CTAK.review),
            state("mine", WFS.ctaTask to "respond"),
            state("frozen", WFS.ctaTask to "respond"),
            state("uncomputed"),
            engagement("done"), engagement("theirs"), engagement("mine"), engagement("frozen"), engagement("uncomputed"),
        )
        val phases = listOf("closedOut", "done", "open", "theirs", "mine", "uncomputed").associateWith { WfPhase.engageable } +
            ("frozen" to WfPhase.lifetimeOnly)
        // The viewer may act on every engaged one; only an engaged workflow with a CTA it can still act in asks it. The
        // eligible one has no Start waiting on them (issue #1662), so it is only theirs to start.
        val all = phases.keys - "open"
        assertEquals(
            listOf(
                "theirs" to WfWeight.yourTurn,
                "mine" to WfWeight.yourTurn,
                // Frozen outside its relevancy window, nobody acts; with no CTA computed, there is nothing to act on yet.
                "frozen" to WfWeight.waiting,
                "uncomputed" to WfWeight.waiting,
                "open" to WfWeight.available,
                "done" to WfWeight.settled,
                "closedOut" to WfWeight.settled,
            ),
            weights(states, phases, all).map { it.workflow.workflowId to it.weight },
        )
        // One the viewer may not act on is someone else's turn, and keeps its keyword.
        val theirs = weights(states, phases, all - "theirs").first { it.workflow.workflowId == "theirs" }
        assertEquals(WfWeight.waiting, theirs.weight)
        assertEquals(CTAK.review, theirs.keyword)
        // An eligible workflow whose Start waits on the viewer (issue #1662) is their turn.
        assertEquals(WfWeight.yourTurn, weights(states, phases, all + "open").first { it.workflow.workflowId == "open" }.weight)
        // One that highlights its Start, which waits on someone else, is waiting (issue #1690); unhighlighted, available.
        fun open(highlights: Boolean) =
            formWorkflowWeights(states, { phases[it] }, { highlights && it == "open" }) { _, _ -> false }
                .first { it.workflow.workflowId == "open" }.weight
        assertEquals(WfWeight.waiting, open(highlights = true))
        assertEquals(WfWeight.available, open(highlights = false))
    }

    @Test
    fun twoReviewCallsToActionWhereTheViewerPassesOneAreTheirTurnForThatOneAlone() {
        val states = listOf(
            state("regional", WFS.ctaTask to "approve", WFS.ctaKeyword to CTAK.review, WFS.ctaFilterRule to "reviewerNorthwest"),
            state("legal", WFS.ctaTask to "approve", WFS.ctaKeyword to CTAK.review, WFS.ctaFilterRule to "reviewerLegal"),
            engagement("regional"), engagement("legal"),
        )
        val phases = mapOf("regional" to WfPhase.engageable, "legal" to WfPhase.engageable)
        // The viewer passes the regional filter only, as the backend's mayAct would say from their facts.
        val weights = formWorkflowWeights(states, { phases[it] }) { _, state -> state[WFS.ctaFilterRule] == "reviewerNorthwest" }
        assertEquals(
            listOf("regional" to WfWeight.yourTurn, "legal" to WfWeight.waiting),
            weights.map { it.workflow.workflowId to it.weight },
        )
        // So the row is the viewer's to review, and is found whoever may act.
        assertTrue(matchesKeywordSearch(weights, CTAK.review, CTKQ.mine))
        assertTrue(matchesKeywordSearch(weights, CTAK.review, CTKQ.any))
        // Neither filter passed: not theirs, though still awaiting review.
        val neither = formWorkflowWeights(states, { phases[it] }) { _, _ -> false }
        assertFalse(matchesKeywordSearch(neither, CTAK.review, CTKQ.mine))
        assertTrue(matchesKeywordSearch(neither, CTAK.review, CTKQ.any))
        // Asked of one workflow (issue #1600), as the workflow pages count: the regional review is theirs, the legal one
        // is not, though the row is theirs to review.
        assertTrue(matchesWorkflowKeyword(weights, "regional", CTAK.review, CTKQ.mine))
        assertFalse(matchesWorkflowKeyword(weights, "legal", CTAK.review, CTKQ.mine))
        assertTrue(matchesWorkflowKeyword(weights, "legal", CTAK.review, CTKQ.any))
        assertFalse(matchesWorkflowKeyword(weights, "other", CTAK.review, CTKQ.any))
    }

    @Test
    fun aKeywordIsTheEngagedCallToActionsOnlyAndMayActIsNotAskedOfAFrozenWorkflow() {
        val asked = mutableListOf<String>()
        val states = listOf(
            // A keyword lingering on a workflow no longer engaged is not its call to action's.
            state("lingering", WFS.eligible to true, WFS.ctaKeyword to CTAK.review),
            state("frozen", WFS.ctaTask to "approve", WFS.ctaKeyword to CTAK.review),
            engagement("frozen"),
        )
        val weights = formWorkflowWeights(states, { if (it == "frozen") WfPhase.lifetimeOnly else WfPhase.engageable }) { workflow, _ ->
            asked.add(workflow.workflowId)
            false
        }
        assertEquals(
            listOf("frozen" to WfWeight.waiting, "lingering" to WfWeight.available),
            weights.map { it.workflow.workflowId to it.weight },
        )
        assertEquals(listOf(CTAK.review, null), weights.map { it.keyword })
        // A frozen workflow is never asked; an eligible one is, for its Start (issue #1662).
        assertEquals(listOf("lingering"), asked)
        // A frozen review is still awaiting review, but is nobody's turn.
        assertTrue(matchesKeywordSearch(weights, CTAK.review, CTKQ.any))
        assertFalse(matchesKeywordSearch(weights, CTAK.review, CTKQ.mine))
        assertFalse(matchesKeywordSearch(weights, CTAK.review, "everyone"))
    }
}
