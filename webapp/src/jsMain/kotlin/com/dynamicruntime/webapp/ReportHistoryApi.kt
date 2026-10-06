package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.report.RHIS
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.gedra.report.ReportSnapshotTrigger
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * The Reports page's History view (issue #1037): a report's stored snapshots (#1034), parsed, and everything the
 * view derives from them that needs no React -- which metrics can be charted, the series a metric makes, and the
 * geometry of the bar chart it is drawn as. Pure, and covered under `jsNodeTest` (`ReportHistoryTest`); the component
 * is in `ReportHistoryPanel.kt`.
 */

/** One stored snapshot: a report's grouped run as it was at [takenAt], in the run's own row shape. */
class ReportSnapshot(
    val snapshotId: Long,
    /** When it was taken, as the wire carries it: ISO, UTC. */
    val takenAt: String,
    /** A [ReportSnapshotTrigger] name. */
    val trigger: String,
    val launchName: String?,
    val groupBy: List<String>,
    val columns: List<ReportColumnInfo>,
    val rows: List<ReportRunRow>,
    val scanned: Int,
    val excluded: Int,
    /** Whether the rows were cut at the stored limit. */
    val truncated: Boolean,
    /** Whether it was taken under the report's definition as bound now: the same grouping and columns. */
    val sameDefinition: Boolean,
) {
    /** The UTC day it was taken on, `yyyy-MM-dd`: how snapshots are told to be of one day, as the backend's pruning tells them. */
    val day: String get() = takenAt.take(10)
}

/** One page of a report's history, newest first. [next] is the cursor of the page after it, null on the last. */
class ReportHistoryPage(val snapshots: List<ReportSnapshot>, val numAvailable: Int, val next: String?)

/** One snapshot off the wire; one with no id or no moment is not a snapshot. */
fun parseSnapshot(raw: Map<String, Any?>): ReportSnapshot? {
    val id = (raw[RHIS.snapshotId] as? Number)?.toLong() ?: return null
    val takenAt = raw[RHIS.takenAt].toOptStr() ?: return null
    return ReportSnapshot(
        snapshotId = id,
        takenAt = takenAt,
        trigger = raw[RHIS.trigger].toOptStr() ?: ReportSnapshotTrigger.manual.name,
        launchName = raw[RHIS.launchName].toOptStr(),
        groupBy = raw[RRUN.groupBy].toJsonListOfStrings(),
        columns = parseReportColumns(raw[RRUN.columns]),
        rows = raw[RHIS.rows].toJsonListOfMaps().map { parseRunRow(it) },
        scanned = (raw[RRUN.scanned] as? Number)?.toInt() ?: 0,
        excluded = (raw[RRUN.excluded] as? Number)?.toInt() ?: 0,
        truncated = raw[RHIS.truncated] == true,
        sameDefinition = raw[RHIS.sameDefinition] != false,
    )
}

/** The history endpoint's envelope ([UADEP.reportHistory]) as a [ReportHistoryPage]. */
fun parseHistoryPage(envelope: Map<String, Any?>): ReportHistoryPage = ReportHistoryPage(
    snapshots = envelope[EP.items].toJsonListOfMaps().mapNotNull { parseSnapshot(it) },
    numAvailable = (envelope[EP.numAvailable] as? Number)?.toInt() ?: 0,
    next = envelope[EP.next].toOptStr(),
)

/** The page size the history is walked at: the listing's own default. */
const val reportHistoryPageSize = 100

/** The history endpoint's query; [client] is sent only when given, as the run's is. */
fun reportHistoryQuery(reportId: String, client: String?, after: String?, limit: Int = reportHistoryPageSize): Map<String, Any?> = buildMap {
    put(RRUN.reportId, reportId)
    client?.let { put(RRUN.client, it) }
    after?.let { put(EP.after, it) }
    put(EP.limit, limit)
}

/** The snapshot endpoint's body. */
fun reportSnapshotBody(reportId: String, client: String?): Map<String, Any?> = buildMap {
    put(RRUN.reportId, reportId)
    client?.let { put(RRUN.client, it) }
}

/**
 * The whole of a report's history, newest first: every page walked by its cursor. Stops at [maxSnapshots] -- far past
 * what the backend keeps -- or after [maxPages], so a cursor that never ended cannot walk for ever.
 */
suspend fun walkReportHistory(
    fetchPage: suspend (after: String?) -> ReportHistoryPage,
    maxSnapshots: Int = 2000,
    maxPages: Int = 100,
): List<ReportSnapshot> {
    val out = mutableListOf<ReportSnapshot>()
    var after: String? = null
    for (i in 0 until maxPages) {
        val page = fetchPage(after)
        out.addAll(page.snapshots)
        after = page.next
        if (after == null || out.size >= maxSnapshots) break
    }
    return out
}

/** How a snapshot's trigger reads on the page. */
fun snapshotTriggerText(trigger: String): String = when (trigger) {
    ReportSnapshotTrigger.scheduled.name -> "nightly"
    ReportSnapshotTrigger.simulated.name -> "simulated"
    else -> "by hand"
}

// --- what can be charted ---------------------------------------------------------------------------------------------

/**
 * One thing a history can be charted by: how many forms each group held, or one of the report's rolled-up columns.
 * [additive] says whether several groups' values may be added into one (a count, a sum) -- which is what lets groups
 * past the chart's limit be drawn as one "Other" bar; an average or a maximum of groups is not their sum.
 */
class HistoryMetric(val key: String, val label: String, val additive: Boolean)

/**
 * The metrics a report's history offers: the count of forms first, then each rolled-up column whose result is a
 * number -- a count of anything, or a numeric column's sum, average, minimum or maximum -- and that the report does
 * not group by. A rolled-up date (the latest audit, say) is a moment, not a height, so it is not offered.
 */
fun historyMetrics(columns: List<ReportColumnInfo>, groupBy: List<String>): List<HistoryMetric> = buildList {
    add(HistoryMetric(RRUN.count, reportCountLabel, additive = true))
    for (c in columns) {
        val rollup = c.rollup ?: continue
        if (c.columnId in groupBy) continue
        val counted = rollup == ReportCombine.count.name
        if (!counted && c.kind != ReportKind.number.name) continue
        add(HistoryMetric(c.columnId, "${c.label} ($rollup)", additive = counted || rollup == ReportCombine.sum.name))
    }
}

/** [row]'s value for [metric], or null when the group has none. */
fun metricValue(row: ReportRunRow, metric: HistoryMetric): Double? =
    if (metric.key == RRUN.count) row.count?.toDouble() else (row.values[metric.key] as? Number)?.toDouble()

// --- the series ------------------------------------------------------------------------------------------------------

/** One group of a history: what tells it from another across snapshots ([key]), and what it is called. */
class HistoryGroup(val key: String, val label: String)

/** What the one group of a report that groups by nothing is called. */
const val historyAllFormsLabel = "All forms"

/** What tells a group from another across snapshots: its grouped-by values, in order, as they print. */
fun historyGroupKey(group: Map<String, Any?>, groupBy: List<String>): String =
    groupBy.joinToString("\u001f") { id -> group[id]?.let { v -> if (v is Number) reportNumberText(v.toDouble()) else v.toString() } ?: "\u0000" }

/** What a group is called: its grouped-by values as the run's table shows them, joined; "All forms" when there are none. */
fun historyGroupLabel(group: Map<String, Any?>, groupBy: List<String>, columns: List<ReportColumnInfo>): String {
    if (groupBy.isEmpty()) return historyAllFormsLabel
    val byId = columns.associateBy { it.columnId }
    return groupBy.joinToString(" / ") { id -> reportCellText(group[id], byId[id]?.kind ?: ReportKind.string.name, ReportCellSource.group) }
}

/**
 * One snapshot per day, oldest day first: of a day's several -- the nightly one and any taken by hand -- the latest,
 * which is what the backend keeps of a past day too. A chart of days has one bar cluster a day.
 */
fun latestPerDay(snapshots: List<ReportSnapshot>): List<ReportSnapshot> =
    snapshots.groupBy { it.day }.map { (_, ofDay) -> ofDay.maxWith(compareBy<ReportSnapshot> { it.takenAt }.thenBy { it.snapshotId }) }
        .sortedBy { it.day }

/** The most groups a chart draws in colours of their own: the categorical palette's eight slots. */
const val historyMaxGroups = 8

/**
 * A history as a chart reads it: [days] oldest first, the [groups] drawn each with a colour of its own, and
 * [values] by day then group -- null where a group had no value that day, which draws nothing.
 *
 * Groups past the limit are folded: [otherCount] of them, drawn as one "Other" bar ([otherValues], by day) when the
 * metric can be added, and left to the table when it cannot.
 */
class HistorySeries(
    val metric: HistoryMetric,
    val days: List<ReportSnapshot>,
    val groups: List<HistoryGroup>,
    val values: List<List<Double?>>,
    val otherCount: Int,
    /** The folded groups' sum by day, or null when the metric cannot be added (or nothing was folded). */
    val otherValues: List<Double?>?,
    /** How many snapshots were left out for being taken under another definition of the report. */
    val otherDefinitions: Int,
) {
    /** Whether "Other" is a bar of its own. */
    val otherDrawn: Boolean get() = otherValues != null
}

/**
 * The series [metric] makes of [snapshots] (in any order). Only snapshots taken under the report's **current**
 * definition are charted: a series spanning a change of grouping is not one question.
 *
 * A group's place -- and so its colour -- is the order groups were **first seen**, oldest day first, never its rank
 * by the metric: switching metric, or a group overtaking another, repaints nothing, and a group that appears later
 * takes the next free place. When there are more than [maxGroups], the ones kept are those with the most forms on
 * the latest day (not the most of the metric, for the same reason), in that same first-seen order.
 */
fun historySeries(snapshots: List<ReportSnapshot>, metric: HistoryMetric, maxGroups: Int = historyMaxGroups): HistorySeries {
    val current = snapshots.filter { it.sameDefinition }
    val days = latestPerDay(current)
    // First seen, oldest day first; a day's rows are in the run's key order.
    val seen = LinkedHashMap<String, HistoryGroup>()
    for (day in days) {
        for (row in day.rows) {
            val key = historyGroupKey(row.group, day.groupBy)
            if (key !in seen) seen[key] = HistoryGroup(key, historyGroupLabel(row.group, day.groupBy, day.columns))
        }
    }
    val rowsByDay = days.map { day -> day.rows.associateBy { historyGroupKey(it.group, day.groupBy) } }
    val latest = rowsByDay.lastOrNull().orEmpty()
    val keptKeys = if (seen.size <= maxGroups) {
        seen.keys
    } else {
        seen.keys.sortedByDescending { latest[it]?.count ?: 0 }.take(maxGroups).toSet()
    }
    val groups = seen.values.filter { it.key in keptKeys }
    val folded = seen.keys.filter { it !in keptKeys }
    val values = rowsByDay.map { rows -> groups.map { g -> rows[g.key]?.let { metricValue(it, metric) } } }
    val otherValues = if (folded.isEmpty() || !metric.additive) {
        null
    } else {
        rowsByDay.map { rows -> folded.mapNotNull { key -> rows[key]?.let { metricValue(it, metric) } }.takeIf { it.isNotEmpty() }?.sum() }
    }
    return HistorySeries(metric, days, groups, values, folded.size, otherValues, snapshots.size - current.size)
}

// --- numbers and dates as the chart writes them ------------------------------------------------------------------------

/** A number as the chart writes it: as a cell does, with the thousands of its whole part set apart. */
fun historyNumberText(value: Double): String {
    val text = reportNumberText(value)
    val negative = text.startsWith("-")
    val body = if (negative) text.drop(1) else text
    val whole = body.substringBefore('.')
    if (whole.any { !it.isDigit() }) return text
    val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
    return (if (negative) "-" else "") + grouped + body.drop(whole.length)
}

private val historyMonths = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** A `yyyy-MM-dd` day as an axis label: `Oct 2`. Anything else is left as it is. */
fun historyDayLabel(day: String): String {
    val month = day.substring(5.coerceAtMost(day.length), 7.coerceAtMost(day.length)).toIntOrNull()
    val dayOfMonth = day.substring(8.coerceAtMost(day.length)).toIntOrNull()
    return if (day.length == 10 && month != null && month in 1..12 && dayOfMonth != null) "${historyMonths[month - 1]} $dayOfMonth" else day
}

// --- the chart's geometry ----------------------------------------------------------------------------------------------

/** The index a bar carries for the "Other" group, which has no place among the coloured ones. */
const val historyOtherIndex = -1

/** One bar: whose it is, where it stands, and the outline it is drawn as -- rounded at its data end, square at the baseline. */
class HistoryBar(
    val dayIndex: Int,
    /** The group's place in [HistorySeries.groups], or [historyOtherIndex]. */
    val groupIndex: Int,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val value: Double,
    val path: String,
)

/** One day's place on the x axis; [label] is null where labels were thinned to fit. */
class HistorySlot(val dayIndex: Int, val x: Double, val width: Double, val label: String?)

/** One gridline of the y axis. */
class HistoryTick(val y: Double, val value: Double, val label: String)

/**
 * A history's bar chart, laid out in pixels, [width] by [height]: the plot's box, the baseline,
 * the days that fit ([firstDay] is the index of the oldest one drawn), their bars and the y axis's ticks.
 */
class HistoryChartLayout(
    val width: Double,
    val height: Double,
    val plotLeft: Double,
    val plotRight: Double,
    val plotTop: Double,
    val plotBottom: Double,
    val baselineY: Double,
    val firstDay: Int,
    val slots: List<HistorySlot>,
    val bars: List<HistoryBar>,
    val ticks: List<HistoryTick>,
)

/** The marks' fixed measures: a bar no thicker than 24 and no thinner than 4, 2 between neighbours, 4 of rounding. */
@Suppress("ConstPropertyName")
object HCH {
    const val barMax = 24.0
    const val barMin = 4.0
    const val barGap = 2.0
    const val barRadius = 4.0
    /** The least air between one day's cluster and the next. */
    const val slotGap = 16.0
    const val marginLeft = 56.0
    const val marginRight = 12.0
    const val marginTop = 12.0
    /** The band under the plot that holds the day labels: part of the chart's height, so they are never clipped. */
    const val axisBand = 28.0
    /** The least width a day label needs; labels are thinned to keep it. */
    const val labelWidth = 48.0
    /** The widest the chart is drawn, and the narrowest: past either it stops following its pane. */
    const val maxWidth = 760.0
    const val minWidth = 320.0
}

/**
 * The width the chart is laid out at for a pane [available] pixels wide: the pane's own, within [HCH.minWidth] and
 * [HCH.maxWidth], in whole pixels. Laid out at its real width rather than scaled to it, so its text stays the size
 * it is set at however narrow the pane.
 */
fun historyChartWidth(available: Double): Double = floor(min(HCH.maxWidth, max(HCH.minWidth, available)))

/**
 * The step between y-axis ticks for values spanning [range]: the smallest of 1, 2 or 5 times a power of ten that
 * divides it into at most four -- so ticks are clean numbers (0, 50, 100, 150) rather than fractions of the maximum.
 * Never under 1 for [wholeOnly] values: there is no half a form.
 */
fun niceTickStep(range: Double, wholeOnly: Boolean = false): Double {
    if (range <= 0.0 || range.isNaN()) return 1.0
    val raw = range / 4
    val magnitude = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= raw * (1 - 1e-9) }
    return if (wholeOnly) max(1.0, step) else step
}

