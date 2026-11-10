package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.report.ReportChartKind
import kotlinx.browser.document
import kotlinx.coroutines.CancellationException
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

private val chartScope = MainScope()

external interface ReportChartPanelProps : Props {
    /** The client an `allClients` administrator chose; null for a client's own administrator, as on the run. */
    var client: String?
    var reportId: String
    var setup: ReportRunSetup
    /** What tells this run from another: the groups fetched for one are never drawn under another. */
    var walkKey: String
    /** The chart in force; its kind is not null, since the table alone draws no panel. */
    var choice: ReportChartChoice
    /** The run's rows on screen: the chart draws them when they are every group, and fetches the rest when not. */
    var run: ReportRunPage
}

/** The groups the chart drew from, with the run they are of. */
private class ChartGroups(val key: String, val page: ReportRunPage)

/**
 * A grouped run's **chart** (issue #1772): its groups as horizontal bars or a pie, measured by the count of forms or a
 * rolled-up column, above the run's table.
 *
 * The groups are every group of the run, not the rows the table has grown to: a chart of the first hundred would keep
 * and fold the wrong ones. When the table holds them all they are drawn as they are; otherwise the run is walked in
 * large pages, up to [chartMaxGroups], and a note says when there are more. The drawing follows the History chart's
 * rules: laid out at the pane's measured width, nothing said only on hover (the bar chart writes each value, the pie's
 * legend each value and share), one Tab stop with the arrows moving among the marks, the `svg` a `group`, and no text
 * in a series colour.
 */
