package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #161) for a client's workflows made from templates (issue #1120): the catalog read, the
 * slot form's edits and checks, the words for what a template fixes, the preview the kernel makes, and the save.
 */
class ClientWorkflowsTest {
    private val template = mapOf(
        WFD.workflowId to "kdr:review",
        WFD.entry to WfEntry.normal.name,
        WFD.label to "Review",
        WFD.tasks to listOf(
            mapOf(
                WFD.id to "record", WFD.label to "Record it",
                WFD.traits to listOf(mapOf(WFD.traitId to "kdr:name", WFD.required to true)),
                WFD.saves to listOf(mapOf(WFD.id to "saveRecord", WFD.label to "Save", WFD.kind to WfSaveKind.edit.name)),
            ),
            mapOf(
                WFD.id to "approve", WFD.label to "Approve it", WFD.traits to emptyList<Any?>(), WFD.saves to emptyList<Any?>(),
                WFD.approval to mapOf(WFD.cfact to "approved", WFD.prompt to "OK?", WFD.button to "Approve"),
            ),
        ),
    )
    private val made = template + mapOf(WFD.workflowId to "siteReview", WFD.fromTemplate to "kdr:review", WFD.label to "Site review")

    private val catalog = parseWorkflowCatalog(
        mapOf(
            CLD.client to "acme",
            CWF.templates to listOf(mapOf(CWF.definition to template), mapOf(CWF.definition to template + (WFD.workflowId to "kdr:other"), CWF.refusal to "Not now.")),
            CWF.workflows to listOf(mapOf(CWF.definition to made, CWF.configName to "workflow_siteReview")),
            CWF.traits to listOf(mapOf(CCT.traitId to "kdr:name", CWF.description to "A name."), mapOf(CCT.traitId to "siteVisit")),
        ),
    )

    @Test
    fun theCatalogReadsTemplatesWorkflowsAndTraits() {
        assertEquals(listOf("kdr:review", "kdr:other"), catalog.templates.map { it.def.workflowId })
        assertEquals("Not now.", catalog.templates[1].refusal)
        val own = catalog.workflows.single()
        assertEquals("kdr:review", own.def.fromTemplate)
        assertNull(own.notEditable)
        assertEquals("Site review", workflowName(own.def))
        assertEquals("Site review", own.name)
        assertEquals(listOf("Record it", "Approve it"), own.taskLabels)
        assertEquals(listOf("kdr:name — A name." to "kdr:name", "siteVisit" to "siteVisit"), traitChoices(catalog, workflowSlots(own.def).tasks.single()))
    }

    @Test
    fun aWorkflowsLabelsAreShownAsTheClientSeesThem() {
        // Issue #1386: a workflow declared in source pulls its labels from a fragment file; the page shows them resolved.
        val pulled = made + (WFD.label to "%{@t(\"acmeWf.review.label\")}")
        val view = parseWorkflowCatalog(
            mapOf(
                CLD.client to "acme",
                CWF.workflows to listOf(
                    mapOf(CWF.definition to pulled, CWF.labels to mapOf(WFD.label to "Review", WFD.tasks to mapOf("record" to "Record"))),
                ),
            ),
        ).workflows.single()
        assertEquals("Review", view.name)
        // A task the answer does not name keeps its label as written.
        assertEquals(listOf("Record", "Approve it"), view.taskLabels)
    }

    @Test
    fun theTemplatesFixedPartsAreSaidInWords() {
        val said = workflowFixedParts(catalog.templates.first().def)
        assertTrue(said.first().contains("A client may have many"))
        assertTrue(said.contains("Record it: “Save” saves what was filled in."))
        assertTrue(said.contains("Approve it: a reviewer approves it with “Approve”."))
    }

    @Test
    fun onlyTheDataEntryTasksAreSlots() {
        val slots = newWorkflowSlots(catalog.templates.first().def)
        assertNull(slots.workflowId)
        assertEquals("kdr:review", slots.templateId)
        assertEquals(listOf("record"), slots.tasks.map { it.taskId })
    }

    @Test
    fun slotEditsKeepWhatTheyDoNotChange() {
        val slots = workflowSlots(catalog.workflows.single().def)
        val optional = slots.withTraitRequired("record", "kdr:name", false)
        // A trait kept keeps whether it is required; one added is required.
        val traits = optional.withTaskTraits("record", listOf("kdr:name", "siteVisit")).task("record")!!.traits
        assertEquals(listOf(false, true), traits.map { it.required })
        assertFalse(workflowSlotsChanged(slots, slots))
        assertTrue(workflowSlotsChanged(slots.withLabel("Visit review"), slots))
        assertTrue(workflowSlotsChanged(newWorkflowSlots(catalog.templates.first().def), null))
    }

    @Test
    fun slotsAreCheckedInTheKernelsWords() {
        val tmpl = catalog.templates.first().def
        val slots = newWorkflowSlots(tmpl)
        assertEquals(emptyList(), workflowSlotProblems(tmpl, slots))
        assertTrue(workflowSlotProblems(tmpl, slots.withLabel(" ")).single().contains("needs a name"))
        assertTrue(workflowSlotProblems(tmpl, slots.withTaskTraits("record", emptyList())).single().contains("at least one trait"))
    }

    @Test
    fun thePreviewIsTheWorkflowTheSlotsMake() {
        val tmpl = catalog.templates.first().def
        val slots = newWorkflowSlots(tmpl).withLabel("Visit").withTaskLabel("record", "Record the visit").withTaskTraits("record", listOf("siteVisit"))
        val preview = assertNotNull(workflowPreview(tmpl, slots))
        assertEquals("kdr:review", preview.fromTemplate)
        assertEquals("Visit", preview.label)
        assertEquals(listOf("Record the visit", "Approve it"), preview.tasks.map { it.label })
        assertEquals(listOf("siteVisit"), preview.tasks.first().traits.map { it.traitId })
    }

    @Test
    fun theSaveSendsTheSlotsAndReadsWhatItDid() {
        val slots = workflowSlots(catalog.workflows.single().def)
        val request = workflowSaveRequest("acme", slots, EDM.live, acknowledge = true)
        assertEquals(slots.toInfo(), request[CWF.slots])
        assertEquals(true, request[IMP.acknowledgeImpact])
        val result = parseWorkflowSaveResult(mapOf(CWF.configName to "workflow_siteReview", CWF.slots to slots.toInfo(), CPY.mode to EDM.draft))
        assertEquals("siteReview", result.slots.workflowId)
        assertEquals(EDM.draft, result.mode)
        assertNull(result.draftImpact)
    }

    @Test
    fun aWorkflowMadeFromATemplateIsRemovedSayingWhere() {
        val own = catalog.workflows.single()
        assertTrue(workflowRemovable(own))
        assertFalse(workflowRemovable(ClientWorkflowView(catalog.templates.first().def, null, "It is written whole.")))
        assertEquals("Remove the workflow 'Site review' of acme (live data)?", workflowRemoveQuestion(own.def, "acme", draft = false))
        assertEquals("Remove the workflow 'Site review' from the draft of acme?", workflowRemoveQuestion(own.def, "acme:sandbox", draft = true))
        val request = workflowRemoveRequest("acme", "siteReview", EDM.live, acknowledge = false)
        assertEquals("siteReview", request[WFD.workflowId])
        assertNull(request[IMP.acknowledgeImpact])
        val result = parseWorkflowRemoveResult(mapOf(CWF.configName to "workflow_siteReview", WFD.workflowId to "siteReview", CPY.mode to EDM.live))
        assertEquals("workflow_siteReview", result.configName)
        assertEquals("siteReview", result.workflowId)
    }
}
