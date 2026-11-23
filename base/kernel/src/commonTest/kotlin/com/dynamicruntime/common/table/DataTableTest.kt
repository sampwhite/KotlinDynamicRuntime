package com.dynamicruntime.common.table

import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A CSV table read against its row type (issue #1882), on the JVM and in JS alike. */
class DataTableTest {
    private val line: SchType = parseSchemaTypes(
        mapOf(
            "ns.Line" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "day" to mapOf(SCH.type to SCT.string, SCH.format to SFMT.date, SCH.title to "Day"),
                    "category" to mapOf(
                        SCH.type to SCT.string,
                        SCH.options to listOf(mapOf(SCH.value to "meal", SCH.label to "Meal"), mapOf(SCH.value to "taxi", SCH.label to "Taxi")),
                    ),
                    "note" to mapOf(SCH.type to SCT.string),
                    "amount" to mapOf(SCH.type to SCT.number, SCH.minimum to 0, SCH.title to "Amount"),
                    "count" to mapOf(SCH.type to SCT.integer),
                    "receipt" to mapOf(SCH.type to SCT.boolean),
                ),
                SCH.required to listOf("day", "amount"),
                SCH.primaryKey to listOf("day", "amount"),
            ),
        ),
    ).getValue("ns.Line")

    private val columns = tableColumns(line).associateBy { it.name }
    private fun col(name: String) = columns.getValue(name)

    @Test
    fun columnsAreTheRowTypesProperties() {
        val all = tableColumns(line)
        assertEquals(listOf("day", "category", "note", "amount", "count", "receipt"), all.map { it.name })
        assertEquals("Amount", col("amount").label)
        assertEquals("note", col("note").label)
        assertEquals(listOf(true, false, false, true, false, false), all.map { it.required })
        assertEquals(
            listOf(ReportKind.date, ReportKind.string, ReportKind.string, ReportKind.number, ReportKind.number, ReportKind.boolean),
            all.map { it.kind },
        )
        assertTrue(col("amount").numeric && !col("amount").whole && col("count").whole)
        assertTrue(col("category").closedChoices && !col("note").closedChoices)
    }

    @Test
    fun numbersReadTheSameEverywhere() {
        assertEquals(12L, typedCell("12", col("amount")).value)
        assertEquals(12L, typedCell("12.0", col("amount")).value)
        assertEquals(0L, typedCell("-0", col("amount")).value)
        assertEquals(12.5, typedCell(" 12.5 ", col("amount")).value)
        assertEquals(1e21, typedCell("1e21", col("amount")).value)
        assertEquals("'7d' is not a number.", typedCell("7d", col("amount")).problem)
        assertEquals("'1,200' is not a number.", typedCell("1,200", col("amount")).problem)
        // A whole-number column takes a whole number written with a fraction, and nothing else.
        assertEquals(3L, typedCell("3.0", col("count")).value)
        assertEquals("'3.5' is not a whole number.", typedCell("3.5", col("count")).problem)
        // Whole, but too large to hold exactly, is said to be so.
        assertEquals("'1e21' is too large a whole number.", typedCell("1e21", col("count")).problem)
        assertEquals(
            "'12345678901234567890' is too large a whole number.",
            typedCell("12345678901234567890", col("count")).problem,
        )
        assertEquals(1.5e20, typedCell("150000000000000000000", col("amount")).value)
    }

    @Test
    fun otherKindsReadByTheirSpelling() {
        assertNull(typedCell("", col("amount")).value)
        assertNull(typedCell("  ", col("amount")).problem)
        assertEquals(LocalDate(2026, 11, 2), typedCell("2026-11-02", col("day")).value)
        assertEquals("'11/2/2026' is not a day (yyyy-MM-dd).", typedCell("11/2/2026", col("day")).problem)
        assertEquals(true, typedCell("yes", col("receipt")).value)
        assertEquals(false, typedCell("FALSE", col("receipt")).value)
        assertTrue(typedCell("maybe", col("receipt")).problem != null)
        // A choice by its value, else by its label, either ignoring case.
        assertEquals("taxi", typedCell("taxi", col("category")).value)
        assertEquals("meal", typedCell("Meal", col("category")).value)
        assertEquals("meal", typedCell("MEAL", col("category")).value)
        assertEquals("'hotel' is not one of the choices.", typedCell("hotel", col("category")).problem)
        // Free text is read without the spaces around it, as a form's field is.
        assertEquals("as typed", typedCell(" as typed ", col("note")).value)
    }

    @Test
    fun aColumnThatKeepsItsSpacesKeepsThem() {
        val kept = parseSchemaTypes(
            mapOf(
                "ns.Row" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf("code" to mapOf(SCH.type to SCT.string, SCH.outerWhitespace to "keep")),
                ),
            ),
        ).getValue("ns.Row")
        assertEquals(" A1 ", typedCell(" A1 ", tableColumns(kept).single()).value)
    }

    @Test
    fun storedTextNormalizesOnlyWhatHasASpelling() {
        assertEquals("meal", canonicalText("Meal", col("category")))
        assertEquals("true", canonicalText("Yes", col("receipt")))
        assertEquals("2026-11-02", canonicalText(" 2026-11-02 ", col("day")))
        // A number is kept as typed, never rounded through a format.
        assertEquals("12.50", canonicalText("12.50", col("amount")))
        assertEquals("0.1234567891", canonicalText("0.1234567891", col("amount")))
        assertEquals("abc", canonicalText("abc", col("amount")))
        assertEquals("", canonicalText("", col("amount")))
    }

    @Test
    fun readsATableWhoseHeaderIsInAnyOrder() {
        val read = readTable(line, "amount,day,category,receipt\r\n12.50,2026-11-02,Meal,yes\r\n30,2026-11-03,,\r\n")
        assertTrue(read.ok, read.problems.toString())
        assertEquals(listOf("amount", "day", "category", "receipt"), read.header)
        assertEquals(
            listOf(
                mapOf("amount" to 12.5, "day" to LocalDate(2026, 11, 2), "category" to "meal", "receipt" to true),
                mapOf("amount" to 30L, "day" to LocalDate(2026, 11, 3)),
            ),
            read.rows,
        )
    }

    @Test
    fun namesEachProblemByRowAndColumn() {
        val read = readTable(line, "day,amount,category\n2026-11-02,abc,meal\n2026-11-03,-4,meal\n,5,hotel\n")
        assertEquals(
            listOf(
                "Row 1, Amount: 'abc' is not a number.",
                "Row 2, Amount: ",
                "Row 3, category: 'hotel' is not one of the choices.",
            ),
            read.problems.map { if (it.message.startsWith("Row 2, Amount: ")) "Row 2, Amount: " else it.message },
        )
        assertEquals(listOf(1, 2, 3), read.problems.map { it.row })
        assertEquals(listOf("amount", "amount", "category"), read.problems.map { it.column })
        assertTrue(read.rows.isEmpty())
        // A required column left empty in a row.
        val missing = readTable(line, "day,amount\n,5\n").problems.single()
        assertEquals("day", missing.column)
        assertTrue(missing.message.startsWith("Row 1, Day: "), missing.message)
    }

    @Test
    fun theHeaderNamesTheColumns() {
        val unknown = readTable(line, "day,amount,tip\n2026-11-02,5,1\n").problems.single()
        assertTrue("'tip'" in unknown.message && "Its columns are: day, category" in unknown.message, unknown.message)
        assertTrue("no 'amount'" in readTable(line, "day\n2026-11-02\n").problems.single().message)
        // A header from the codec's own refusal is passed on.
        assertTrue("Line 2" in readTable(line, "day,amount\n2026-11-02\n").problems.single().message)
    }

    @Test
    fun aKeyDoesNotRepeat() {
        val read = readTable(line, "day,amount,note\n2026-11-02,5,a\n2026-11-03,5,b\n2026-11-02,5.0,c\n")
        assertEquals(listOf("Row 3 repeats row 1's Day, Amount, which tell rows apart."), read.problems.map { it.message })
    }

    @Test
    fun aTableHoldsAtMostItsRows() {
        val text = "day,amount\n" + (1..4).joinToString("") { "2026-11-0$it,$it\n" }
        assertTrue(readTable(line, text, maxRows = 4).ok)
        val over = readTable(line, text, maxRows = 3).problems.single()
        assertEquals("The table has 4 rows; this field holds at most 3.", over.message)
    }

    @Test
    fun reportsAtMostItsLimitOfProblems() {
        val text = "day,amount\n" + (1..50).joinToString("") { "2026-11-02,x$it\n" }
        assertEquals(DTAB.maxProblems, readTable(line, text).problems.size)
    }

    @Test
    fun writesATableItReadsBack() {
        val text = writeTable(line, listOf(listOf("2026-11-02", "meal", "a, \"b\"", "12.50", "", "true")))
        assertTrue(text.startsWith("${CSVT.bom}day,category,note,amount,count,receipt\r\n"))
        val read = readTable(line, text)
        assertTrue(read.ok, read.problems.toString())
        assertEquals("a, \"b\"", read.rows.single()["note"])
        assertTrue(checkTable(line, text).isEmpty())
    }
}
