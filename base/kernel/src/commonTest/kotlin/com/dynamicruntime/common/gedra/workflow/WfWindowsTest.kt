package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A normal workflow's time windows (issue #790): the fallbacks a narrower window's missing bound takes, the phase
 * a moment falls in, the definitions refused for not nesting, and the round trip through the definition's JSON.
 */
class WfWindowsTest {
    private val cxt: KdrCxtBase = LiteCxt()
    private fun at(s: String) = Instant.parse(s)
    private val jan = at("2026-01-01T00:00:00Z")
    private val mar = at("2026-03-01T00:00:00Z")
    private val jun = at("2026-06-01T00:00:00Z")
    private val sep = at("2026-09-01T00:00:00Z")
    private val dec = at("2026-12-01T00:00:00Z")

    private fun normal(windows: WfDefBuilder.() -> Unit) = WfDefBuilder("audit", WfEntry.normal).apply {
        task("record", "Record") { trait("a"); save("s", "S", WfSaveKind.edit) }
        windows()
    }.build()

    @Test
    fun aMissingStartFallsBackAndAMissingEndFallsForward() {
        val w = WfWindows(lifetime = WfWindow(jan, dec), relevancy = WfWindow(start = mar), engagement = WfWindow(end = jun))
        // Relevancy has no end, so it lasts as long as the lifetime.
        assertEquals(listOf(mar, dec), listOf(w.effectiveRelevancy.start, w.effectiveRelevancy.end))
        // Engagement has no start, so it opens with relevancy -- not with the lifetime it skips over.
        assertEquals(listOf(mar, jun), listOf(w.effectiveEngagement.start, w.effectiveEngagement.end))
    }

    @Test
    fun aMomentFallsInOnePhaseFromTheOutsideIn() {
        val w = WfWindows(lifetime = WfWindow(jan, dec), relevancy = WfWindow(mar, sep), engagement = WfWindow(end = jun))
        assertEquals(WfPhase.outsideLifetime, w.phaseAt(at("2025-12-31T23:59:59Z")))
        assertEquals(WfPhase.lifetimeOnly, w.phaseAt(jan))
        assertEquals(WfPhase.engageable, w.phaseAt(mar))
        // Half-open: the end instant itself is outside.
        assertEquals(WfPhase.relevant, w.phaseAt(jun))
        assertEquals(WfPhase.lifetimeOnly, w.phaseAt(sep))
        assertEquals(WfPhase.outsideLifetime, w.phaseAt(dec))
        // No windows at all: always engageable.
        assertEquals(WfPhase.engageable, WfWindows.none.phaseAt(dec))
    }

    @Test
    fun whatEachPhaseAllows() {
        assertFalse(WfPhase.outsideLifetime.isShown(engaged = true))
        // Past relevancy: only a form already engaged sees it, and nothing is calculated for it.
        assertTrue(WfPhase.lifetimeOnly.isShown(engaged = true))
        assertFalse(WfPhase.lifetimeOnly.isShown(engaged = false))
        assertFalse(WfPhase.lifetimeOnly.calculates)
        assertFalse(WfPhase.relevant.isShown(engaged = false))
        assertTrue(WfPhase.relevant.calculates)
        assertTrue(WfPhase.engageable.isShown(engaged = false))
    }

    @Test
    fun windowsThatDoNotNestAreRefused() {
        // Engagement ending after relevancy does.
        assertFailsWith<KdrException> {
            parseWfDef(cxt, normal { relevancy(end = "2026-06-01T00:00:00Z"); engagement(end = "2026-09-01T00:00:00Z") })
        }
        // Relevancy starting before the lifetime does.
        assertFailsWith<KdrException> {
            parseWfDef(cxt, normal { lifetime(start = "2026-03-01T00:00:00Z"); relevancy(start = "2026-01-01T00:00:00Z") })
        }
        // A window that closes before it opens -- here only once the engagement start meets relevancy's end.
        assertFailsWith<KdrException> {
            parseWfDef(cxt, normal { relevancy(end = "2026-06-01T00:00:00Z"); engagement(start = "2026-09-01T00:00:00Z") })
        }
        // A date that is not one.
        assertFailsWith<KdrException> { parseWfDef(cxt, normal { lifetime(end = "next year") }) }
    }

    @Test
    fun onlyANormalWorkflowHasWindows() {
        val raw = WfDefBuilder("review", WfEntry.survey).apply {
            task("t", "T") { trait("a"); save("s", "S", WfSaveKind.edit) }
            lifetime(end = "2026-12-01T00:00:00Z")
        }.build()
        assertFailsWith<KdrException> { parseWfDef(cxt, raw) }
    }

    @Test
    fun windowsParseAndRoundTripAsDeclared() {
        val def = parseWfDef(cxt, normal { lifetime("2026-01-01T00:00:00Z", "2026-12-01T00:00:00Z"); engagement(end = "2026-06-01T00:00:00Z") })
        assertEquals(listOf(jan, dec), listOf(def.windows.lifetime.start, def.windows.lifetime.end))
        assertTrue(def.windows.relevancy.isEmpty)
        assertEquals(WfPhase.relevant, def.phaseAt(sep))

        val again = parseWfDef(cxt, def.toJsonMap()).windows
        assertEquals(listOf(jan, dec, null, jun), listOf(again.lifetime.start, again.lifetime.end, again.engagement.start, again.engagement.end))
        // Only what was declared is stored: the fallbacks are recomputed, not written down.
        assertFalse(def.toJsonMap().containsKey(WFD.relevancy))
    }
}
