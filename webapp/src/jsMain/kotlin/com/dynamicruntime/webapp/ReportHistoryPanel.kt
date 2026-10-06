package com.dynamicruntime.webapp

import com.dynamicruntime.common.exception.EXC
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import react.ChildrenBuilder
import react.FC
import react.Key
import react.Props
import react.dom.aria.AriaRole
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h3
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.strong
import react.dom.html.ReactHTML.table
import react.dom.html.ReactHTML.tbody
import react.dom.html.ReactHTML.td
import react.dom.html.ReactHTML.th
import react.dom.html.ReactHTML.thead
import react.dom.html.ReactHTML.tr
import react.dom.svg.DominantBaseline
import react.dom.svg.ReactSVG.line
import react.dom.svg.ReactSVG.path
import react.dom.svg.ReactSVG.rect
import react.dom.svg.ReactSVG.svg
import react.dom.svg.ReactSVG.text
import react.dom.svg.TextAnchor
import react.useEffect
import react.useEffectOnce
import react.useMemo
import react.useRef
import react.useState
import web.cssom.ClassName
import web.dom.ElementId

private val historyScope = MainScope()

external interface ReportHistoryPanelProps : Props {
    /** The client an `allClients` administrator chose; null for a client's own administrator, as on the run. */
    var client: String?
    var report: ReportInfo
}

/** A report's fetched history, with the report it was fetched for: another report's is never drawn under this one's heading. */
private class HistoryShown(val key: String, val snapshots: List<ReportSnapshot>)

/**
 * A fetch of a report's history that failed, with the report it failed for: said under that report only, never under
 * the next one's heading while its own history is on its way. A 403 is a [refusal], in the endpoint's words.
 */
private class HistoryFailure(val key: String, val error: DisplayError?, val refusal: String?)

/** The bar the pointer or the keyboard is on. */
private class HistoryHover(val dayIndex: Int, val groupIndex: Int)

/**
 * A report's **History** view (issue #1037): its stored snapshots (#1034) as a bar chart over days -- one cluster a
 * day, one bar a group -- with a table of the same numbers under it, a choice of what to chart, and **Snapshot now**.
 *
 * The series, its colours' places and the chart's geometry are all the pure half's (`ReportHistoryApi.kt`); this
 * draws them. The chart is hand-drawn SVG laid out at the pane's own width -- measured, and followed as the pane
 * resizes -- rather than scaled to it, so its text keeps its size; the hover readout is HTML over it, placed in the
 * same pixels. Every bar can be reached by the pointer or the keyboard and says the same thing either way, and
 * nothing the readout says is missing from the table. To the keyboard the chart is **one** Tab stop -- the latest
 * day's first bar -- and the arrows, Home and End move among the bars (`historyBarStep`); a chart of sixty bars is
 * not sixty stops between the controls and the table.
 *
 * A refetch -- after a snapshot, or on the app's refresh -- keeps the chart on screen, dimmed, until its replacement
 * arrives. An administrator confined to an organization is refused by the endpoint (history is client-wide), and
 * the refusal is shown in its words.
 */
