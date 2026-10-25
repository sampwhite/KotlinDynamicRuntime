package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfWindow
import com.dynamicruntime.common.gedra.workflow.WfWindows
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WfParams
import com.dynamicruntime.common.gedra.workflow.WfParamsFrom
import com.dynamicruntime.common.gedra.workflow.WfParamsSetting
import com.dynamicruntime.common.gedra.sandboxOf
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Pure-logic coverage (issue #161) for a normal workflow's time windows on the client page (issue #1121): setting a
 * bound, what a missing one falls back to, where the workflow stands at a moment, the timeline's layout, and the save.
 */
class WorkflowWindowsTest {
    private val zone = "UTC"
    private val now = Instant.parse("2026-10-23T12:00:00Z")

    @Test
    fun aBoundIsSetAndClearedInItsOwnWindow() {
        val set = WfWindows.none.withBound(WindowBand.relevancy, isStart = false, now + 5.days)
        assertEquals(now + 5.days, set.relevancy.end)
        assertNull(set.relevancy.start)
        assertTrue(set.lifetime.isEmpty)
        assertTrue(set.withBound(WindowBand.relevancy, isStart = false, null).isEmpty)
    }

    @Test
    fun aMissingBoundSaysWhatItFallsBackTo() {
        val windows = WfWindows(lifetime = WfWindow(start = Instant.parse("2026-10-01T09:30:00Z")))
        assertEquals("No start", boundFallbackText(WfWindows.none, WindowBand.lifetime, isStart = true, zone))
        assertEquals("No end", boundFallbackText(WfWindows.none, WindowBand.lifetime, isStart = false, zone))
        // A narrower window's is its enclosing one's, with the moment that comes to.
        val tracked = boundFallbackText(windows, WindowBand.relevancy, isStart = true, zone)
        assertTrue(tracked.startsWith("Same as Available ("), tracked)
        assertTrue(tracked.contains("2026"), tracked)
        assertEquals("Same as Tracked", boundFallbackText(windows, WindowBand.engagement, isStart = false, zone))
    }

    @Test
    fun wherePhaseTextSaysWhereTheWorkflowStandsAndWhatIsNext() {
        assertEquals("Open to new forms", windowPhaseText(WfWindows.none, now, zone))
        val closing = WfWindows(engagement = WfWindow(end = now + 3.hours))
        assertEquals("Open to new forms; closes to new forms in 3h", windowPhaseText(closing, now, zone))
        val notYet = WfWindows(lifetime = WfWindow(start = now + 2.hours))
        assertEquals("Not available yet; becomes available in 2h", windowPhaseText(notYet, now, zone))
        val frozen = WfWindows(lifetime = WfWindow(end = now + 10.days), relevancy = WfWindow(end = now - 1.days))
        assertTrue(windowPhaseText(frozen, now, zone).startsWith("No longer tracked: forms engaged with it keep their last state"))
        assertEquals("No longer available", windowPhaseText(WfWindows(lifetime = WfWindow(end = now - 1.hours)), now, zone))
        // As of a later moment, measured from now.
        assertEquals("No longer available", windowPhaseText(closing.withBound(WindowBand.lifetime, false, now + 1.days), now + 2.days, zone, now))
    }

    @Test
    fun theTimelineSpansEveryBoundAndTheMarkers() {
        val open = timelineLayout(WfWindows.none, now)
        assertTrue(open.bands.all { it.left == 0.0 && it.right == 100.0 && it.openStart && it.openEnd })
        val windows = WfWindows(lifetime = WfWindow(now - 10.days, now + 10.days), engagement = WfWindow(end = now + 5.days))
        val laid = timelineLayout(windows, now, asOf = now + 20.days)
        val lifetime = laid.bands.first()
        assertTrue(lifetime.left > 0 && lifetime.right < 100, "${lifetime.left}..${lifetime.right}")
        // Engagement starts with the lifetime (its fallback) and ends earlier.
        val engagement = laid.bands.last()
        assertEquals(lifetime.left, engagement.left)
        assertTrue(engagement.right < lifetime.right)
        assertTrue(laid.nowAt > lifetime.left && laid.nowAt < lifetime.right)
        assertTrue(assertNotNull(laid.asOfAt) > lifetime.right)
    }

    @Test
    fun theSaveSendsTheParametersAsMomentsAndReadsWhatItDid() {
        // Windows are a workflow's parameters (issue #1356), sent under `params`; set empty is no limits (issue #1386).
        val windows = WfWindows(lifetime = WfWindow(end = now + 1.days))
        val request = windowsSaveRequest("acme", "review", windows, acknowledge = true)
        assertEquals("review", request[WFD.workflowId])
        val sent = mapOf(WFP.windows to mapOf(WFD.lifetime to mapOf(WFD.end to "2026-10-24T12:00:00.000Z")))
        assertEquals(sent, request[WFP.params])
        assertEquals(true, request[IMP.acknowledgeImpact])
        assertEquals(mapOf(WFP.windows to emptyMap<String, Any?>()), windowsSaveRequest("acme", "review", WfWindows.none, acknowledge = false)[WFP.params])
        // A way back to the fallback unsets the windows and sends nothing else.
        val back = windowsSaveRequest("acme", "review", null, acknowledge = false)
        assertEquals(listOf(WFP.windows), back[WFP.unset])
        assertFalse(back.containsKey(WFP.params))
        // The answer is the parameters now, the setting they replaced first among the earlier ones.
        val result = parseWindowsSaveResult(
            mapOf(
                CWF.params to mapOf(
                    WFP.current to mapOf(WFP.params to sent, WFP.setAt to "2026-10-23T12:00:00.000Z", WFP.setByName to "Pat Lee"),
                    WFP.history to listOf(mapOf(WFP.params to emptyMap<String, Any?>(), WFP.setAt to "2026-10-20T12:00:00.000Z")),
                ),
            ),
        )
        assertEquals(windows, result.windows)
        assertEquals("Pat Lee", result.own?.setByName)
        // An earlier setting that set no windows followed the fallback.
        assertNull(result.history.single().params.windows)
        assertNull(result.windowsFrom)
    }

    @Test
    fun theEditorSaysWhereTheWindowsComeFromAndOffersTheWayBack() {
        val sourceWindows = WfWindows(lifetime = WfWindow(end = now + 30.days))
        val source = mapOf(WFP.windows to mapOf(WFD.lifetime to mapOf(WFD.end to (now + 30.days).toString())))
        // Nothing set here: the source code's defaults, and nothing to go back to.
        val fromSource = parseWorkflowParams(mapOf(WFP.source to source, WFP.history to emptyList<Any?>()))
        assertEquals(sourceWindows, fromSource.windows)
        assertEquals(WfParamsFrom.source, fromSource.windowsFrom)
        assertTrue(windowsSourceText(fromSource, "acme", zone).orEmpty().startsWith("The source code's default windows"))
        assertTrue(windowsSourceText(fromSource, sandboxOf("acme"), zone).orEmpty().contains("until it or its client sets its own"))
        assertNull(windowsFallbackLabel(fromSource))
        // A sandbox following its client: the client's setting is named.
        val clientSet = mapOf(WFP.params to mapOf(WFP.windows to emptyMap<String, Any?>()), WFP.setAt to "2026-10-20T12:00:00.000Z", WFP.setByName to "Pat Lee")
        val fromParent = parseWorkflowParams(mapOf(WFP.parent to clientSet, WFP.source to source, WFP.history to emptyList<Any?>()))
        assertEquals(WfWindows.none, fromParent.windows)
        assertEquals(WfParamsFrom.parent, fromParent.windowsFrom)
        assertTrue(windowsSourceText(fromParent, sandboxOf("acme"), zone).orEmpty().let { it.startsWith("Its client's windows") && it.contains("by Pat Lee") })
        // Its own: who set them, and a way back named for where it leads.
        val own = parseWorkflowParams(mapOf(WFP.current to clientSet, WFP.source to source, WFP.history to emptyList<Any?>()))
        assertNull(own.windowsFrom)
        assertTrue(windowsSourceText(own, "acme", zone).orEmpty().endsWith("by Pat Lee."))
        assertEquals("Use the source code's windows", windowsFallbackLabel(own))
        assertEquals("Use its client's windows", windowsFallbackLabel(parseWorkflowParams(mapOf(WFP.current to clientSet, WFP.parent to clientSet))))
        // Nothing set anywhere: no limits, and nothing said.
        assertNull(windowsSourceText(WorkflowParamsView.none, "acme", zone))
    }

    @Test
    fun theEditorSaysWhoSetTheWindowsHowASaveTakesEffectAndEachEarlierSettingInALine() {
        val setting = WfParamsSetting(WfParams(WfWindows(lifetime = WfWindow(start = now))), now, 7L, "Pat Lee")
        val byline = assertNotNull(settingByline(setting, zone))
        assertTrue(byline.startsWith("Set ") && byline.endsWith(" by Pat Lee"), byline)
        assertNull(settingByline(WfParamsSetting(WfParams.none, null, null), zone))
        assertTrue(windowsSummaryText(setting.params.windowsInForce, zone).startsWith("Available: from "))
        assertEquals("No limits", windowsSummaryText(WfWindows.none, zone))
        // At once, with no draft: in a sandbox, never reaching its client.
        assertTrue(windowsEffectText("acme").contains("at once for this client's people"))
        assertTrue(windowsEffectText(sandboxOf("acme")).contains("never reaches its client"))
    }
}
