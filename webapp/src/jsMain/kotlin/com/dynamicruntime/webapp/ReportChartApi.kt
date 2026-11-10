package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.report.RDEF
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.ReportChart
import com.dynamicruntime.common.gedra.report.ReportChartKind
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.gedra.report.ReportMode
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * A report's charts (issues #1037, #1772): what a grouped run can be measured by, what its groups are called, and -- for
 * the Grouped view -- which chart is in force, the groups it draws, and the geometry of its bar and pie. Pure, and
 * covered under `jsNodeTest` (`ReportsPageTest`); the component is `ReportChartPanel.kt`, and the History view's own
 * chart is `ReportHistoryApi.kt`.
 */

// --- what a group is measured by ---------------------------------------------------------------------------------------

/**
 * One thing a grouped run can be charted by: how many forms each group held, or one of the report's rolled-up columns.
 * [additive] says whether several groups' values may be added into one (a count, a sum) -- which is what lets groups
 * past a chart's limit be drawn as one "Other", and what a pie's slices must be; an average or a maximum of groups is
 * not their sum.
 */
class ReportMeasure(val key: String, val label: String, val additive: Boolean)

/**
 * The measures a report offers: the count of forms first, then each rolled-up column whose result is a number -- a
 * count of anything, or a numeric column's sum, average, minimum or maximum -- and that the run does not group by. A
 * rolled-up date (the latest audit, say) is a moment, not a length, so it is not offered. The rule a report's declared
 * chart is held to when it binds (issue #1771).
 */
fun reportMeasures(columns: List<ReportColumnInfo>, groupBy: List<String>): List<ReportMeasure> = buildList {
    add(ReportMeasure(RRUN.count, reportCountLabel, additive = true))
    for (c in columns) {
        val rollup = c.rollup ?: continue
        if (c.columnId in groupBy) continue
        val combine = ReportCombine.entries.firstOrNull { it.name == rollup }
        val counted = combine == ReportCombine.count
        if (!counted && c.kind != ReportKind.number.name) continue
        // Adding up is the kernel's rule (issue #1771), the one a report's pie is held to.
        add(ReportMeasure(c.columnId, "${c.label} ($rollup)", additive = combine?.addsUp == true))
    }
}

/** [row]'s value for [measure], or null when the group has none. */
fun measureValue(row: ReportRunRow, measure: ReportMeasure): Double? =
    if (measure.key == RRUN.count) row.count?.toDouble() else (row.values[measure.key] as? Number)?.toDouble()

// --- what a group is called --------------------------------------------------------------------------------------------

/** What the one group of a report that groups by nothing is called. */
const val reportAllFormsLabel = "All forms"

/** What tells a group from another across snapshots: its grouped-by values, in order, as they print. */
fun reportGroupKey(group: Map<String, Any?>, groupBy: List<String>): String =
    groupBy.joinToString("\u001f") { id -> group[id]?.let { v -> if (v is Number) reportNumberText(v.toDouble()) else v.toString() } ?: "\u0000" }

/** What a group is called: its grouped-by values as the run's table shows them, joined; "All forms" when there are none. */
fun reportGroupLabel(group: Map<String, Any?>, groupBy: List<String>, columns: List<ReportColumnInfo>): String {
    if (groupBy.isEmpty()) return reportAllFormsLabel
    val byId = columns.associateBy { it.columnId }
    return groupBy.joinToString(" / ") { id ->
        val column = byId[id]
        reportCellText(group[id], column?.kind ?: ReportKind.string.name, ReportCellSource.group, column?.choices.orEmpty())
    }
}

// --- which chart is in force ---------------------------------------------------------------------------------------------

/** A report's declared chart as the listing carries it (`RRUN.chart`, issue #1771), or null for none or one unreadable. */
fun parseReportChart(raw: Any?): ReportChart? {
    val m = raw?.toJsonMapOrEmpty() ?: return null
    val kind = ReportChartKind.entries.firstOrNull { it.name == m[RDEF.kind].toOptStr() } ?: return null
    return ReportChart(kind, m[RDEF.measure].toOptStr())
}

/** The address's word for the table alone, beside the chart kinds' names: what keeps a declared chart from being drawn. */
const val reportChartTable = "table"

/** The chart the Grouped view draws above its table: a [kind] -- null for the table alone -- of a [measure]. */
class ReportChartChoice(val kind: ReportChartKind?, val measure: ReportMeasure)

