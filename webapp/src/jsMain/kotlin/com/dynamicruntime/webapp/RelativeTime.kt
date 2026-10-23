package com.dynamicruntime.webapp

import com.dynamicruntime.common.util.parseDateOrNull
import com.dynamicruntime.common.util.relativeTimeText
import kotlinx.coroutines.delay
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

/**
 * A wire timestamp as a table column shows it (issue #1141), measured from [now]: `just now` within a minute either
 * way, then `12m ago`, `1h 23m ago`, `in 3h`, and from 48 hours on the day alone in [zone] (the client's, issue #1115).
 * The kernel's rule ([relativeTimeText], issue #1321), which a template's `formatDate(x, "relative")` reads too. At
 * most eleven characters, where [formatTimestamp] takes twenty; the exact time is the cell's tooltip ([RelativeTime]).
 * A value that is not a timestamp is returned unchanged, as [formatTimestamp] does. Covered under `jsNodeTest`.
 */
fun formatRelativeTime(iso: String, now: Instant, zone: String = DisplayZone.current): String {
    val at = iso.parseDateOrNull() ?: return iso
    return relativeTimeText(at, now, scriptZone(zone))
}

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
