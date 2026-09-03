package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic coverage (issue #536) for the create page's model: a `/gedra/workflow/view` response mapped into
 * a render plan with each trait's schema resolved from the view's own `$defs`, and the save body built from
 * collected values. Maps in, model out -- no React, no DOM, no server.
 */
class WorkflowModelTest {
    // A minimal view: one task, one required `name` trait, resolving against a `$defs` the view carries.
    private fun view(found: Boolean = true): Map<String, Any?> = mapOf(
        WVF.found to found,
        WFD.workflowId to "createForm",
        WVF.showTaskList to false,
        // The caller's delivered cfacts (issue #569): the whole frontend vocabulary, present-mapped.
        WVF.cfacts to mapOf("hasAdminLevel" to true, "hasEnvAuth" to false),
        SCH.dDefs to mapOf(
            "globalconfig.NameData" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf("name" to mapOf(SCH.type to SCT.string)),
            ),
        ),
        WFD.tasks to listOf(
            mapOf(
                WFD.id to "identify",
                WFD.label to "Name it",
                WFD.traits to listOf(
                    mapOf(WFD.traitId to "name", WFD.required to true, WVF.schemaRef to "#/${SCH.dDefs}/globalconfig.NameData"),
                ),
                WFD.saves to listOf(mapOf(WFD.id to "create", WFD.label to "Create form", WFD.kind to "create")),
            ),
        ),
    )

    @Test
    fun foundFalseParsesToNull() {
        assertNull(parseWorkflowView(view(found = false)))
        assertNull(parseWorkflowView(emptyMap()))
    }

    @Test
    fun parsesTasksTraitsSavesAndResolvesTheTraitTypeFromTheViewsOwnDefs() {
        val wf = parseWorkflowView(view())!!
        assertEquals("createForm", wf.workflowId)
        assertTrue(!wf.showTaskList)
        val task = wf.task
        assertEquals("identify", task.id)
        assertEquals("Name it", task.label)
        val trait = task.traits.single()
        assertEquals("name", trait.traitId)
        assertTrue(trait.required)
        // The type resolved out of the view's $defs, with its own property.
        assertEquals(SCT.kObject, trait.type.jsonType)
        assertTrue(trait.type.properties.containsKey("name"))
        val save = task.saves.single()
        assertEquals("create", save.id)
        assertEquals("Create form", save.label)
    }

    @Test
    fun carriesTheDeliveredCfactsForTheVisibleWhenGate() {
        // The map the rendered SchemaForm evaluates each property's g-visibleWhen against (issue #569): present
        // and absent cfacts both, so a gate naming an absent one still parses.
        val wf = parseWorkflowView(view())!!
        assertEquals(true, wf.cfacts["hasAdminLevel"])
        assertEquals(false, wf.cfacts["hasEnvAuth"])
        // A view with no cfacts key parses to an empty map, not a failure.
        val noCfacts = parseWorkflowView(view() - WVF.cfacts)!!
        assertTrue(noCfacts.cfacts.isEmpty())
    }

    @Test
    fun buildsTheSaveEntriesAndBody() {
        val entries = workflowSaveEntries(mapOf("name" to mapOf("name" to "My form")))
        assertEquals(1, entries.size)
        assertEquals("name", entries.single()[GE.traitId])
        assertEquals(mapOf("name" to "My form"), entries.single()[GE.data])

        val body = workflowSaveBody("createForm", "identify", "create", entries)
        assertEquals("createForm", body[GDF.workflowId])
        assertEquals("identify", body[GDF.taskId])
        assertEquals("create", body[GDF.saveId])
        assertEquals(entries, body[GDF.entries])
    }

    @Test
    fun readsTheSaveOutcomeBothWays() {
        val refused = parseSaveOutcome(mapOf(WSF.saved to false, WSF.unmetTraits to listOf("name")))
        assertTrue(!refused.saved)
        assertEquals(listOf("name"), refused.unmetTraits)

        val ok = parseSaveOutcome(mapOf(WSF.saved to true, WSF.item to mapOf(GDF.gedraId to "gd.fd.acme.u1")))
        assertTrue(ok.saved)
        assertEquals("gd.fd.acme.u1", ok.item[GDF.gedraId])
    }
}
