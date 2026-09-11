package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxtBase
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The kernel wiring of workflow-function **usages** onto a definition (issue #677): a def's global-event
 * usages and a task's task-event usages parse from the builder, order by priority, and round-trip through
 * `toJsonMap` -- all as pure kernel data. Resolving a usage into a runnable function (and the
 * scope-matches-placement check) is a `base:common` second pass, so `resolvedFunctions` is empty here; that is
 * asserted too. In `commonTest` so the model behaves the same on the JVM and under Kotlin/JS.
 */
class WfFunctionWiringTest {
    private val cxt: KdrCxtBase = LiteCxt()

    private fun fnData(fn: String, priority: Int? = null): Map<String, Any?> = buildMap {
        put(WFD.fn, fn)
        priority?.let { put(WFD.priority, it) }
    }

    @Test
    fun usagesParseFromTheBuilderAndRoundTrip() {
        val raw = WfDefBuilder("wf", WfEntry.normal).apply {
            function(fnData("computeCFactsFromData", priority = 5))
            task("t", "T") { trait("a"); save("s", "S"); function(fnData("prefillFromOwner")) }
        }.build()

        val def = parseWfDef(cxt, raw)
        assertEquals(listOf("computeCFactsFromData"), def.functionUsages.map { it.fn })
        assertEquals(5, def.functionUsages.single().priority)
        assertEquals(listOf("prefillFromOwner"), def.tasks.single().functionUsages.map { it.fn })

        // Resolution is the common second pass; the resolved store is empty until it runs.
        assertTrue(def.resolvedFunctions.isEmpty())
        assertTrue(def.tasks.single().resolvedFunctions.isEmpty())

        // A usage re-emits its own init data, so serialize + reparse yields the same usages.
        val again = parseWfDef(cxt, def.toJsonMap())
        assertEquals(def.functionUsages.map { it.fn }, again.functionUsages.map { it.fn })
        assertEquals(def.tasks.single().functionUsages.map { it.fn }, again.tasks.single().functionUsages.map { it.fn })
    }

    @Test
    fun usagesAreOrderedByPriority() {
        val raw = WfDefBuilder("wf", WfEntry.normal).apply {
            function(fnData("late", priority = 5))
            function(fnData("early", priority = 1))
            task("t", "T") { trait("a"); save("s", "S") }
        }.build()
        val def = parseWfDef(cxt, raw)
        assertEquals(listOf("early", "late"), def.functionUsages.map { it.fn })
    }

    @Test
    fun aUsageWithoutAnFnIsRefused() {
        assertFailsWith<KdrException> { WfFunctionUsage(mapOf(WFD.priority to 1)) }
    }
}
