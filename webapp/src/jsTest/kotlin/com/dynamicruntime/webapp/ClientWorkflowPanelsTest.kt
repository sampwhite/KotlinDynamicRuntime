package com.dynamicruntime.webapp

import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WfDefBuilder
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfParams
import com.dynamicruntime.common.gedra.workflow.WfParamsSetting
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
import com.dynamicruntime.common.gedra.workflow.WfVisibility
import com.dynamicruntime.common.gedra.workflow.WfVisibilityMode
import com.dynamicruntime.common.gedra.workflow.WfWindow
import com.dynamicruntime.common.gedra.workflow.WfWindows
import com.dynamicruntime.common.gedra.workflow.parseWfDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

// A workflow's switches on the client page (issue #1740 gathered these into one file): whether it is turned on, and who
// sees it. The two share a shape: the save, where a choice comes from, and its history. Each class is the file it used
// to be, kept whole with its own helpers.

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
