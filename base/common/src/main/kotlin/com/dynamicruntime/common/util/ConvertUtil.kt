package com.dynamicruntime.common.util

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