/**
 * The chart of [series], in a box [width] wide with a plot [plotHeight] high.
 *
 * Each day is a slot holding one bar per group (and "Other", last); bars are as thick as the slot allows up to
 * [HCH.barMax], and when the days are too many for every bar to be at least [HCH.barMin], only the **latest** that
 * fit are drawn -- the table under the chart has them all. The y axis starts at zero (below it when a value is
 * negative) and ends on a tick; bars grow from zero.
 */
fun historyChartLayout(series: HistorySeries, width: Double = HCH.maxWidth, plotHeight: Double = 240.0): HistoryChartLayout {
    val plotLeft = HCH.marginLeft
    val plotRight = width - HCH.marginRight
    val plotTop = HCH.marginTop
    val plotBottom = plotTop + plotHeight
    val plotWidth = plotRight - plotLeft
    val perSlot = series.groups.size + (if (series.otherDrawn) 1 else 0)
    val barCount = max(1, perSlot)
    val minSlot = barCount * HCH.barMin + (barCount - 1) * HCH.barGap + HCH.slotGap
    val fits = max(1, floor(plotWidth / minSlot).toInt())
    val firstDay = max(0, series.days.size - fits)
    val shown = series.days.size - firstDay
    val slotWidth = if (shown == 0) plotWidth else plotWidth / shown
    val barWidth = min(HCH.barMax, max(HCH.barMin, (slotWidth - HCH.slotGap - (barCount - 1) * HCH.barGap) / barCount))
    val cluster = barCount * barWidth + (barCount - 1) * HCH.barGap

    // The y scale, over what is drawn.
    val drawn = (firstDay until series.days.size).flatMap { d -> series.values[d].filterNotNull() + listOfNotNull(series.otherValues?.get(d)) }
    val top = max(0.0, drawn.maxOrNull() ?: 0.0)
    val bottom = min(0.0, drawn.minOrNull() ?: 0.0)
    val step = niceTickStep(top - bottom, wholeOnly = series.metric.key == RRUN.count)
    val hi = if (top == 0.0 && bottom == 0.0) step else ceil(top / step - 1e-9) * step
    val lo = floor(bottom / step + 1e-9) * step
    fun yOf(v: Double): Double = plotBottom - (v - lo) / (hi - lo) * plotHeight
    val baselineY = yOf(0.0)
    val ticks = buildList {
        var v = lo
        while (v <= hi + step * 1e-6) {
            add(HistoryTick(yOf(v), v, historyNumberText(v)))
            v += step
        }
    }

    val labelEvery = max(1, ceil(HCH.labelWidth / slotWidth).toInt())
    val slots = mutableListOf<HistorySlot>()
    val bars = mutableListOf<HistoryBar>()
    for (i in 0 until shown) {
        val d = firstDay + i
        val slotX = plotLeft + i * slotWidth
        // Thinned from the latest day backwards, so the day the eye lands on is always named.
        val label = if ((shown - 1 - i) % labelEvery == 0) historyDayLabel(series.days[d].day) else null
        slots.add(HistorySlot(d, slotX, slotWidth, label))
        val clusterX = slotX + (slotWidth - cluster) / 2
        val dayValues = series.values[d].mapIndexed { g, v -> g to v } +
            (if (series.otherDrawn) listOf(historyOtherIndex to series.otherValues?.get(d)) else emptyList())
        dayValues.forEachIndexed { place, (groupIndex, value) ->
            if (value == null) return@forEachIndexed
            val x = clusterX + place * (barWidth + HCH.barGap)
            val yValue = yOf(value)
            val y = min(yValue, baselineY)
            val h = kotlin.math.abs(baselineY - yValue)
            bars.add(HistoryBar(d, groupIndex, x, y, barWidth, h, value, barPath(x, y, barWidth, h, upward = value >= 0)))
        }
    }
    return HistoryChartLayout(
        width, plotBottom + HCH.axisBand, plotLeft, plotRight, plotTop, plotBottom, baselineY, firstDay, slots, bars, ticks,
    )
}