/**
 * The chart in force for [report] run as [setup] (issue #1772), or null when the run is not grouped -- only a grouped
 * run has groups to draw. **The address decides**, as it does the view: its `chart` ([chartParam]: a kind's name, or
 * [reportChartTable]) and its `measure` ([measureParam]: a column id, or the count's key). Where it says nothing, the
 * report's declared chart applies -- but only while the run is grouped **as the report groups**, since a measure named
 * for one grouping need not fit another -- and otherwise the table alone.
 *
 * What the address asks for is held to what can be drawn: a measure the run does not offer (one the user's Group by has
 * made a grouped-by column, say) falls back to the count, and a pie of a measure that does not add up is drawn as bars.
 */
fun reportChartInForce(chartParam: String?, measureParam: String?, report: ReportInfo, setup: ReportRunSetup): ReportChartChoice? {
    if (setup.mode != ReportMode.aggregate) return null
    val groupBy = setup.groupBy ?: report.groupBy
    val measures = reportMeasures(report.columns, groupBy)
    val declared = report.chart?.takeIf { groupBy == report.groupBy }
    val kind = when (chartParam) {
        null -> declared?.kind
        reportChartTable -> null
        else -> ReportChartKind.entries.firstOrNull { it.name == chartParam } ?: declared?.kind
    }
    val measureKey = measureParam ?: declared?.measure ?: RRUN.count
    val measure = measures.firstOrNull { it.key == measureKey } ?: measures.first()
    // A pie's slices are shares of a whole, which a measure that does not add up does not make.
    val drawn = if (kind == ReportChartKind.pie && !measure.additive) ReportChartKind.bar else kind
    return ReportChartChoice(drawn, measure)
}

/** The address's `chart` and `measure` for [choice]: both written, so a choice made overrides the report's declared one. */
fun reportChartParams(choice: ReportChartChoice): Pair<String, String> =
    (choice.kind?.name ?: reportChartTable) to choice.measure.key

// --- the groups a chart draws --------------------------------------------------------------------------------------------

/** The most slices a pie draws: the palette's eight, the last of them "Other" when there are more groups. */
const val pieMaxSlices = 8

/** The most bars a bar chart draws, the last of them "Other" when there are more groups and the measure adds up. */
const val barMaxBars = 20

/** The most groups the chart reads of a run (issue #1772): as many as a snapshot keeps (`REP.historyMaxGroups`). */
const val chartMaxGroups = 2000

/** One mark of a chart: a group, or "Other" ([colorIndex] [chartOtherIndex]), its [value] and its palette slot. */
class ChartEntry(val label: String, val value: Double, val colorIndex: Int) {
    val isOther: Boolean get() = colorIndex == chartOtherIndex
}

/**
 * What a chart of a grouped run draws: its [entries] in the run's key order, "Other" last. [read] is how many groups it
 * read; of those, [leftOut] had no value to draw (for a pie, none above zero), [otherCount] were folded into "Other",
 * and [notShown] were past the limit with a measure that cannot be added into one.
 */
class ReportChartData(
    val kind: ReportChartKind,
    val measure: ReportMeasure,
    val entries: List<ChartEntry>,
    val read: Int,
    val leftOut: Int,
    val otherCount: Int,
    val notShown: Int,
) {
    /** The whole a pie's slices are shares of. */
    val total: Double get() = entries.sumOf { it.value }
}

/**
 * The marks [kind] draws of [rows] measured by [measure] (issue #1772). Each group is called as the table calls it.
 *
 * - A group with no value draws nothing, and for a **pie** neither does one at zero or below: a slice is a share, and a
 *   negative share is not one. Both are counted ([ReportChartData.leftOut]) for the chart to say so.
 * - Past the limit -- [pieMaxSlices], [barMaxBars] -- the largest are kept and the rest fold into one "Other" when the
 *   measure adds up; when it does not, they are left to the table and counted. A pie's measure always adds up.
 * - The kept groups stay in the **run's order**, so a year reads in order; a colour is a place among them, never a rank.
 *   A bar chart is one series, so all its bars wear the first colour; a pie's slices each wear their own.
 */
fun reportChartData(rows: List<ReportRunRow>, summary: ReportRunSummary, kind: ReportChartKind, measure: ReportMeasure): ReportChartData {
    val pie = kind == ReportChartKind.pie
    val valued = rows.mapNotNull { row ->
        val v = measureValue(row, measure) ?: return@mapNotNull null
        if (pie && v <= 0.0) return@mapNotNull null
        reportGroupLabel(row.group, summary.groupBy, summary.columns) to v
    }
    val limit = if (pie) pieMaxSlices else barMaxBars
    val folds = pie || measure.additive
    val keptAt: Set<Int> = if (valued.size <= limit) {
        valued.indices.toSet()
    } else {
        valued.indices.sortedByDescending { abs(valued[it].second) }.take(if (folds) limit - 1 else limit).toSet()
    }
    val kept = valued.filterIndexed { i, _ -> i in keptAt }
    val past = valued.filterIndexed { i, _ -> i !in keptAt }
    val other = if (past.isNotEmpty() && folds) ChartEntry(chartOtherLabel(past.size), past.sumOf { it.second }, chartOtherIndex) else null
    val entries = kept.mapIndexed { place, (label, v) -> ChartEntry(label, v, if (pie) place else 0) } + listOfNotNull(other)
    return ReportChartData(
        kind, measure, entries, read = rows.size, leftOut = rows.size - valued.size,
        otherCount = if (folds) past.size else 0, notShown = if (folds) 0 else past.size,
    )
}

