package com.dynamicruntime.webapp

import com.dynamicruntime.common.util.parseDateOrNull
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import react.FC
import react.Props
import react.create
import react.dom.html.ReactHTML.span
import react.useEffectOnce
import react.useState

/** How often a table showing relative times re-renders them (issue #1141): minutes are the finest unit it shows. */
const val relativeClockMs = 30_000

/** Under this a time is `just now`, either way -- which also absorbs a small server/browser clock difference. */
private const val justNowMs = 60_000L

/** At or past this, either way, a time is shown as its day alone. Elapsed time, not calendar days. */
private const val dayOnlyMs = 48 * 60 * 60_000L

/**
 * A wire timestamp as a table column shows it (issue #1141), measured from [now]: `just now` within a minute either
 * way, then the largest unit and the one below it -- `12m ago`, `1h 23m ago`, `1d 2h ago` -- with a zero lower unit
 * left out (`3h ago`), and from 48 hours on the day alone, in [zone] (the client's, issue #1115), `2026-10-10`. A
 * future time reads `in 12m`, `in 1d 2h`.
 * At most eleven characters (`23h 59m ago`), where [formatTimestamp] takes twenty; the exact time is the cell's
 * tooltip ([RelativeTime]). A value that is not a timestamp is returned unchanged, as [formatTimestamp] does. Pure,
 * covered under `jsNodeTest`.
 */
fun formatRelativeTime(iso: String, now: Instant, zone: String = DisplayZone.current): String {
    val at = iso.parseDateOrNull() ?: return iso
    val deltaMs = (at - now).inWholeMilliseconds
    val ms = abs(deltaMs)
    if (ms < justNowMs) return "just now"
    if (ms >= dayOnlyMs) return zonedDay(at, zone)
    val minutes = ms / 60_000
    val span = when {
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> unitPair(minutes / 60, "h", minutes % 60, "m")
        else -> unitPair(minutes / (24 * 60), "d", minutes / 60 % 24, "h")
    }
    return if (deltaMs < 0) "$span ago" else "in $span"
}

private fun unitPair(major: Long, majorUnit: String, minor: Long, minorUnit: String): String =
    if (minor == 0L) "$major$majorUnit" else "$major$majorUnit $minor$minorUnit"

/**
 * The time a component showing relative times reads them against (issue #1141), re-rendering it every
 * [relativeClockMs] so `12m ago` does not stay `12m ago` while the page sits open. Read fresh on every render, so a
 * render caused by anything else -- new rows arriving -- is measured from that moment, not the last tick. One per
 * table: the rows of a table share it and so agree with each other.
 */
fun useRelativeClock(): Instant {
    val (_, setTick) = useState(0)
    // An effect is a coroutine React cancels on unmount, so the loop ends with the component.
    useEffectOnce {
        while (true) {
            delay(relativeClockMs.milliseconds)
            setTick { it + 1 }
        }
    }
    return Clock.System.now()
}

external interface RelativeTimeProps : Props {
    /** The wire timestamp. */
    var iso: String

    /** What it is measured from: the table's [useRelativeClock]. */
    var now: Instant
}

/** A table cell's time (issue #1141): [formatRelativeTime], with the exact [formatTimestamp] on hover. */
val RelativeTime = FC<RelativeTimeProps> { props ->
    span {
        title = formatTimestamp(props.iso)
        +formatRelativeTime(props.iso, props.now)
    }
}

/** Where an antd table's row files a time column's tooltip, beside the column's own `dataIndex`. */
fun timeTipKey(dataIndex: String): String = "${dataIndex}Tip"

/**
 * An antd column `render` for a time column (issue #1141): the cell's text as the row has it -- already
 * [formatRelativeTime]'d against the table's clock -- with the exact time on hover, read from the row's [tipKey]. A
 * row with no time there (a dash) has no tooltip.
 */
fun timeColumnRender(tipKey: String): dynamic = fun(value: dynamic, record: dynamic, _: dynamic): dynamic =
    span.create {
        (record[tipKey] as? String)?.let { title = it }
        +((value as? String).orEmpty())
    }
