package com.dynamicruntime.webapp

import com.dynamicruntime.common.util.formatDate
import com.dynamicruntime.common.util.isPlaceZoneName
import com.dynamicruntime.common.util.parseDateOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

// Date-time entry in the client's zone (issue #1235). A moment is shown in the client's zone (#1115), so it must be
// entered in it too: a person in Denver typing 5:00 PM for a Chicago client means 5:00 PM in Chicago. antd's picker
// knows only the browser's zone -- it makes its own values with a plain `dayjs()` for "Now", for typed text and for
// an empty field -- so the conversion is done on its *wall clock* rather than by handing it zone-aware values: a
// moment goes in as the date and time it reads in the client's zone, and what is picked comes back as a date and
// time read there. `Intl` does the zone arithmetic, as it does the display, so the webapp ships no zone data; the
// kernel's kotlinx-datetime cannot, since the browser bundle carries no zone database.
//
// One edge is left, and documented rather than handled: a picked time falling in the *browser's* own skipped
// spring-forward hour, which dayjs moves forward before it reaches here. That needs the browser's zone and the
// client's to change clocks on different days, and the person to pick that hour.

/**
 * The date and time [at] reads in [zone], as `yyyy-MM-ddTHH:mm:ss` with no zone: the wall clock the picker shows.
 * Pure over its arguments (it reads `Intl`), and covered under `jsNodeTest`.
 */
fun zonedWallClock(at: Instant, zone: String): String {
    val options: dynamic = js(
        "({ hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })"
    )
    val p = intlZonedParts(at.toEpochMilliseconds().toDouble(), zone, options)
    return "${p.getValue("year")}-${p.getValue("month")}-${p.getValue("day")}T" +
        "${p.getValue("hour")}:${p.getValue("minute")}:${p.getValue("second")}"
}

/**
 * The moment at which [zone]'s clocks read [wall] (`yyyy-MM-ddTHH:mm:ss`), or null when [wall] is not a date and time.
 * Where daylight saving makes that ambiguous it answers as `java.time` does, so the browser and the server agree: a
 * time the clocks pass twice (the fall-back hour) is the earlier moment, and a time they skip (the spring-forward
 * hour) is moved forward by the gap -- 2:30 AM on a Chicago spring-forward day is 3:30 AM CDT. Pure over its arguments
 * (it reads `Intl`), and covered under `jsNodeTest`.
 */
fun instantOfWallClock(wall: String, zone: String): Instant? {
    // The wall clock read as if it were UTC; the moment is that less the zone's offset there.
    val asUtc = "${wall}Z".parseDateOrNull() ?: return null
    val shown = zonedWallClock(asUtc, "UTC")
    // The offsets in force a day either side: every clock change near the time is between them.
    val offsets = listOf(asUtc - 1.days, asUtc, asUtc + 1.days).map { zoneOffset(it, zone) }.distinct()
    val readsRight = offsets.map { asUtc - it }.filter { zonedWallClock(it, zone) == shown }
    // None reads right: the time is skipped, and taking the offset from before the change moves it forward.
    return readsRight.minOrNull() ?: (asUtc - offsets.min())
}

/** How far [zone]'s clocks are ahead of UTC at [at] (negative behind), to the second. Throws for a zone `Intl` does not know. */
fun zoneOffset(at: Instant, zone: String): Duration {
    val whole = Instant.fromEpochSeconds(at.epochSeconds)
    return "${zonedWallClock(whole, zone)}Z".parseDateOrNull()!! - whole
}

/**
 * The zone a date-time is entered in (issue #1235): [zone] when `Intl` knows it, else the [deploymentZone] --
 * the zone [formatTimestamp] then shows it in, so entry and display cannot disagree. The last answer is kept, since
 * every date-time field asks on every render and the zone changes only with the shell's config.
 */
fun entryZone(zone: String = DisplayZone.current): String {
    lastEntryZone?.let { (asked, answer) -> if (asked == zone) return answer }
    val answer = try {
        zonedWallClock(Clock.System.now(), zone)
        zone
    } catch (_: Throwable) {
        deploymentZone
    }
    lastEntryZone = zone to answer
    return answer
}

