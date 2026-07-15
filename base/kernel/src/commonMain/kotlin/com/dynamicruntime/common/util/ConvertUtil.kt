package com.dynamicruntime.common.util

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.exception.KdrException
import kotlin.math.round
import kotlin.time.Instant

// Extension methods for converting / coercing values.

/**
 * Forcibly coerces this value to the parameterized type [T] via an unchecked
 * cast. The caller is responsible for the value actually being a [T] -- this just
 * centralizes the (suppressed) unchecked cast in one place.
 */
@Suppress("UNCHECKED_CAST")
fun <T> Any.toT(): T = this as T

/** Forcibly coerces this value to a JSON-style `Map<String, Any?>` (see [toT]). */
fun Any.toJsonMap(): Map<String, Any?> = toT()

/**
 * A null-tolerant view of a (loosely typed) parsed-JSON value as a `Map<String, Any?>`, or empty when it is
 * null or not a map. Unlike [toJsonMap] (which assumes a map), this guards first -- handy when reaching into a
 * decoded JSON tree, e.g. an endpoint response envelope or a UI-config payload.
 */
fun Any?.toJsonMapOrEmpty(): Map<String, Any?> = if (this is Map<*, *>) toJsonMap() else emptyMap()

/** A null-tolerant view of a parsed-JSON value as a `List<Any?>`, or empty when it is null or not a list. */
fun Any?.toJsonListOrEmpty(): List<Any?> = this as? List<*> ?: emptyList()

/**
 * Returns this value's `toString()` rendering only if it is a [CharSequence]
 * (e.g., a String); otherwise null. The receiver is nullable, so a null value (or
 * a non-CharSequence such as a number) yields null.
 */
fun Any?.toOptStr(): String? = if (this is CharSequence) this.toString() else null

/**
 * Loosely coerces this string to a boolean, tolerating the many spellings found in CSV and other loose
 * data sources (yes/no, y/n, t/f, true/false, 1/0). Examines the first non-whitespace character
 * (case-insensitively): 'y'/'t'/'1' -> true, 'n'/'f'/'0' -> false, anything else -> null. A string with
 * no non-whitespace character also yields null.
 *
 * Mishandled booleans are one of the most common data errors, so this errs toward simplicity: it will
 * over-accept (e.g. "tremendous" reads as true) rather than risk silently mangling a real boolean.
 *
 * Whitespace is treated as any character whose code point is <= a space; this deliberately does not
 * forgive exotic locale-specific whitespace above a space.
 */
fun String.toOptBool(): Boolean? {
    for (ch in this) {
        if (ch > ' ') {
            return when (ch.lowercaseChar()) {
                'y', 't', '1' -> true
                'n', 'f', '0' -> false
                else -> null
            }
        }
    }
    return null
}

/**
 * Loosely coerces this value to a [Long]: null (and a blank string) yield null; a [Number] is narrowed; a
 * numeric string is parsed (tolerating a fractional part, which is truncated). A non-numeric string or an
 * otherwise unconvertible value throws [KdrException.mkConv] -- unlike [toOptStr]/[toOptBool], a malformed
 * number is surfaced rather than silently dropped, since it usually signals bad stored data or a bad bind.
 */
fun Any?.toOptLong(): Long? = when (this) {
    null -> null
    is Long -> this
    is Number -> this.toLong()
    is CharSequence -> {
        val s = this.trim()
        if (s.isEmpty()) null
        else s.toString().toLongOrNull() ?: s.toString().toDoubleOrNull()?.toLong()
            ?: throw KdrException.mkConv("Cannot convert '$s' to an integer.")
    }
    else -> throw KdrException.mkConv("Cannot convert value of type ${this::class.simpleName} to an integer.")
}

/** Loosely coerces this value to a [Double]; see [toOptLong] for the null/parse/throw semantics. */
fun Any?.toOptDouble(): Double? = when (this) {
    null -> null
    is Double -> this
    is Number -> this.toDouble()
    is CharSequence -> {
        val s = this.trim()
        if (s.isEmpty()) null
        else s.toString().toDoubleOrNull() ?: throw KdrException.mkConv("Cannot convert '$s' to a number.")
    }
    else -> throw KdrException.mkConv("Cannot convert value of type ${this::class.simpleName} to a number.")
}

