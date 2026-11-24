package com.dynamicruntime.webapp

import com.dynamicruntime.common.filekind.FileKinds
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.tableRowLimit
import com.dynamicruntime.common.table.TableColumn
import com.dynamicruntime.common.table.TableSummary
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.StateSetter
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.tfoot
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.dom.html.ReactHTML.ul
import react.memo
import react.use
import react.useEffect
import react.useMemo
import react.useRef
import react.useState
import web.cssom.ClassName
import web.html.HTMLDivElement

/**
 * The data grid (issue #1885): a table field's rows edited in the page, as a spreadsheet is, and saved with the form. Its
 * rules are the pure model's ([GridState], `DataGridModel.kt`); this is the drawing and the wiring.
 *
 * - **One stop in the page's Tab order**: the grid box takes the keys ([onGridKey]) and says which cell is active
 *   (`aria-activedescendant`); Tab leaves it. A cell's editor takes its own ([onEditorKey]).
 * - **Copy and paste** go through the clipboard's own events, as tab-separated text a spreadsheet reads and writes.
 * - **Saved with the form**: the grid registers with the page's [TableDrafts], which flushes it on Save -- a new CSV
 *   file uploaded, which the page then names in the field. Outside a form surface (no [TableDraftsContext]) a table
 *   field is uploaded as a file instead ([DataFileField]).
 * - **Rows are drawn by a memoized row component**, so an edit redraws its own row, and a thousand rows stay quick.
 *
 * Theme: #DTABL.
 */

private val gridScope = MainScope()

/** How many grids this page has drawn: each one's own prefix for its cells' ids. */
private var gridsMade = 0

/** What a column's summary function is called in the footer's picker. */
fun combineLabel(combine: ReportCombine): String = when (combine) {
    ReportCombine.count -> "Count"
    ReportCombine.sum -> "Sum"
    ReportCombine.avg -> "Average"
    ReportCombine.min -> "Least"
    ReportCombine.max -> "Greatest"
    else -> combine.name
}

/** A summary's value in words, without its function: a whole number whole, a day as it is. */
fun summaryValueText(value: Any?): String = when (value) {
    null -> "—"
    is Double -> wholeNumberTextOrNull(value) ?: value.toString()
    else -> value.toString()
}

/** What a paste that did not fit says: how many rows were left out, and why. */
fun pasteDroppedText(dropped: Int, maxRows: Int): String? = when (dropped) {
    0 -> null
    1 -> "1 pasted row did not fit: this field holds at most $maxRows rows."
    else -> "$dropped pasted rows did not fit: this field holds at most $maxRows rows."
}

/** The rows a grid holds, in words, with its limit. */
fun gridRowCountText(rows: Int, maxRows: Int): String = "${tableRowCountText(rows)} · at most $maxRows"

external interface DataGridProps : Props {
    /** The table field's schema. */
    var type: SchType

    /** The field's file id, or null for a table not saved yet. */
    var value: String?

    /** The field's path in its form's values, which the page sets the saved file at. */
    var path: String

    /** What the field is called, for a problem that stops a save. */
    var label: String?
    var describedBy: String?
    var onEmit: (String?) -> Unit
    var onCommit: (() -> Unit)?
}

/** A table field edited as a grid, or -- outside a form surface, or with no columns -- uploaded as a file. */
val DataGrid = FC<DataGridProps> { props ->
    val drafts = use(TableDraftsContext)
    if (drafts == null || props.type.fileRowType == null) {
        DataFileField {
            type = props.type
            value = props.value
            describedBy = props.describedBy
            onEmit = props.onEmit
            onCommit = props.onCommit
        }
        return@FC
    }
    GridEditor {
        type = props.type
        value = props.value
        path = props.path
        label = props.label
        describedBy = props.describedBy
        this.drafts = drafts
    }
}

