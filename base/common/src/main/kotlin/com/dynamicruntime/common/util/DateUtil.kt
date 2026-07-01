package com.dynamicruntime.common.util

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.KdrException
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date

// Date parsing, formatting, and simple date arithmetic, ported from the prior-art DnDateUtil and
// reshaped as extension methods on String and Date.
//
// This is one of the few deliberately JVM-only utilities: it builds on java.time and java.util.Date
// (the same Date type used by KdrCxt). A future Kotlin Multiplatform build would need to move to
// kotlinx-datetime. Unlike DecimalFormat, DateTimeFormatter is immutable and thread-safe, so the
// shared formatters below are safe to reuse concurrently.

/** UTC, the zone in which system timestamps are expressed. */
val utcTimeZone: ZoneId = ZoneId.of("UTC")

/** ISO-8601 in UTC with milliseconds and a literal `Z` -- the canonical system timestamp format. */
val systemDateTimeFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

/** Builds a UTC formatter for [pattern]. Handy for callers that need a custom system-style format. */
fun mkSystemDateFormatter(pattern: String): DateTimeFormatter =
    DateTimeFormatter.ofPattern(pattern).withZone(utcTimeZone)

/** Full system timestamp with milliseconds, e.g. `2021-06-01T08:00:00.250Z`. */
val systemFormatter: DateTimeFormatter = mkSystemDateFormatter(systemDateTimeFormat)

/** System timestamp without milliseconds, e.g. `2021-06-01T08:00:00Z`. */
val shortSystemFormatter: DateTimeFormatter = mkSystemDateFormatter("yyyy-MM-dd'T'HH:mm:ss'Z'")

/** RFC 1123 date, suitable for HTTP cookie expirations. */
val cookieDateFormatter: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(utcTimeZone)

/**
 * The server's shared timezone, used to define a common notion of day start and day end aligned with
 * the people maintaining the server. Defaults to a non-DST US West-coast offset (`UTC-08:00`): midnight
 * in any US timezone is at or before the start of a day in this zone, so a job launched at the start of
 * the server day runs somewhere between midnight and ~4am across US timezones, reducing the chance it
 * competes with live traffic. International deployments should run region-specific servers.
 *
 * Overridable via the `KDR_SERVER_TIME_ZONE` environment variable.
 */
val serverTimeZone: ZoneId = determineServerTimeZone()

/** Day-only formatter (`yyyy-MM-dd`) in the [serverTimeZone]. */
val dayOnlyFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(serverTimeZone)

@KdrPrivate
fun determineServerTimeZone(): ZoneId =
    ZoneId.of(System.getenv("KDR_SERVER_TIME_ZONE") ?: "UTC-08:00")

/**
 * Parses a date string in the system formats: a full timestamp `yyyy-MM-dd'T'HH:mm:ss[.SSS]'Z'` (the
 * trailing `Z` is optional) or a date-only `yyyy-MM-dd` (interpreted as the start of that day in the
 * [serverTimeZone]). Throws [KdrException] if the string does not match a recognized format.
 */
fun String.parseDate(): Date {
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
        val zdt: ZonedDateTime = if (str.length == 10) {
            LocalDate.parse(str, dayOnlyFormatter).atStartOfDay(serverTimeZone)
        } else {
            val dotIndex = str.indexOf('.', 10)
            if (dotIndex >= 0 && dotIndex != 19) {
                throw KdrException.mkConv("Date string '$this' does not have a '.' at the correct location.")
            }
            if (str.last() != 'Z') {
                str += "Z"
            }
            if (dotIndex > 0) ZonedDateTime.parse(str, systemFormatter)
            else ZonedDateTime.parse(str, shortSystemFormatter)
        }
        return Date.from(zdt.toInstant())
    } catch (e: DateTimeException) {
        throw KdrException.mkConv("Date string '$this' failed to parse.", e)
    }
}

/** Formats this date as a full system timestamp (ISO-8601, UTC, milliseconds). */
fun Date.formatDate(): String = systemFormatter.format(this.toInstant())

/** Formats only this date's day part (`yyyy-MM-dd`) in the [serverTimeZone]. */
fun Date.formatDayPart(): String = dayOnlyFormatter.format(this.toInstant())

/** Formats this date as an RFC 1123 string, suitable for HTTP cookies. */
fun Date.formatCookieDate(): String = cookieDateFormatter.format(this.toInstant())

/** Returns the start of this date's day in the [serverTimeZone]. */
fun Date.toStartOfDay(): Date {
    val ld = LocalDate.ofInstant(this.toInstant(), serverTimeZone)
    return Date.from(ld.atStartOfDay(serverTimeZone).toInstant())
}

/** Moves forward (or back, for a negative count) by whole days. Simple arithmetic; ignores daylight savings. */
fun Date.addDays(numDays: Int): Date = Date(this.time + numDays * 24L * 3600 * 1000)

/** Moves forward (or back, for a negative count) by whole hours. Simple arithmetic; ignores daylight savings. */
fun Date.addHours(numHours: Int): Date = Date(this.time + numHours * 3600L * 1000)
