package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfWindow
import com.dynamicruntime.common.gedra.workflow.WfWindows
import com.dynamicruntime.common.util.toJsonMapOrEmpty
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
    fun theSaveSendsTheWindowsAsMomentsAndReadsWhatItDid() {
        val windows = WfWindows(lifetime = WfWindow(end = now + 1.days))
        val request = windowsSaveRequest("acme", "review", windows, EDM.live, acknowledge = true)
        assertEquals("review", request[WFD.workflowId])
        assertEquals(mapOf(WFD.lifetime to mapOf(WFD.end to "2026-10-24T12:00:00.000Z")), request[CWF.windows])
        assertEquals(true, request[IMP.acknowledgeImpact])
        val result = parseWindowsSaveResult(mapOf(CWF.configName to "main", CWF.windows to request[CWF.windows].toJsonMapOrEmpty(), CPY.mode to EDM.live))
        assertEquals(windows, result.windows)
        assertEquals("main", result.configName)
    }
}
