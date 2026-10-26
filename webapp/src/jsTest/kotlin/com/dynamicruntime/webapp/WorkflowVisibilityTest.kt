package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WfParams
import com.dynamicruntime.common.gedra.workflow.WfParamsSetting
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
import com.dynamicruntime.common.gedra.workflow.WfVisibility
import com.dynamicruntime.common.gedra.workflow.WfVisibilityMode
import com.dynamicruntime.common.gedra.workflow.WfWindow
import com.dynamicruntime.common.gedra.workflow.WfWindows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Who sees a workflow on the client page (issue #1384): the words, the save, where a choice comes from, and its history. */
class WorkflowVisibilityTest {
    private val at = Instant.parse("2026-10-20T14:30:00Z")
    private val zone = "America/Chicago"
    private fun restricted(vararg labels: String) = WfVisibility(WfVisibilityMode.restricted, labels.toList())

    @Test
    fun aVisibilityIsSaidInAPhraseAndExplainedBesideTheChoice() {
        assertEquals("Everyone", visibilityText(WfVisibility.everyone))
        assertEquals("Administrators only", visibilityText(WfVisibility.adminsOnly))
        assertEquals("Administrators, and anyone labeled pilot", visibilityText(restricted("pilot")))
        assertEquals("Administrators, and anyone labeled pilot or east", visibilityText(restricted("pilot", "east")))
        assertEquals("Administrators, and anyone labeled a, b, or c", visibilityText(restricted("a", "b", "c")))
        // An empty list is administrators only, never everybody; labels kept on an everyone are said to be kept.
        assertTrue(visibilityExplained(WfVisibility.adminsOnly).contains("Only the client's administrators"))
        assertTrue(visibilityExplained(restricted("pilot").withMode(WfVisibilityMode.everyone)).contains("labels are kept"))
        // The labels offered are the client's suggestions, then any the visibility names besides.
        assertEquals(listOf("pilot", "east", "west"), visibilityLabelChoices(listOf("pilot", "east"), restricted("west", "pilot")))
    }

    @Test
    fun theSaveSendsOnlyTheVisibilityOrItsUnset() {
        val request = visibilitySaveRequest("acme", "review", restricted("pilot"), acknowledge = true)
        assertEquals("acme", request[CLD.client])
        assertEquals("review", request[WFD.workflowId])
        assertEquals(mapOf(WFP.visibility to restricted("pilot").toInfo()), request[WFP.params])
        assertEquals(true, request[IMP.acknowledgeImpact])
        val back = visibilitySaveRequest("acme", "review", null, acknowledge = false)
        assertEquals(listOf(WFP.visibility), back[WFP.unset])
        assertFalse(back.containsKey(WFP.params))
        // A new workflow's save carries the maker's choice; a change of slots never does.
        val slots = WfTemplateSlots(null, WFT.collect, "Pilot", emptyList())
        assertEquals(WfVisibility.adminsOnly.toInfo(), workflowSaveRequest("acme", slots, "live", false, WfVisibility.adminsOnly)[CWF.visibility])
        val edit = WfTemplateSlots("pilot", WFT.collect, "Pilot", emptyList())
        assertFalse(workflowSaveRequest("acme", edit, "live", false, WfVisibility.adminsOnly).containsKey(CWF.visibility))
    }

    @Test
    fun theEditorSaysWhereTheChoiceComesFromAndOffersTheWayBack() {
        val nobody = WorkflowParamsView.none
        assertEquals(WfVisibility.everyone, nobody.visibility)
        assertTrue(visibilitySourceText(nobody, zone).contains("Nobody has restricted it"))
        assertNull(visibilityFallbackLabel(nobody))
        // A sandbox following its client's choice says so; once it makes its own, it is offered the way back.
        val client = WfParamsSetting(WfParams(visibility = restricted("pilot")), at, 12L, "Pat Lee")
        val following = WorkflowParamsView(null, client, WfParams.none, emptyList())
        assertEquals(restricted("pilot"), following.visibility)
        assertTrue(visibilitySourceText(following, zone).startsWith("Its client's choice"))
        assertNull(visibilityFallbackLabel(following))
        val own = WorkflowParamsView(WfParamsSetting(WfParams(visibility = WfVisibility.everyone), at, 12L, "Pat Lee"), client, WfParams.none, emptyList())
        assertEquals("Use its client's choice", visibilityFallbackLabel(own))
        assertTrue(visibilitySourceText(own, zone).contains("by Pat Lee"))
        // A client's own choice has nothing to go back to: none is everyone.
        assertNull(visibilityFallbackLabel(WorkflowParamsView(own.own, null, WfParams.none, emptyList())))
        assertTrue(visibilityEffectText(sandboxOf("acme")).contains("never reaches its client"))
        assertTrue(newWorkflowVisibilityNote(draft = true).contains("a publish never makes a workflow visible"))
    }

    @Test
    fun eachEditorListsOnlyTheEarlierSettingsThatChangedItsParameter() {
        val windows = WfWindows(lifetime = WfWindow(end = at))
        fun setting(params: WfParams) = WfParamsSetting(params, at, null)
        // Newest first: own, then a windows change, then a visibility change, then the first visibility.
        val params = WorkflowParamsView(
            setting(WfParams(windows, restricted("pilot"))),
            null,
            WfParams.none,
            listOf(
                setting(WfParams(visibility = restricted("pilot"))),
                setting(WfParams(visibility = WfVisibility.adminsOnly)),
            ),
        )
        assertEquals(listOf(WfVisibility.adminsOnly), params.historyOf(WFP.visibility).map { it.params.visibility })
        assertEquals(1, params.historyOf(WFP.windows).size)
        assertNull(params.historyOf(WFP.windows).single().params.windows)
    }
}
