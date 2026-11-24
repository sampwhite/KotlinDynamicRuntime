package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.schema.uniquenessKey
import com.dynamicruntime.common.table.CSVT
import com.dynamicruntime.common.table.Csv
import com.dynamicruntime.common.table.CsvParsed
import com.dynamicruntime.common.table.TableColumn
import com.dynamicruntime.common.table.TableSummary
import com.dynamicruntime.common.table.canonicalText
import com.dynamicruntime.common.table.tableColumns
import com.dynamicruntime.common.table.typedCell
import com.dynamicruntime.common.table.writeTable

/**
 * The data grid's model (issue #1884), the pure half of the grid a table field is edited with (#1885): its rows as
 * text, where the cursor and the selection are, the cell being edited, and every change a key or a paste makes --
 * each an immutable [GridState] in, a new one out, so a change is one function a test can call. Covered under
 * `jsNodeTest`; the component is `DataGrid.kt`.
 *
 * **A cell's text is its stored value**: what the CSV will hold, as typed, a choice by its value and a boolean and a
 * day in their own spellings ([canonicalText]). What a cell means -- its typed value, its problem -- is derived from
 * the text and kept on the row ([GridRow.typed], [GridRow.problems]), worked out when the row changes, so an edit
 * re-checks one row, and the footer and the problem list read what is kept rather than reading every cell again.
 *
 * Theme: #DTABL.
 */

/** A cell's place: its row, by index from 0 among the rows, and its column, by index among the columns. */
data class GridCell(val row: Int, val col: Int)

/** The cell being edited, [at], and the text in its editor, not yet committed. */
data class GridEdit(val at: GridCell, val text: String)

/**
 * One row of the grid: a [id] that stays with it however rows move (a React key), its [cells]' text, its [problems] by
 * column index, and its [typed] values by column name -- each cell read as its column's type, an empty cell and one
 * with a problem left out: what the footer sums up and a key is compared by.
 */
class GridRow(val id: Int, val cells: List<String>, val problems: Map<Int, String>, val typed: Map<String, Any?>)

/**
 * A paste's outcome ([GridState.paste]): the grid after it, and how many pasted rows did not fit -- past the most rows
 * the field holds -- which the grid says rather than dropping them unseen.
 */
class GridPaste(val state: GridState, val droppedRows: Int)

/** The selected block: rows [top] to [bottom] and columns [left] to [right], each inclusive. */
data class GridBlock(val top: Int, val left: Int, val bottom: Int, val right: Int) {
    fun contains(cell: GridCell): Boolean = cell.row in top..bottom && cell.col in left..right
}

/** One problem of the grid, for the list under it: the [cell] it is in, when it is in one, and its [message]. */
class GridProblem(val cell: GridCell?, val message: String)

/**
 * The grid: its [rowType] and [columns], its [rows], the [active] cell (null while there are none), the [anchor] a
 * selection stretches from, the cell being edited ([editing]), whether anything changed since it was loaded ([dirty]),
 * the file it was loaded from ([baseFileId], null for a field with none), and the most rows it may hold ([maxRows]).
 */
class GridState(
    val rowType: SchType,
    val columns: List<TableColumn>,
    val rows: List<GridRow>,
    val active: GridCell?,
    val anchor: GridCell?,
    val editing: GridEdit?,
    val dirty: Boolean,
    val baseFileId: String?,
    val maxRows: Int,
    /** The id the next new row gets. */
    val nextId: Int,
) {
    fun copy(
        rows: List<GridRow> = this.rows,
        active: GridCell? = this.active,
        anchor: GridCell? = this.anchor,
        editing: GridEdit? = this.editing,
        dirty: Boolean = this.dirty,
        nextId: Int = this.nextId,
    ): GridState = GridState(rowType, columns, rows, active, anchor, editing, dirty, baseFileId, maxRows, nextId)

    /** The selected block: from [anchor] to [active], or just [active]; null while there is no cell. */
    val selection: GridBlock?
        get() {
            val a = active ?: return null
            val b = anchor ?: a
            return GridBlock(minOf(a.row, b.row), minOf(a.col, b.col), maxOf(a.row, b.row), maxOf(a.col, b.col))
        }

    /** The text of the cell at [cell], or empty when there is none. */
    fun text(cell: GridCell): String = rows.getOrNull(cell.row)?.cells?.getOrNull(cell.col).orEmpty()

    /** Whether another row may be added: fewer than [maxRows]. */
    val canAddRow: Boolean get() = rows.size < maxRows
}