private external interface GridEditorProps : Props {
    var type: SchType
    var value: String?
    var path: String
    var label: String?
    var describedBy: String?
    var drafts: TableDrafts
}

/** What a row's cells do, the same object across renders, so the memoized rows are not redrawn for a new callback. */
private class GridHandlers(
    val down: (GridCell, Boolean) -> Unit,
    val open: (GridCell) -> Unit,
    val editText: (String) -> Unit,
    val editorKey: (String, Boolean) -> Boolean,
    val commit: () -> Unit,
    val choose: (String) -> Unit,
)

private val GridEditor = FC<GridEditorProps> { props ->
    val scope = use(TableDraftsScopeContext)
    val rowType = props.type.fileRowType!!
    val maxRows = props.type.tableRowLimit()
    val gridId = useMemo(*emptyArray<Any>()) { "data-grid-${++gridsMade}" }
    val gridState = useState<GridState?>(null)
    val grid = gridState.component1()
    val setGrid: StateSetter<GridState?> = gridState.component2()
    var loadError by useState<DisplayError?>(null)
    var note by useState<String?>(null)
    var combines by useState(emptyMap<String, ReportCombine>())
    val reloadState = useState(0)
    val reloadAsk = reloadState.component1()
    val setReloadAsk = reloadState.component2()
    val latest = useRef<GridState>(null)
    latest.current = grid
    val box = useRef<HTMLDivElement>(null)
    // What the grid holds is the field's file at this reload: a file the grid itself saved is not read back.
    val loadedFor = useRef<String>(null)

    fun focusBox() {
        box.current?.focus()
    }

    // Loads the field's file -- or starts empty -- when the field names another file than the grid holds, or on a Reset.
    useEffect(props.value, reloadAsk) {
        val id = props.value
        val wanted = "$id|$reloadAsk"
        if (loadedFor.current == wanted) return@useEffect
        loadedFor.current = wanted
        loadError = null
        note = null
        if (id == null) {
            setGrid(loadGrid(rowType, maxRows, null, null))
            return@useEffect
        }
        gridScope.launch {
            try {
                val parsed = DataTableApi.read(id)
                if (loadedFor.current != wanted) return@launch
                if (!parsed.ok) {
                    loadError = DisplayError.expected("The field's file is not a table that can be read: ${parsed.problems.first().message}")
                    return@launch
                }
                setGrid(loadGrid(rowType, maxRows, parsed, id))
            } catch (e: Throwable) {
                if (loadedFor.current == wanted) loadError = userFacingError(e)
            }
        }
    }

    // The flush the page calls on Save: the open edit committed, a problem refused with the cursor on it, else the table
    // uploaded as a new CSV file and the grid rebased on it.
    val flush: suspend () -> TableFlush = {
        val before = latest.current
        val g = before?.let { if (it.editing != null) it.commitEdit() else it }
        when {
            g == null || !g.dirty -> TableFlush.Unchanged
            else -> {
                val problem = g.problems(1).firstOrNull()
                if (problem != null) {
                    setGrid(problem.cell?.let { g.moveTo(it) } ?: g)
                    focusBox()
                    TableFlush.Refused("${props.label ?: props.path}: ${problem.message}")
                } else {
                    val text = g.csvText()
                    val name = (props.path.substringAfterLast('.').substringBefore('[').ifEmpty { "table" }) + ".csv"
                    val file: dynamic = js("new File([text], name, { type: 'text/csv' })")
                    val view = DataFileApi.upload(file, FileKinds.csv, null, {}, {})
                    DataFileCache.remember(view)
                    DataTableApi.remember(view.fileId, text)
                    loadedFor.current = "${view.fileId}|${reloadAsk}"
                    // Rows typed while it went up stay unsaved, on the new base.
                    setGrid { current -> if (current == null || current === before || current === g) g.savedAs(view.fileId) else current.rebasedOn(view.fileId) }
                    TableFlush.Saved(view.fileId)
                }
            }
        }
    }
    val flushRef = useRef(flush)
    flushRef.current = flush

    // Registered with the page while shown, under its field: the page asks it whether it has unsaved rows, flushes it on
    // Save, and resets it on Reset.
    useEffect(props.drafts, scope, props.path) {
        val key = TableDraftKey(scope, props.path)
        props.drafts.register(
            key,
            TableDraft(
                dirty = { latest.current?.dirty == true },
                flush = { flushRef.current!!.invoke() },
                reset = { setReloadAsk { it + 1 } },
            ),
        )
        // An effect is a coroutine here, cancelled when the grid goes: that is when it leaves the page's grids.
        try {
            awaitCancellation()
        } finally {
            props.drafts.unregister(key)
        }
    }
    // The page's Save and Reset follow whether the grid has unsaved rows.
    useEffect(grid?.dirty) { props.drafts.onChange() }

    val handlers = useMemo(setGrid) {
        GridHandlers(
            down = { cell, shift -> setGrid { it?.let { g -> if (shift) g.extendTo(cell) else g.moveTo(cell) } } },
            open = { cell -> setGrid { it?.moveTo(cell)?.startEdit() } },
            editText = { text -> setGrid { it?.editText(text) } },
            editorKey = { key, shift ->
                val handled = latest.current?.onEditorKey(key, shift) != null
                if (handled) setGrid { it?.onEditorKey(key, shift) ?: it }
                handled
            },
            commit = { setGrid { it?.commitEdit() } },
            choose = { value -> setGrid { it?.editText(value)?.commitEdit() } },
        )
    }

    // The box takes focus back once an edit ends -- its editor gone, the focus with it -- so the keys keep working. Not
    // when the edit ended because focus went elsewhere: a click on Save, or on another field, keeps its focus.
    val wasEditing = useRef(false)
    useEffect(grid?.editing == null) {
        val editingNow = grid?.editing != null
        val ended = wasEditing.current == true && !editingNow
        wasEditing.current = editingNow
        val focused = web.dom.document.activeElement
        if (ended && (focused == null || focused == web.dom.document.body || box.current?.contains(focused) == true)) focusBox()
    }

    val g = grid
    div {
        className = ClassName("data-grid-field")
        loadError?.let { errorText("Couldn't read the table.", it) }
        if (g == null) {
            if (loadError == null) span { className = ClassName("type-hint"); +"Reading the table…" }
            return@div
        }
        val selection = g.selection
        val activeId = g.active?.let { cellId(gridId, g, it) }
        div {
            ref = box
            className = ClassName("data-grid-box")
            tabIndex = 0
            asDynamic()["role"] = "grid"
            asDynamic()["aria-label"] = props.label ?: "Table"
            activeId?.let { asDynamic()["aria-activedescendant"] = it }
            props.describedBy?.let { asDynamic()["aria-describedby"] = it }
            onKeyDown = { e ->
                // Only the box's own keys: an editor's and a footer picker's are theirs.
                if (e.target == e.currentTarget) {
                    val next = latest.current?.onGridKey(e.key, e.shiftKey, e.ctrlKey || e.metaKey)
                    if (next != null) {
                        e.preventDefault()
                        setGrid(next)
                        note = null
                    }
                }
            }
            onCopy = { e ->
                val current = latest.current
                if (current != null && current.editing == null && e.target == e.currentTarget) {
                    e.asDynamic().clipboardData.setData("text/plain", current.copyText())
                    e.preventDefault()
                }
            }
            onPaste = { e ->
                val current = latest.current
                if (current != null && current.editing == null && e.target == e.currentTarget) {
                    val text = e.asDynamic().clipboardData.getData("text/plain") as? String
                    if (text != null) {
                        e.preventDefault()
                        val pasted = current.paste(text)
                        setGrid(pasted.state)
                        note = pasteDroppedText(pasted.droppedRows, current.maxRows)
                    }
                }
            }
            table {
                className = ClassName("op-table data-grid")
                thead {
                    tr {
                        th { className = ClassName("data-grid-rownum"); +"#" }
                        for (column in g.columns) {
                            th {
                                key = column.name.unsafeCast<Key>()
                                if (column.numeric) className = ClassName("op-num")
                                +(column.label + if (column.required) " *" else "")
                            }
                        }
                    }
                }
                tbody {
                    g.rows.forEachIndexed { index, row ->
                        GridRowView {
                            key = row.id.toString().unsafeCast<Key>()
                            this.row = row
                            this.index = index
                            this.columns = g.columns
                            this.gridId = gridId
                            activeCol = if (g.active?.row == index) g.active.col else -1
                            selFrom = if (selection != null && index in selection.top..selection.bottom) selection.left else -1
                            selTo = if (selection != null && index in selection.top..selection.bottom) selection.right else -1
                            editing = g.editing?.takeIf { it.at.row == index }
                            this.handlers = handlers
                        }
                    }
                }
                gridFooter(g, combines) { name, combine -> combines = combines + (name to combine) }
            }
            if (g.rows.isEmpty()) div { className = ClassName("type-hint data-grid-empty"); +"No rows yet. Add one, or paste rows from a spreadsheet." }
        }
        div {
            className = ClassName("data-grid-bar")
            Button {
                size = "small"
                disabled = !g.canAddRow
                onClick = {
                    setGrid { it?.addRow() }
                    focusBox()
                }
                +"Add row"
            }
            val removable = selection?.let { it.bottom - it.top + 1 } ?: 0
            Button {
                size = "small"
                disabled = removable == 0
                onClick = {
                    setGrid { it?.removeRows() }
                    focusBox()
                }
                +(if (removable > 1) "Remove $removable rows" else "Remove row")
            }
            span { className = ClassName("type-hint"); +gridRowCountText(g.rows.size, g.maxRows) }
            if (g.dirty) span { className = ClassName("type-hint"); +" · Unsaved: saved with the form" }
        }
        note?.let { div { className = ClassName("type-hint"); +it } }
        val problems = g.problems()
        if (problems.isNotEmpty()) {
            ul {
                className = ClassName("data-grid-problems")
                problems.forEachIndexed { at, problem ->
                    li {
                        key = at.toString().unsafeCast<Key>()
                        Button {
                            type = "link"
                            size = "small"
                            onClick = {
                                problem.cell?.let { cell -> setGrid { it?.moveTo(cell) } }
                                focusBox()
                            }
                            +problem.message
                        }
                    }
                }
            }
        }
    }
}

