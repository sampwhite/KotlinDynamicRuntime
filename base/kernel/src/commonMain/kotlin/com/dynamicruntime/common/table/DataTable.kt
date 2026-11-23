package com.dynamicruntime.common.table

import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.gedra.report.plainNumberOrNull
import com.dynamicruntime.common.gedra.report.reportKindOf
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.schema.parseExactBool
import com.dynamicruntime.common.schema.uniquenessKey
import com.dynamicruntime.common.util.formatDay
import com.dynamicruntime.common.util.parseDayOrNull
import kotlinx.datetime.LocalDate

/** A table's bounds and defaults (issue #1882). Each name matches its value. */
@Suppress("ConstPropertyName")
object DTAB {
    /** The most rows a table field's table holds when the field does not say (`g-fileMaxRows`). */
    const val defaultMaxRows = 1000

    /** The most problems a read of a table reports: enough to fix a file by, a page's worth. */
    const val maxProblems = 20

    /** The largest whole number a Double holds exactly, 2^53: the bound of a whole number written with a fraction. */
    const val maxExactWhole = 9007199254740992.0
}

/**
 * One column of a table (issue #1882): a property of the table field's row type -- its [name], which the CSV's header
 * names it by, the [label] a person reads, its [type], whether a row must have it ([required]), and its [kind], which
 * says how its cells are read and combined.
 */
class TableColumn(val name: String, val label: String, val type: SchType, val required: Boolean, val kind: ReportKind) {
    /** Whether this column's cells are numbers: a [ReportKind.number], drawn right-aligned and summed. */
    val numeric: Boolean get() = kind == ReportKind.number

    /** Whether this column holds whole numbers only. */
    val whole: Boolean get() = type.jsonType == SCT.integer

    /** Whether a cell must be one of a closed list of choices: its value, or its label, which reads as the value. */
    val closedChoices: Boolean get() = type.options != null && !type.openOptions

    override fun toString(): String = name
}

/**
 * The columns of [rowType], in the order it declares them (issue #1882). The parser holds a table field's row type to
 * scalar columns, so each has a kind; a property with none -- reached only through a type the parser never judged --
 * is left out rather than read as text.
 */
fun tableColumns(rowType: SchType): List<TableColumn> = rowType.properties.values.mapNotNull { prop ->
    val type = prop.valueType
    val kind = reportKindOf(type) ?: return@mapNotNull null
    TableColumn(prop.name, prop.title ?: type.title ?: prop.name, type, prop.name in rowType.required, kind)
}

/** One cell's text read as its column's type: its [value] -- null for an empty cell -- or the [problem] with it. */
class TypedCell(val value: Any?, val problem: String? = null)

/**
 * [text], a cell of [column], read as the column's type (issue #1882): an empty cell is no value; a number by
 * [plainNumberOrNull], the same on the JVM and in JS -- a whole number kept whole, and one written with a fraction that
 * is whole (`12.0`) read as the whole number it is; a boolean by its spellings ([parseExactBool]); a day as
 * `yyyy-MM-dd`; and a choice by its value, then by its label, ignoring case. Free text is taken as it is. What else
 * the column asks -- bounds, required, a pattern -- is the validator's to judge ([readTable]).
 */
fun typedCell(text: String, column: TableColumn): TypedCell {
    val cell = text.trim()
    if (cell.isEmpty()) return TypedCell(null)
    val shown = if (cell.length > 40) cell.take(40) + "…" else cell
    return when (column.kind) {
        ReportKind.number -> {
            val whole = cell.toLongOrNull() ?: plainNumberOrNull(cell)?.let { d ->
                if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < DTAB.maxExactWhole) d.toLong() else null
            }
            when {
                whole != null -> TypedCell(whole)
                column.whole -> TypedCell(null, "'$shown' is not a whole number.")
                else -> plainNumberOrNull(cell)?.takeIf { it.isFinite() }?.let { TypedCell(it) }
                    ?: TypedCell(null, "'$shown' is not a number.")
            }
        }
        ReportKind.boolean -> parseExactBool(cell)?.let { TypedCell(it) }
            ?: TypedCell(null, "'$shown' is not yes or no (true or false).")
        ReportKind.date -> cell.parseDayOrNull()?.let { TypedCell(it) }
            ?: TypedCell(null, "'$shown' is not a day (yyyy-MM-dd).")
        ReportKind.string -> {
            val options = column.type.options
            if (options == null) return TypedCell(text)
            val chosen = options.firstOrNull { it.value == cell }
                ?: options.firstOrNull { it.label.equals(cell, ignoreCase = true) }
                ?: options.firstOrNull { it.value.equals(cell, ignoreCase = true) }
            when {
                chosen != null -> TypedCell(chosen.value)
                column.closedChoices -> TypedCell(null, "'$shown' is not one of the choices.")
                else -> TypedCell(text)
            }
        }
    }
}

/**
 * [text], a cell of [column], as it is stored (issue #1882): a choice as its value, a boolean as `true` or `false`, a
 * day as `yyyy-MM-dd`. Anything else -- a number above all, which is kept as typed rather than rounded through a
 * format -- and a cell that does not read as its type, is left as it is.
 */
fun canonicalText(text: String, column: TableColumn): String {
    val typed = typedCell(text, column)
    if (typed.problem != null) return text
    return when (val value = typed.value) {
        null -> ""
        is Boolean -> value.toString()
        is LocalDate -> value.formatDay()
        is String -> if (column.type.options != null) value else text
        else -> text.trim()
    }
}

/** One problem with a table: the data [row] (from 1) and [column] it is in, when it is in one, and its [message]. */
class TableProblem(val row: Int?, val column: String?, val message: String) {
    override fun toString(): String = message
}