/**
 * A row of [cells] for [rowType]'s [columns], read once: each cell as its column's type ([typedCell]), then -- when every
 * cell reads -- the row checked whole against [rowType] by the validator, as a save checks it. A failure the validator
 * does not place in a column is put on the first. The typed values leave out each column with a problem.
 */
fun gridRow(id: Int, cells: List<String>, rowType: SchType, columns: List<TableColumn>): GridRow {
    val problems = LinkedHashMap<Int, String>()
    val typed = LinkedHashMap<String, Any?>()
    columns.forEachIndexed { at, column ->
        val read = typedCell(cells.getOrNull(at).orEmpty(), column)
        if (read.problem != null) problems[at] = read.problem!! else if (read.value != null) typed[column.name] = read.value
    }
    if (problems.isEmpty()) {
        for (failure in coerceAndValidate(rowType, typed).failures) {
            val name = failure.path.substringBefore('.').substringBefore('[')
            val at = columns.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: 0
            if (at !in problems) problems[at] = failure.userMessage ?: failure.message
        }
        problems.keys.forEach { typed.remove(columns[it].name) }
    }
    return GridRow(id, cells, problems, typed)
}

/**
 * The grid of a table field whose row type is [rowType], loaded from [parsed] -- its file's CSV, read with its header --
 * or empty when the field has no file yet ([parsed] null). The header names the columns in any order, and a column it
 * does not name is empty in every row; a name that is no column is dropped, which a save would refuse. [baseFileId] is
 * the file it came from.
 */
fun loadGrid(rowType: SchType, maxRows: Int, parsed: CsvParsed?, baseFileId: String?): GridState {
    val columns = tableColumns(rowType)
    val at = parsed?.header?.map { name -> columns.indexOfFirst { it.name == name } }.orEmpty()
    val rows = parsed?.rows.orEmpty().mapIndexed { id, cells ->
        val ordered = MutableList(columns.size) { "" }
        cells.forEachIndexed { i, cell -> at.getOrNull(i)?.takeIf { it >= 0 }?.let { ordered[it] = cell } }
        gridRow(id, ordered, rowType, columns)
    }
    val first = if (rows.isEmpty() || columns.isEmpty()) null else GridCell(0, 0)
    return GridState(rowType, columns, rows, first, first, null, false, baseFileId, maxRows, rows.size)
}

private fun GridState.clamp(cell: GridCell): GridCell? {
    if (rows.isEmpty() || columns.isEmpty()) return null
    return GridCell(cell.row.coerceIn(0, rows.size - 1), cell.col.coerceIn(0, columns.size - 1))
}

/** The cursor moved by [dRow] rows and [dCol] columns, held to the grid, the selection collapsed to it. */
fun GridState.move(dRow: Int, dCol: Int): GridState {
    val from = active ?: return this
    val to = clamp(GridCell(from.row + dRow, from.col + dCol))
    return copy(active = to, anchor = to, editing = null)
}

/** The cursor put on [cell], held to the grid, the selection collapsed to it. */
fun GridState.moveTo(cell: GridCell): GridState {
    val to = clamp(cell)
    return copy(active = to, anchor = to, editing = null)
}

/** The selection stretched by moving the cursor [dRow] rows and [dCol] columns, the anchor where it was. */
fun GridState.extend(dRow: Int, dCol: Int): GridState {
    val from = active ?: return this
    return copy(active = clamp(GridCell(from.row + dRow, from.col + dCol)), anchor = anchor ?: from, editing = null)
}

/** The selection stretched to [cell], from the anchor. */
fun GridState.extendTo(cell: GridCell): GridState {
    val from = active ?: return moveTo(cell)
    return copy(active = clamp(cell), anchor = anchor ?: from, editing = null)
}

/** Editing the active cell, its editor starting with [initial] -- a key typed, which replaces the cell -- or its text. */
fun GridState.startEdit(initial: String? = null): GridState {
    val at = active ?: return this
    return copy(editing = GridEdit(at, initial ?: text(at)), anchor = at)
}

