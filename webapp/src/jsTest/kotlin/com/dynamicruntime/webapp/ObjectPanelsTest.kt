package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage for [objectPanels] (issue #1620): the panels an operator page draws a free-form object as, such
 * as System info. One per top-level section in the object's own order, and a leading Summary for any top-level
 * scalars, so every value belongs to a panel.
 */
class ObjectPanelsTest {

    @Test
    fun eachSectionIsAPanelInTheObjectsOwnOrder() {
        val obj = mapOf(
            "node" to mapOf("nodeId" to "n1"),
            "build" to mapOf("commit" to "abc"),
            "gcCollectors" to listOf(mapOf("name" to "G1")),
        )
        assertEquals(
            listOf(
                ObjectPanel("node", "op-key-node", "Node"),
                ObjectPanel("build", "op-key-build", "Build"),
                ObjectPanel("gcCollectors", "op-key-gcCollectors", "Gc collectors"),
            ),
            objectPanels(obj),
        )
    }

    @Test
    fun topLevelScalarsGoInALeadingSummary() {
        val obj = mapOf("node" to mapOf("nodeId" to "n1"), "pid" to 42)
        assertEquals(
            listOf(ObjectPanel(null, "op-summary", "Summary"), ObjectPanel("node", "op-key-node", "Node")),
            objectPanels(obj),
        )
    }

    @Test
    fun aSectionNamedSummaryKeepsItsOwnPanel() {
        val obj = mapOf("status" to "ok", "summary" to mapOf("count" to 3))
        val ids = objectPanels(obj).map { it.id }
        assertEquals(listOf("op-summary", "op-key-summary"), ids)
    }

    @Test
    fun anEmptyObjectHasNoPanels() {
        assertEquals(emptyList(), objectPanels(emptyMap()))
    }
}
