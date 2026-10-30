package com.dynamicruntime.webapp

import com.dynamicruntime.common.util.ScriptEnv
import com.dynamicruntime.common.util.ScriptZone
import com.dynamicruntime.common.util.TZONE
import com.dynamicruntime.common.util.momentText
import com.dynamicruntime.common.util.parseDateOrNull
import com.dynamicruntime.common.util.serverTimeZone
import com.dynamicruntime.common.util.zonedDayText
import kotlinx.datetime.UtcOffset
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
    if (hours == 0) TZONE.utc else "Etc/GMT${if (hours < 0) "+" else "-"}${abs(hours)}"
}

/**
 * The [deploymentZone] as its moments are labeled (`GMT-8`, `GMT`), for a page that names the zone itself (Profile,
 * issue #1479): whole hours, with the minutes only where the offset has them.
 */
val deploymentZoneLabel: String = run {
    val seconds = serverTimeZone.offsetAt(Clock.System.now()).totalSeconds
    val hours = abs(seconds) / 3600
    val minutes = abs(seconds) % 3600 / 60
    when {
        seconds == 0 -> "GMT"
        minutes == 0 -> "GMT${if (seconds < 0) "-" else "+"}$hours"
        else -> "GMT${if (seconds < 0) "-" else "+"}$hours:${minutes.toString().padStart(2, '0')}"
    }
}

/**
 * The zone the webapp shows moments in (issue #1115): the IANA zone the shell config says ([HomeConfig.timeZone]) --
 * the caller's client's, else the deployment's display default -- or the [deploymentZone] when it says none. The app
 * bar sets it on every config it reads. A module value rather than a context, because what reads it --
 * [formatTimestamp] and [formatRelativeTime] -- are pure functions called from parsers and table cells, far from any
 * component; each takes the zone as an argument too, which is how the tests pass one.
 */
object DisplayZone {
    /** The IANA zone moments are shown in now. */
    var current: String = deploymentZone
        private set

    /**
     * What a moment shown in [current] is labeled with (issue #1321): the backend's generic name for it
     * ([HomeConfig.timeZoneLabel]), so the browser words a moment as a mail the backend renders does; null when the
     * backend gave none.
     */
    var label: String? = null
        private set

    /** Shows moments in [timeZone], labeled [label], from now on, or in the [deploymentZone] when it is null. */
    fun set(timeZone: String?, label: String? = null) {
        current = timeZone ?: deploymentZone
        this.label = label.takeIf { timeZone != null }
    }

    /** What a template evaluated for the reader reads its dates in (issue #1321): [current], and [now]. */
    fun scriptEnv(now: Instant = Clock.System.now()): ScriptEnv = ScriptEnv(scriptZone(current), now)
}

/**
 * [zone] as the kernel's date wording reads it (issue #1321): its offsets from `Intl`, since the kernel's date library
 * has no zone database in the browser, and labeled with the backend's name for [DisplayZone.current], else `Intl`'s
 * generic one. A zone `Intl` does not know is the [deploymentZone], as [formatTimestamp] has always shown it.
 */
fun scriptZone(zone: String): ScriptZone {
    val known = entryZone(zone)
    val label = DisplayZone.label.takeIf { zone == DisplayZone.current && known == zone } ?: intlGenericLabel(known)
    return object : ScriptZone {
        override val label = label
        override fun offsetAt(at: Instant): UtcOffset = UtcOffset(seconds = zoneOffset(at, known).inWholeSeconds.toInt())
    }
}

/**
 * `Intl`'s generic short name for [zone] ("CT"), or null where that is only an offset ("GMT-3"), which the kernel
 * then writes per moment -- the rule the backend's names follow, so either source labels a zone alike.
 */
private fun intlGenericLabel(zone: String): String? {
    val options: dynamic = js("({ timeZoneName: 'shortGeneric' })")
    val name = intlZonedParts(Clock.System.now().toEpochMilliseconds().toDouble(), zone, options)["timeZoneName"]
    return name?.takeUnless { it.startsWith("GMT") || it.startsWith("UTC") }
}

/**
 * A wire timestamp shown to a person (issue #1115): `2026-10-17T22:00:00Z` in `America/Chicago` is
 * `Oct 17, 2026, 5:00 PM CT` -- minute precision, in [zone], labeled with the zone so it is never mistaken for the
 * reader's own. A day-only value (`2026-10-17`) never shifts, so it is returned as it is, as is anything not shaped
 * like a timestamp. A [zone] `Intl` does not know is shown in the [deploymentZone] rather than not at all. Worded by
 * the kernel ([momentText], issue #1321), as a template's `formatDate(x, "moment")` is. Covered under `jsNodeTest`.
 */
fun formatTimestamp(iso: String, zone: String = DisplayZone.current): String {
    if (iso.length < 16 || iso[10] != 'T') return iso
    val at = iso.parseDateOrNull() ?: return iso
    return momentText(at, scriptZone(zone))
}

/** The day [at] falls on in [zone], as `yyyy-MM-dd` (issue #1115): what a time shown as its day alone reads. */
fun zonedDay(at: Instant, zone: String = DisplayZone.current): String = zonedDayText(at, scriptZone(zone))


/**
 * The moment [epochMs] in [zone], formatted with [options] -- `Intl`'s own, without the zone, which this sets -- as its
 * parts by type (`year`, `hour`, `timeZoneName`, ...). Throws, as `Intl` does, for a zone it does not know. What the
 * display (issue #1115) and date-time entry (issue #1235) both read a moment through.
 */
fun intlZonedParts(epochMs: Double, zone: String, options: dynamic): Map<String, String> {
    options.timeZone = zone
    return intlFormatParts(epochMs, options).associate { (it.type as String) to (it.value as String) }
}

/**
 * `new Intl.DateTimeFormat('en-US', options)`, in English as every date here is shown (issues #1115, #1257); throws,
 * as `Intl` does, for an `options.timeZone` it does not know. Its own step because `js()` reads
 * `new X(...).method(...)` as a constructor call of `X(...).method`: the format must be made before it is used.
 */
@Suppress("UNUSED_PARAMETER")
fun intlDateTimeFormat(options: dynamic): dynamic = js("new Intl.DateTimeFormat('en-US', options)")

/** The moment [epochMs] formatted with [options] ([intlDateTimeFormat]), as `Intl`'s typed parts. */
@Suppress("UNUSED_PARAMETER")
fun intlFormatParts(epochMs: Double, options: dynamic): Array<dynamic> =
    intlDateTimeFormat(options).formatToParts(js("new Date(epochMs)")).unsafeCast<Array<dynamic>>()