/** The editor's text changed to [text]. */
fun GridState.editText(text: String): GridState = editing?.let { copy(editing = it.copy(text = text)) } ?: this

/** The edit abandoned: the cell keeps what it had. */
fun GridState.cancelEdit(): GridState = copy(editing = null)

/**
 * The edit committed: its text, as stored ([canonicalText]), put in its cell, the row checked again, and then the
 * cursor moved [dRow] rows and [dCol] columns. With [addRowAtEnd], committing on the last row and moving down adds a
 * row, while the grid may hold another.
 */
fun GridState.commitEdit(dRow: Int = 0, dCol: Int = 0, addRowAtEnd: Boolean = false): GridState {
    val edit = editing ?: return this
    val changed = setCells(mapOf(edit.at to edit.text)).copy(editing = null)
    val pastEnd = dRow > 0 && edit.at.row == changed.rows.size - 1
    if (addRowAtEnd && pastEnd && changed.canAddRow) {
        return changed.addRow(after = edit.at.row).moveTo(GridCell(edit.at.row + 1, edit.at.col))
    }
    return changed.moveTo(GridCell(edit.at.row + dRow, edit.at.col + dCol))
}

/** Every selected cell emptied. */
fun GridState.clear(): GridState {
    val block = selection ?: return this
    val cells = (block.top..block.bottom).flatMap { r -> (block.left..block.right).map { c -> GridCell(r, c) to "" } }.toMap()
    return setCells(cells).copy(editing = null)
}

/**
 * The cells of [changes] set, each to its stored text ([canonicalText]), and each row they touch checked again. Cells
 * past the grid are ignored. Dirty when any cell changed.
 */
fun GridState.setCells(changes: Map<GridCell, String>): GridState {
    if (changes.isEmpty()) return this
    val byRow = changes.entries.groupBy { it.key.row }
    var changed = false
    val next = rows.mapIndexed { r, row ->
        val edits = byRow[r] ?: return@mapIndexed row
        val cells = row.cells.toMutableList()
        for ((cell, text) in edits) {
            val column = columns.getOrNull(cell.col) ?: continue
            val stored = canonicalText(text, column)
            if (cells[cell.col] != stored) {
                cells[cell.col] = stored
                changed = true
            }
        }
        if (cells == row.cells) row else gridRow(row.id, cells, rowType, columns)
    }
    return if (changed) copy(rows = next, dirty = true) else this
}

/**
 * An empty row added after row [after] -- the cursor's when not given -- or at the end when there is none, while the grid
 * may hold another ([GridState.canAddRow]). The cursor goes to it.
 */
fun GridState.addRow(after: Int? = null): GridState {
    if (!canAddRow) return this
    val at = ((after ?: active?.row ?: (rows.size - 1)) + 1).coerceIn(0, rows.size)
    val row = gridRow(nextId, List(columns.size) { "" }, rowType, columns)
    val next = rows.toMutableList().apply { add(at, row) }
    val cell = if (columns.isEmpty()) null else GridCell(at, active?.col ?: 0)
    return copy(rows = next, active = cell, anchor = cell, editing = null, dirty = true, nextId = nextId + 1)
}

/** The selected rows removed -- every row the selection touches -- and the cursor kept where it was, or the row above. */
fun GridState.removeRows(): GridState {
    val block = selection ?: return this
    val next = rows.filterIndexed { r, _ -> r !in block.top..block.bottom }
    val kept = copy(rows = next, editing = null, dirty = true)
    val cell = if (next.isEmpty() || columns.isEmpty()) null else GridCell(minOf(block.top, next.size - 1), block.left)
    return kept.copy(active = cell, anchor = cell)
}

/**
 * Whether [cells], a pasted row, is a header: each cell names, in order, a column -- by its name or its label, ignoring
 * case and the spaces around it -- and at least one cell is not empty.
 */
fun isHeaderRow(cells: List<String>, columns: List<TableColumn>, fromCol: Int): Boolean =
    cells.any { it.isNotBlank() } && cells.withIndex().all { (i, cell) ->
        val column = columns.getOrNull(fromCol + i) ?: return@all true
        val text = cell.trim()
        text.isEmpty() || text.equals(column.name, ignoreCase = true) || text.equals(column.label, ignoreCase = true)
    }