/**
 * A bar's outline: its data end rounded by [HCH.barRadius] (less for a bar too thin or too short to carry it), its
 * baseline end square. [upward] bars round at the top; a negative value's bar hangs from the baseline and rounds below.
 */
fun barPath(x: Double, y: Double, width: Double, height: Double, upward: Boolean): String {
    val r = min(HCH.barRadius, min(width / 2, height))
    fun n(v: Double): String = ((v * 100).toLong() / 100.0).toString()
    val right = x + width
    val bottom = y + height
    return if (upward) {
        "M${n(x)},${n(bottom)} V${n(y + r)} Q${n(x)},${n(y)} ${n(x + r)},${n(y)} H${n(right - r)} Q${n(right)},${n(y)} ${n(right)},${n(y + r)} V${n(bottom)} Z"
    } else {
        "M${n(x)},${n(y)} V${n(bottom - r)} Q${n(x)},${n(bottom)} ${n(x + r)},${n(bottom)} H${n(right - r)} Q${n(right)},${n(bottom)} ${n(right)},${n(bottom - r)} V${n(y)} Z"
    }
}

// --- the table under the chart -------------------------------------------------------------------------------------------

/** How many days the chart leaves out for want of room: the table has them. */
fun historyDaysNotDrawn(layout: HistoryChartLayout): Int = layout.firstDay

