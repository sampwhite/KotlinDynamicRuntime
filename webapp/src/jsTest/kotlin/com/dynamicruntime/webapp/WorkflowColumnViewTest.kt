package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.workflow.WCOL
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WfColumnCategory
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The forms list's workflow column on the page side (issue #791): reading the listing's summary, joining a row's
 * workflows to it by client and id, where each link goes, the counts a crowded cell shows -- and the workflow
 * page's note on why a workflow cannot be worked on.
 */
class WorkflowColumnViewTest {
    private fun summaryEntry(client: String, id: String, phase: WfPhase = WfPhase.engageable) = mapOf(
        WCOL.client to client, WFD.workflowId to id, WFD.label to "Label of $id", WCOL.phase to phase.name,
        WCOL.explanations to mapOf("surveyDone" to "Finish the survey first."), WCOL.lastTask to "last",
    )

    private val summary = parseWorkflowSummary(
        mapOf(
            WCOL.workflows to listOf(
                summaryEntry("acme", "audit"),
                summaryEntry("acme", "followUp"),
                summaryEntry("globex", "audit"),
                // A phase this page does not know is dropped rather than guessed at.
                summaryEntry("acme", "odd") + (WCOL.phase to "someFuturePhase"),
            ),
        ),
    )

    private fun state(id: String, vararg fields: Pair<String, Any?>) =
        mapOf(GE.traitId to WFS.workflowState, GE.data to mapOf(WFD.workflowId to id, *fields))

    private fun engaged(id: String) =
        mapOf(GE.traitId to WFS.workflowEngagement, GE.data to mapOf(WFD.workflowId to id, WFS.engaged to true))

    @Test
    fun theSummaryParsesAndARowJoinsItsOwnClientsEntries() {
        assertEquals(listOf("acme/audit", "acme/followUp", "globex/audit"), summary.map { "${it.client}/${it.workflowId}" })
        val states = listOf(
            state("audit", WFS.eligible to false, WFS.eligibilityFailures to listOf(mapOf(WFD.id to "surveyDone"))),
            state("followUp", WFS.ctaTask to "note"),
            engaged("followUp"),
        )
        val cell = workflowCellOf(states, "acme", summary)
        assertEquals(listOf("followUp" to WfColumnCategory.engaged, "audit" to WfColumnCategory.ineligible), cell.map { it.entry.workflowId to it.workflow.category })
        // The engaged one opens on its current task; the ineligible one explains itself instead.
        assertEquals("note", cell[0].linkTask)
        assertEquals(listOf("Finish the survey first."), cell[1].reasons)
        // Another client's form with the same workflow id joins that client's entry, never acme's.
        assertEquals("Label of audit", workflowCellOf(states, "globex", summary).single().entry.label)
        // A client the summary does not name shows nothing.
        assertTrue(workflowCellOf(states, "initech", summary).isEmpty())
    }

    @Test
    fun aFinishedWorkflowOpensOnItsLastTaskAndAnEligibleOneOnTheWorkflow() {
        val states = listOf(state("audit", WFS.tasksDone to true), engaged("audit"), state("followUp", WFS.eligible to true))
        val cell = workflowCellOf(states, "acme", summary)
        assertEquals(listOf(WfColumnCategory.eligible, WfColumnCategory.finished), cell.map { it.workflow.category })
        assertNull(cell[0].linkTask)
        assertEquals("last", cell[1].linkTask)
    }

    @Test
    fun aCrowdedCellCountsOnlyTheCategoriesHoldingMoreThanOne() {
        val entry = summary.first()
        fun item(category: WfColumnCategory) =
            WorkflowCellItem(entry, com.dynamicruntime.common.gedra.workflow.FormWorkflow("x", category, null, emptyList()))
        val items = listOf(
            item(WfColumnCategory.engaged), item(WfColumnCategory.engaged),
            item(WfColumnCategory.eligible),
            item(WfColumnCategory.finished), item(WfColumnCategory.finished), item(WfColumnCategory.finished),
        )
        assertEquals("2 in progress · 3 finished", workflowCellCounts(items))
        assertEquals("", workflowCellCounts(items.take(1)))
    }

    @Test
    fun theWorkflowPageSaysWhyItsTasksAreReadOnly() {
        fun view(phase: WfPhase?, engaged: Boolean?, entry: WfEntry = WfEntry.normal, eligible: Boolean? = null) = WorkflowView(
            workflowId = "audit", entry = entry.name, showTaskList = true, tasks = emptyList(), cfacts = emptyMap(),
            phase = phase?.name, engaged = engaged, eligible = eligible,
        )
        assertNull(workflowNote(view(null, null, WfEntry.survey)))
        assertNull(workflowNote(view(WfPhase.relevant, true)))
        assertTrue(workflowNote(view(WfPhase.engageable, false, eligible = true)).orEmpty().contains("Engage"))
        assertTrue(workflowNote(view(WfPhase.lifetimeOnly, true)).orEmpty().contains("closed"))
        assertTrue(view(WfPhase.engageable, false, eligible = true).canEngage)
        assertTrue(!view(WfPhase.lifetimeOnly, true).canWork)
        // Not eligible: no Engage that could only be refused, and the note leads into the reasons.
        val ineligible = view(WfPhase.engageable, false, eligible = false)
        assertTrue(!ineligible.canEngage)
        assertTrue(workflowNote(ineligible).orEmpty().contains("cannot be put into"))
    }

    @Test
    fun onlyAnEngagedWorkflowsLinkOpensInEditMode() {
        assertEquals(
            listOf(HP.page to pageSurveyEdit, HP.from to "forms", HP.gedra to "g1", HP.workflow to "audit", HP.task to "record", HP.edit to "1"),
            workflowPageHash("g1", "audit", "record", edit = true),
        )
        // A finished workflow's last task is there to be looked at; an eligible one opens on the workflow.
        assertEquals(null, workflowPageHash("g1", "audit", "last", edit = false).toMap()[HP.edit])
        assertEquals(null, workflowPageHash("g1", "audit", null, edit = true).toMap()[HP.task])
    }

    @Test
    fun theWorkflowKeyIsNavigationNotASearchFilter() {
        assertEquals(mapOf("q" to "x"), formsSearchFromHash(mapOf(HP.page to "surveyEdit", HP.workflow to "audit", "q" to "x")))
    }
}
