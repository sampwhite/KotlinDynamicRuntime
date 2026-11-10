package com.dynamicruntime.webapp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * What every chart the webapp draws shares (issues #1037, #1772): the marks' measures, clean ticks, a bar's and a slice's
 * outline, numbers as a chart writes them, the palette's classes, and the keyboard's steps among the marks. Pure, and
 * covered under `jsNodeTest` (`ReportsPageTest`). The charts are hand-drawn SVG (`react.dom.svg`), with no chart
 * library: the Reports page's History view (`ReportHistoryPanel.kt`) and its grouped run's bar and pie
 * (`ReportChartPanel.kt`).
 */

/** The marks' fixed measures: a bar no thicker than 24 and no thinner than 4, 2 between neighbours, 4 of rounding. */
@Suppress("ConstPropertyName")
object CHT {
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
 * The width a chart is laid out at for a pane [available] pixels wide: the pane's own, within [least] and
 * [CHT.maxWidth], in whole pixels. Laid out at its real width rather than scaled to it, so its text stays the size
 * it is set at however narrow the pane. A chart whose marks run down the page -- bars a row each, a pie -- needs less
 * room than days side by side do, and passes a smaller [least] (issue #1772), so it stays inside a phone's card.
 */
fun chartWidthFor(available: Double, least: Double = CHT.minWidth): Double = floor(min(CHT.maxWidth, max(least, available)))

/** The least width a chart whose marks run down the page is laid out at (issue #1772): a bar chart's rows, a pie. */
const val chartRowsMinWidth = 200.0

/** One gridline of a value axis: where along the axis it is drawn ([at], a y or an x), and what it reads. */
class ChartTick(val at: Double, val value: Double, val label: String)

/**
 * The step between ticks for values spanning [range]: the smallest of 1, 2 or 5 times a power of ten that divides it
 * into at most four -- so ticks are clean numbers (0, 50, 100, 150) rather than fractions of the maximum. Never under 1
 * for [wholeOnly] values: there is no half a form.
 */
fun niceTickStep(range: Double, wholeOnly: Boolean = false): Double {
    if (range <= 0.0 || range.isNaN()) return 1.0
    val raw = range / 4
    val magnitude = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= raw * (1 - 1e-9) }
    return if (wholeOnly) max(1.0, step) else step
}

/**
 * The value axis over values from [bottom] to [top]: it starts at zero (below it when a value is negative) and ends on
 * a clean tick ([niceTickStep]), so a bar grows from zero. Returns the axis's low and high ends and its step.
 */
fun chartAxis(bottom: Double, top: Double, wholeOnly: Boolean): Triple<Double, Double, Double> {
    val hiValue = max(0.0, top)
    val loValue = min(0.0, bottom)
    val step = niceTickStep(hiValue - loValue, wholeOnly)
    val hi = if (hiValue == 0.0 && loValue == 0.0) step else kotlin.math.ceil(hiValue / step - 1e-9) * step
    val lo = floor(loValue / step + 1e-9) * step
    return Triple(lo, hi, step)
}

/** A number as a chart writes it: as a cell does, with the thousands of its whole part set apart. */
fun chartNumberText(value: Double): String {
    val text = reportNumberText(value)
    val negative = text.startsWith("-")
    val body = if (negative) text.drop(1) else text
    val whole = body.substringBefore('.')
    if (whole.any { !it.isDigit() }) return text
    val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
    return (if (negative) "-" else "") + grouped + body.drop(whole.length)
}

/** A coordinate as a path writes it: to two places, truncated, so a path's text is stable across platforms. */
private fun n(v: Double): String = ((v * 100).toLong() / 100.0).toString()

/**
 * A bar's outline: its data end rounded by [CHT.barRadius] (less for a bar too thin or too short to carry it), its
 * baseline end square. [upward] bars round at the top; a negative value's bar hangs from the baseline and rounds below.
 */
fun barPath(x: Double, y: Double, width: Double, height: Double, upward: Boolean): String {
    val r = min(CHT.barRadius, min(width / 2, height))
    val right = x + width
    val bottom = y + height
    return if (upward) {
        "M${n(x)},${n(bottom)} V${n(y + r)} Q${n(x)},${n(y)} ${n(x + r)},${n(y)} H${n(right - r)} Q${n(right)},${n(y)} ${n(right)},${n(y + r)} V${n(bottom)} Z"
    } else {
        "M${n(x)},${n(y)} V${n(bottom - r)} Q${n(x)},${n(bottom)} ${n(x + r)},${n(bottom)} H${n(right - r)} Q${n(right)},${n(bottom)} ${n(right)},${n(bottom - r)} V${n(y)} Z"
    }
}

/**
 * A horizontal bar's outline (issue #1772), [barPath] turned on its side: its data end rounded, its baseline end square.
 * A [rightward] bar grows right from the baseline and rounds at its right; a negative value's grows left and rounds there.
 */
fun hBarPath(x: Double, y: Double, width: Double, height: Double, rightward: Boolean): String {
    val r = min(CHT.barRadius, min(height / 2, width))
    val right = x + width
    val bottom = y + height
    return if (rightward) {
        "M${n(x)},${n(y)} H${n(right - r)} Q${n(right)},${n(y)} ${n(right)},${n(y + r)} V${n(bottom - r)} Q${n(right)},${n(bottom)} ${n(right - r)},${n(bottom)} H${n(x)} Z"
    } else {
        "M${n(right)},${n(y)} H${n(x + r)} Q${n(x)},${n(y)} ${n(x)},${n(y + r)} V${n(bottom - r)} Q${n(x)},${n(bottom)} ${n(x + r)},${n(bottom)} H${n(right)} Z"
    }
}

/** The point at [angle] -- in radians, clockwise from twelve o'clock -- on the circle of radius [r] about ([cx], [cy]). */
fun pointOnCircle(cx: Double, cy: Double, r: Double, angle: Double): Pair<Double, Double> = (cx + r * sin(angle)) to (cy - r * cos(angle))

/**
 * A pie slice's outline (issue #1772): from the centre to the rim at [start], along the rim clockwise to [end] (radians,
 * clockwise from twelve o'clock), and back. A slice that is the whole circle has no edge to run along -- an arc from a
 * point to itself draws nothing -- so it is drawn as the circle, in two halves.
 */
fun slicePath(cx: Double, cy: Double, r: Double, start: Double, end: Double): String {
    if (end - start >= 2 * PI - 1e-9) {
        return "M${n(cx)},${n(cy - r)} A${n(r)},${n(r)} 0 1 1 ${n(cx)},${n(cy + r)} A${n(r)},${n(r)} 0 1 1 ${n(cx)},${n(cy - r)} Z"
    }
    val (x0, y0) = pointOnCircle(cx, cy, r, start)
    val (x1, y1) = pointOnCircle(cx, cy, r, end)
    val large = if (end - start > PI) 1 else 0
    return "M${n(cx)},${n(cy)} L${n(x0)},${n(y0)} A${n(r)},${n(r)} 0 $large 1 ${n(x1)},${n(y1)} Z"
}

/** The index a mark carries for "Other", the folded groups, which has no place among the coloured ones. */
const val chartOtherIndex = -1

/**
 * The class that gives a mark its colour: its place among a chart's series ([index], 0 to 7, the validated palette's
 * slots `--chart-1..8` in `app.css`), or the folded groups' grey for [chartOtherIndex]. A colour is a place, never a rank.
 */
fun chartColorClass(index: Int): String = if (index == chartOtherIndex) "rh-other" else "rh-c$index"

/** The keys that move the keyboard's place among a chart's marks. Each name matches the DOM's `KeyboardEvent.key`. */
@Suppress("ConstPropertyName")
object HKEY {
    const val left = "ArrowLeft"
    const val right = "ArrowRight"
    const val up = "ArrowUp"
    const val down = "ArrowDown"
    const val home = "Home"
    const val end = "End"
}

/**
 * Where [key] takes the keyboard from the mark at [index] of [count], in the order the marks are drawn: the arrows to the
 * neighbour, Home and End to the first and the last. Left and Right always step; Up and Down step too where the marks run
 * down the page ([vertical], a horizontal bar chart's rows, issue #1772), and are left to the browser where they do not.
 * Null for any other key, and for an arrow at the end it points off -- the key is then left to the browser.
 */
fun chartStep(index: Int, count: Int, key: String, vertical: Boolean = false): Int? = when {
    key == HKEY.left || (vertical && key == HKEY.up) -> (index - 1).takeIf { it >= 0 }
    key == HKEY.right || (vertical && key == HKEY.down) -> (index + 1).takeIf { it < count }
    key == HKEY.home -> 0.takeIf { count > 0 && index != 0 }
    key == HKEY.end -> (count - 1).takeIf { count > 0 && index != count - 1 }
    else -> null
}