/** The id of [cell]'s element, which the box names as its active descendant. */
private fun cellId(gridId: String, g: GridState, cell: GridCell): String? =
    g.rows.getOrNull(cell.row)?.let { "$gridId-r${it.id}-c${cell.col}" }

/** The footer: each column's summary, by the function picked for it ([TableSummary.defaultFor] until one is). */
private fun ChildrenBuilder.gridFooter(g: GridState, combines: Map<String, ReportCombine>, pick: (String, ReportCombine) -> Unit) {
    if (g.rows.isEmpty()) return
    val values = g.summaries(combines)
    tfoot {
        tr {
            td { className = ClassName("data-grid-rownum") }
            for (column in g.columns) {
                td {
                    key = column.name.unsafeCast<Key>()
                    className = ClassName(if (column.numeric) "op-num data-table-summary" else "data-table-summary")
                    summaryCell(column, combines[column.name] ?: TableSummary.defaultFor(column), values[column.name], pick)
                }
            }
        }
    }
}

private fun ChildrenBuilder.summaryCell(column: TableColumn, combine: ReportCombine, value: Any?, pick: (String, ReportCombine) -> Unit) {
    div {
        className = ClassName("data-grid-summary")
        Select {
            size = "small"
            this.value = combine.name
            options = optionsToJs(TableSummary.combines.filter { TableSummary.allows(column, it) }.map { SchOptionOf(it.name, combineLabel(it)) })
            virtual = false
            asDynamic()["popupMatchSelectWidth"] = false
            onChange = { v -> ReportCombine.entries.firstOrNull { it.name == v.toString() }?.let { pick(column.name, it) } }
        }
        span { +" ${summaryValueText(value)}" }
    }
}

