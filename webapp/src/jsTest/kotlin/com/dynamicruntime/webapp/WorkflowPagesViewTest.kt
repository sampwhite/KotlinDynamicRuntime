package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.workflow.CTAK
import com.dynamicruntime.common.gedra.workflow.CTKF
import com.dynamicruntime.common.gedra.workflow.CTKQ
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
            workflowDrillRoute("audit", WfColumnCategory.engaged, client = null),
        )
        // Across clients, the listing's client says whose workflow it is.
        assertEquals("acme", workflowDrillRoute("audit", WfColumnCategory.finished, "acme").toMap()[EI.client])
        // The drill-down rides the address as search parameters, not navigation, so the listing sends it on.
        assertEquals(
            mapOf(WAGG.workflowId to "audit", WAGG.workflowState to "engaged"),
            formsSearchFromRoute(workflowDrillRoute("audit", WfColumnCategory.engaged, null).toMap()),
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

    @Test
    fun aKeywordCountParsesAndLeadsToTheWorkflowsEngagedFormsItCounts() {
        val entries = parseWorkflowAggregate(
            listOf(
                mapOf(
                    WCOL.client to "acme", WFD.workflowId to "audit", WFD.label to "Audit review",
                    WAGG.keywords to listOf(mapOf(CTKF.keyword to CTAK.review, CTKQ.any to 4, CTKQ.mine to 2), mapOf(CTKQ.any to 9)),
                ),
                mapOf(WCOL.client to "acme", WFD.workflowId to "intake", WFD.label to "Intake"),
            ),
        )
        // Only an entry naming its keyword counts, and a workflow carrying none has no counts at all.
        assertEquals(listOf(Triple(CTAK.review, 4, 2)), entries[0].keywords.map { Triple(it.keyword, it.any, it.mine) })
        assertEquals(emptyList(), entries[1].keywords)
        // The page heads a pair of counts for each keyword some workflow counts.
        assertEquals(listOf(CTAK.review), aggregateKeywords(entries))
        assertEquals(emptyList(), aggregateKeywords(entries.drop(1)))

        // A count leads to the workflow's engaged forms, narrowed to the keyword and whose.
        val route = workflowKeywordDrillRoute("audit", CTAK.review, CTKQ.mine, client = null)
        assertEquals(
            mapOf(WAGG.workflowId to "audit", WAGG.workflowState to "engaged", WAGG.keyword to CTAK.review, WAGG.keywordWhose to CTKQ.mine),
            formsSearchFromRoute(route.toMap()),
        )
        assertEquals("acme", workflowKeywordDrillRoute("audit", CTAK.review, CTKQ.any, "acme").toMap()[EI.client])
        val applied = formsSearchFromRoute(route.toMap())
        assertEquals(CTAK.review to CTKQ.mine, workflowKeywordDrillOf(applied))
        // Unsaid is anyone's, and a keyword with no workflow drilled into is no drill at all.
        assertEquals(CTAK.review to CTKQ.any, workflowKeywordDrillOf(applied - WAGG.keywordWhose))
        assertNull(workflowKeywordDrillOf(applied - WAGG.workflowId))
        assertNull(workflowKeywordDrillOf(applied - WAGG.keyword))
        // The keyword's pair is the listing's own too, kept by Clear.
        assertTrue(WAGG.keyword in formsScopeKeys && WAGG.keywordWhose in formsScopeKeys)

        // Worded as the forms list's keyword filter chip says it, from the client's copy.
        val keywords = listOf(CtaKeywordWords(CTAK.review, "Review", "Needs review", "To review", "Needs review", "Needs Review"))
        val words = WorkflowCellWords(keywordMine = "Mine", keywordAny = "Everyone's")
        assertEquals("Needs review: Mine", keywordCountWords(CTAK.review, CTKQ.mine, keywords, words))
        assertEquals("Needs review: Everyone's", keywordCountWords(CTAK.review, CTKQ.any, keywords, words))
        assertEquals("sign: Mine", keywordCountWords("sign", CTKQ.mine, keywords, words))
        assertEquals(
            "The forms engaged with this workflow, with work under way — Needs review: Mine.",
            workflowDrillNote(WfColumnCategory.engaged, "Needs review: Mine"),
        )
    }

    /**
     * A form page opened from a workflow's listing goes home to it (issue #792): every way back reads the listing off
     * `from`, and a page opened from My forms, or from nowhere the back rules know, still goes to My forms.
     */
    @Test
    fun aFormOpenedFromTheWorkflowsListingReturnsToIt() {
        val fromWorkflow = mapOf(
            HP.page to pageCoreDetailsEdit, HP.from to pageWorkflowForms, HP.gedra to "g1", HP.workflow to "audit",
            WAGG.workflowId to "audit", WAGG.workflowState to "engaged",
        )
        val home = formsListingReturn(fromWorkflow, "g1").toMap()
        assertEquals(pageWorkflowForms, home[HP.page])
        // The workflow and state ride home as search keys, so the listing reopens on the same state.
        assertEquals("audit", home[WAGG.workflowId])
        assertEquals("engaged", home[WAGG.workflowState])
        assertEquals(pageWorkflowForms, formsRawViewRoute(fromWorkflow, "g1").toMap()[HP.page])
        assertEquals(pageWorkflowForms, formsCoreDetailsViewRoute(fromWorkflow, "g1").toMap()[HP.from])
        // A workflow-column link on the workflow's listing says so, so the core details page's back link leads there.
        assertEquals(pageWorkflowForms, workflowPageRoute("g1", "audit", "t", edit = true, from = pageWorkflowForms).toMap()[HP.from])
        assertEquals(HMENU.pageForms, workflowPageRoute("g1", "audit", "t", edit = true).toMap()[HP.from])
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