val ReportHistoryPanel = FC<ReportHistoryPanelProps> { props ->
    val generation = useRefreshGeneration()
    val report = props.report
    val key = "${props.client.orEmpty()}|${report.reportId}"

    var shown by useState<HistoryShown?>(null)
    var failure by useState<HistoryFailure?>(null)
    // Not `loading`: inside a Button's builder that name is the button's own property, and a local would shadow it.
    var fetching by useState(false)
    var metricKey by useState<String?>(null)
    var taking by useState(false)
    var takeError by useState<DisplayError?>(null)
    var note by useState<String?>(null)
    var reload by useState(0)
    var hover by useState<HistoryHover?>(null)
    // The bar the keyboard is on, apart from the pointer's: the pointer leaving a bar must not move the Tab stop.
    var focus by useState<HistoryHover?>(null)
    // Monotonic, so a slow answer for a report the user has moved on from is dropped.
    val latest = useRef(0)
    // The width the chart is laid out at: the panel's own, followed as it changes.
    var chartWidth by useState(HCH.maxWidth)
    useEffectOnce {
        val panel = document.getElementById(historyPanelId) ?: return@useEffectOnce
        val measure: () -> Unit = { chartWidth = historyChartWidth(panel.clientWidth.toDouble()) }
        measure()
        val observer = resizeObserver(measure)
        observer.observe(panel)
        // The effect's scope is cancelled when the component goes: suspend until then, and stop observing.
        try {
            awaitCancellation()
        } finally {
            observer.disconnect()
        }
    }

    useEffect(key) {
        // Another report: nothing said about the last one carries over.
        note = null
        takeError = null
        hover = null
        focus = null
    }
    useEffect(key, generation, reload) {
        val token = (latest.current ?: 0) + 1
        latest.current = token
        fetching = true
        historyScope.launch {
            try {
                val snapshots = ReportHistoryApi.all(report.reportId, props.client)
                if (latest.current == token) {
                    shown = HistoryShown(key, snapshots)
                    failure = null
                    fetching = false
                }
            } catch (e: Throwable) {
                if (latest.current != token) return@launch
                failure = if ((e as? ApiError)?.status == EXC.notAuthorized) {
                    HistoryFailure(key, null, e.message ?: "Report history is not open to you.")
                } else {
                    HistoryFailure(key, userFacingError(e), null)
                }
                fetching = false
            }
        }
    }

    fun take() {
        val token = latest.current
        taking = true
        takeError = null
        note = null
        historyScope.launch {
            try {
                ReportHistoryApi.snapshot(report.reportId, props.client)
                if (latest.current == token) {
                    note = "Snapshot taken."
                    reload += 1
                }
            } catch (e: Throwable) {
                if (latest.current == token) takeError = userFacingError(e)
            } finally {
                taking = false
            }
        }
    }

    val metrics = historyMetrics(report.columns, report.groupBy)
    val metric = metrics.firstOrNull { it.key == metricKey } ?: metrics.first()
    val snapshots = shown?.takeIf { it.key == key }?.snapshots
    val failed = failure?.takeIf { it.key == key }
    val error = failed?.error
    val refusal = failed?.refusal
    // Made once per history, metric and width -- not on every render: the readout following the pointer re-renders
    // the panel at each bar it crosses, and regrouping every snapshot's rows for that would be work for nothing.
    val series = useMemo(snapshots, report, metric.key) { snapshots?.let { historySeries(it, metric) } }
    val layout = useMemo(series, chartWidth) { series?.let { historyChartLayout(it, chartWidth) } }

    div {
        id = ElementId(historyPanelId)
        // One row above everything it scopes: what to chart, and taking a snapshot.
        div {
            className = ClassName("row reports-controls")
            if (metrics.size > 1) {
                span {
                    className = ClassName("type-hint")
                    +"Show:"
                }
                Select {
                    value = metric.key
                    options = metrics.map { m ->
                        val option: dynamic = js("({})")
                        option.label = m.label
                        option.value = m.key
                        option
                    }.toTypedArray()
                    style = js("({ minWidth: 220 })")
                    onChange = { v -> metricKey = v as? String }
                }
            }
            if (refusal == null) {
                Button {
                    size = "small"
                    loading = taking
                    onClick = { take() }
                    +"Snapshot now"
                }
            }
        }
        takeError?.let { errorText("Couldn't take the snapshot.", it) }
        note?.let {
            p {
                className = ClassName("subtitle")
                +it
            }
        }

        when {
            refusal != null -> p {
                className = ClassName("subtitle")
                +refusal
            }
            snapshots == null || series == null || layout == null -> {
                error?.let { errorText("Couldn't load the report's history.", it) }
                if (error == null) p {
                    className = ClassName("subtitle")
                    +"Loading…"
                }
            }
            else -> {
                // A refresh that failed with the history already up: said, with what was loaded kept.
                error?.let { errorText("Couldn't refresh the history; showing what was loaded.", it) }
                p {
                    className = ClassName("subtitle")
                    +historySummaryText(snapshots)
                }
                if (series.days.isEmpty()) {
                    p {
                        className = ClassName("subtitle")
                        +historyEmptyText(report, series.otherDefinitions)
                    }
                } else {
                    historyChart(report, series, layout, hover, focus, fetching, hoverAt = { hover = it }, focusAt = { focus = it })
                    historyNotes(series)
                    historyTable(series)
                }
            }
        }
    }
}