/**
 * A table read against its row type ([readTable]): its [columns], the [header] its file named them by, its [rows] as
 * typed data -- each row's values by column name, an empty cell left out -- and its [problems].
 */
class TableRead(
    val columns: List<TableColumn>,
    val header: List<String>,
    val rows: List<Map<String, Any?>>,
    val problems: List<TableProblem>,
) {
    val ok: Boolean get() = problems.isEmpty()
}

/**
 * [text], a CSV table, read against [rowType] (issue #1882) -- what a save checks a table field's file against, and
 * what its summaries are computed from.
 *
 * - **The header** names the columns, in any order. A name that is no column is refused, as is a required column
 *   missing; an optional one missing is empty in every row.
 * - **Each cell** is read as its column's type ([typedCell]), and **each row** is then checked against [rowType]
 *   whole, by the validator -- required columns, bounds, patterns, choices -- with its failures named by row and column.
 * - **A key** (`g-primaryKey` on the row type) must not repeat across the rows.
 * - At most [maxRows] rows; and the codec's own limits ([CsvLimits]) bound what is read at all.
 *
 * Problems read as a person fixes them -- "Row 4, Amount: 'abc' is not a number." -- and at most [DTAB.maxProblems] of
 * them are reported.
 */
fun readTable(rowType: SchType, text: String, maxRows: Int = DTAB.defaultMaxRows): TableRead =
    readTable(rowType, Csv.parse(text), maxRows)

/** [readTable] over CSV already parsed ([parsed]). */
fun readTable(rowType: SchType, parsed: CsvParsed, maxRows: Int = DTAB.defaultMaxRows): TableRead {
    val columns = tableColumns(rowType)
    if (!parsed.ok) return TableRead(columns, parsed.header, emptyList(), parsed.problems.map { TableProblem(null, null, it.message) })
    val problems = ArrayList<TableProblem>()
    fun add(problem: TableProblem): Boolean {
        if (problems.size < DTAB.maxProblems) problems.add(problem)
        return problems.size < DTAB.maxProblems
    }
    val byName = columns.associateBy { it.name }
    val unknown = parsed.header.filter { it !in byName }
    if (unknown.isNotEmpty()) {
        add(
            TableProblem(
                null, null,
                "The header names ${unknown.joinToString(", ") { "'$it'" }}, which ${if (unknown.size == 1) "is" else "are"} " +
                    "not a column of this table. Its columns are: ${columns.joinToString(", ") { it.name }}.",
            ),
        )
    }
    val missing = columns.filter { it.required && it.name !in parsed.header }
    if (missing.isNotEmpty()) {
        add(
            TableProblem(
                null, null,
                "The header has no ${missing.joinToString(", ") { "'${it.name}'" }}, which every row must have.",
            ),
        )
    }
    if (parsed.rows.size > maxRows) {
        add(TableProblem(null, null, "The table has ${parsed.rows.size} rows; this field holds at most $maxRows."))
    }
    if (problems.isNotEmpty()) return TableRead(columns, parsed.header, emptyList(), problems)
    val at = parsed.header.map { byName.getValue(it) }
    val rows = ArrayList<Map<String, Any?>>(parsed.rows.size)
    val keys = HashMap<String, Int>()
    for ((index, cells) in parsed.rows.withIndex()) {
        val rowNumber = index + 1
        val row = LinkedHashMap<String, Any?>()
        var typed = true
        for ((i, cell) in cells.withIndex()) {
            val column = at[i]
            val read = typedCell(cell, column)
            if (read.problem != null) {
                typed = false
                if (!add(TableProblem(rowNumber, column.name, "Row $rowNumber, ${column.label}: ${read.problem}"))) break
            } else if (read.value != null) {
                row[column.name] = read.value
            }
        }
        if (problems.size >= DTAB.maxProblems) break
        if (typed) {
            val failures = coerceAndValidate(rowType, row).failures
            for (failure in failures) {
                val column = byName[failure.path.substringBefore('.').substringBefore('[')]
                val where = column?.let { "Row $rowNumber, ${it.label}" } ?: "Row $rowNumber"
                if (!add(TableProblem(rowNumber, column?.name, "$where: ${failure.userMessage ?: failure.message}"))) break
            }
            if (failures.isEmpty() && rowType.primaryKey.isNotEmpty()) {
                val key = rowType.primaryKey.joinToString("|") { uniquenessKey(row[it]) }
                val first = keys[key]
                if (first != null) {
                    val named = rowType.primaryKey.map { byName[it]?.label ?: it }
                    add(TableProblem(rowNumber, null, "Row $rowNumber repeats row $first's ${named.joinToString(", ")}, which tell rows apart."))
                } else {
                    keys[key] = rowNumber
                }
            }
        }
        if (problems.size >= DTAB.maxProblems) break
        rows.add(row)
    }
    return TableRead(columns, parsed.header, if (problems.isEmpty()) rows else emptyList(), problems)
}

/** The problems with [text] as a table of [rowType] holding at most [maxRows] rows ([readTable]); empty when it is one. */
fun checkTable(rowType: SchType, text: String, maxRows: Int = DTAB.defaultMaxRows): List<TableProblem> =
    readTable(rowType, text, maxRows).problems

/**
 * A table of [rowType]'s columns as CSV text (issue #1882): a header of its column names, then [rows], each a list of
 * cell text in the columns' order, as a grid holds them. Written with the codec ([Csv.write]): CRLF line ends and a
 * byte-order mark.
 */
fun writeTable(rowType: SchType, rows: List<List<String>>): String =
    Csv.write(listOf(tableColumns(rowType).map { it.name }) + rows)