/**
 * [text] -- a spreadsheet's clipboard, tab-separated -- pasted from the top left of the selection: each row into a grid
 * row, rows added as needed while the grid may hold them, columns past the grid's last dropped, and a first row that
 * names the columns ([isHeaderRow]) skipped. Text that is not tab-separated cells, or none at all, changes nothing; a
 * single value fills only the cursor's cell. The cursor and selection then cover what was pasted, and the outcome says
 * how many rows did not fit ([GridPaste.droppedRows]).
 */
fun GridState.paste(text: String): GridPaste {
    val parsed = Csv.parse(text, CSVT.tab, header = false)
    if (!parsed.ok || columns.isEmpty()) return GridPaste(this, 0)
    val start = selection?.let { GridCell(it.top, it.left) } ?: GridCell(rows.size, 0)
    val lines = parsed.rows.let { if (it.isNotEmpty() && isHeaderRow(it.first(), columns, start.col)) it.drop(1) else it }
    if (lines.isEmpty()) return GridPaste(this, 0)
    var state = this
    // Rows enough for the paste, as many as the grid may hold.
    while (state.rows.size < start.row + lines.size && state.canAddRow) {
        state = state.addRow(after = state.rows.size - 1)
    }
    val changes = LinkedHashMap<GridCell, String>()
    lines.forEachIndexed { i, cells ->
        val r = start.row + i
        if (r >= state.rows.size) return@forEachIndexed
        cells.forEachIndexed { j, cell ->
            val c = start.col + j
            if (c < columns.size) changes[GridCell(r, c)] = cell
        }
    }
    val pasted = state.setCells(changes).copy(editing = null)
    val lastRow = minOf(start.row + lines.size, pasted.rows.size) - 1
    val lastCol = minOf(start.col + lines.maxOf { it.size }, columns.size) - 1
    val dropped = (start.row + lines.size - pasted.rows.size).coerceAtLeast(0)
    return GridPaste(pasted.copy(active = GridCell(lastRow, lastCol), anchor = start), dropped)
}

/** The selected block's text, tab-separated, as a spreadsheet takes a paste: what Copy puts on the clipboard. */
fun GridState.copyText(): String {
    val block = selection ?: return ""
    val lines = (block.top..block.bottom).map { r -> (block.left..block.right).map { c -> text(GridCell(r, c)) } }
    return Csv.write(lines, CSVT.tab, bom = false)
}

/** The rows as their values, by column name, as a summary reads them: each row's kept [GridRow.typed]. */
fun GridState.typedRows(): List<Map<String, Any?>> = rows.map { it.typed }

/**
 * Each column's summary for the grid's footer: [combines] by column name -- a column not named gets its default
 * ([TableSummary.defaultFor]) -- computed by the kernel ([TableSummary.summarize]), as a save stores one. Null where
 * there is nothing to sum up.
 */
fun GridState.summaries(combines: Map<String, ReportCombine> = emptyMap()): Map<String, Any?> {
    val typed = typedRows()
    return columns.associate { column ->
        val combine = combines[column.name]?.takeIf { TableSummary.allows(column, it) } ?: TableSummary.defaultFor(column)
        column.name to TableSummary.summarize(typed, column, combine)
    }
}

/**
 * Every problem of the grid, in order -- each row's, then each repeat of the row type's key -- at most [limit] of them:
 * the list under the grid, whose first is where a save that cannot go sends the cursor.
 */
fun GridState.problems(limit: Int = 10): List<GridProblem> {
    val out = ArrayList<GridProblem>()
    rows.forEachIndexed { r, row ->
        for ((c, message) in row.problems) {
            if (out.size >= limit) return out
            out.add(GridProblem(GridCell(r, c), "Row ${r + 1}, ${columns[c].label}: $message"))
        }
    }
    val key = rowType.primaryKey
    if (key.isNotEmpty()) {
        val seen = HashMap<String, Int>()
        rows.forEachIndexed { r, row ->
            if (row.problems.isNotEmpty()) return@forEachIndexed
            val k = key.joinToString("|") { uniquenessKey(row.typed[it]) }
            val first = seen[k]
            if (first == null) {
                seen[k] = r
            } else if (out.size < limit) {
                val labels = key.map { name -> columns.firstOrNull { it.name == name }?.label ?: name }
                out.add(GridProblem(GridCell(r, 0), "Row ${r + 1} repeats row ${first + 1}'s ${labels.joinToString(", ")}, which tell rows apart."))
            }
        }
    }
    return out
}

