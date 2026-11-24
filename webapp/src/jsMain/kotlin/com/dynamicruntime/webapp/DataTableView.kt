package com.dynamicruntime.webapp

import com.dynamicruntime.common.datafile.DFILE
import com.dynamicruntime.common.filekind.FileKinds
import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseExactBool
import com.dynamicruntime.common.schema.tableRowLimit
import com.dynamicruntime.common.table.Csv
import com.dynamicruntime.common.table.CsvParsed
import com.dynamicruntime.common.table.TableColumn
import com.dynamicruntime.common.table.TableSummary
import com.dynamicruntime.common.table.tableColumns
import com.dynamicruntime.common.util.byteSizeText
import com.dynamicruntime.common.util.fmt
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import react.FC
import react.Key
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.tfoot
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.useEffect
import react.useMemo
import react.useRef
import react.useState
import web.cssom.ClassName

/**
 * A table field's file shown as its table (issue #1884), and a CSV file saved as a copy made safe for a spreadsheet:
 * what a read-only form draws for a table field ([DataTableValue]), and what every page offering a CSV file's content
 * offers instead of the stored text ([SpreadsheetCopyLink]).
 *
 * Theme: #DTABL.
 */

private val tableScope = MainScope()

/**
 * A table file's requests: its text, read once and kept with its parse -- a file's content never changes under its id --
 * so the table drawn and the copy downloaded come from one read.
 */
object DataTableApi {
    /** How many files a page keeps, the least recently used given up first: each at most a 4 MiB file and its rows. */
    const val cacheSize = 16

    private class Kept(val text: String) {
        val parsed: CsvParsed by lazy { Csv.parse(text) }
    }

    // In the order they were last used, the least recent first.
    private val kept = LinkedHashMap<String, Kept>()

    /** [fileId]'s content, kept: read through `/files/content` when it is not, a non-2xx the server's refusal (`ApiError`). */
    private suspend fun keptOf(fileId: String): Kept {
        kept.remove(fileId)?.let { hit ->
            kept[fileId] = hit
            return hit
        }
        val read = Kept(Http.getApiText(DFILE.contentPath + queryString(mapOf(DFILE.fileId to fileId))))
        kept[fileId] = read
        while (kept.size > cacheSize) kept.remove(kept.keys.first())
        return read
    }

    /** [text] kept as [fileId]'s content: a table a grid just saved, which it need not read back. */
    fun remember(fileId: String, text: String) {
        kept.remove(fileId)
        kept[fileId] = Kept(text)
        while (kept.size > cacheSize) kept.remove(kept.keys.first())
    }

    /** [fileId]'s content as text. */
    suspend fun readText(fileId: String): String = keptOf(fileId).text

    /** [fileId]'s content parsed as a CSV table, with its header ([Csv.parse]). */
    suspend fun read(fileId: String): CsvParsed = keptOf(fileId).parsed

    /** [fileId]'s content as a copy made safe for a spreadsheet ([Csv.spreadsheetCopy]). */
    suspend fun spreadsheetCopy(fileId: String): String =
        Csv.spreadsheetCopy(readText(fileId)) ?: throw IllegalStateException("The file is not CSV that can be read.")

    /** Saves [view]'s spreadsheet copy under its name ending `.csv` ([spreadsheetCopyName]). */
    suspend fun saveSpreadsheetCopy(view: DataFileView) {
        saveTextFile(spreadsheetCopyName(view), FileKinds.csv.mimeType + ";charset=utf-8", spreadsheetCopy(view.fileId))
    }
}

external interface SpreadsheetCopyLinkProps : Props {
    var view: DataFileView

    /** What the link says: the file's name when not given. */
    var label: String?
}

/**
 * A CSV file's content offered as a download (issue #1881): a link-styled button that saves a copy made safe for a
 * spreadsheet, never the stored text, since a spreadsheet would run a formula a cell begins with. A failure is said
 * beside it.
 */
val SpreadsheetCopyLink = FC<SpreadsheetCopyLinkProps> { props ->
    var failure by useState<DisplayError?>(null)
    span {
        className = ClassName("spreadsheet-copy-link")
        Button {
            type = "link"
            size = "small"
            onClick = {
                failure = null
                tableScope.launch {
                    try {
                        DataTableApi.saveSpreadsheetCopy(props.view)
                    } catch (e: Throwable) {
                        failure = userFacingError(e)
                    }
                }
            }
            +(props.label ?: props.view.title)
        }
    }
    failure?.let { errorText("Couldn't download the file.", it) }
}

/** What [DataTableValue] has read: the parsed file, or why not, or neither yet. */
class TableFileState(val parsed: CsvParsed?, val failure: DisplayError?)

/** [fileId]'s parsed CSV, read when the id is first seen; an answer about an id the caller has moved on from is dropped. */
fun useTableFile(fileId: String?): TableFileState {
    var state by useState(TableFileState(null, null))
    val latest = useRef<String>(null)
    useEffect(fileId) {
        latest.current = fileId
        state = TableFileState(null, null)
        if (fileId == null) return@useEffect
        tableScope.launch {
            val next = try {
                TableFileState(DataTableApi.read(fileId), null)
            } catch (e: Throwable) {
                TableFileState(null, userFacingError(e))
            }
            if (latest.current == fileId) state = next
        }
    }
    return state
}

