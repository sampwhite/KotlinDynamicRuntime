package com.dynamicruntime.common.table

import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.gedra.report.coerceReportValue
import com.dynamicruntime.common.gedra.report.combineReportValues
import com.dynamicruntime.common.gedra.report.isEmptyReportValue
import com.dynamicruntime.common.gedra.report.reportNumber
import kotlin.math.pow

/**
 * A table's column functions (issue #1883): one value summing up a column -- how many rows, a total, an average, the
 * least or the greatest -- computed by the same code where a grid shows it live and where a save stores it in a
 * trait's derived field, so the two cannot disagree.
 *
 * A summary is a report's rollup over the rows instead of over forms: each cell read as its column's kind
 * ([coerceReportValue]), empty cells left out ([isEmptyReportValue]), and the rest combined by [combineReportValues] --
 * a sum of whole numbers kept whole, and falling back to a fraction rather than overflowing; an average whole when it
 * comes out whole. **A total of decimals is rounded to the most decimal places among its cells**, so `1.10` and `2.20`
 * add to `3.3`, not to binary floating point's `3.3000000000000003`; an average to [avgPlaces], which drops the same
 * noise. A report's rollup does not round, since it is read and shown, never stored; a summary is stored in a form's
 * data, where an API reader or a workflow rule sees it as it is.
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
        val combined = combineReportValues(values, column.kind, combine)
        if (combined !is Double) return combined
        return when (combine) {
            ReportCombine.sum -> decimalPlacesOf(values)?.let { reportNumber(roundTo(combined, it)) } ?: combined
            ReportCombine.avg -> reportNumber(roundTo(combined, avgPlaces))
            else -> combined
        }
    }

    /** The decimal places an average is rounded to: past any amount's, short of floating point's noise. */
    const val avgPlaces = 10

    /**
     * The most decimal places among [values] as they read -- `12.5` one, `30` none -- or null when one is written with
     * an exponent, whose places are not its spelling's. Read from the shortest spelling of each number, which the JVM
     * and JS agree on for a decimal a person typed; the JVM's `12.0` counts one place, which rounds nothing away.
     */
    private fun decimalPlacesOf(values: List<Any>): Int? {
        var most = 0
        for (value in values) {
            if (value !is Double) continue
            val text = value.toString()
            if ('e' in text || 'E' in text) return null
            val dot = text.indexOf('.')
            if (dot >= 0) most = maxOf(most, text.length - dot - 1)
        }
        return most
    }

    /** [value] rounded to [places] decimal places, or as it is when that many places would pass what a Double holds. */
    private fun roundTo(value: Double, places: Int): Double {
        if (places > 15) return value
        val scale = 10.0.pow(places)
        val scaled = value * scale
        if (kotlin.math.abs(scaled) >= 9.0e15) return value
        return kotlin.math.round(scaled) / scale
    }
}
