package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.gedra.GE
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A form's workflow cell (issue #791), on the JVM and under Kotlin/JS alike: which workflows it shows, in which
 * category, and in what order -- from the form's state entries and the phases the summary recorded.
 */
class WorkflowColumnTest {
    private fun state(id: String, vararg fields: Pair<String, Any?>) =
        mapOf(GE.traitId to WFS.workflowState, GE.data to mapOf(WFD.workflowId to id, *fields))

    private fun engagement(id: String, engaged: Boolean = true) =
        mapOf(GE.traitId to WFS.workflowEngagement, GE.data to mapOf(WFD.workflowId to id, WFS.engaged to engaged))

    private val failing = listOf(mapOf(WFD.id to "surveyDone"))

    private fun cell(states: List<Map<String, Any?>>, phases: Map<String, WfPhase>) =
        formWorkflowsOf(states) { phases[it] }.map { it.workflowId to it.category }

    @Test
    fun eachWorkflowFallsInOneCategoryAndTheCellListsThemInOrder() {
        val states = listOf(
            state("closedOut", WFS.eligible to false, WFS.eligibilityFailures to failing),
            state("done", WFS.tasksDone to true),
            state("open", WFS.eligible to true),
            state("inProgress", WFS.ctaTask to "record"),
            state("approved", WFS.singletonCfacts to listOf(WSC.finished), WFS.ctaTask to "later"),
            engagement("done"), engagement("inProgress"), engagement("approved"),
        )
        val all = listOf("closedOut", "done", "open", "inProgress", "approved").associateWith { WfPhase.engageable }
        assertEquals(
            listOf(
                "inProgress" to WfColumnCategory.engaged,
                "open" to WfColumnCategory.eligible,
                // Finished either way: every task done, or the finished cfact emitted.
                "done" to WfColumnCategory.finished,
                "approved" to WfColumnCategory.finished,
                "closedOut" to WfColumnCategory.ineligible,
            ),
            cell(states, all),
        )
        val inProgress = formWorkflowsOf(states) { all[it] }.first()
        assertEquals("record", inProgress.ctaTask)
        assertEquals(listOf("surveyDone"), formWorkflowsOf(states) { all[it] }.last().failureIds)
    }

    @Test
    fun whatTheSummaryDoesNotNameOrTheWindowsHideIsLeftOut() {
        val states = listOf(
            state("open", WFS.eligible to true),
            state("frozen", WFS.ctaTask to "record"),
            state("pastEngagement", WFS.eligible to true),
            state("unknown", WFS.eligible to true),
            engagement("frozen"),
            // Disengaged: the trail stays, but it is not engaged.
            engagement("pastEngagement", engaged = false),
        )
        val phases = mapOf(
            "open" to WfPhase.engageable,
            "frozen" to WfPhase.lifetimeOnly,
            "pastEngagement" to WfPhase.relevant,
        )
        assertEquals(
            listOf("frozen" to WfColumnCategory.engaged, "open" to WfColumnCategory.eligible),
            cell(states, phases),
        )
    }
}