val ReportChartPanel = FC<ReportChartPanelProps> { props ->
    val generation = useRefreshGeneration()
    val run = props.run
    val whole = run.numAvailable <= run.rows.size
    var fetched by useState<ChartGroups?>(null)
    var error by useState<DisplayError?>(null)
    var hover by useState<Int?>(null)
    // The mark the keyboard is on, apart from the pointer's: the pointer leaving a mark must not move the Tab stop.
    var focus by useState<Int?>(null)
    val latest = useRef(0)
    var chartWidth by useState(CHT.maxWidth)
    useEffectOnce {
        val panel = document.getElementById(chartPanelId) ?: return@useEffectOnce
        val measure: () -> Unit = { chartWidth = chartWidthFor(panel.clientWidth.toDouble(), chartRowsMinWidth) }
        measure()
        val observer = resizeObserver(measure)
        observer.observe(panel)
        try {
            awaitCancellation()
        } finally {
            observer.disconnect()
            // Leaving abandons a walk under way: it runs in a scope that outlives the component.
            latest.current = (latest.current ?: 0) + 1
        }
    }
    useEffect(props.walkKey, props.choice.kind, props.choice.measure.key) {
        hover = null
        focus = null
    }
    // Every group, when the table holds fewer than the run has: walked once per run and refresh, not per growth.
    useEffect(props.walkKey, whole, generation) {
        val token = (latest.current ?: 0) + 1
        latest.current = token
        if (whole) {
            error = null
            return@useEffect
        }
        chartScope.launch {
            try {
                val page = ReportsApi.chartGroups(props.reportId, props.client, props.setup) { latest.current == token } ?: return@launch
                if (latest.current == token) {
                    fetched = ChartGroups(props.walkKey, page)
                    error = null
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (latest.current == token) error = userFacingError(e)
            }
        }
    }

    val groups = if (whole) run else fetched?.takeIf { it.key == props.walkKey }?.page
    val kind = props.choice.kind ?: ReportChartKind.bar
    val data = useMemo(groups, kind, props.choice.measure.key) {
        groups?.let { reportChartData(it.rows, it.summary, kind, props.choice.measure) }
    }

    div {
        id = ElementId(chartPanelId)
        className = ClassName("rc-panel")
        when {
            data == null -> {
                error?.let { errorText("Couldn't read the run's groups for the chart.", it) }
                if (error == null) p {
                    className = ClassName("subtitle")
                    +"Reading every group for the chart…"
                }
            }
            else -> {
                h3 {
                    className = ClassName("rh-title")
                    +reportChartTitle(data.measure, (groups ?: run).summary)
                }
                if (data.entries.isEmpty()) {
                    p {
                        className = ClassName("subtitle")
                        +"Nothing to draw: no group has a ${data.measure.label}${if (kind == ReportChartKind.pie) " above zero" else ""}."
                    }
                } else if (kind == ReportChartKind.pie) {
                    pieChart(data, pieChartLayout(data, chartWidth), hover, focus, hoverAt = { hover = it }, focusAt = { focus = it })
                } else {
                    barChart(data, barChartLayout(data, chartWidth), hover, focus, hoverAt = { hover = it }, focusAt = { focus = it })
                }
                reportChartNotes(data, (groups ?: run).numAvailable).forEachIndexed { i, note ->
                    p {
                        key = "n$i".unsafeCast<Key>()
                        className = ClassName("subtitle")
                        +note
                    }
                }
            }
        }
    }
}

/** The panel's element id, which the width the chart is laid out at is measured from. */
private const val chartPanelId = "report-chart-panel"

/** The id of the hit target of the mark at [index]: what the arrow keys move the focus to. */
private fun chartHitId(index: Int): String = "rc-hit-$index"

/** Moves the keyboard from the mark at [index] of [count] as [key] says ([chartStep]), handling the key when it moves. */
private fun stepFrom(index: Int, count: Int, key: String, vertical: Boolean, prevent: () -> Unit) {
    chartStep(index, count, key, vertical)?.let { next ->
        prevent()
        document.getElementById(chartHitId(next))?.asDynamic()?.focus()
    }
}

/**
 * Horizontal bars, a row a group: its name on a line, its bar under it, its value in the column at the right, and the
 * row as the hit target. The readout of the row the pointer ([hover]) or the keyboard ([focus]) is on names it in full.
 */
private fun ChildrenBuilder.barChart(
    data: ReportChartData,
    layout: BarChartLayout,
    hover: Int?,
    focus: Int?,
    // Not `onHover`/`onFocus`: inside the rect's builder `onFocus = ...` would assign a parameter of that name.
    hoverAt: (Int?) -> Unit,
    focusAt: (Int?) -> Unit,
) {
    val at = hover ?: focus
    val tabStop = focus?.takeIf { it in layout.bars.indices } ?: 0
    div {
        className = ClassName("rh-chart")
        val box: dynamic = js("({})")
        box.width = "${layout.width}px"
        asDynamic().style = box
        svg {
            width = layout.width
            height = layout.height
            viewBox = "0 0 ${layout.width} ${layout.height}"
            asDynamic().className = "rh-svg"
            role = AriaRole.group
            ariaLabel = "A bar chart of ${data.measure.label}. Arrow keys move between bars; each bar's value is written beside it."
            layout.ticks.forEach { t ->
                line {
                    key = "g${t.value}".unsafeCast<Key>()
                    asDynamic().className = if (t.value == 0.0) "rh-baseline" else "rh-grid"
                    x1 = t.at
                    x2 = t.at
                    y1 = layout.plotTop
                    y2 = layout.plotBottom
                }
                text {
                    key = "t${t.value}".unsafeCast<Key>()
                    asDynamic().className = "rh-tick"
                    x = t.at
                    y = layout.plotBottom + 16
                    textAnchor = TextAnchor.middle
                    +t.label
                }
            }
            layout.bars.forEach { b ->
                text {
                    key = "l${b.index}".unsafeCast<Key>()
                    asDynamic().className = "rc-label"
                    x = layout.plotLeft
                    y = b.labelY
                    // Cut to the plot's width: the readout and the row's name for a screen reader have it whole.
                    +chartFitText(b.entry.label, layout.plotRight - layout.plotLeft)
                }
                path {
                    key = "b${b.index}".unsafeCast<Key>()
                    asDynamic().className = "rh-bar ${chartColorClass(b.entry.colorIndex)}" + (if (at == b.index) " rh-hot" else "")
                    d = b.path
                }
                text {
                    key = "v${b.index}".unsafeCast<Key>()
                    asDynamic().className = "rc-value"
                    x = layout.width - 4
                    y = b.y + HBAR.barThickness / 2
                    textAnchor = TextAnchor.end
                    dominantBaseline = DominantBaseline.middle
                    +chartNumberText(b.entry.value)
                }
            }
            // The hit targets, over everything: a row's whole height and width, its name included.
            layout.bars.forEach { b ->
                rect {
                    key = "h${b.index}".unsafeCast<Key>()
                    asDynamic().className = "rh-hit"
                    asDynamic().id = chartHitId(b.index)
                    x = 0.0
                    y = b.rowTop
                    width = layout.width
                    height = b.rowHeight
                    role = AriaRole.img
                    tabIndex = if (b.index == tabStop) 0 else -1
                    ariaLabel = "${b.entry.label}: ${chartNumberText(b.entry.value)}"
                    onMouseEnter = { hoverAt(b.index) }
                    onMouseLeave = { hoverAt(null) }
                    onFocus = { focusAt(b.index) }
                    onBlur = { focusAt(null) }
                    onKeyDown = { e -> stepFrom(b.index, layout.bars.size, e.key, vertical = true) { e.preventDefault() } }
                }
            }
        }
        val bar = at?.let { layout.bars.getOrNull(it) }
        if (bar != null) {
            chartTip(bar.x + bar.width / 2, bar.y, chartNumberText(bar.entry.value), bar.entry, share = null)
        }
    }
}

/**
 * A pie, beside its legend: each slice its group's share of the whole, clockwise from twelve o'clock in the run's
 * order, and the legend a row a slice -- swatch, name, value and share -- so every number is there without a pointer.
 */
private fun ChildrenBuilder.pieChart(
    data: ReportChartData,
    layout: PieChartLayout,
    hover: Int?,
    focus: Int?,
    hoverAt: (Int?) -> Unit,
    focusAt: (Int?) -> Unit,
) {
    val at = hover ?: focus
    val tabStop = focus?.takeIf { it in layout.slices.indices } ?: 0
    div {
        className = ClassName("rc-pie")
        div {
            className = ClassName("rh-chart")
            val box: dynamic = js("({})")
            box.width = "${layout.size}px"
            asDynamic().style = box
            svg {
                width = layout.size
                height = layout.size
                viewBox = "0 0 ${layout.size} ${layout.size}"
                asDynamic().className = "rh-svg"
                role = AriaRole.group
                ariaLabel = "A pie of ${data.measure.label}. Arrow keys move between slices; the legend holds each value and share."
                layout.slices.forEach { s ->
                    path {
                        key = "s${s.index}".unsafeCast<Key>()
                        asDynamic().className = "rh-bar rc-slice ${chartColorClass(s.entry.colorIndex)}" + (if (at == s.index) " rh-hot" else "")
                        asDynamic().id = chartHitId(s.index)
                        d = s.path
                        role = AriaRole.img
                        tabIndex = if (s.index == tabStop) 0 else -1
                        ariaLabel = "${s.entry.label}: ${chartNumberText(s.entry.value)}, ${chartShareText(s.share)}"
                        onMouseEnter = { hoverAt(s.index) }
                        onMouseLeave = { hoverAt(null) }
                        onFocus = { focusAt(s.index) }
                        onBlur = { focusAt(null) }
                        onKeyDown = { e -> stepFrom(s.index, layout.slices.size, e.key, vertical = true) { e.preventDefault() } }
                    }
                }
            }
            val slice = at?.let { layout.slices.getOrNull(it) }
            if (slice != null) chartTip(slice.tipX, slice.tipY, chartNumberText(slice.entry.value), slice.entry, slice.share)
        }
        div {
            className = ClassName("rc-legend")
            layout.slices.forEach { s ->
                div {
                    key = "l${s.index}".unsafeCast<Key>()
                    className = ClassName(if (at == s.index) "rc-legend-row rc-legend-hot" else "rc-legend-row")
                    span { className = ClassName("rh-swatch ${chartColorClass(s.entry.colorIndex)}") }
                    span {
                        className = ClassName("rc-legend-name")
                        +s.entry.label
                    }
                    span {
                        className = ClassName("rc-legend-value")
                        +chartNumberText(s.entry.value)
                    }
                    span {
                        className = ClassName("rc-legend-share")
                        +chartShareText(s.share)
                    }
                }
            }
        }
    }
}

/** The readout: the value leads, then whose it is, and for a slice its share. Placed in the chart's own pixels. */
private fun ChildrenBuilder.chartTip(x: Double, y: Double, value: String, entry: ChartEntry, share: Double?) {
    div {
        className = ClassName("rh-tip")
        val place: dynamic = js("({})")
        place.left = "${x}px"
        place.top = "${y}px"
        asDynamic().style = place
        strong { +value }
        div {
            className = ClassName("rh-tip-series")
            span { className = ClassName("rh-key ${chartColorClass(entry.colorIndex)}") }
            +entry.label
        }
        share?.let {
            div {
                className = ClassName("rh-tip-date")
                +"${chartShareText(it)} of the whole"
            }
        }
    }
}