/** The panel's element id, which the width it is laid out at is measured from. */
private const val historyPanelId = "report-history-panel"

/** The id of the hit target of the bar at [index] of the chart's bars: what the arrow keys move the focus to. */
private fun historyHitId(index: Int): String = "rh-hit-$index"

/** A `ResizeObserver` calling [changed] whenever what it observes changes size. */
private fun resizeObserver(changed: () -> Unit): dynamic = js("new ResizeObserver(function () { changed(); })")

/** What an empty history says: what would fill it. */
private fun historyEmptyText(report: ReportInfo, otherDefinitions: Int): String = when {
    otherDefinitions > 0 ->
        "Its $otherDefinitions earlier snapshot${if (otherDefinitions == 1) " was" else "s were"} taken when the report was " +
            "defined differently, so there is nothing yet to chart under the report as it is now. Take one with Snapshot now."
    report.history -> "The nightly job takes a snapshot of this report; take one now with Snapshot now."
    else -> "This report does not ask for history, so nothing takes a snapshot of it nightly. Take one with Snapshot now."
}

/** The chart's title: what is charted, by what. The one colour of a single series needs no legend; this names it. */
private fun historyChartTitle(report: ReportInfo, series: HistorySeries): String {
    val by = report.groupBy.mapNotNull { id -> report.columns.firstOrNull { it.columnId == id }?.label }
    return if (by.isEmpty()) "${series.metric.label}, by day" else "${series.metric.label} by ${by.joinToString(" and ")}, by day"
}

/** The class that gives a group's mark its colour: its place among the series' groups, or the folded groups' grey. */
private fun colorClass(groupIndex: Int): String = if (groupIndex == historyOtherIndex) "rh-other" else "rh-c$groupIndex"

private fun groupName(series: HistorySeries, groupIndex: Int): String =
    if (groupIndex == historyOtherIndex) "Other (${series.otherCount} groups)" else series.groups[groupIndex].label

/**
 * The chart: its title, the legend (for two series or more -- one needs none), and the plot with its readout.
 * Text is in the text colours throughout; only the marks, and the swatches beside names, wear a series colour.
 *
 * The readout is of the bar the pointer is on ([hover]), else the one the keyboard is ([focus]). The plot is a
 * `group`, not an `img`: an image's children are not there for a screen reader, and each bar's hit target is one --
 * focusable, an `img` of its own, named "day, group: value".
 */
