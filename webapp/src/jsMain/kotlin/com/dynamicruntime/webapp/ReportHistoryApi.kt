package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.report.RHIS
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.ReportSnapshotTrigger
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.formatDay
import com.dynamicruntime.common.util.parseDateOrNull
import com.dynamicruntime.common.util.toDay
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

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
    /**
     * The official day it was taken on (the deployment's day start, issue #1115), `yyyy-MM-dd`: how snapshots are told
     * to be of one day, by the kernel rule the backend's pruning uses -- never the viewer's zone, so every client's
     * history buckets a day alike.
     */
    val day: String get() = takenAt.parseDateOrNull()?.toDay()?.formatDay() ?: takenAt.take(10)
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

/**
 * The page size the history is walked at: past what the backend keeps of a report by default (a snapshot a day for
 * 400 days, and today's), so a year of history is one request rather than five in a row, each waiting on the last
 * one's cursor. A deployment keeping more is walked in pages, as before.
 */
const val reportHistoryPageSize = 500

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

// --- the series ------------------------------------------------------------------------------------------------------

/** One group of a history: what tells it from another across snapshots ([key]), and what it is called. */
class HistoryGroup(val key: String, val label: String)

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
    val metric: ReportMeasure,
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
 * takes the next free place.
 *
 * That holds while the groups fit. When there are more than [maxGroups], the ones kept are those with the most forms
 * on the latest day (not the most of the metric, so that switching metric still repaints nothing), in that same
 * first-seen order -- and there a **new snapshot can** repaint: a group entering the kept set takes its first-seen
 * place among them, and those after it move down one. Eight colours cannot be a fixed place for a ninth group.
 */
fun historySeries(snapshots: List<ReportSnapshot>, metric: ReportMeasure, maxGroups: Int = historyMaxGroups): HistorySeries {
    val current = snapshots.filter { it.sameDefinition }
    val days = latestPerDay(current)
    // A group is named by the newest snapshot whose column carries its choices (issue #1438): one taken before columns
    // carried them would otherwise name a group by its stored value for the life of the series.
    val labelColumns = days.flatMap { it.columns }.groupBy { it.columnId }.values
        .map { cs -> cs.lastOrNull { it.choices.isNotEmpty() } ?: cs.last() }
    // First seen, oldest day first; a day's rows are in the run's key order.
    val seen = LinkedHashMap<String, HistoryGroup>()
    for (day in days) {
        for (row in day.rows) {
            val key = reportGroupKey(row.group, day.groupBy)
            if (key !in seen) seen[key] = HistoryGroup(key, reportGroupLabel(row.group, day.groupBy, labelColumns))
        }
    }
    val rowsByDay = days.map { day -> day.rows.associateBy { reportGroupKey(it.group, day.groupBy) } }
    val latest = rowsByDay.lastOrNull().orEmpty()
    val keptKeys = if (seen.size <= maxGroups) {
        seen.keys
    } else {
        seen.keys.sortedByDescending { latest[it]?.count ?: 0 }.take(maxGroups).toSet()
    }
    val groups = seen.values.filter { it.key in keptKeys }
    val folded = seen.keys.filter { it !in keptKeys }
    val values = rowsByDay.map { rows -> groups.map { g -> rows[g.key]?.let { measureValue(it, metric) } } }
    val otherValues = if (folded.isEmpty() || !metric.additive) {
        null
    } else {
        rowsByDay.map { rows -> folded.mapNotNull { key -> rows[key]?.let { measureValue(it, metric) } }.takeIf { it.isNotEmpty() }?.sum() }
    }
    return HistorySeries(metric, days, groups, values, folded.size, otherValues, snapshots.size - current.size)
}

// --- numbers and dates as the chart writes them ------------------------------------------------------------------------

private val historyMonths = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** A `yyyy-MM-dd` day as an axis label: `Oct 2`. Anything else is left as it is. */
fun historyDayLabel(day: String): String {
    if (day.length != 10) return day
    val month = day.substring(5, 7).toIntOrNull()?.takeIf { it in 1..12 } ?: return day
    val dayOfMonth = day.substring(8).toIntOrNull() ?: return day
    return "${historyMonths[month - 1]} $dayOfMonth"
}

