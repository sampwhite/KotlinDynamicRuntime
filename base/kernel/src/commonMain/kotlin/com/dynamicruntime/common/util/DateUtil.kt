@file:Suppress("DuplicatedCode")

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

// Date parsing, formatting, and simple date arithmetic, reshaped as extension methods on String, Instant and
// LocalDate. Calendar/zone operations use `kotlinx-datetime`. This is all KMP-friendly (no java.* APIs), so it
// can transpile to frontend code. All timestamps are millisecond precision in the wire format.
//
// **Two date types, deliberately** (issue #189). A moment in time is a `kotlin.time.Instant` — the type for
// anything stamped, compared or ordered, and the one the code base reaches for by default. A *day* — a
// birthday, a "since" filter, an invoice date — is a `kotlinx.datetime.LocalDate`, because it genuinely has
// no instant: pinning one to midnight somewhere immediately makes the day itself wrong for readers in another
// zone. Keeping them apart is what lets a day-only value round-trip byte-for-byte, which is the property this
// code guarded by leaving such values as raw strings before `LocalDate` was available.
//
// Crossing between them is an explicit act, never a silent one: [Instant.toDay] picks a day out of a moment,
// and [LocalDate.toStartOfDay] gives a day a moment. Both need the [serverTimeZone], and requiring the caller
// to ask makes that dependence visible at the point where it matters.

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
 * Compact UTC timestamp with no separators (`yyyyMMddHHmmssSSS`, millisecond precision) -- the system
 * timestamp stripped of its `-`, `T`, `:`, `.`, and trailing `Z`. Used as the leading part of a unique id
 * (`mkUniqueId`): it is all digits, so a log tokenizer keeps it whole, and it still sorts lexically in time
 * order. There is deliberately no parser -- ids are opaque once created.
 */
private val compactIdFormat = LocalDateTime.Format {
    year(); monthNumber(); day()
    hour(); minute(); second()
    secondFraction(3)
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

/**
 * Parses a day-only string (`yyyy-MM-dd`) into a [LocalDate], with **no timezone involved** — which is what
 * keeps the value identical on the way back out. Strict: a full timestamp is rejected, not silently truncated.
 * Throws [KdrException] on anything that is not exactly a day.
 *
 * Strict is the plain-named default here, deliberately, because this direction *discards* information. Going
 * the other way ([parseDate] accepting a day and widening it to midnight) only adds a convention; going this
 * way throws away a time of day, and doing that unasked is how a value quietly stops meaning what it said.
 * The forgiving variant is [parseDayLenient], and callers reach for it on purpose.
 */
fun String.parseDay(): LocalDate {
    val str = this.trim()
    try {
        return LocalDate.parse(str)
    } catch (e: IllegalArgumentException) {
        throw KdrException.mkConv("Date string '$this' failed to parse as a day (expected yyyy-MM-dd).", e)
    }
}

/**
 * Parses a day-only string like [parseDay], but additionally accepts a full timestamp and narrows it to its
 * day in the [serverTimeZone] — so a client that sends more precision than a day-only field asked for is
 * normalized rather than refused.
 *
 * That forgiveness is a coercion, which is why the schema layer only uses this when the field's `allowCoerce`
 * is on; a strict day-only field takes only a day.
 */
fun String.parseDayLenient(): LocalDate {
    val str = this.trim()
    return if (str.length == 10) str.parseDay() else str.parseDate().toDay()
}

/** Formats this instant as a full system timestamp (ISO-8601, UTC, milliseconds). */
fun Instant.formatDate(): String = this.toLocalDateTime(TimeZone.UTC).format(systemFormat) + "Z"

/** Formats this day as `yyyy-MM-dd`. The exact inverse of [String.parseDay] for a day-only string. */
fun LocalDate.formatDay(): String = this.format(dayOnlyFormat)

/** The day this instant falls on, in the [serverTimeZone] — narrowing a moment to a calendar day. */
fun Instant.toDay(): LocalDate = this.toLocalDateTime(serverTimeZone).date

/** The moment this day begins, in the [serverTimeZone] — giving a calendar day an instant. */
fun LocalDate.toStartOfDay(): Instant = this.atStartOfDayIn(serverTimeZone)

/** Formats this instant as a compact, separator-free UTC timestamp (`yyyyMMddHHmmssSSS`); see [compactIdFormat]. */
fun Instant.formatCompactId(): String = this.toLocalDateTime(TimeZone.UTC).format(compactIdFormat)

/** Formats only this instant's day part (`yyyy-MM-dd`) in the [serverTimeZone]. */
fun Instant.formatDayPart(): String = this.toDay().formatDay()

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
