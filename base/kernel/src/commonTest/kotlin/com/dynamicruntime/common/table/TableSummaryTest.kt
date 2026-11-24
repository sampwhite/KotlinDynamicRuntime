package com.dynamicruntime.common.table

import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A table's column functions (issue #1883), on the JVM and in JS alike. */
class TableSummaryTest {
    private val columns = tableColumns(
        parseSchemaTypes(
            mapOf(
                "ns.Line" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf(
                        "day" to mapOf(SCH.type to SCT.string, SCH.format to SFMT.date),
                        "note" to mapOf(SCH.type to SCT.string),
                        "amount" to mapOf(SCH.type to SCT.number),
                        "count" to mapOf(SCH.type to SCT.integer),
                        "receipt" to mapOf(SCH.type to SCT.boolean),
                    ),
                ),
            ),
        ).getValue("ns.Line"),
    ).associateBy { it.name }

    private fun col(name: String) = columns.getValue(name)

    private val rows = listOf(
        mapOf("day" to LocalDate(2026, 11, 3), "note" to "taxi", "amount" to 12.5, "count" to 2L, "receipt" to true),
        mapOf("day" to LocalDate(2026, 11, 1), "amount" to 30L, "count" to 3L),
        mapOf("note" to " ", "receipt" to false),
    )

    @Test
    fun eachCombineSumsUpAColumn() {
        assertEquals(42.5, TableSummary.summarize(rows, col("amount"), ReportCombine.sum))
        assertEquals(21.25, TableSummary.summarize(rows, col("amount"), ReportCombine.avg))
        assertEquals(12.5, TableSummary.summarize(rows, col("amount"), ReportCombine.min))
        assertEquals(30L, TableSummary.summarize(rows, col("amount"), ReportCombine.max))
        assertEquals(LocalDate(2026, 11, 3), TableSummary.summarize(rows, col("day"), ReportCombine.max))
        assertEquals(LocalDate(2026, 11, 1), TableSummary.summarize(rows, col("day"), ReportCombine.min))
        assertEquals("taxi", TableSummary.summarize(rows, col("note"), ReportCombine.max))
    }

    @Test
    fun aCountCountsFilledInCellsOrRows() {
        // A blank cell is empty; false is a value.
        assertEquals(1L, TableSummary.summarize(rows, col("note"), ReportCombine.count))
        assertEquals(2L, TableSummary.summarize(rows, col("receipt"), ReportCombine.count))
        assertEquals(3L, TableSummary.summarize(rows, null, ReportCombine.count))
        assertEquals(0L, TableSummary.summarize(emptyList(), null, ReportCombine.count))
        assertEquals(0L, TableSummary.summarize(emptyList(), col("amount"), ReportCombine.count))
    }

    @Test
    fun wholeNumbersStayWholeAndOverflowFallsBack() {
        assertEquals(5L, TableSummary.summarize(rows, col("count"), ReportCombine.sum))
        // An average that comes out whole is whole.
        assertEquals(2L, TableSummary.summarize(listOf(mapOf("count" to 1L), mapOf("count" to 3L)), col("count"), ReportCombine.avg))
        val big = listOf(mapOf("count" to Long.MAX_VALUE), mapOf("count" to 10L))
        assertTrue(TableSummary.summarize(big, col("count"), ReportCombine.sum) is Double)
    }

    @Test
    fun decimalsAreRoundedToTheirPlaces() {
        val amount = col("amount")
        fun sum(vararg values: Double) = TableSummary.summarize(values.map { mapOf("amount" to it) }, amount, ReportCombine.sum)
        assertEquals(0.3, sum(0.1, 0.2))
        assertEquals(12.75, sum(12.5, 0.25))
        // Whole after rounding is whole.
        assertEquals(1L, sum(0.7, 0.3))
        // An average keeps its own places, without the noise.
        assertEquals(0.3333333333, TableSummary.summarize(listOf(mapOf("amount" to 1L), mapOf("amount" to 0L), mapOf("amount" to 0L)), amount, ReportCombine.avg))
        // An exponent says nothing of places: left as computed.
        assertEquals(1e-20 + 1.5, sum(1e-20, 1.5))
    }

    @Test
    fun nothingToCombineIsNoValue() {
        assertNull(TableSummary.summarize(emptyList(), col("amount"), ReportCombine.sum))
        assertNull(TableSummary.summarize(listOf(mapOf("note" to "x")), col("amount"), ReportCombine.max))
    }

    @Test
    fun aCombineMustFitItsColumn() {
        assertFalse(TableSummary.allows(col("note"), ReportCombine.sum))
        assertFalse(TableSummary.allows(col("receipt"), ReportCombine.max))
        assertFalse(TableSummary.allows(null, ReportCombine.sum))
        assertFalse(TableSummary.allows(col("amount"), ReportCombine.first))
        assertTrue(TableSummary.allows(col("day"), ReportCombine.min))
        assertNull(TableSummary.summarize(rows, col("note"), ReportCombine.sum))
        assertEquals(ReportKind.number, TableSummary.resultKind(col("note"), ReportCombine.count))
        assertEquals(ReportKind.date, TableSummary.resultKind(col("day"), ReportCombine.max))
        assertEquals(ReportCombine.sum, TableSummary.defaultFor(col("amount")))
        assertEquals(ReportCombine.count, TableSummary.defaultFor(col("day")))
    }

    @Test
    fun aTableReadIsSummedUpAsItsRowsAre() {
        val line = parseSchemaTypes(
            mapOf(
                "ns.L" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf("amount" to mapOf(SCH.type to SCT.number)),
                ),
            ),
        ).getValue("ns.L")
        val read = readTable(line, "amount\r\n1.10\r\n2.20\r\n\r\n")
        assertTrue(read.ok, read.problems.toString())
        // Rounded to the cells' most decimal places, so the noise of binary floating point is not stored.
        assertEquals(3.3, TableSummary.summarize(read.rows, tableColumns(line).single(), ReportCombine.sum))
        assertEquals(1.65, TableSummary.summarize(read.rows, tableColumns(line).single(), ReportCombine.avg))
        assertEquals(3L, TableSummary.summarize(read.rows, null, ReportCombine.count))
    }
}
