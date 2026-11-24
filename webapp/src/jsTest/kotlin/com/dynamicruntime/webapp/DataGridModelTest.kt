package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.table.CSVT
import com.dynamicruntime.common.table.Csv
import com.dynamicruntime.common.table.readTable
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The data grid's model (issue #1884), and what the read-only table shows: pure, under Node. */
class DataGridModelTest {
    private val line: SchType = parseSchemaTypes(
        mapOf(
            "ns.Line" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "day" to mapOf(SCH.type to SCT.string, SCH.format to SFMT.date, SCH.title to "Day"),
                    "category" to mapOf(
                        SCH.type to SCT.string, SCH.title to "Category",
                        SCH.options to listOf(mapOf(SCH.value to "meal", SCH.label to "Meal"), mapOf(SCH.value to "taxi", SCH.label to "Taxi")),
                    ),
                    "amount" to mapOf(SCH.type to SCT.number, SCH.minimum to 0, SCH.title to "Amount"),
                    "receipt" to mapOf(SCH.type to SCT.boolean, SCH.title to "Receipt"),
                ),
                SCH.required to listOf("day", "amount"),
                SCH.primaryKey to listOf("day", "amount"),
            ),
        ),
    ).getValue("ns.Line")

    private fun grid(text: String? = "amount,day,category\r\n12.50,2026-11-02,meal\r\n30,2026-11-03,taxi\r\n", maxRows: Int = 5): GridState =
        loadGrid(line, maxRows, text?.let { Csv.parse(it) }, if (text == null) null else "gd.df.c.1")

    private fun GridState.cells(): List<List<String>> = rows.map { it.cells }

    @Test
    fun loadsAFileIntoTheFieldsColumns() {
        val g = grid()
        assertEquals(listOf("day", "category", "amount", "receipt"), g.columns.map { it.name })
        assertEquals(listOf(listOf("2026-11-02", "meal", "12.50", ""), listOf("2026-11-03", "taxi", "30", "")), g.cells())
        assertEquals(GridCell(0, 0), g.active)
        assertFalse(g.dirty)
        assertEquals("gd.df.c.1", g.baseFileId)
        assertTrue(g.rows.all { it.problems.isEmpty() })
        // A field with no file yet is an empty grid, with no cursor until it has a row.
        val empty = grid(null)
        assertEquals(0, empty.rows.size)
        assertNull(empty.active)
    }

    @Test
    fun movesAndSelects() {
        val g = grid()
        assertEquals(GridCell(1, 1), g.move(1, 1).active)
        // Held to the grid.
        assertEquals(GridCell(1, 3), g.move(9, 9).active)
        assertEquals(GridCell(0, 0), g.move(-1, -1).active)
        val stretched = g.extend(1, 2)
        assertEquals(GridBlock(0, 0, 1, 2), stretched.selection)
        // A move collapses it.
        assertEquals(GridBlock(1, 3, 1, 3), stretched.move(0, 1).selection)
        assertEquals(GridBlock(0, 1, 1, 2), g.moveTo(GridCell(1, 2)).extendTo(GridCell(0, 1)).selection)
    }

    @Test
    fun anEditIsCommittedAsStoredAndChecked() {
        val g = grid().moveTo(GridCell(0, 1)).startEdit("Taxi")
        assertEquals(GridEdit(GridCell(0, 1), "Taxi"), g.editing)
        val done = g.commitEdit(dRow = 1)
        // A choice's label is stored as its value; the cursor moves down.
        assertEquals("taxi", done.text(GridCell(0, 1)))
        assertEquals(GridCell(1, 1), done.active)
        assertNull(done.editing)
        assertTrue(done.dirty)
        // A bad value is kept as typed, and its row says what is wrong.
        val bad = done.moveTo(GridCell(1, 2)).startEdit().editText("abc").commitEdit()
        assertEquals("abc", bad.text(GridCell(1, 2)))
        assertEquals(mapOf(2 to "'abc' is not a number."), bad.rows[1].problems)
        assertEquals("Row 2, Amount: 'abc' is not a number.", bad.problems().single().message)
        // The validator's own rules too: a bound, and a required column emptied.
        val negative = grid().moveTo(GridCell(0, 2)).startEdit("-4").commitEdit()
        assertTrue(negative.rows[0].problems.containsKey(2))
        val cleared = grid().moveTo(GridCell(0, 0)).clear()
        assertTrue(cleared.rows[0].problems.containsKey(0))
    }

    @Test
    fun anEditCanBeAbandoned() {
        val g = grid().startEdit("x").cancelEdit()
        assertEquals("2026-11-02", g.text(GridCell(0, 0)))
        assertFalse(g.dirty)
        // Committing what was there changes nothing.
        assertFalse(grid().startEdit().commitEdit().dirty)
    }

    @Test
    fun enterOnTheLastRowAddsOneWhileTheGridMayHoldIt() {
        val g = grid(maxRows = 3).moveTo(GridCell(1, 0)).startEdit("2026-11-04").commitEdit(dRow = 1, addRowAtEnd = true)
        assertEquals(3, g.rows.size)
        assertEquals(GridCell(2, 0), g.active)
        val full = g.startEdit("2026-11-05").commitEdit(dRow = 1, addRowAtEnd = true)
        assertEquals(3, full.rows.size)
        assertEquals(GridCell(2, 0), full.active)
    }

    @Test
    fun clearsTheSelectedBlock() {
        val g = grid().extend(1, 1).clear()
        assertEquals(listOf(listOf("", "", "12.50", ""), listOf("", "", "30", "")), g.cells())
        assertTrue(g.dirty)
    }

    @Test
    fun addsAndRemovesRows() {
        val g = grid().addRow()
        assertEquals(3, g.rows.size)
        assertEquals(GridCell(1, 0), g.active)
        assertEquals(listOf("", "", "", ""), g.rows[1].cells)
        // Row ids stay with their rows.
        assertEquals(listOf(0, 2, 1), g.rows.map { it.id })
        val removed = g.moveTo(GridCell(0, 0)).extend(1, 0).removeRows()
        assertEquals(listOf(1), removed.rows.map { it.id })
        assertEquals(GridCell(0, 0), removed.active)
        assertNull(removed.removeRows().active)
        // At most the field's rows.
        assertEquals(2, grid(maxRows = 2).addRow().rows.size)
    }

    @Test
    fun pastesASpreadsheetsCells() {
        val tsv = "Day\tCategory\tAmount\n2026-11-05\tMeal\t7.25\n2026-11-06\ttaxi\t3\n2026-11-07\t\t1\textra\n"
        val outcome = grid(maxRows = 4).moveTo(GridCell(1, 0)).paste(tsv)
        assertEquals(0, outcome.droppedRows)
        val g = outcome.state
        // The header row skipped, rows added up to the field's limit, columns past the grid dropped.
        assertEquals(
            listOf(
                listOf("2026-11-02", "meal", "12.50", ""),
                listOf("2026-11-05", "meal", "7.25", ""),
                listOf("2026-11-06", "taxi", "3", ""),
                listOf("2026-11-07", "", "1", "extra"),
            ),
            g.cells(),
        )
        assertTrue(g.dirty)
        assertEquals(GridBlock(1, 0, 3, 3), g.selection)
        assertEquals("'extra' is not yes or no (true or false).", g.rows[3].problems[3])
        // A single value fills the cursor's cell.
        assertEquals("99", grid().moveTo(GridCell(1, 2)).paste("99").state.text(GridCell(1, 2)))
        // Text that is not cells changes nothing.
        val same = grid()
        assertEquals(same.cells(), same.paste("a\t\"b").state.cells())
    }

    @Test
    fun aPastePastTheFieldsRowsSaysHowManyDidNotFit() {
        val outcome = grid(maxRows = 3).moveTo(GridCell(1, 0)).paste("2026-11-05\tmeal\t1\n2026-11-06\tmeal\t2\n2026-11-07\tmeal\t3\n")
        assertEquals(3, outcome.state.rows.size)
        assertEquals(1, outcome.droppedRows)
        assertEquals(GridBlock(1, 0, 2, 2), outcome.state.selection)
    }

    @Test
    fun aRowKeepsItsTypedValues() {
        val g = grid()
        assertEquals(mapOf("day" to LocalDate(2026, 11, 2), "category" to "meal", "amount" to 12.5), g.rows[0].typed)
        // A cell with a problem, the validator's included, is not among them.
        assertEquals(mapOf("day" to LocalDate(2026, 11, 2), "category" to "meal"), g.moveTo(GridCell(0, 2)).startEdit("-1").commitEdit().rows[0].typed)
        assertEquals(mapOf("category" to "meal", "amount" to 12.5), g.moveTo(GridCell(0, 0)).startEdit("May 2").commitEdit().rows[0].typed.minus("day"))
    }

    @Test
    fun copiesTheBlockAsASpreadsheetTakesIt() {
        assertEquals("2026-11-02\tmeal\r\n2026-11-03\ttaxi\r\n", grid().extend(1, 1).copyText())
        val copied = grid().extend(1, 1).copyText()
        assertEquals(listOf(listOf("2026-11-02", "meal"), listOf("2026-11-03", "taxi")), Csv.parse(copied, CSVT.tab, header = false).rows)
    }

    @Test
    fun sumsUpEachColumnAsASaveWould() {
        val g = grid()
        assertEquals(mapOf("day" to 2L, "category" to 2L, "amount" to 42.5, "receipt" to 0L), g.summaries())
        assertEquals(LocalDate(2026, 11, 3), g.summaries(mapOf("day" to ReportCombine.max))["day"])
        // A combine the column does not allow falls back to its default.
        assertEquals(2L, g.summaries(mapOf("category" to ReportCombine.sum))["category"])
        // A cell with a problem counts for nothing.
        val bad = g.moveTo(GridCell(1, 2)).startEdit("abc").commitEdit()
        assertEquals(12.5, bad.summaries()["amount"])
    }

    @Test
    fun aRepeatedKeyIsAProblem() {
        val g = grid().moveTo(GridCell(1, 0)).startEdit("2026-11-02").commitEdit().moveTo(GridCell(1, 2)).startEdit("12.5").commitEdit()
        assertEquals(listOf("Row 2 repeats row 1's Day, Amount, which tell rows apart."), g.problems().map { it.message })
    }

    @Test
    fun writesWhatASaveStores() {
        val text = grid().addRow().moveTo(GridCell(1, 0)).paste("2026-11-09\ttaxi\t4\tyes").state.csvText()
        val read = readTable(line, text)
        assertTrue(read.ok, read.problems.toString())
        assertEquals(3, read.rows.size)
        assertEquals(true, read.rows[1]["receipt"])
    }

    @Test
    fun aTableShowsChoicesAndYesOrNoInWords() {
        val columns = grid().columns.associateBy { it.name }
        assertEquals("Meal", tableCellText("meal", columns.getValue("category")))
        assertEquals("Yes", tableCellText("true", columns.getValue("receipt")))
        // Any spelling a save reads as a yes or a no.
        assertEquals("Yes", tableCellText("Y", columns.getValue("receipt")))
        assertEquals("No", tableCellText("0", columns.getValue("receipt")))
        assertEquals("maybe", tableCellText("maybe", columns.getValue("receipt")))
        assertEquals("12.50", tableCellText("12.50", columns.getValue("amount")))
        assertEquals("Sum 42.5", tableSummaryText(columns.getValue("amount"), 42.5))
        assertEquals("Sum 30", tableSummaryText(columns.getValue("amount"), 30.0))
        assertEquals("Count 2", tableSummaryText(columns.getValue("day"), 2L))
        assertEquals("", tableSummaryText(columns.getValue("amount"), null))
        assertEquals("1 row", tableRowCountText(1))
        assertEquals("3 rows", tableRowCountText(3))
    }

    @Test
    fun theKeysMoveSelectAndEdit() {
        val g = grid()
        assertEquals(GridCell(1, 0), g.onGridKey("ArrowDown", shift = false, ctrl = false)?.active)
        assertEquals(GridBlock(0, 0, 0, 1), g.onGridKey("ArrowRight", shift = true, ctrl = false)?.selection)
        assertEquals(GridCell(0, 3), g.onGridKey("End", shift = false, ctrl = false)?.active)
        assertEquals(GridCell(1, 3), g.onGridKey("End", shift = false, ctrl = true)?.active)
        assertEquals(GridCell(1, 0), g.onGridKey("PageDown", shift = false, ctrl = false)?.active)
        assertEquals(GridCell(0, 0), g.move(1, 2).onGridKey("Home", shift = false, ctrl = true)?.active)
        assertEquals(GridEdit(GridCell(0, 0), "2026-11-02"), g.onGridKey("F2", shift = false, ctrl = false)?.editing)
        // A character typed replaces the cell.
        assertEquals("7", g.moveTo(GridCell(0, 2)).onGridKey("7", shift = false, ctrl = false)?.editing?.text)
        assertEquals("", g.onGridKey("Delete", shift = false, ctrl = false)?.text(GridCell(0, 0)))
        // Space turns a yes or no.
        assertEquals("true", g.moveTo(GridCell(0, 3)).onGridKey(" ", shift = false, ctrl = false)?.text(GridCell(0, 3)))
        assertEquals("false", g.moveTo(GridCell(0, 3)).toggleBoolean().toggleBoolean().text(GridCell(0, 3)))
        // Tab, and a key with Ctrl, are left to the browser.
        assertNull(g.onGridKey("Tab", shift = false, ctrl = false))
        assertNull(g.onGridKey("c", shift = false, ctrl = true))
    }

    @Test
    fun theEditorsKeysCommitMoveOrDrop() {
        val g = grid().moveTo(GridCell(0, 2)).startEdit("5")
        assertEquals(GridCell(1, 2), g.onEditorKey("Enter", shift = false)?.active)
        assertEquals(GridCell(0, 3), g.onEditorKey("Tab", shift = false)?.active)
        assertEquals(GridCell(0, 1), g.onEditorKey("Tab", shift = true)?.active)
        assertEquals("12.50", g.onEditorKey("Escape", shift = false)?.text(GridCell(0, 2)))
        assertEquals("5", g.onEditorKey("Enter", shift = false)?.text(GridCell(0, 2)))
        assertNull(g.onEditorKey("a", shift = false))
    }

    @Test
    fun aSaveRebasesTheGrid() {
        val changed = grid().moveTo(GridCell(0, 2)).startEdit("5").commitEdit()
        val saved = changed.savedAs("gd.df.c.9")
        assertFalse(saved.dirty)
        assertEquals("gd.df.c.9", saved.baseFileId)
        val rebased = changed.rebasedOn("gd.df.c.9")
        assertTrue(rebased.dirty)
        assertEquals("gd.df.c.9", rebased.baseFileId)
        assertEquals("Count", combineLabel(ReportCombine.count))
        assertEquals("42.5", summaryValueText(42.5))
        assertEquals("30", summaryValueText(30.0))
        assertEquals("2 pasted rows did not fit: this field holds at most 5 rows.", pasteDroppedText(2, 5))
        assertNull(pasteDroppedText(0, 5))
        assertEquals("3 rows · at most 5", gridRowCountText(3, 5))
    }

    @Test
    fun aClickElsewhereCommitsTheOpenEdit() {
        val editing = grid().moveTo(GridCell(1, 2)).startEdit().editText("45")
        val clicked = editing.clickCell(GridCell(0, 1), shift = false)
        assertEquals("45", clicked.text(GridCell(1, 2)))
        assertNull(clicked.editing)
        assertEquals(GridCell(0, 1), clicked.active)
        assertTrue(clicked.dirty)
        // A double click elsewhere commits too, then edits there.
        val opened = editing.openCell(GridCell(0, 0))
        assertEquals("45", opened.text(GridCell(1, 2)))
        assertEquals(GridEdit(GridCell(0, 0), "2026-11-02"), opened.editing)
        assertEquals(GridBlock(0, 0, 1, 2), editing.clickCell(GridCell(0, 0), shift = true).selection)
    }

    @Test
    fun anOpenEditThatChangedACellIsUnsaved() {
        val g = grid()
        assertFalse(g.unsaved)
        assertFalse(g.startEdit().unsaved)
        assertTrue(g.startEdit().editText("2026-11-09").unsaved)
        assertTrue(g.startEdit("x").committed().dirty)
        assertTrue(g.committed() === g)
    }
}
