package com.dynamicruntime.common.util

/** The most decimals `formatNumber` writes: what [formatFractional] keeps of a fractional value. */
const val maxNumberDecimals = 7

/**
 * [value] -- a `Long` or a `Double` -- with its whole part grouped in threes and [decimals] decimals (issue #1322):
 * `1,234.50` for `1234.5` at 2. With no [decimals], as few as the value needs, up to [maxNumberDecimals]: `1,234.5`.
 *
 * Rounded on the value's decimal text, half away from zero, so `1.005` at 2 is `1.01` as a person expects -- where
 * rounding the `Double` itself, a hair under `1.005`, gives `1.00`. One convention for the separators until the product
 * is internationalized (`deferred-work.md`). A value too large to hold exactly is written as the evaluator writes it.
 */
fun numberText(value: Number, decimals: Int? = null): String {
    val plain = if (value is Double) formatFractional(value, maxNumberDecimals) else value.toLong().toString()
    if (plain == "null" || 'E' in plain) return plain
    val negative = plain.startsWith('-')
    val digits = plain.removePrefix("-")
    var whole = digits.substringBefore('.')
    var fraction = digits.substringAfter('.', "")
    if (decimals != null) {
        if (fraction.length > decimals) {
            val roundUp = fraction[decimals] >= '5'
            fraction = fraction.take(decimals)
            if (roundUp) {
                val carried = incrementDigits(whole + fraction)
                whole = carried.dropLast(decimals).ifEmpty { "0" }
                fraction = carried.takeLast(decimals)
            }
        }
        fraction = fraction.padEnd(decimals, '0')
    }
    val zero = whole.all { it == '0' } && fraction.all { it == '0' }
    val sign = if (negative && !zero) "-" else ""
    return sign + groupThousands(whole) + if (fraction.isEmpty()) "" else ".$fraction"
}

/**
 * [value] rounded to [decimals] decimals (issue #1501), half away from zero on its decimal text as [numberText] writes
 * it -- so `round(1.005, 2)` is `1.01`, and a value and its written form agree. At 0 decimals a whole number, as a
 * `Long` while one holds it; otherwise a `Double`. A `Long` is already whole and comes back as it is, a value too large
 * to write without an exponent is already whole too, and one that is not finite is returned unchanged.
 */
fun roundedNumber(value: Number, decimals: Int): Number {
    if (value !is Double) return value.toLong()
    if (!value.isFinite()) return value
    val rounded = numberText(value, decimals).replace(",", "").toDoubleOrNull() ?: return value
    // Every double this large is already whole, so only the `Long` range bounds the whole number it becomes.
    return if (decimals == 0 && kotlin.math.abs(rounded) < 9.2e18) rounded.toLong() else rounded
}

/** [digits] plus one in its last place, carrying: `"0999"` is `"1000"`, `"99"` is `"100"`. */
private fun incrementDigits(digits: String): String {
    val chars = digits.toCharArray()
    var i = chars.size - 1
    while (i >= 0) {
        if (chars[i] < '9') {
            chars[i] = chars[i] + 1
            return chars.concatToString()
        }
        chars[i] = '0'
        i--
    }
    return "1" + chars.concatToString()
}

private fun groupThousands(whole: String): String {
    val trimmed = whole.trimStart('0').ifEmpty { "0" }
    val head = trimmed.length % 3
    return buildString {
        append(trimmed, 0, head)
        for (i in head until trimmed.length step 3) {
            if (isNotEmpty()) append(',')
            append(trimmed, i, i + 3)
        }
    }
}

/**
 * A currency as `currency(x, code)` writes it (issue #1322): its [symbol], written before the amount, and the
 * [decimals] its amounts are usually written with.
 */
class CurrencyStyle(val symbol: String, val decimals: Int)

/**
 * The ISO 4217 currencies the kernel knows a symbol for, as a browser's `Intl` writes them in US English: `$` alone is
 * the US dollar, and another dollar says whose (`CA$`). A code not here is written after the amount, with two decimals.
 */
val currencyStyles: Map<String, CurrencyStyle> = mapOf(
    "USD" to CurrencyStyle("$", 2),
    "EUR" to CurrencyStyle("€", 2),
    "GBP" to CurrencyStyle("£", 2),
    "JPY" to CurrencyStyle("¥", 0),
    "CNY" to CurrencyStyle("CN¥", 2),
    "INR" to CurrencyStyle("₹", 2),
    "KRW" to CurrencyStyle("₩", 0),
    "CAD" to CurrencyStyle("CA$", 2),
    "AUD" to CurrencyStyle("A$", 2),
    "NZD" to CurrencyStyle("NZ$", 2),
    "MXN" to CurrencyStyle("MX$", 2),
    "BRL" to CurrencyStyle("R$", 2),
    "HKD" to CurrencyStyle("HK$", 2),
    "ILS" to CurrencyStyle("₪", 2),
    "VND" to CurrencyStyle("₫", 0),
    "PHP" to CurrencyStyle("₱", 2),
)

/** Whether [code] is shaped like an ISO 4217 code: three letters, `USD` or `usd`. */
fun isCurrencyCode(code: String): Boolean = code.length == 3 && code.all { it in 'A'..'Z' || it in 'a'..'z' }

/**
 * [value] as an amount of the currency [code] (issue #1322): `$1,234.50` for `1234.5` in `USD`, `¥1,234` in `JPY`,
 * `-$5.00` for a negative amount, and `1,234.50 CHF` for a code with no symbol here ([currencyStyles]). The code is read
 * in either case.
 */
fun currencyText(value: Number, code: String): String {
    val upper = code.uppercase()
    val style = currencyStyles[upper] ?: return numberText(value, 2) + " " + upper
    val amount = numberText(value, style.decimals)
    return if (amount.startsWith('-')) "-" + style.symbol + amount.drop(1) else style.symbol + amount
}
