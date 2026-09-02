package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.PRES
import com.dynamicruntime.common.schema.PSTAT
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-logic coverage (issue #161) for [operatorVerdict], the verdict-first summary an operator list page leads
 * with (issue #540). It reads the status column off the schema and counts the rows, so any list endpoint with a
 * `presentation: status` column gets a summary for free -- and a list without one gets none.
 */
class OperatorVerdictTest {

    /** An element type with a `presentation: status` column, built through the real parser (as the page does). */
    private fun withStatus(): SchType = parseSchemaTypes(
        mapOf(
            "Row" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "name" to mapOf(SCH.type to SCT.string),
                    "state" to mapOf(SCH.type to SCT.string, SCH.presentation to PRES.status),
                ),
            ),
        ),
    ).getValue("Row")

    private fun row(state: String) = mapOf("name" to "x", "state" to state)

    @Test
    fun leadsWithAllClearWhenNothingNeedsAttention() {
        // ok and info are both "fine"; only warning/error count as attention.
        val items = listOf(row(PSTAT.ok), row(PSTAT.info))
        assertEquals("All 2 OK.", operatorVerdict(withStatus(), items))
    }

    @Test
    fun countsWarningsAndErrorsAsAttention() {
        val items = listOf(row(PSTAT.ok), row(PSTAT.warning), row(PSTAT.error))
        assertEquals("2 of 3 need attention.", operatorVerdict(withStatus(), items))
    }

    @Test
    fun reportsNothingForAnEmptyList() {
        assertEquals("Nothing to report.", operatorVerdict(withStatus(), emptyList()))
    }

    @Test
    fun noVerdictWhenThereIsNoStatusColumn() {
        // A list whose element type declares no status column gets no summary line at all.
        val noStatus = parseSchemaTypes(
            mapOf(
                "Row" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf("name" to mapOf(SCH.type to SCT.string)),
                ),
            ),
        ).getValue("Row")
        assertNull(operatorVerdict(noStatus, listOf(mapOf("name" to "x"))))
        assertNull(operatorVerdict(null, listOf(mapOf("name" to "x"))))
    }
}
