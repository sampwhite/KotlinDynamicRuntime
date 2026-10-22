package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.overlay.MCH
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A workflow made from a template (issue #1120): its overlay expanded over the template, what an overlay may not say,
 * and the diff that turns an edited workflow back into an overlay -- which expands to that workflow again.
 */
class WfTemplateTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private val template: WfDef = parseWfDef(
        cxt,
        WfDefBuilder("kdr:review", WfEntry.normal).apply {
            label = "Review"
            task("record", "Record it") {
                trait("kdr:name")
                trait("kdr:notes", required = false)
                save("saveRecord", "Save", WfSaveKind.edit)
            }
            task("approve", "Approve it") { approval("approved", "Approve this?", "Approve") }
            singleton(WSC.finished, "approved")
        }.build(),
    )

    private fun expand(overlay: Map<String, Any?>) = expandWorkflowTemplate(cxt, template, overlay)

    private val overlay = mapOf(
        WFD.workflowId to "siteReview",
        WFD.fromTemplate to "kdr:review",
        WFD.label to "Site review",
        WFD.tasks to listOf(
            mapOf(WFD.id to "record", WFD.label to "Record the visit", WFD.traits to listOf(mapOf(WFD.traitId to "siteVisit"))),
        ),
    )

    @Test
    fun anOverlayIsTheTemplateWithItsChanges() {
        val made = expand(overlay)
        assertEquals("siteReview", made.workflowId)
        assertEquals("kdr:review", made.fromTemplate)
        assertEquals(overlay, made.templateOverlay)
        assertEquals("Site review", made.label)
        // The task named is changed -- its traits restated -- and the one not named is the template's.
        assertEquals(listOf("Record the visit", "Approve it"), made.tasks.map { it.label })
        assertEquals(listOf("siteVisit"), made.tasks[0].traits.map { it.traitId })
        assertEquals("Save", made.tasks[0].saves.single().label)
        assertEquals("approved", made.tasks[1].approval?.cfact)
        assertEquals(listOf(WSC.finished), made.singletons.map { it.cfact })
    }

    @Test
    fun anOverlayMayMergeATasksTraitsWhenItSaysSo() {
        val merged = overlay + (WFD.tasks to listOf(
            mapOf(
                WFD.id to "record",
                WFD.merge to mapOf(WFD.traits to MCH.merge),
                WFD.traits to listOf(mapOf(WFD.traitId to "kdr:notes", WFD.required to true), mapOf(WFD.traitId to "siteVisit")),
            ),
        ))
        val traits = expand(merged).tasks[0].traits
        assertEquals(listOf("kdr:name", "kdr:notes", "siteVisit"), traits.map { it.traitId })
        assertEquals(listOf(true, true, true), traits.map { it.required })
    }

    @Test
    fun anOverlayMayNotChangeWhatTheTemplateFixes() {
        fun refusal(o: Map<String, Any?>) = assertFailsWith<KdrException> { expand(o) }.message.orEmpty()
        assertTrue(refusal(overlay + (WFD.entry to WfEntry.survey.name)).contains("'entry' is the template's"))
        assertTrue(refusal(overlay + (WFD.singletons to emptyList<Any?>())).contains("'singletons' is the template's"))
        val saveKind = mapOf(WFD.id to "record", WFD.saves to listOf(mapOf(WFD.id to "saveRecord", WFD.kind to "create")))
        assertTrue(refusal(overlay + (WFD.tasks to listOf(saveKind))).contains("save 'saveRecord''s 'kind'"))
        val approval = mapOf(WFD.id to "approve", WFD.approval to mapOf(WFD.cfact to "other"))
        assertTrue(refusal(overlay + (WFD.tasks to listOf(approval))).contains("Task 'approve''s 'approval'"))
        // A task the template does not have cannot be added.
        val added = mapOf(WFD.id to "extra", WFD.label to "Extra")
        assertTrue(refusal(overlay + (WFD.tasks to listOf(added))).contains("has its template's tasks"))
        // A template made from a template is not a template.
        val second = parseWfDef(cxt, template.toJsonMap() + (WFD.fromTemplate to "kdr:other"))
        assertFailsWith<KdrException> { expandWorkflowTemplate(cxt, second, overlay) }
    }

    @Test
    fun anEditedWorkflowIsKeptAsTheOverlayThatMakesIt() {
        val made = expand(overlay)
        // An editor relabels a save and lays a type out for this workflow's pages.
        val layout = mapOf("siteVisit.Data" to mapOf(SCH.layout to mapOf(SL.schemaFields to listOf(mapOf(SL.field to "when", SL.label to "Visited on")))))
        val editedJson = made.toJsonMap().let { json ->
            val tasks = (json[WFD.tasks] as List<*>).map { t ->
                @Suppress("UNCHECKED_CAST")
                val task = t as Map<String, Any?>
                if (task[WFD.id] != "record") task else task + (WFD.saves to listOf(mapOf(WFD.id to "saveRecord", WFD.label to "Keep it", WFD.kind to WfSaveKind.edit.name)))
            }
            json + (WFD.tasks to tasks) + (WFD.types to layout)
        }
        val edited = parseWfDef(cxt, editedJson)
        val kept = workflowTemplateOverlay(template, edited)
        assertEquals(listOf(WFD.workflowId, WFD.fromTemplate), kept.keys.take(2))
        assertEquals(layout, kept[WFD.types])
        // The template's own parts are not restated: the approval task and the singleton are left to it.
        assertEquals(1, (kept[WFD.tasks] as List<*>).size)
        assertTrue(WFD.singletons !in kept)
        // And it makes the edited workflow again.
        assertEquals(edited.toJsonMap(), expand(kept).toJsonMap())
        // Nothing changed is the bare reference.
        val unchanged = parseWfDef(cxt, template.toJsonMap() + mapOf(WFD.workflowId to "siteReview", WFD.fromTemplate to "kdr:review"))
        assertEquals(setOf(WFD.workflowId, WFD.fromTemplate), workflowTemplateOverlay(template, unchanged).keys)
    }

    @Test
    fun anEditTheOverlayCannotStateIsRefused() {
        val made = expand(overlay)
        val json = made.toJsonMap()
        val oneTask = json + (WFD.tasks to (json[WFD.tasks] as List<*>).take(1))
        // Dropping the approval task leaves a valid workflow, which is not one made from this template.
        val error = assertFailsWith<KdrException> { workflowTemplateOverlay(template, parseWfDef(cxt, oneTask - WFD.singletons)) }
        assertTrue(error.message.orEmpty().contains("'approve' is gone"))
    }
}
