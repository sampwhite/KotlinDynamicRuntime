package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WfVisibility
import com.dynamicruntime.common.gedra.workflow.WfVisibilityMode
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WfTaskSlot
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
import com.dynamicruntime.common.gedra.workflow.WfTraitRef
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
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
            CWF.workflows to listOf(
                mapOf(
                    CWF.definition to made, CWF.configName to "workflow_siteReview",
                    CWF.params to mapOf(WFP.current to mapOf(WFP.params to mapOf(WFP.visibility to mapOf(WFP.mode to "restricted", WFP.labels to listOf("pilot"))))),
                ),
            ),
            CWF.traits to listOf(mapOf(CCT.traitId to "kdr:name", CWF.name to "Name", CWF.description to "A name."), mapOf(CCT.traitId to "siteVisit")),
            CWF.userLabels to listOf("pilot", "east"),
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
        // Each by its name (issue #1438), or its id when the catalog gives none or does not list it.
        assertEquals(listOf("Name — A name." to "kdr:name", "siteVisit" to "siteVisit"), traitChoices(catalog, workflowSlots(own.def).tasks.single()))
        assertEquals("Name", catalogTraitName(catalog, "kdr:name"))
        assertEquals("gone", catalogTraitName(catalog, "gone"))
        // Collects says a step takes several (issue #1891) -- and, with nothing else offered, where another comes from.
        val record = workflowSlots(own.def).tasks.single()
        assertTrue(collectsHint(catalog, record).endsWith("pick another to add it beside these."))
        val full = WfTaskSlot(record.taskId, record.label, record.traits + WfTraitRef("siteVisit", required = false))
        assertTrue(collectsHint(catalog, full).endsWith("include one under Traits above to collect it here."))
        // "Add a trait" offers what the step does not collect yet, and the list shows each with its description.
        assertEquals(listOf("siteVisit" to "siteVisit"), traitsToAdd(catalog, record))
        assertEquals(emptyList(), traitsToAdd(catalog, full))
        assertEquals("A name.", catalogTraitDescription(catalog, "kdr:name"))
        assertNull(catalogTraitDescription(catalog, "siteVisit"))
        // The templates, closed, say how many there are (issue #1891).
        assertEquals("Make a workflow from a template · 1 template", templatesSummary(1, 1))
        assertEquals("Make a workflow from a template · 3 templates, 1 usable now", templatesSummary(3, 1))
        assertEquals("Make a workflow from a template · 2 templates, none usable now", templatesSummary(2, 0))
        // Who sees it (issue #1384), and the labels the client suggests for it.
        assertEquals(WfVisibility(WfVisibilityMode.restricted, listOf("pilot")), own.visibility)
        assertEquals(listOf("pilot", "east"), catalog.userLabels)
    }

    @Test
    fun collectsKeepsAKeyboardUsersPlace() {
        // Issue #1891: after a move, the same arrow on the moved row -- or the other once it reaches an end.
        val order = listOf("a", "b", "c")
        assertEquals(CollectsFocus("a", COLF.down), collectsFocusAfterMove(order, "a", 1))
        assertEquals(CollectsFocus("b", COLF.up), collectsFocusAfterMove(order, "b", 1))
        assertEquals(CollectsFocus("c", COLF.up), collectsFocusAfterMove(order, "c", -1))
        assertEquals(CollectsFocus("b", COLF.down), collectsFocusAfterMove(order, "b", -1))
        // After a removal, the row that takes its place, else the one above, else the picker.
        assertEquals(CollectsFocus("b", COLF.remove), collectsFocusAfterRemove(order, "a"))
        assertEquals(CollectsFocus("b", COLF.remove), collectsFocusAfterRemove(order, "c"))
        assertEquals(CollectsFocus(null, COLF.add), collectsFocusAfterRemove(listOf("a"), "a"))
        assertEquals("collects-record-kdr:name-up", collectsControlId("record", CollectsFocus("kdr:name", COLF.up)))
        assertEquals("collects-record-add", collectsControlId("record", CollectsFocus(null, COLF.add)))
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
    fun anApprovalsRowSaysWhatItShows() {
        // Issue #1875: the Steps column names what an approval shows, when it names it; the default it leaves unsaid.
        fun row(approval: Map<String, Any?>): List<String> {
            val tasks = made[WFD.tasks].toJsonListOfMaps().map { t ->
                if (t[WFD.id] == "approve") t + (WFD.approval to t[WFD.approval].toJsonMapOrEmpty() + approval) else t
            }
            val catalog = mapOf(CLD.client to "acme", CWF.workflows to listOf(mapOf(CWF.definition to made + (WFD.tasks to tasks))))
            val w = parseWorkflowCatalog(catalog)
                .workflows.single()
            return workflowTaskSummary(w, mapOf("kdr:name" to "Name"))
        }
        assertEquals(listOf("Record it", "Approve it"), row(emptyMap()))
        val named = row(mapOf(WFD.showTraits to listOf("kdr:name", "siteVisit")))
        assertEquals(listOf("Record it", "Approve it (shows Name, siteVisit)"), named)
        assertEquals(listOf("Record it", "Approve it (shows Record it)"), row(mapOf(WFD.showTasks to listOf("record"))))
        assertEquals(listOf("Record it", "Approve it (shows nothing)"), row(mapOf(WFD.showTraits to emptyList<String>())))
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
        // Moved within the step's order (issue #1891), and left as it is past either end.
        val two = optional.withTaskTraits("record", listOf("kdr:name", "siteVisit"))
        fun order(moved: WfTemplateSlots) = moved.task("record")!!.traits.map { it.traitId }
        assertEquals(listOf("siteVisit", "kdr:name"), order(two.withTraitMoved("record", "siteVisit", -1)))
        assertEquals(listOf("siteVisit", "kdr:name"), order(two.withTraitMoved("record", "kdr:name", 1)))
        assertEquals(listOf("kdr:name", "siteVisit"), order(two.withTraitMoved("record", "kdr:name", -1)))
        // Its required flag moves with it.
        assertFalse(two.withTraitMoved("record", "kdr:name", 1).task("record")!!.traits[1].required)
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