/**
 * About how wide [c] is in the chart's 12-pixel proportional type: the layout is pure and cannot measure text, so it
 * estimates per character -- a capital or a wide letter more, a narrow one less -- erring wide, so a cut name stays inside
 * the plot rather than running into the values beside it.
 */
fun chartCharWidth(c: Char): Double = when {
    c in "mwMW@%" -> 10.0
    c.isUpperCase() -> 8.5
    c in "iljtfr.,:;'|!" -> 4.0
    c == ' ' -> 3.5
    else -> 7.0
}

/** What a chart writes where it cuts a name. */
const val chartEllipsis = "…"

/** [text] cut to fit [width] pixels of the chart's type ([chartCharWidth]), with an ellipsis where it was cut. */
fun chartFitText(text: String, width: Double): String {
    if (text.sumOf { chartCharWidth(it) } <= width) return text
    val room = width - chartEllipsis.sumOf { chartCharWidth(it) }
    var used = 0.0
    var keep = 0
    while (keep < text.length && used + chartCharWidth(text[keep]) <= room) used += chartCharWidth(text[keep++])
    return text.take(keep).trimEnd() + chartEllipsis
}

/** What "Other" is called: how many groups it holds. */
fun chartOtherLabel(groups: Int): String = "Other ($groups ${if (groups == 1) "group" else "groups"})"

/** A share of the whole as a pie's legend writes it: whole percents, and "<1%" for a sliver that would read as none. */
fun chartShareText(share: Double): String {
    if (share <= 0.0) return "0%"
    val percent = (share * 100).roundToInt()
    return if (percent == 0) "<1%" else "$percent%"
}

/** The chart's title: what is measured, by what the run groups by -- "Forms by Auditor". */
fun reportChartTitle(measure: ReportMeasure, summary: ReportRunSummary): String {
    val by = summary.groupBy.mapNotNull { id -> summary.columns.firstOrNull { it.columnId == id }?.label }
    return if (by.isEmpty()) measure.label else "${measure.label} by ${by.joinToString(" and ")}"
}

/** What the chart leaves out, said: groups with nothing to draw, groups past the limit, and groups past what was read. */
fun reportChartNotes(data: ReportChartData, available: Int): List<String> = buildList {
    if (available > data.read) {
        add("The chart reads the first ${chartNumberText(data.read.toDouble())} of ${chartNumberText(available.toDouble())} groups.")
    }
    if (data.leftOut > 0) {
        val groups = if (data.leftOut == 1) "1 group has" else "${data.leftOut} groups have"
        add(
            if (data.kind == ReportChartKind.pie) "$groups no ${data.measure.label} above zero, so no slice."
            else "$groups no ${data.measure.label}, so no bar.",
        )
    }
    if (data.notShown > 0) {
        val limit = if (data.kind == ReportChartKind.pie) pieMaxSlices else barMaxBars
        add(
            "${data.notShown} more group${if (data.notShown == 1) "" else "s"} not shown: the chart draws the $limit largest, and " +
                "${data.measure.label} cannot be added up into one \"Other\". The table has them all.",
        )
    }
}

// --- a bar chart's geometry ------------------------------------------------------------------------------------------------

/** A horizontal bar chart's fixed measures (issue #1772): a row is the group's name on a line, then its bar. */
@Suppress("ConstPropertyName")
object HBAR {
    /** The line the group's name is on, above its bar. */
    const val labelLine = 16.0
    const val barThickness = 14.0
    const val rowGap = 12.0
    const val rowHeight = labelLine + barThickness + rowGap
    const val marginLeft = 8.0
    /** The column at the right that holds each bar's value, so the numbers read down one edge as a table's do. */
    const val valueColumn = 76.0
    const val marginTop = 4.0
    /** The band under the plot that holds the value axis's labels. */
    const val axisBand = 24.0
}

