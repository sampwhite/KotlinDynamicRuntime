package com.dynamicruntime.webapp

import com.dynamicruntime.common.util.parseDateOrNull
import com.dynamicruntime.common.util.serverTimeZone
import kotlinx.datetime.offsetAt
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The deployment's zone as `Intl` names it (issue #1115): the kernel's `serverTimeZone`, a fixed offset, as the
 * `Etc/GMT` zone of that offset -- whose sign is POSIX's, the reverse of the offset's, so UTC-8 is `Etc/GMT+8`.
 * What a client naming no zone of its own is shown in, labeled by `Intl` as the offset (`GMT-8`).
 */
val deploymentZone: String = run {
    val hours = serverTimeZone.offsetAt(Clock.System.now()).totalSeconds / 3600
    if (hours == 0) "UTC" else "Etc/GMT${if (hours < 0) "+" else "-"}${abs(hours)}"
}

/**
 * The zone the webapp shows moments in (issue #1115): the caller's client's IANA time zone, as the shell config says
 * it ([HomeConfig.timeZone]), or the [deploymentZone] for a client that names none. The app bar sets it on every
 * config it reads. A module value rather than a context, because what reads it -- [formatTimestamp] and
 * [formatRelativeTime] -- are pure functions called from parsers and table cells, far from any component; each takes
 * the zone as an argument too, which is how the tests pass one.
 */
object DisplayZone {
    /** The IANA zone moments are shown in now. */
    var current: String = deploymentZone
        private set

    /** Shows moments in [timeZone] from now on, or in the [deploymentZone] when it is null. */
    fun set(timeZone: String?) {
        current = timeZone ?: deploymentZone
    }
}

/**
 * A wire timestamp shown to a person (issue #1115): `2026-10-17T22:00:00Z` in `America/Chicago` is
 * `Oct 17, 2026, 5:00 PM CT` -- minute precision, in [zone], labeled with the zone so it is never mistaken for the
 * reader's own. A day-only value (`2026-10-17`) never shifts, so it is returned as it is, as is anything not shaped
 * like a timestamp. A [zone] `Intl` does not know is shown in the [deploymentZone] rather than not at all. Pure over
 * its arguments, and covered under `jsNodeTest`.
 */
fun formatTimestamp(iso: String, zone: String = DisplayZone.current): String {
    if (iso.length < 16 || iso[10] != 'T') return iso
    val at = iso.parseDateOrNull() ?: return iso
    return zonedParts(at, zone, withTime = true).let { p ->
        "${p.getValue("month")} ${p.getValue("day")}, ${p.getValue("year")}, " +
            "${p.getValue("hour")}:${p.getValue("minute")} ${p.getValue("dayPeriod")} ${p.getValue("timeZoneName")}"
    }
}

/** The day [at] falls on in [zone], as `yyyy-MM-dd` (issue #1115): what a time shown as its day alone reads. */
fun zonedDay(at: Instant, zone: String = DisplayZone.current): String =
    zonedParts(at, zone, withTime = false).let { p -> "${p.getValue("year")}-${p.getValue("month")}-${p.getValue("day")}" }

/**
 * [at]'s parts in [zone] as `Intl.DateTimeFormat` gives them, by part type: the short month, day, year, hour, minute,
 * day period, and the zone's generic short name with [withTime]; the numeric year, month, and day without.
 */
private fun zonedParts(at: Instant, zone: String, withTime: Boolean): Map<String, String> = try {
    intlParts(at.toEpochMilliseconds().toDouble(), zone, withTime)
} catch (_: Throwable) {
    // An unknown zone is a RangeError from Intl: show the moment in the deployment's zone rather than not at all.
    intlParts(at.toEpochMilliseconds().toDouble(), deploymentZone, withTime)
}

@Suppress("UNUSED_VARIABLE")
private fun intlParts(epochMs: Double, zone: String, withTime: Boolean): Map<String, String> {
    val options: dynamic = if (withTime) {
        js("({ year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZoneName: 'shortGeneric' })")
    } else {
        js("({ year: 'numeric', month: '2-digit', day: '2-digit' })")
    }
    options.timeZone = zone
    // Two steps, not one expression: `js()` reads `new X(...).formatToParts(...)` as a constructor call.
    val format: dynamic = js("new Intl.DateTimeFormat('en-US', options)")
    val date: dynamic = js("new Date(epochMs)")
    val parts: Array<dynamic> = format.formatToParts(date)
    return parts.associate { (it.type as String) to (it.value as String) }
}