/** The grid as its table's CSV, as a save stores it ([writeTable]): a header of the column names, then each row's cells. */
fun GridState.csvText(): String = writeTable(rowType, rows.map { it.cells })

/** The grid's keyboard numbers (issue #1885). Each name matches its value. */
@Suppress("ConstPropertyName")
object GRIDK {
    /** How many rows Page Up and Page Down move. */
    const val pageRows = 20
}

/** This grid, its field's table saved as [fileId]: the new base, with nothing unsaved. */
fun GridState.savedAs(fileId: String): GridState =
    GridState(rowType, columns, rows, active, anchor, null, false, fileId, maxRows, nextId)

/** This grid on a new base file, [fileId], its unsaved rows kept: rows typed while the last save went up. */
fun GridState.rebasedOn(fileId: String): GridState =
    GridState(rowType, columns, rows, active, anchor, editing, dirty, fileId, maxRows, nextId)

/** The active cell's yes or no turned the other way, when its column is a yes or no; an empty one becomes yes. */
fun GridState.toggleBoolean(): GridState {
    val at = active ?: return this
    val column = columns.getOrNull(at.col) ?: return this
    if (column.kind != ReportKind.boolean) return this
    val now = typedCell(text(at), column).value as? Boolean
    return setCells(mapOf(at to (now != true).toString()))
}

/**
 * A key pressed on the grid while no cell is edited, as `KeyboardEvent.key` names it, or null when the grid does not
 * take it -- a Tab among them, which leaves the grid, its one stop in the page's order:
 *
 * - the arrows move the cursor, and with Shift stretch the selection;
 * - Home and End go to the row's first and last cell, and with Ctrl (or ⌘) to the grid's; Page Up and Page Down move
 *   [GRIDK.pageRows] rows;
 * - Enter and F2 edit the cell, and a character typed starts an edit with it, replacing what was there;
 * - Delete and Backspace empty the selection; Space turns a yes or no the other way.
 */
fun GridState.onGridKey(key: String, shift: Boolean, ctrl: Boolean): GridState? {
    val at = active ?: return null
    fun go(cell: GridCell) = if (shift) extendTo(cell) else moveTo(cell)
    return when (key) {
        "ArrowUp" -> if (shift) extend(-1, 0) else move(-1, 0)
        "ArrowDown" -> if (shift) extend(1, 0) else move(1, 0)
        "ArrowLeft" -> if (shift) extend(0, -1) else move(0, -1)
        "ArrowRight" -> if (shift) extend(0, 1) else move(0, 1)
        "Home" -> go(if (ctrl) GridCell(0, 0) else GridCell(at.row, 0))
        "End" -> go(if (ctrl) GridCell(rows.size - 1, columns.size - 1) else GridCell(at.row, columns.size - 1))
        "PageUp" -> go(GridCell(at.row - GRIDK.pageRows, at.col))
        "PageDown" -> go(GridCell(at.row + GRIDK.pageRows, at.col))
        "Enter", "F2" -> startEdit()
        "Delete", "Backspace" -> clear()
        " " -> if (columns.getOrNull(at.col)?.kind == ReportKind.boolean) toggleBoolean() else startEdit(key)
        else -> if (key.length == 1 && !ctrl) startEdit(key) else null
    }
}

/**
 * A key pressed in a cell's editor, or null when the editor keeps it (a character, an arrow within the text): Enter
 * commits and moves down -- adding a row on the last, while the grid may hold one -- and Shift+Enter up; Tab commits and
 * moves right, Shift+Tab left; Escape drops the edit.
 */
fun GridState.onEditorKey(key: String, shift: Boolean): GridState? = when (key) {
    "Enter" -> if (shift) commitEdit(dRow = -1) else commitEdit(dRow = 1, addRowAtEnd = true)
    "Tab" -> commitEdit(dCol = if (shift) -1 else 1)
    "Escape" -> cancelEdit()
    else -> null
}
