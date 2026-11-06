package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WfDefBuilder
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfParams
import com.dynamicruntime.common.gedra.workflow.WfParamsSetting
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WfVisibility
import com.dynamicruntime.common.gedra.workflow.parseWfDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Turning a workflow on and off on the client page (issue #1635): the words, the save, where a choice comes from, and its history. */
class WorkflowEnabledTest {
    private val at = Instant.parse("2026-11-06T14:30:00Z")
    private val zone = "America/Chicago"

    private fun def(entry: WfEntry) = parseWfDef(
        LiteCxt(),
        WfDefBuilder("review", entry).apply { task("record", "Record") { trait("a"); save("s", "S", WfSaveKind.edit) } }.build(),
    )

    private fun view(params: WorkflowParamsView?, entry: WfEntry = WfEntry.normal) = ClientWorkflowView(def(entry), null, null, params)

    @Test
    fun theSaveSendsOnlyTheSwitchOrItsUnset() {
        val request = enabledSaveRequest("acme", "review", false, acknowledge = true)
        assertEquals("acme", request[CLD.client])
        assertEquals("review", request[WFD.workflowId])
        assertEquals(mapOf(WFP.enabled to false), request[WFP.params])
        assertEquals(true, request[IMP.acknowledgeImpact])
        val back = enabledSaveRequest("acme", "review", null, acknowledge = false)
        assertEquals(listOf(WFP.enabled), back[WFP.unset])
        assertFalse(back.containsKey(WFP.params))
        assertFalse(back.containsKey(IMP.acknowledgeImpact))
    }

    @Test
    fun turnedOffIsSaidAsNotDeleted() {
        assertEquals("On", enabledLabel(true))
        assertTrue(enabledExplained(false).contains("What they recorded is kept"))
        assertTrue(enabledSavedNote("review", false).contains("what its forms recorded is kept"))
        assertTrue(enabledSavedNote("review", null).contains("follows the fallback"))
        assertTrue(enabledEffectText(sandboxOf("acme")).contains("never reaches its client"))
        assertTrue(enabledEffectText("acme").contains("with no draft and no publish"))
    }

    @Test
    fun theEditorSaysWhereTheChoiceComesFromAndOffersTheWayBack() {
        val nobody = WorkflowParamsView.none
        assertTrue(nobody.enabled)
        assertTrue(enabledSourceText(nobody, zone).contains("Nobody has turned it off"))
        assertNull(enabledFallbackLabel(nobody))
        // The code ships it off: the client follows that until it makes its own choice, then is offered the way back.
        val shippedOff = WorkflowParamsView(null, null, WfParams(enabled = false), emptyList())
        assertFalse(shippedOff.enabled)
        assertTrue(enabledSourceText(shippedOff, zone).startsWith("The code's default"))
        val turnedOn =
            WorkflowParamsView(WfParamsSetting(WfParams(enabled = true), at, 12L, "Pat Lee"), null, WfParams(enabled = false), emptyList())
        assertTrue(turnedOn.enabled)
        assertEquals("Use the code's default", enabledFallbackLabel(turnedOn))
        assertTrue(enabledSourceText(turnedOn, zone).contains("by Pat Lee"))
        // A sandbox following its client's choice.
        val client = WfParamsSetting(WfParams(enabled = false), at, 12L, "Pat Lee")
        assertTrue(enabledSourceText(WorkflowParamsView(null, client, WfParams.none, emptyList()), zone).startsWith("Its client's choice"))
        // A client's own "on" has nothing to go back to: none is on.
        assertNull(enabledFallbackLabel(WorkflowParamsView(turnedOn.own, null, WfParams.none, emptyList())))
        // Its history lists only the settings that changed the switch.
        val history = WorkflowParamsView(
            WfParamsSetting(WfParams(visibility = WfVisibility.adminsOnly, enabled = false), at, null),
            null,
            WfParams.none,
            listOf(WfParamsSetting(WfParams(visibility = WfVisibility.adminsOnly), at, null), WfParamsSetting(WfParams.none, at, null)),
        )
        assertEquals(1, history.historyOf(WFP.enabled).size)
    }

    @Test
    fun theTableSaysOffBeforeTheWindows() {
        fun off(own: Boolean = false, parent: Boolean = false, source: Boolean = false) = view(
            WorkflowParamsView(
                WfParamsSetting(WfParams(enabled = false), at, null).takeIf { own },
                WfParamsSetting(WfParams(enabled = false), at, null).takeIf { parent },
                if (source) WfParams(enabled = false) else WfParams.none,
                emptyList(),
            ),
        )
        assertEquals("Off", workflowNowText(off(own = true), at, zone))
        assertEquals("Off: the code's default", workflowNowText(off(source = true), at, zone))
        assertEquals("Off: its client's choice", workflowNowText(off(parent = true), at, zone))
        assertEquals("Open to new forms", workflowNowText(view(WorkflowParamsView.none), at, zone))
        // A workflow that is not normal has no switch: always on, and nothing to say of it.
        val details = view(WorkflowParamsView(null, null, WfParams(enabled = false), emptyList()), WfEntry.coreDetails)
        assertFalse(details.enabledOffered)
        assertTrue(details.enabled)
        assertEquals("—", workflowNowText(details, at, zone))
    }
}
