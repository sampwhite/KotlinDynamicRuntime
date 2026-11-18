package com.dynamicruntime.webapp

import com.dynamicruntime.common.counter.CTR
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a create page asks a counter for, and where an issued id lands (issue #1849). */
class CounterIdsTest {
    private val receipt: SchType = parseSchemaTypes(
        mapOf(
            "c.Receipt" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "number" to mapOf(SCH.type to SCT.string, SCH.description to "The id.", SCH.counter to "receipt"),
                    "note" to mapOf(SCH.type to SCT.string, SCH.description to "A note."),
                ),
            ),
        ),
    ).getValue("c.Receipt")

    @Test
    fun aFieldWithNoIdIsAskedForOnceATrait() {
        val traits = listOf("receipt" to receipt, "receipt" to receipt)
        assertEquals(listOf(CounterSlot("receipt", "number", "receipt")), counterSlotsToFill(traits) { emptyMap() })
        // One that holds an id already, or a trait the page has no values for, asks nothing.
        assertTrue(counterSlotsToFill(traits) { mapOf("number" to "R-0001") }.isEmpty())
        assertTrue(counterSlotsToFill(traits) { null }.isEmpty())
    }

    @Test
    fun eachEntryOfATraitIsGivenItsOwnId() {
        val entries = listOf(
            mapOf(GE.traitId to "receipt", GE.data to mapOf("note" to "a")),
            mapOf(GE.traitId to "receipt", GE.data to mapOf("number" to "R-0001")),
            mapOf(GE.traitId to "receipt", GE.data to emptyMap<String, Any?>()),
            mapOf(GE.traitId to "other", GE.data to emptyMap<String, Any?>()),
        )
        val slots = counterSlotsInEntries(entries) { if (it == "receipt") receipt else null }
        assertEquals(2, slots.size)
        // Filled in order: each id to the first entry of its trait still without one.
        val once = withCounterId(entries, slots[0], "R-0002")
        val twice = withCounterId(once, slots[1], "R-0003")
        assertEquals(listOf("R-0002", "R-0001", "R-0003", null), twice.map { (it[GE.data] as Map<*, *>)["number"] })
        assertEquals("a", (twice[0][GE.data] as Map<*, *>)["note"])
        // With every entry given one, a late id goes nowhere.
        assertEquals(twice, withCounterId(twice, slots[0], "R-0004"))
    }

    @Test
    fun aSaveSendsTheTokensItWasGiven() {
        val body = workflowSaveBody("create", "first", "save", emptyList(), counterTokens = listOf("t1"))
        assertEquals(listOf("t1"), body[CTR.counterTokens])
        assertTrue(CTR.counterTokens !in workflowSaveBody("create", "first", "save", emptyList()))
    }
}