// --- the chart's geometry ----------------------------------------------------------------------------------------------

/** One bar: whose it is, where it stands, and the outline it is drawn as -- rounded at its data end, square at the baseline. */
class HistoryBar(
    val dayIndex: Int,
    /** The group's place in [HistorySeries.groups], or [chartOtherIndex]. */
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
    val ticks: List<ChartTick>,
)

/**
 * The chart of [series], in a box [width] wide with a plot [plotHeight] high.
 *
 * Each day is a slot holding one bar per group (and "Other", last); bars are as thick as the slot allows up to
 * [CHT.barMax], and when the days are too many for every bar to be at least [CHT.barMin], only the **latest** that
 * fit are drawn -- the table under the chart has them all. The y axis starts at zero (below it when a value is
 * negative) and ends on a tick; bars grow from zero.
 */
fun historyChartLayout(series: HistorySeries, width: Double = CHT.maxWidth, plotHeight: Double = 240.0): HistoryChartLayout {
    val plotLeft = CHT.marginLeft
    val plotRight = width - CHT.marginRight
    val plotTop = CHT.marginTop
    val plotBottom = plotTop + plotHeight
    val plotWidth = plotRight - plotLeft
    val perSlot = series.groups.size + (if (series.otherDrawn) 1 else 0)
    val barCount = max(1, perSlot)
    val minSlot = barCount * CHT.barMin + (barCount - 1) * CHT.barGap + CHT.slotGap
    val fits = max(1, floor(plotWidth / minSlot).toInt())
    val firstDay = max(0, series.days.size - fits)
    val shown = series.days.size - firstDay
    val slotWidth = if (shown == 0) plotWidth else plotWidth / shown
    val barWidth = min(CHT.barMax, max(CHT.barMin, (slotWidth - CHT.slotGap - (barCount - 1) * CHT.barGap) / barCount))
    val cluster = barCount * barWidth + (barCount - 1) * CHT.barGap

    // The y scale, over what is drawn.
    val drawn = (firstDay until series.days.size).flatMap { d -> series.values[d].filterNotNull() + listOfNotNull(series.otherValues?.get(d)) }
    val (lo, hi, step) = chartAxis(drawn.minOrNull() ?: 0.0, drawn.maxOrNull() ?: 0.0, wholeOnly = series.metric.key == RRUN.count)
    fun yOf(v: Double): Double = plotBottom - (v - lo) / (hi - lo) * plotHeight
    val baselineY = yOf(0.0)
    val ticks = buildList {
        var v = lo
        while (v <= hi + step * 1e-6) {
            add(ChartTick(yOf(v), v, chartNumberText(v)))
            v += step
        }
    }

    val labelEvery = max(1, ceil(CHT.labelWidth / slotWidth).toInt())
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
            (if (series.otherDrawn) listOf(chartOtherIndex to series.otherValues?.get(d)) else emptyList())
        dayValues.forEachIndexed { place, (groupIndex, value) ->
            if (value == null) return@forEachIndexed
            val x = clusterX + place * (barWidth + CHT.barGap)
            val yValue = yOf(value)
            val y = min(yValue, baselineY)
            val h = kotlin.math.abs(baselineY - yValue)
            bars.add(HistoryBar(d, groupIndex, x, y, barWidth, h, value, barPath(x, y, barWidth, h, upward = value >= 0)))
        }
    }
    return HistoryChartLayout(
        width, plotBottom + CHT.axisBand, plotLeft, plotRight, plotTop, plotBottom, baselineY, firstDay, slots, bars, ticks,
    )
}

/**
 * The bar the Tab key lands on when the chart is entered: the **latest** day's first -- the day the eye lands on --
 * so the chart is one stop on the way through the page, and the arrows move within it. -1 when there are no bars.
 */
fun historyBarEntry(bars: List<HistoryBar>): Int {
    val latest = bars.lastOrNull()?.dayIndex ?: return -1
    return bars.indexOfFirst { it.dayIndex == latest }
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
