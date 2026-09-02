package com.dynamicruntime.common.schema

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recursive `$defs` closure ([collectDefClosure]) -- the hunt that makes a workflow view self-contained
 * (issue #534). In `commonTest`, so the exact walk runs on the JVM and under Kotlin/JS.
 */
class SchemaDefsTest {
    // A -> B (via a property) -> C (via array items); D is unrelated; C also appears in a nested anyOf branch.
    private val defs: Map<String, Any?> = mapOf(
        "ns.A" to mapOf(
            "type" to "object",
            "properties" to mapOf("b" to mapOf(SCH.dRef to "#/${SCH.dDefs}/ns.B")),
            "anyOf" to listOf(mapOf(SCH.dRef to "#/${SCH.dDefs}/ns.C")),
        ),
        "ns.B" to mapOf("type" to "array", "items" to mapOf(SCH.dRef to "#/${SCH.dDefs}/ns.C")),
        "ns.C" to mapOf("type" to "string"),
        "ns.D" to mapOf("type" to "integer"),
    )

    @Test
    fun closureFollowsRefsAtAnyDepthAndStopsAtTheReachableSet() {
        val closure = collectDefClosure(listOf("ns.A"), defs)
        assertEquals(setOf("ns.A", "ns.B", "ns.C"), closure.keys)   // D is not reachable, so not included
        assertEquals(defs["ns.C"], closure["ns.C"])                 // bodies are carried verbatim
    }

    @Test
    fun aSeedAbsentFromTheBagIsSkippedNotFaulted() {
        // A dangling ref is a boot-time concern, not this walk's; it collects what exists and moves on.
        val closure = collectDefClosure(listOf("ns.A", "ns.missing"), defs)
        assertEquals(setOf("ns.A", "ns.B", "ns.C"), closure.keys)
    }

    @Test
    fun aCycleTerminates() {
        val cyclic = mapOf(
            "ns.X" to mapOf("properties" to mapOf("y" to mapOf(SCH.dRef to "#/${SCH.dDefs}/ns.Y"))),
            "ns.Y" to mapOf("properties" to mapOf("x" to mapOf(SCH.dRef to "#/${SCH.dDefs}/ns.X"))),
        )
        assertEquals(setOf("ns.X", "ns.Y"), collectDefClosure(listOf("ns.X"), cyclic).keys)
    }

    @Test
    fun refNameReadsALocalDefsPointerAndRejectsAnythingElse() {
        assertEquals("ns.A", refName("#/${SCH.dDefs}/ns.A"))
        assertNull(refName("https://example.com/schema"))
        assertNull(refName("#/properties/x"))
    }

    @Test
    fun emptySeedsGiveAnEmptyClosure() {
        assertTrue(collectDefClosure(emptyList(), defs).isEmpty())
    }
}