/**
 * Loosely coerces this value to an [Instant]: null (and a blank string) yield null; an [Instant] passes
 * through; an epoch-millis [Number] is converted at millisecond precision; a string is parsed via
 * [parseDate]. Anything else throws [KdrException.mkConv].
 *
 * Deliberately KMP-safe: it knows nothing about JVM/JDBC date types. The only place a `java.util.Date` /
 * `java.sql.Timestamp` enters the runtime is the JDBC boundary, so the SQL layer's `toDbInstant` layers
 * those on top of this shared coercer rather than burdening it (and every transpile target) with them.
 */
fun Any?.toOptInstant(): Instant? = when (this) {
    null -> null
    is Instant -> this
    is Number -> Instant.fromEpochMilliseconds(this.toLong())
    is CharSequence -> {
        val s = this.trim()
        if (s.isEmpty()) null else s.toString().parseDate()
    }
    else -> throw KdrException.mkConv("Cannot convert value of type ${this::class.simpleName} to a date.")
}

// --- map-field accessors (a required/optional value at a key) ---------------------------------------------

/** The value at [key] rendered as a String, or a bad-input error if it is absent/null. */
fun Map<String, Any?>.getReqStr(key: String): String =
    this[key]?.toString() ?: throw KdrException.mkInput("Missing required field '$key'.")

/** The value at [key] rendered as a String, or null if it is absent/null. */
fun Map<String, Any?>.getOptStr(key: String): String? = this[key]?.toString()

/** The value at [key] coerced to a Long, or a bad-input error if it is absent/null. */
fun Map<String, Any?>.getReqLong(key: String): Long =
    this[key].toOptLong() ?: throw KdrException.mkInput("Missing required field '$key'.")

/** The value at [key] coerced to a Boolean, or null if it is absent/null/unrecognized. */
fun Map<String, Any?>.getOptBool(key: String): Boolean? = when (val v = this[key]) {
    null -> null
    is Boolean -> v
    else -> v.toString().toOptBool()
}

/** Alternative to normal toString() that creates output that is more friendly to humans.
 * This will be expanded later on for dates and objects with specialized interfaces. */
fun Any?.fmt(): String {
    if (this == null) return "null"
    if (this is Double) return fmtD()
    if (this is Float) return fmtF()
    if (this is Instant) return formatDate() // ISO system timestamp, so coerced dates serialize as JSON strings
    return toString()
}

/** We are essentially mandating that code that deals with very small numbers converts to units where the
 * numbers are not so small. Doing this allows us to limit the number of decimal digits we have to deal with. */
fun Double.fmtD(): String = formatFractional(this, 7)

/** We are making a bet that most floating point values actually represent numbers with at most three digits of
 * real precision. It is unusual to use a low-precision representation of a very small floating point number. */
fun Float.fmtF(): String = formatFractional(this.toDouble(), 3)

@KdrPrivate
val pow10 = longArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000)

/**
 * Rounds [value] to at most [maxDigits] fractional digits (ties to even) and trims trailing zeros. The
 * result is always a valid JSON number: the integer part carries no leading zeros, a leading digit
 * introduces any fractional part and holds at least one digit, and there is never a trailing decimal point.
 *
 * Non-finite values (NaN and the infinities) have no JSON number representation, so they render as `null` --
 * consistent with how the parser forgives such values when `strictValues` is off.
 *
 * This is pure Kotlin with no shared mutable state, so it is safe to call concurrently (unlike the
 * `DecimalFormat` it replaced) and is portable to non-JVM targets.
 */
@KdrPrivate
fun formatFractional(value: Double, maxDigits: Int): String {
    if (!value.isFinite()) return "null"
    if (value == 0.0) return "0" // Also folds -0.0 to "0".
    val negative = value < 0.0
    val absV = if (negative) -value else value
    val scale = pow10[maxDigits]
    // Very large magnitudes overflow the Long scaling; fall back to the shortest round-trippable form,
    // which for such values is a valid JSON number (using an `E` exponent when needed).
    if (absV >= (Long.MAX_VALUE / scale).toDouble()) return value.toString()
    val scaled = round(absV * scale).toLong()
    if (scaled == 0L) return "0"
    val sb = StringBuilder()
    if (negative) sb.append('-')
    sb.append(scaled / scale)
    val fracStr = (scaled % scale).toString().padStart(maxDigits, '0').trimEnd('0')
    if (fracStr.isNotEmpty()) sb.append('.').append(fracStr)
    return sb.toString()
}


