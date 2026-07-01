package com.dynamicruntime.common.util

import com.dynamicruntime.common.annotation.KdrPrivate
import kotlin.math.round

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
 * Returns this value's `toString()` rendering only if it is a [CharSequence]
 * (e.g., a String); otherwise null. The receiver is nullable, so a null value (or
 * a non-CharSequence such as a number) yields null.
 */
fun Any?.toOptStr(): String? = if (this is CharSequence) this.toString() else null

/** Alternative to normal toString() that creates output that is more friendly to humans.
 * This will be expanded later on for dates and objects with specialized interfaces. */
fun Any?.fmt(): String {
    if (this == null) return "null"
    if (this is Double) return fmtD()
    if (this is Float) return fmtF()
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
 * result is always a valid JSON number: the integer part carries no leading zeros, any fractional part is
 * introduced by a leading digit and holds at least one digit, and there is never a trailing decimal point.
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