/** One bar: its entry's place, its outline, where its name and value are written, and its row (the hit target). */
class ChartBarMark(
    val index: Int,
    val entry: ChartEntry,
    val x: Double,
    val y: Double,
    val width: Double,
    val path: String,
    /** The baseline of the group's name, above the bar. */
    val labelY: Double,
    /** The row's top and height: the whole row is the bar's hit target, its name included. */
    val rowTop: Double,
    val rowHeight: Double,
)

/** A bar chart laid out in pixels, [width] by [height]: the plot's left and right, the zero line, the bars, and the ticks. */
class BarChartLayout(
    val width: Double,
    val height: Double,
    val plotLeft: Double,
    val plotRight: Double,
    val plotTop: Double,
    val plotBottom: Double,
    val baselineX: Double,
    val bars: List<ChartBarMark>,
    val ticks: List<ChartTick>,
)

/**
 * [data] as horizontal bars (issue #1772), [width] wide: a row per entry, top to bottom in its order, the group's name
 * on a line of its own above the bar -- so a long name needs no room beside the bars -- and the values in a column at the
 * right. The value axis starts at zero (left of it when a value is negative) and ends on a clean tick ([chartAxis]).
 */
fun barChartLayout(data: ReportChartData, width: Double = CHT.maxWidth): BarChartLayout {
    val plotLeft = HBAR.marginLeft
    val plotRight = max(plotLeft + 1.0, width - HBAR.valueColumn)
    val plotTop = HBAR.marginTop
    val plotBottom = plotTop + data.entries.size * HBAR.rowHeight
    val values = data.entries.map { it.value }
    val (lo, hi, step) = chartAxis(values.minOrNull() ?: 0.0, values.maxOrNull() ?: 0.0, wholeOnly = data.measure.key == RRUN.count)
    fun xOf(v: Double): Double = plotLeft + (v - lo) / (hi - lo) * (plotRight - plotLeft)
    val baselineX = xOf(0.0)
    val ticks = buildList {
        var v = lo
        while (v <= hi + step * 1e-6) {
            add(ChartTick(xOf(v), v, chartNumberText(v)))
            v += step
        }
    }
    val bars = data.entries.mapIndexed { i, entry ->
        val rowTop = plotTop + i * HBAR.rowHeight
        val y = rowTop + HBAR.labelLine
        val end = xOf(entry.value)
        val x = min(end, baselineX)
        val w = abs(end - baselineX)
        ChartBarMark(
            i, entry, x, y, w, hBarPath(x, y, w, HBAR.barThickness, rightward = entry.value >= 0),
            labelY = rowTop + HBAR.labelLine - 4, rowTop = rowTop, rowHeight = HBAR.rowHeight,
        )
    }
    return BarChartLayout(width, plotBottom + HBAR.axisBand, plotLeft, plotRight, plotTop, plotBottom, baselineX, bars, ticks)
}

// --- a pie's geometry -----------------------------------------------------------------------------------------------------

/** One slice: its entry's place, its share of the whole, its outline, and where its readout points. */
class PieSliceMark(
    val index: Int,
    val entry: ChartEntry,
    val share: Double,
    val path: String,
    /** Where the readout is placed: inside the slice, at the middle of its arc. */
    val tipX: Double,
    val tipY: Double,
)

/** A pie laid out in pixels: a square [size] wide, its centre and radius, and its slices. */
class PieChartLayout(val size: Double, val cx: Double, val cy: Double, val r: Double, val slices: List<PieSliceMark>)

/** The widest a pie is drawn: a share is read from the legend, so a bigger disc says nothing more. */
const val pieMaxDiameter = 240.0

/**
 * [data] as a pie (issue #1772), at most [pieMaxDiameter] across and no wider than [width] allows: the slices clockwise
 * from twelve o'clock in the entries' order, each its share of the whole. One entry is the whole circle.
 */
fun pieChartLayout(data: ReportChartData, width: Double = CHT.maxWidth): PieChartLayout {
    val diameter = min(pieMaxDiameter, max(80.0, width - 32.0))
    val size = diameter + 8.0
    val cx = size / 2
    val cy = size / 2
    val r = diameter / 2
    val total = data.total
    var at = 0.0
    val slices = data.entries.mapIndexed { i, entry ->
        val share = if (total > 0) entry.value / total else 0.0
        val start = at
        val end = if (i == data.entries.lastIndex) 2 * PI else at + share * 2 * PI
        at = end
        val (tipX, tipY) = pointOnCircle(cx, cy, r * 0.6, (start + end) / 2)
        PieSliceMark(i, entry, share, slicePath(cx, cy, r, start, end), tipX, tipY)
    }
    return PieChartLayout(size, cx, cy, r, slices)
}
