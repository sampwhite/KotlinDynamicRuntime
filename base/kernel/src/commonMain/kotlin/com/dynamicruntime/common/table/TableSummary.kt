package com.dynamicruntime.common.table

import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.gedra.report.coerceReportValue
import com.dynamicruntime.common.gedra.report.combineReportValues
import com.dynamicruntime.common.gedra.report.isEmptyReportValue

/**
 * A table's column functions (issue #1883): one value summing up a column -- how many rows, a total, an average, the
 * least or the greatest -- computed by the same code where a grid shows it live and where a save stores it in a
 * trait's derived field, so the two cannot disagree.
 *
 * A summary is a report's rollup over the rows instead of over forms: each cell read as its column's kind
 * ([coerceReportValue]), empty cells left out ([isEmptyReportValue]), and the rest combined by [combineReportValues] --
 * a sum of whole numbers kept whole, and falling back to a fraction rather than overflowing; an average whole when it
 * comes out whole.
 *
 * Theme: #DTABL.
 */
object TableSummary {
    /** The functions a column offers, in the order a picker lists them: the five that say something of a column. */
    val combines: List<ReportCombine> =
        listOf(ReportCombine.count, ReportCombine.sum, ReportCombine.avg, ReportCombine.min, ReportCombine.max)

    /**
     * Whether [combine] may sum up [column]: one of [combines], allowed for the column's kind ([ReportCombine.allows]
     * -- arithmetic needs numbers, an order something ordered). With no column, only a count, which counts the rows.
     */
    fun allows(column: TableColumn?, combine: ReportCombine): Boolean = when {
        combine !in combines -> false
        column == null -> combine == ReportCombine.count
        else -> combine.allows(column.kind)
    }

    /** The kind of [combine]'s value over [column]: a count is a number, and the rest keep the column's kind. */
    fun resultKind(column: TableColumn?, combine: ReportCombine): ReportKind =
        if (column == null) ReportKind.number else combine.resultKind(column.kind)

    /**
     * The function a column's summary shows until a person picks another: a total for a number, and how many are
     * filled in for anything else.
     */
    fun defaultFor(column: TableColumn): ReportCombine = if (column.numeric) ReportCombine.sum else ReportCombine.count

    /**
     * [combine] over [column] of [rows] -- each a row's values by column name, as [readTable] gives them, or as a grid
     * holds them typed -- or null when there is nothing to combine (no filled-in cell, for anything but a count) or
     * the combine is not one the column allows ([allows]). With no [column], a count of the rows. A count of a column
     * counts its filled-in cells.
     */
    fun summarize(rows: List<Map<String, Any?>>, column: TableColumn?, combine: ReportCombine): Any? {
        if (!allows(column, combine)) return null
        if (column == null) return rows.size.toLong()
        val values = rows.mapNotNull { row -> coerceReportValue(row[column.name], column.kind)?.takeIf { !isEmptyReportValue(it) } }
        return combineReportValues(values, column.kind, combine)
    }
}