private var lastEntryZone: Pair<String, String>? = null

/**
 * What a date-time picker is handed for the stored [iso] moment: its wall clock in [zone] ([zonedWallClock]), or null
 * when the field holds nothing. A value that is not a full timestamp (a day, from a payload typed by hand) is passed
 * through as it is, as [formatTimestamp] leaves one alone. Pure, and covered under `jsNodeTest`.
 */
fun pickerWallOf(iso: String?, zone: String): String? {
    val text = iso?.takeIf { it.isNotBlank() } ?: return null
    if (text.length < 16 || text[10] != 'T') return text
    // One with its own offset (`...T17:00:00-05:00`, from a payload typed by hand) is the moment it names too: passed
    // through, dayjs would read it in the browser's zone and the next pick would shift it by the zones' difference.
    val at = text.parseDateOrNull() ?: offsetTimestamp(text) ?: return text
    return zonedWallClock(at, zone)
}

/** A timestamp carrying its own offset, as JavaScript's `Date.parse` reads one; null for anything it cannot. */
@Suppress("UNUSED_PARAMETER")
private fun offsetTimestamp(text: String): Instant? {
    if (!timestampWithOffset.matches(text)) return null
    val ms = js("Date.parse(text)") as Double
    return if (ms.isNaN()) null else Instant.fromEpochMilliseconds(ms.toLong())
}

private val timestampWithOffset = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?[+-]\d{2}:?\d{2}$""")

/**
 * The stored moment for a [wall] clock the picker returned, read in [zone] ([instantOfWallClock]), as the ISO-8601
 * UTC text the kernel writes; null for none. Pure, and covered under `jsNodeTest`.
 */
fun pickedIso(wall: String?, zone: String): String? =
    wall?.takeIf { it.isNotBlank() }?.let { instantOfWallClock(it, zone) }?.formatDate()

/** The format the picker's wall clock is read back in, dayjs's spelling of [pickerWallOf]'s. */
const val pickerWallFormat = "YYYY-MM-DDTHH:mm:ss"

/** [iso] as a date-time picker's value, in [zone]: a dayjs of the wall clock there, or null for none or a bad one. */
fun zonedPickerValue(iso: String?, zone: String = entryZone()): Dayjs? =
    pickerWallOf(iso, zone)?.let { dayjs(it) }?.takeIf { it.isValid() }

/** What a date-time picker's [date] stores, read in [zone]: ISO-8601 UTC, or null when it was cleared. */
fun zonedPickerIso(date: Dayjs?, zone: String = entryZone()): String? = date?.let { pickedIso(it.format(pickerWallFormat), zone) }

/**
 * What a date-time picker says beside itself (issue #1235): the zone its times are read in, as the time-zone picker
 * labels it ("Central Time (Chicago) · GMT−5"); a zone that is only an offset -- the deployment's, `Etc/GMT+8` -- as the
 * offset alone ("GMT−8"), since its place is POSIX's reversed sign. Pure over its arguments, covered under `jsNodeTest`.
 */
fun entryZoneNote(zone: String, at: Instant): String {
    val choice = timeZoneChoice(zone, at) ?: return zone
    return if (isPlaceZoneName(zone)) choice.label else choice.offset.ifEmpty { zone }
}

/**
 * [entryZoneNote] for now, kept between renders: every date-time field draws it on every render, and it changes only
 * with the zone, or when the zone's offset does -- on the hour at most, which is what the kept answer is keyed by.
 */
fun entryZoneNote(zone: String): String {
    val now = Clock.System.now()
    val key = "$zone@${now.epochSeconds / 3600}"
    lastEntryZoneNote?.let { (asked, note) -> if (asked == key) return note }
    return entryZoneNote(zone, now).also { lastEntryZoneNote = key to it }
}

private var lastEntryZoneNote: Pair<String, String>? = null