private fun SchOptionOf(value: String, label: String) = com.dynamicruntime.common.schema.SchOption(value, label)

private external interface GridRowProps : Props {
    var row: GridRow
    var index: Int
    var columns: List<TableColumn>
    var gridId: String
    var activeCol: Int
    var selFrom: Int
    var selTo: Int
    var editing: GridEdit?
    var handlers: GridHandlers
}

/** One row, redrawn only when its row, its part of the cursor or selection, or its edit changes. */
private val GridRowView = memo(
    FC<GridRowProps> { props ->
        val row = props.row
        tr {
            td { className = ClassName("data-grid-rownum"); +"${props.index + 1}" }
            props.columns.forEachIndexed { col, column ->
                val cell = GridCell(props.index, col)
                val problem = row.problems[col]
                val classes = listOfNotNull(
                    "data-grid-cell",
                    if (column.numeric) "op-num" else null,
                    if (col == props.activeCol) "data-grid-active" else null,
                    if (props.selFrom >= 0 && col in props.selFrom..props.selTo) "data-grid-selected" else null,
                    if (problem != null) "data-grid-invalid" else null,
                )
                td {
                    key = col.toString().unsafeCast<Key>()
                    asDynamic()["id"] = "${props.gridId}-r${row.id}-c$col"
                    asDynamic()["role"] = "gridcell"
                    className = ClassName(classes.joinToString(" "))
                    asDynamic()["aria-selected"] = col == props.activeCol
                    if (problem != null) {
                        asDynamic()["aria-invalid"] = true
                        title = problem
                    }
                    onMouseDown = { e ->
                        if (props.editing?.at != cell) {
                            props.handlers.down(cell, e.shiftKey)
                        }
                    }
                    onDoubleClick = { props.handlers.open(cell) }
                    val edit = props.editing?.takeIf { it.at.col == col }
                    if (edit != null) cellEditor(column, edit, props.handlers) else +tableCellText(row.cells[col], column)
                }
            }
        }
    },
)