/**
 * A cell of [column] as a table shows it: a choice by its label, a yes or no in words -- any spelling a save reads as
 * one ([parseExactBool]) -- and anything else as stored.
 */
fun tableCellText(text: String, column: TableColumn): String {
    val cell = text.trim()
    if (cell.isEmpty()) return ""
    column.type.options?.firstOrNull { it.value == cell }?.let { return it.label }
    if (column.kind == ReportKind.boolean) parseExactBool(cell)?.let { return if (it) "Yes" else "No" }
    return text
}

/** A column's summary in words for a table's footer: its function and its value, or nothing when it has none. */
fun tableSummaryText(column: TableColumn, value: Any?): String {
    if (value == null) return ""
    val combine = TableSummary.defaultFor(column)
    val shown = when (value) {
        is Double -> wholeNumberTextOrNull(value) ?: value.fmt()
        else -> value.fmt()
    }
    return "${combine.name.replaceFirstChar { it.uppercase() }} $shown"
}

/** How many rows a table has, in words. */
fun tableRowCountText(rows: Int): String = if (rows == 1) "1 row" else "$rows rows"

external interface DataTableValueProps : Props {
    /** The table field's file. */
    var fileId: String

    /** The table field's schema, whose row type is the table's columns. */
    var type: SchType
}

/**
 * A table field shown read-only (issue #1884): the file -- its name, and a download of a copy made safe for a
 * spreadsheet -- then its rows under the field's columns, in the order the row type declares them, numbers to the
 * right; a footer summing up each column as a grid's does ([TableSummary.defaultFor]: a total for a number, how many
 * are filled in otherwise); and how many rows there are. A file whose header names other columns than the field's
 * shows the columns it has in common; one that cannot be read says so.
 */
val DataTableValue = FC<DataTableValueProps> { props ->
    val file = useDataFile(props.fileId)
    val table = useTableFile(props.fileId)
    val rowType = props.type.fileRowType
    val parsed = table.parsed
    // Typed once per file and field, not on every render: a table may hold a thousand rows.
    val grid = useMemo(parsed, rowType) {
        if (parsed != null && parsed.ok && rowType != null) loadGrid(rowType, props.type.tableRowLimit(), parsed, props.fileId) else null
    }
    val footer = useMemo(grid) { grid?.summaries() }
    // A sort here orders what is shown and nothing else: the stored table is as it is.
    var sort by useState<GridSort?>(null)
    val shownRows = useMemo(grid, sort) { sort?.let { s -> grid?.sortBy(s.col, s.descending)?.rows } ?: grid?.rows.orEmpty() }
    div {
        className = ClassName("data-table-value")
        div {
            className = ClassName("data-table-file")
            val view = file.view
            if (view != null) {
                span { className = ClassName("field-value"); +view.title }
                view.size?.let { span { className = ClassName("type-hint"); +" · ${byteSizeText(it)} · " } }
                SpreadsheetCopyLink {
                    this.view = view
                    label = "Download CSV"
                }
            } else {
                span { className = ClassName("type-hint"); +(if (file.missing) "File unavailable" else "Reading the file…") }
            }
        }
        when {
            table.failure != null -> errorText("Couldn't read the table.", table.failure)
            rowType == null -> span { className = ClassName("type-hint"); +"This field declares no columns." }
            parsed == null -> span { className = ClassName("type-hint"); +"Reading the table…" }
            grid == null -> span { className = ClassName("type-hint"); +"The file is not a table that can be read: ${parsed.problems.first().message}" }
            else -> {
                val summaries = footer ?: emptyMap()
                div {
                    className = ClassName("op-table-scroll data-table-scroll")
                    table {
                        className = ClassName("op-table data-table")
                        thead {
                            tr {
                                grid.columns.forEachIndexed { col, column ->
                                    sortableHeader(column, column.label, sort?.takeIf { it.col == col }) { sort = nextSort(sort, col) }
                                }
                            }
                        }
                        tbody {
                            for (row in shownRows) {
                                tr {
                                    key = row.id.toString().unsafeCast<Key>()
                                    grid.columns.forEachIndexed { at, column ->
                                        td {
                                            key = column.name.unsafeCast<Key>()
                                            if (column.numeric) className = ClassName("op-num")
                                            +tableCellText(row.cells[at], column)
                                        }
                                    }
                                }
                            }
                        }
                        if (grid.rows.isNotEmpty()) {
                            tfoot {
                                tr {
                                    for (column in grid.columns) {
                                        td {
                                            key = column.name.unsafeCast<Key>()
                                            className = ClassName(if (column.numeric) "op-num data-table-summary" else "data-table-summary")
                                            +tableSummaryText(column, summaries[column.name])
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                div { className = ClassName("type-hint"); +tableRowCountText(grid.rows.size) }
            }
        }
    }
}
