package com.dynamicruntime.common.util

import com.dynamicruntime.common.exception.KdrException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

// Date parsing, formatting, and simple date arithmetic, reshaped as extension methods on String and
// Instant. The one-date type across the code base is the stdlib `kotlin.time.Instant`; calendar/zone
// operations use `kotlinx-datetime`. This is all KMP-friendly (no java.* APIs), so it can transpile to
// frontend code. All timestamps are millisecond precision in the wire format.

/** Day-only formatter (`yyyy-MM-dd`). */
private val dayOnlyFormat = LocalDate.Format {
    year(); char('-'); monthNumber(); char('-'); day()
}

/** Full system timestamp: ISO-8601 in UTC with exactly three fractional digits and a literal `Z`. */
private val systemFormat = LocalDateTime.Format {
    year(); char('-'); monthNumber(); char('-'); day()
    char('T')
    hour(); char(':'); minute(); char(':'); second()
    char('.'); secondFraction(3)
}

/**
 * RFC 1123 / HTTP IMF-fixdate for cookie expirations, e.g. `Tue, 01 Jun 2021 08:00:00 GMT`. Built by hand
 * (rather than `DateTimeComponents.Formats.RFC_1123`) because that predefined format omits zero seconds,
 * which is not valid HTTP date. English names keep the output locale-independent.
 */
private val cookieDateFormat = DateTimeComponents.Format {
    dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
    chars(", ")
    day()
    char(' ')
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    char(' ')
    year()
    char(' ')
    hour(); char(':'); minute(); char(':'); second()
    chars(" GMT")
}

/**
 * The server's shared timezone, used to define a common notion of day start and day end aligned with the
 * people maintaining the server. A non-DST US West-coast offset (`UTC-08:00`): midnight in any US timezone
 * is at or before the start of a day in this zone, so a job launched at the start of the server day runs
 * somewhere between midnight and ~4am across US timezones, reducing the chance it competes with live
 * traffic. If a deployment ever needs this configurable, inject the zone at the backend boundary.
 */
val serverTimeZone: TimeZone = UtcOffset(hours = -8).asTimeZone()

/**
 * Parses a date string in the system formats: a full timestamp `yyyy-MM-dd'T'HH:mm:ss[.SSS]'Z'` (the
 * trailing `Z` is optional) or a date-only `yyyy-MM-dd` (interpreted as the start of that day in the
 * [serverTimeZone]). Throws [KdrException] if the string does not match a recognized format.
 */
fun String.parseDate(): Instant {
    var str = this.trim()
    if (str.isEmpty()) {
        throw KdrException.mkConv("Date string to be parsed was null or empty.")
    }

    // Inspect the string for the different possible formats.
    val firstDash = str.indexOf('-')
    val secondDash = if (firstDash == 4) str.indexOf('-', 5) else 0
    if (secondDash != 7 || str.length < 10) {
        throw KdrException.mkConv("Date string '$this' does not follow a recognizable date format.")
    }
    try {
        if (str.length == 10) {
            return LocalDate.parse(str).atStartOfDayIn(serverTimeZone)
        }
        val dotIndex = str.indexOf('.', 10)
        if (dotIndex >= 0 && dotIndex != 19) {
            throw KdrException.mkConv("Date string '$this' does not have a '.' at the correct location.")
        }
        if (str.last() != 'Z') {
            str += "Z"
        }
        return Instant.parse(str)
    } catch (e: IllegalArgumentException) {
        throw KdrException.mkConv("Date string '$this' failed to parse.", e)
    }
}

/** Formats this instant as a full system timestamp (ISO-8601, UTC, milliseconds). */
fun Instant.formatDate(): String = this.toLocalDateTime(TimeZone.UTC).format(systemFormat) + "Z"

/** Formats only this instant's day part (`yyyy-MM-dd`) in the [serverTimeZone]. */
fun Instant.formatDayPart(): String = this.toLocalDateTime(serverTimeZone).date.format(dayOnlyFormat)

/** Formats this instant as an RFC 1123 / HTTP date string, suitable for cookies. */
fun Instant.formatCookieDate(): String = this.format(cookieDateFormat, UtcOffset.ZERO)

/** Returns the start of this instant's day in the [serverTimeZone]. */
fun Instant.toStartOfDay(): Instant = this.toLocalDateTime(serverTimeZone).date.atStartOfDayIn(serverTimeZone)

/** Moves forward (or back, for a negative count) by whole days. Simple arithmetic; ignores daylight savings. */
fun Instant.addDays(numDays: Int): Instant = this + numDays.days

/** Moves forward (or back, for a negative count) by whole hours. Simple arithmetic; ignores daylight savings. */
fun Instant.addHours(numHours: Int): Instant = this + numHours.hours

/** Truncates to millisecond precision — the precision of the wire format. Clock reads carry finer
 *  precision than that, so truncating keeps a value round-trippable through [formatDate] / [parseDate]. */
fun Instant.truncateToMs(): Instant = Instant.fromEpochMilliseconds(this.toEpochMilliseconds())