/** The editor of the cell being edited: a choice's list, or a box of text for anything else. */
private fun ChildrenBuilder.cellEditor(column: TableColumn, edit: GridEdit, handlers: GridHandlers) {
    val options = column.type.options
    if (options != null && column.closedChoices) {
        Select {
            size = "small"
            value = options.firstOrNull { it.value == edit.text || it.label.equals(edit.text, ignoreCase = true) }?.value
            this.options = optionsToJs(options)
            asDynamic()["autoFocus"] = true
            asDynamic()["defaultOpen"] = true
            virtual = false
            showSearch = true
            style = js("({ width: '100%' })")
            onChange = { v -> handlers.choose(v.toString()) }
            asDynamic()["onKeyDown"] = { e: dynamic ->
                val key = e.key as String
                if ((key == "Escape" || key == "Tab") && handlers.editorKey(key, e.shiftKey == true)) {
                    e.preventDefault()
                    e.stopPropagation()
                }
            }
            asDynamic()["onBlur"] = { handlers.commit() }
        }
        return
    }
    input {
        className = ClassName("data-grid-input")
        value = edit.text
        autoFocus = true
        onChange = { e -> handlers.editText(e.target.value) }
        onKeyDown = { e ->
            if (handlers.editorKey(e.key, e.shiftKey)) {
                e.preventDefault()
                e.stopPropagation()
            }
        }
        onBlur = { handlers.commit() }
    }
}
