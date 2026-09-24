package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.workflow.WAGG
import com.dynamicruntime.common.gedra.workflow.WCOL
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfColumnCategory
import com.dynamicruntime.common.gedra.workflow.WfPhase
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The workflow pages on the page side (issue #792): reading the aggregate, where a count leads, how the forms listing
 * names a drill-down it was opened with -- and that the listing's own flags and filters never become search boxes.
 */
class WorkflowPagesViewTest {
    @Test
    fun theAggregateParses() {
        val parsed = parseWorkflowAggregate(
            listOf(
                mapOf(
                    WCOL.client to "acme", WFD.workflowId to "audit", WFD.label to "Audit review",
                    WCOL.phase to WfPhase.lifetimeOnly.name, WAGG.eligible to 0, WAGG.engaged to 2, WAGG.finished to 5,
                ),
                // No id: not a workflow anyone can open.
                mapOf(WFD.label to "stray"),
            ),
        ).single()
        assertEquals(listOf("acme", "audit", "Audit review"), listOf(parsed.client, parsed.workflowId, parsed.label))
        assertEquals(listOf(0, 2, 5), listOf(parsed.eligible, parsed.engaged, parsed.finished))
        assertEquals("closed", workflowPhaseText(parsed.phase))
        assertEquals("", workflowPhaseText(WfPhase.engageable))
    }

    @Test
    fun aCountLeadsToTheListingDrilledIntoItsWorkflowAndState() {
        assertEquals(
            listOf(HP.page to HMENU.pageForms, WAGG.workflowId to "audit", WAGG.workflowState to "engaged"),
            workflowDrillHash("audit", WfColumnCategory.engaged, client = null),
        )
        // Across clients, the listing's client says whose workflow it is.
        assertEquals("acme", workflowDrillHash("audit", WfColumnCategory.finished, "acme").toMap()[EI.client])
        // The drill-down rides the hash as search parameters, not navigation, so the listing sends it on.
        assertEquals(
            mapOf(WAGG.workflowId to "audit", WAGG.workflowState to "engaged"),
            formsSearchFromHash(workflowDrillHash("audit", WfColumnCategory.engaged, null).toMap()),
        )
    }

    @Test
    fun theListingNamesTheDrillDownItWasOpenedWith() {
        val summary = parseWorkflowSummary(
            mapOf(
                WCOL.workflows to listOf(
                    mapOf(
                        WCOL.client to "acme", WFD.workflowId to "audit", WFD.label to "Audit review",
                        WCOL.phase to WfPhase.engageable.name, WCOL.explanations to emptyMap<String, String>(), WCOL.lastTask to "t",
                    ),
                ),
            ),
        )
        val applied = mapOf(WAGG.workflowId to "audit", WAGG.workflowState to "engaged")
        assertEquals("audit" to WfColumnCategory.engaged, workflowDrillOf(applied))
        // Named with the word the count's heading used, so the click and the listing agree.
        assertEquals("Workflow: Audit review (engaged)", workflowDrillChip(applied, summary))
        // Without a summary naming it, the id stands in; without a state, any.
        assertEquals("Workflow: other", workflowDrillChip(mapOf(WAGG.workflowId to "other"), summary))
        assertNull(workflowDrillChip(emptyMap(), summary))
    }

    @Test
    fun theListingsFlagsAndWorkflowFilterAreNeverSearchBoxes() {
        fun prop() = mapOf(SCH.type to SCT.string, SCH.description to "d")
        val schema = mapOf(
            SCH.properties to mapOf(
                GDF.withWorkflowSummary to mapOf(SCH.type to SCT.boolean),
                WAGG.workflowId to prop(),
                WAGG.workflowState to prop(),
            ),
        )
        assertTrue(searchGroups(schema).isEmpty())
    }
}