private fun ChildrenBuilder.historyChart(
    report: ReportInfo,
    series: HistorySeries,
    layout: HistoryChartLayout,
    hover: HistoryHover?,
    focus: HistoryHover?,
    refetching: Boolean,
    // Not `onHover`/`onFocus`: inside the rect's builder `onFocus = ...` would assign a parameter of that name.
    hoverAt: (HistoryHover?) -> Unit,
    focusAt: (HistoryHover?) -> Unit,
) {
    val at = hover ?: focus
    // The chart's one Tab stop: the bar the keyboard is on, else the one it enters at.
    val tabStop = focus?.let { f -> layout.bars.indexOfFirst { it.dayIndex == f.dayIndex && it.groupIndex == f.groupIndex } }
        ?.takeIf { it >= 0 } ?: historyBarEntry(layout.bars)
    val seriesCount = series.groups.size + (if (series.otherDrawn) 1 else 0)
    h3 {
        className = ClassName("rh-title")
        +historyChartTitle(report, series)
    }
    if (seriesCount >= 2) {
        div {
            className = ClassName("rh-legend")
            val entries = series.groups.indices.toList() + (if (series.otherDrawn) listOf(historyOtherIndex) else emptyList())
            entries.forEach { g ->
                span {
                    key = "l$g".unsafeCast<Key>()
                    className = ClassName("rh-legend-item")
                    span { className = ClassName("rh-swatch ${colorClass(g)}") }
                    +groupName(series, g)
                }
            }
        }
    }
    div {
        className = ClassName(if (refetching) "rh-chart rh-refetching" else "rh-chart")
        val box: dynamic = js("({})")
        box.width = "${layout.width}px"
        asDynamic().style = box
        svg {
            width = layout.width
            height = layout.height
            viewBox = "0 0 ${layout.width} ${layout.height}"
            asDynamic().className = "rh-svg"
            role = AriaRole.group
            ariaLabel = "${historyChartTitle(report, series)}. Arrow keys move between bars; the table below holds the same numbers."
            // The y axis: hairline gridlines one step off the surface, and the baseline a step firmer.
            layout.ticks.forEach { t ->
                line {
                    key = "g${t.value}".unsafeCast<Key>()
                    asDynamic().className = if (t.value == 0.0) "rh-baseline" else "rh-grid"
                    x1 = layout.plotLeft
                    x2 = layout.plotRight
                    y1 = t.y
                    y2 = t.y
                }
                text {
                    key = "t${t.value}".unsafeCast<Key>()
                    asDynamic().className = "rh-tick"
                    x = layout.plotLeft - 8
                    y = t.y
                    textAnchor = TextAnchor.end
                    dominantBaseline = DominantBaseline.middle
                    +t.label
                }
            }
            layout.slots.forEach { s ->
                val label = s.label ?: return@forEach
                text {
                    key = "d${s.dayIndex}".unsafeCast<Key>()
                    asDynamic().className = "rh-tick"
                    x = s.x + s.width / 2
                    y = layout.plotBottom + 18
                    textAnchor = TextAnchor.middle
                    +label
                }
            }
            layout.bars.forEach { b ->
                val hot = at != null && at.dayIndex == b.dayIndex && at.groupIndex == b.groupIndex
                path {
                    key = "b${b.dayIndex}_${b.groupIndex}".unsafeCast<Key>()
                    asDynamic().className = "rh-bar ${colorClass(b.groupIndex)}" + (if (hot) " rh-hot" else "")
                    d = b.path
                }
            }
            // The hit targets, over the bars: the bar's whole column of the plot, gap included, so a short bar is
            // as easy to land on as a tall one. Focusable, and saying on focus what they say on hover -- but only
            // one is a Tab stop, and the arrows move the focus from it (a roving tabindex).
            layout.bars.forEachIndexed { i, b ->
                rect {
                    key = "h${b.dayIndex}_${b.groupIndex}".unsafeCast<Key>()
                    asDynamic().className = "rh-hit"
                    asDynamic().id = historyHitId(i)
                    x = b.x - HCH.barGap / 2
                    y = layout.plotTop
                    width = b.width + HCH.barGap
                    height = layout.plotBottom - layout.plotTop
                    role = AriaRole.img
                    tabIndex = if (i == tabStop) 0 else -1
                    ariaLabel = "${series.days[b.dayIndex].day}, ${groupName(series, b.groupIndex)}: ${historyNumberText(b.value)}"
                    onMouseEnter = { hoverAt(HistoryHover(b.dayIndex, b.groupIndex)) }
                    onMouseLeave = { hoverAt(null) }
                    onFocus = { focusAt(HistoryHover(b.dayIndex, b.groupIndex)) }
                    onBlur = { focusAt(null) }
                    onKeyDown = { e ->
                        historyBarStep(i, layout.bars.size, e.key)?.let { next ->
                            e.preventDefault()
                            document.getElementById(historyHitId(next))?.asDynamic()?.focus()
                        }
                    }
                }
            }
        }
        val bar = at?.let { h -> layout.bars.firstOrNull { it.dayIndex == h.dayIndex && it.groupIndex == h.groupIndex } }
        if (bar != null) {
            // The readout: the value leads, then whose it is and when. Placed in the chart's own pixels.
            div {
                className = ClassName("rh-tip")
                val place: dynamic = js("({})")
                place.left = "${bar.x + bar.width / 2}px"
                place.top = "${bar.y}px"
                asDynamic().style = place
                strong { +historyNumberText(bar.value) }
                div {
                    className = ClassName("rh-tip-series")
                    span { className = ClassName("rh-key ${colorClass(bar.groupIndex)}") }
                    +groupName(series, bar.groupIndex)
                }
                div {
                    className = ClassName("rh-tip-date")
                    +series.days[bar.dayIndex].day
                }
            }
        }
    }
    val hidden = historyDaysNotDrawn(layout)
    if (hidden > 0) {
        p {
            className = ClassName("subtitle")
            +"The chart shows the latest ${layout.slots.size} of ${series.days.size} days; the table has them all."
        }
    }
}

