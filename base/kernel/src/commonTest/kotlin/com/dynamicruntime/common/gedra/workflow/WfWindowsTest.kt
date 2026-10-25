package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    private fun normal(more: WfDefBuilder.() -> Unit) = WfDefBuilder("audit", WfEntry.normal).apply {
        task("record", "Record") { trait("a"); save("s", "S", WfSaveKind.edit) }
        more()
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
        fun problem(w: WfWindows) = w.problem("audit")
        // Engagement ending after relevancy does.
        assertNotNull(problem(WfWindows(relevancy = WfWindow(end = jun), engagement = WfWindow(end = sep))))
        // Relevancy starting before the lifetime does.
        assertNotNull(problem(WfWindows(lifetime = WfWindow(start = mar), relevancy = WfWindow(start = jan))))
        // A window that closes before it opens -- here only once the engagement start meets relevancy's end. The
        // refusal says the end was inherited, so the author is not sent looking for an engagement end they never wrote.
        val never = problem(WfWindows(relevancy = WfWindow(end = jun), engagement = WfWindow(start = sep))).orEmpty()
        assertTrue(never.contains("ends when its relevancy window does"), never)
        assertNull(problem(WfWindows(lifetime = WfWindow(jan, dec), engagement = WfWindow(end = jun))))
        // A date that is not one.
        assertFailsWith<KdrException> { WfParams.fromInfo(mapOf(WFP.windows to mapOf(WFD.lifetime to mapOf(WFD.end to "next year")))) }
    }

    @Test
    fun aDefinitionHoldsNoWindows() {
        // Windows are a workflow's parameters, set per client (issue #1356): a definition naming one is not a definition.
        val raw = normal {} + (WFD.lifetime to mapOf(WFD.end to "2026-12-01T00:00:00Z"))
        assertFailsWith<KdrException> { parseWfDef(cxt, raw) }
        assertFalse(parseWfDef(cxt, normal {}).toJsonMap().containsKey(WFD.lifetime))
    }

    @Test
    fun parametersRoundTripAsDeclared() {
        val params = WfParams(WfWindows(lifetime = WfWindow(jan, dec), engagement = WfWindow(end = jun)))
        val again = WfParams.fromInfo(params.toInfo())
        assertEquals(params, again)
        assertEquals(WfPhase.relevant, again.windowsInForce.phaseAt(sep))
        // Only what was declared is stored: the fallbacks are recomputed, not written down.
        assertFalse(params.toInfo().getValue(WFP.windows).toJsonMapOrEmpty().containsKey(WFD.relevancy))
        assertTrue(WfParams.none.toInfo().isEmpty())
        // A setting keeps who and when, newest first in a history.
        val setting = WfParamsSetting(params, jan, 12L, "Pat Lee")
        val read = WfParamsSetting.listFrom(listOf(setting.toInfo())).single()
        assertEquals(listOf<Any?>(params, jan, 12L, "Pat Lee"), listOf(read.params, read.setAt, read.setBy, read.setByName))
    }

    @Test
    fun eachParameterFollowsTheFirstLayerThatSetsIt() {
        // Issue #1386: windows not set follow the fallback; windows set empty are deliberately no limits.
        val noLimits = WfParams(WfWindows.none)
        assertEquals(mapOf<String, Any?>(WFP.windows to emptyMap<String, Any?>()), noLimits.toInfo())
        assertEquals(noLimits, WfParams.fromInfo(noLimits.toInfo()))
        assertTrue(WfParams.fromInfo(emptyMap<String, Any?>()).isEmpty)

        val source = WfParams(WfWindows(lifetime = WfWindow(jan, dec)))
        val parent = WfParams(WfWindows(lifetime = WfWindow(jan, jun)))
        // Nothing of its own: the client's, then the source code's.
        val fromSource = WfParamsLayers(WfParams.none, source = source)
        assertEquals(source, fromSource.inForce)
        assertEquals(mapOf(WFP.windows to WfParamsFrom.source), fromSource.from)
        val fromParent = WfParamsLayers(WfParams.none, parent, source)
        assertEquals(parent, fromParent.inForce)
        assertEquals(mapOf(WFP.windows to WfParamsFrom.parent), fromParent.from)
        // Its own, even no limits, wins; the fallback says where it would go back to.
        val own = fromParent.withOwn(noLimits)
        assertEquals(noLimits, own.inForce)
        assertTrue(own.from.isEmpty())
        assertEquals(WfParamsFrom.parent, own.fallbackFrom(WFP.windows))
        assertNull(WfParamsLayers(noLimits).fallbackFrom(WFP.windows))

        // An edit replaces what it sets and leaves the rest; an unset returns a parameter to the fallback.
        assertEquals(parent, noLimits.edited(parent))
        assertEquals(noLimits, noLimits.edited(WfParams.none))
        assertTrue(noLimits.edited(WfParams.none, setOf(WFP.windows)).isEmpty)
    }

    @Test
    fun sourceDefaultsAreBuiltFromMomentsAndMustNest() {
        val built = WfParamsBuilder("review").apply {
            lifetime(start = "2026-01-01T00:00:00Z", end = "2026-12-01T00:00:00Z")
            engagement(end = "2026-06-01T00:00:00Z")
        }.build()
        assertEquals(WfWindows(lifetime = WfWindow(jan, dec), engagement = WfWindow(end = jun)), built.windows)
        assertFailsWith<KdrException> { WfParamsBuilder("review").build() }.message.orEmpty().let { assertTrue(it.contains("declare nothing"), it) }
        assertFailsWith<KdrException> { WfParamsBuilder("review").apply { lifetime(end = "soon") } }
        assertFailsWith<KdrException> {
            WfParamsBuilder("review").apply { lifetime(end = "2026-06-01T00:00:00Z"); engagement(end = "2026-12-01T00:00:00Z") }.build()
        }
    }
}