/** The line that says what the history holds: how many snapshots over how many days, and when and how the latest was taken. */
fun historySummaryText(snapshots: List<ReportSnapshot>): String {
    if (snapshots.isEmpty()) return "No snapshots yet."
    val days = snapshots.map { it.day }.toSet().size
    val latest = snapshots.maxWith(compareBy<ReportSnapshot> { it.takenAt }.thenBy { it.snapshotId })
    val count = if (snapshots.size == 1) "1 snapshot" else "${snapshots.size} snapshots"
    val over = if (days == 1) "on 1 day" else "over $days days"
    return "$count $over; the latest ${formatTimestamp(latest.takenAt)} (${snapshotTriggerText(latest.trigger)})."
}

/** The History view's fetches (issue #1037), each a fetch and a pure parse. */
object ReportHistoryApi {
    /** Every snapshot of [reportId] for [client] -- the caller's own client when null -- newest first. */
    suspend fun all(reportId: String, client: String?): List<ReportSnapshot> = walkReportHistory({ after ->
        parseHistoryPage(Http.getApi(UADEP.reportHistory + queryString(reportHistoryQuery(reportId, client, after))))
    })

    /** Takes a snapshot now, and returns it. */
    suspend fun snapshot(reportId: String, client: String?): ReportSnapshot? =
        parseSnapshot(Http.sendApi("POST", UADEP.reportSnapshot, reportSnapshotBody(reportId, client))[EP.results].toJsonMapOrEmpty())
}