/** What the chart leaves out, said: groups past its limit that cannot be added up, cut snapshots, another definition's. */
private fun ChildrenBuilder.historyNotes(series: HistorySeries) {
    val notes = buildList {
        if (series.otherCount > 0 && !series.otherDrawn) {
            add(
                "${series.otherCount} more group${if (series.otherCount == 1) "" else "s"} not shown: the chart draws the " +
                    "$historyMaxGroups with the most forms, and ${series.metric.label} cannot be added up into one \"Other\".",
            )
        }
        if (series.days.any { it.truncated }) add("Some snapshots held more groups than a snapshot stores, and were cut.")
        if (series.otherDefinitions > 0) {
            add(
                "${series.otherDefinitions} earlier snapshot${if (series.otherDefinitions == 1) " was" else "s were"} taken when the " +
                    "report was defined differently, and ${if (series.otherDefinitions == 1) "is" else "are"} not charted.",
            )
        }
    }
    notes.forEachIndexed { i, text ->
        p {
            key = "n$i".unsafeCast<Key>()
            className = ClassName("subtitle")
            +text
        }
    }
}

/**
 * The chart's table: a row a day, newest first -- every day, including those the chart had no room for -- with when
 * and how the day's snapshot was taken and each drawn group's value. What the hover readout says is all here.
 */
private fun ChildrenBuilder.historyTable(series: HistorySeries) {
    div {
        className = ClassName("op-table-scroll rh-table")
        table {
            className = ClassName("op-table")
            thead {
                tr {
                    th { +"Day" }
                    th { +"Taken" }
                    series.groups.forEachIndexed { g, group ->
                        th {
                            key = "g$g".unsafeCast<Key>()
                            className = ClassName("op-num")
                            +group.label
                        }
                    }
                    if (series.otherDrawn) th { className = ClassName("op-num"); +"Other" }
                }
            }
            tbody {
                series.days.indices.reversed().forEach { d ->
                    val day = series.days[d]
                    tr {
                        key = day.snapshotId.toString().unsafeCast<Key>()
                        td { +day.day }
                        td { +"${formatTimestamp(day.takenAt)} (${snapshotTriggerText(day.trigger)})" }
                        series.values[d].forEachIndexed { g, v ->
                            td {
                                key = "v$g".unsafeCast<Key>()
                                className = ClassName("op-num")
                                +(v?.let { historyNumberText(it) } ?: reportBlank)
                            }
                        }
                        if (series.otherDrawn) {
                            td {
                                className = ClassName("op-num")
                                +(series.otherValues?.get(d)?.let { historyNumberText(it) } ?: reportBlank)
                            }
                        }
                    }
                }
            }
        }
    }
}
