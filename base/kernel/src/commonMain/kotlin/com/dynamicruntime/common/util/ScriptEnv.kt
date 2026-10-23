package com.dynamicruntime.common.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What a template's date functions read beside their arguments (issue #1321): the [zone] a moment is shown in, and
 * [now], what a relative time is measured from. Handed in by whoever evaluates -- the backend the client's zone and the
 * request's moment, the webapp `DisplayZone` and the table's clock, a mail its render time -- and carried on the
 * evaluation's state, beside the fragment resolver. The functions stay pure over their arguments and this, so a
 * template evaluated twice with the same environment reads the same.
 *
 * An evaluation given none reads [deployment]: the deployment's zone and the clock, as every template did before.
 */
class ScriptEnv(val zone: ScriptZone, val now: Instant) {
    companion object {
        /** The deployment's zone, with no name, and the clock at the moment of asking. */
        fun deployment(): ScriptEnv = ScriptEnv(ScriptZone.of(serverTimeZone), Clock.System.now())
    }
}

/**
 * A zone as a template's date functions need it (issue #1321): its offset at a moment, and its generic [label] ("CT"),
 * when it has one. An interface rather than a `TimeZone` because `kotlinx-datetime` cannot convert a named zone in the
 * browser, whose bundle carries no zone database: the backend's is a `TimeZone` ([of]), the webapp's reads `Intl`.
 * Either way the wording is the kernel's ([momentText], [zonedDayText], [relativeTimeText]), so a mail the backend
 * renders and a cell the browser draws read alike.
 */
interface ScriptZone {
    /**
     * The zone's generic short name, the same all year ("CT", never "CST" or "CDT", which change with daylight time);
     * null for a zone with none, which is then labeled by its offset at the moment shown ([offsetLabel]). Supplied by
     * the backend, from Java's names, since the kernel has none and browsers word some zones differently.
     */
    val label: String?

    /** The zone's offset from UTC at [at]. */
    fun offsetAt(at: Instant): UtcOffset

    companion object {
        /** [zone], named [label]: a `TimeZone`'s rules, which on the JVM include every named zone. */
        fun of(zone: TimeZone, label: String? = null): ScriptZone = object : ScriptZone {
            override val label = label
            override fun offsetAt(at: Instant): UtcOffset = zone.offsetAt(at)
        }
    }
}

/** The wall clock [at] reads in this zone. */
private fun ScriptZone.local(at: Instant): LocalDateTime = at.toLocalDateTime(offsetAt(at).asTimeZone())

/** The day [at] falls on in [zone], as `yyyy-MM-dd` (issue #1321): what `formatDay` and the `"day"` style read. */
fun zonedDayText(at: Instant, zone: ScriptZone): String = zone.local(at).date.formatDay()

private val monthAbbrevs = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/**
 * [at] in [zone] to the minute, labeled with the zone so it is never mistaken for the reader's own (issue #1321):
 * `Oct 17, 2026, 5:00 PM CT`, or `Oct 17, 2026, 1:00 AM GMT+5:30` for a zone with no [ScriptZone.label]. The wording
 * the webapp has shown moments in since #1115. What the `"moment"` style reads.
 */
fun momentText(at: Instant, zone: ScriptZone): String {
    val t = zone.local(at)
    val hour12 = (t.hour % 12).let { if (it == 0) 12 else it }
    val period = if (t.hour < 12) "AM" else "PM"
    val minute = t.minute.toString().padStart(2, '0')
    val label = zone.label ?: offsetLabel(zone.offsetAt(at))
    return "${monthAbbrevs[t.month.ordinal]} ${t.day}, ${t.year}, $hour12:$minute $period $label"
}

/** An offset as a moment's label: `GMT` for none, `GMT-5`, `GMT+5:30` -- the form `Intl` writes one in. */
fun offsetLabel(offset: UtcOffset): String {
    val seconds = offset.totalSeconds
    if (seconds == 0) return "GMT"
    val sign = if (seconds < 0) "-" else "+"
    val minutes = abs(seconds) / 60
    val hours = minutes / 60
    val rest = minutes % 60
    return "GMT$sign$hours" + if (rest == 0) "" else ":" + rest.toString().padStart(2, '0')
}

/** Under this a time is `just now`, either way -- which also absorbs a small server/browser clock difference. */
private const val justNowMs = 60_000L

/** At or past this, either way, a time is shown as its day alone. Elapsed time, not calendar days. */
private const val dayOnlyMs = 48 * 60 * 60_000L

/**
 * [at] measured from [now] (issues #1141, #1321): `just now` within a minute either way, then the largest unit and the
 * one below it -- `12m ago`, `1h 23m ago`, `1d 2h ago` -- with a zero lower unit left out (`3h ago`), and from 48 hours
 * on the day alone in [zone], `2026-10-10`. A future time reads `in 12m`, `in 1d 2h`. At most eleven characters
 * (`23h 59m ago`). The rule a table column shows times by, and what the `"relative"` style reads.
 */
fun relativeTimeText(at: Instant, now: Instant, zone: ScriptZone): String {
    val deltaMs = (at - now).inWholeMilliseconds
    val ms = abs(deltaMs)
    if (ms < justNowMs) return "just now"
    if (ms >= dayOnlyMs) return zonedDayText(at, zone)
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
