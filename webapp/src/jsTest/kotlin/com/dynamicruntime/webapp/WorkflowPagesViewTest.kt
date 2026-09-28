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
    fun aCountLeadsToTheWorkflowsListingOfItsFormsInThatState() {
        // The workflow's own listing page, not My forms filtered: the workflow and state are the page's identity.
        assertEquals(
            listOf(HP.page to pageWorkflowForms, WAGG.workflowId to "audit", WAGG.workflowState to "engaged"),
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
    fun theWorkflowsListingIsHeadedByTheWorkflowAndSaysWhichFormsTheseAre() {
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
        // Headed with the workflow's label, as the Workflows page showed it; the id stands in where no summary names it.
        assertEquals("Audit review", workflowDrillLabel(applied, summary))
        assertEquals("other", workflowDrillLabel(mapOf(WAGG.workflowId to "other"), summary))
        assertNull(workflowDrillLabel(emptyMap(), summary))
        // Under another client the summary's entry is another client's workflow, so it does not name this one.
        assertEquals("audit", workflowDrillLabel(applied + (EI.client to "globex"), summary))
        // The line under the heading says which forms these are, with the word the count's heading used.
        assertEquals("The forms engaged with this workflow, with work under way.", workflowDrillNote(WfColumnCategory.engaged))
        assertEquals("Every form this workflow applies to.", workflowDrillNote(null))
        // The workflow and state are the listing's own, kept by Clear beside the scope controls.
        assertTrue(formsDrillKeys.all { it in formsScopeKeys } && EI.user in formsScopeKeys && EI.client in formsScopeKeys)
    }

    /**
     * A form page opened from a workflow's listing goes home to it (issue #792): every way back reads the listing off
     * `from`, and a page opened from My forms, or from nowhere the back rules know, still goes to My forms.
     */
    @Test
    fun aFormOpenedFromTheWorkflowsListingReturnsToIt() {
        val fromWorkflow = mapOf(
            HP.page to pageSurveyEdit, HP.from to pageWorkflowForms, HP.gedra to "g1", HP.workflow to "audit",
            WAGG.workflowId to "audit", WAGG.workflowState to "engaged",
        )
        val home = formsListingReturn(fromWorkflow, "g1").toMap()
        assertEquals(pageWorkflowForms, home[HP.page])
        // The workflow and state ride home as search keys, so the listing reopens on the same state.
        assertEquals("audit", home[WAGG.workflowId])
        assertEquals("engaged", home[WAGG.workflowState])
        assertEquals(pageWorkflowForms, formsRawViewHash(fromWorkflow, "g1").toMap()[HP.page])
        assertEquals(pageWorkflowForms, formsSurveyViewHash(fromWorkflow, "g1").toMap()[HP.from])
        // A workflow-column link on the workflow's listing says so, so the survey page's back link leads there.
        assertEquals(pageWorkflowForms, workflowPageHash("g1", "audit", "t", edit = true, from = pageWorkflowForms).toMap()[HP.from])
        assertEquals(HMENU.pageForms, workflowPageHash("g1", "audit", "t", edit = true).toMap()[HP.from])
        // Opened from My forms, or from an unknown page, home is My forms.
        assertEquals(HMENU.pageForms, formsListingOf(mapOf(HP.from to HMENU.pageForms)))
        assertEquals(HMENU.pageForms, formsListingOf(mapOf(HP.from to "users")))
        assertEquals(HMENU.pageForms, formsListingOf(emptyMap()))
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
