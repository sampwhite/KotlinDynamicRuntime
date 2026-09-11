package com.dynamicruntime.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/**
 * [pluckDataPath] (issue #677): the workflow-function value addressing -- a dotted path, tolerant of misses and
 * **spreading across arrays**. In `commonTest` because the same resolution runs on the JVM (function execution)
 * and could be reused on JS. Also pins that the strict `resolvePath` a `${'$'}{...}` template uses is
 * **unchanged** by the shared implementation, since the two now share one walk.
 */
class PluckDataPathTest {

    @Test
    fun drillsNestedMaps() {
        assertEquals(1, pluckDataPath(mapOf("a" to mapOf("b" to 1)), "a.b"))
        assertEquals(1, pluckDataPath(mapOf("a" to 1), "a"))
    }

    @Test
    fun spreadsAcrossAnArray() {
        // The motivating case: a segment landing on a list applies the rest of the path to each element.
        val data = mapOf("a" to listOf(mapOf("b" to 1), mapOf("b" to 2)))
        assertEquals(listOf(1, 2), pluckDataPath(data, "a.b"))
    }

    @Test
    fun spreadIsTolerantPerElement() {
        // A ragged array: an element missing the next segment contributes nothing, rather than a null or a fault.
        val data = mapOf("a" to listOf(mapOf("b" to 1), mapOf("c" to 9), mapOf("b" to 3)))
        assertEquals(listOf(1, 3), pluckDataPath(data, "a.b"))
    }

    @Test
    fun flattensOneLevelPerCrossedArray() {
        val data = mapOf("a" to listOf(mapOf("b" to listOf(mapOf("c" to 1), mapOf("c" to 2)))))
        assertEquals(listOf(1, 2), pluckDataPath(data, "a.b.c"))
    }

    @Test
    fun aLeafArrayIsReturnedWhole() {
        // No further segment past the array, so it is the value, not something to spread.
        assertEquals(listOf(1, 2), pluckDataPath(mapOf("a" to listOf(1, 2)), "a"))
    }

    @Test
    fun aMissReadsAsNull() {
        assertNull(pluckDataPath(mapOf("a" to mapOf("b" to 1)), "a.x"))
        assertNull(pluckDataPath(mapOf("a" to mapOf("b" to 1)), "x.y"))
        assertNull(pluckDataPath(emptyMap(), "a"))
    }

    @Test
    fun strictResolutionIsUnchangedByTheSharedWalk() {
        val node = PathNode(listOf("a", "b"), "a.b")
        val state = ScriptState("a.b", '$')
        val overArray = mapOf<String, Any?>("a" to listOf(mapOf("b" to 1)))
        // Default (spreadArrays = false): drilling into the array still faults, exactly as before.
        assertFails { resolvePath(state, overArray, node, tolerant = false) }
        // Opting in spreads it instead.
        assertEquals(listOf(1), resolvePath(state, overArray, node, tolerant = true, spreadArrays = true))
    }
}
