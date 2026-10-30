package com.dynamicruntime.common.util

import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A template's numbers as a person reads them (issue #1322): `formatNumber` groups and fixes decimals, `currency` writes
 * an amount with its currency's symbol and decimals. In `commonTest`, so the backend and the browser write them alike.
 */
class ScriptNumberTest {
    private fun eval(text: String, vararg data: Pair<String, Any?>): String = text.evalTemplate(mapOf(*data))

    @Test
    fun formatNumberGroupsAndFixesDecimals() {
        assertEquals("1,234.50", eval($$"${formatNumber(x, 2)}", "x" to 1234.5))
        assertEquals("1,234.5", eval($$"${formatNumber(x)}", "x" to 1234.5))
        assertEquals("1,234,567", eval($$"${formatNumber(x)}", "x" to 1234567L))
        assertEquals("12.0 km", eval($$"${formatNumber(x, 1)} km", "x" to 12L))
        assertEquals("999", numberText(999L))
        assertEquals("0.5", numberText(0.5))
        assertEquals("-1,000", numberText(-1000L))
    }

    @Test
    fun roundingIsOnTheDecimalValueHalfAwayFromZero() {
        // The Double nearest 1.005 is a hair under it; a person means 1.005, and rounds it up.
        assertEquals("1.01", numberText(1.005, 2))
        assertEquals("1,000", numberText(999.5, 0))
        assertEquals("-2.68", numberText(-2.675, 2))
        // Rounded to zero, a negative value is plain zero.
        assertEquals("0.00", numberText(-0.001, 2))
    }

    @Test
    fun currencyWritesTheSymbolAndTheCurrencysDecimals() {
        assertEquals("$1,234.50", eval($$"""${currency(x, "USD")}""", "x" to 1234.5))
        assertEquals("¥1,234", eval($$"""${currency(x, "JPY")}""", "x" to 1234L))
        assertEquals("€0.99", eval($$"""${currency(x, "eur")}""", "x" to 0.99))
        assertEquals("-$5.00", currencyText(-5L, "USD"))
        assertEquals("CA$12.00", currencyText(12L, "CAD"))
        // A code with no symbol here is written as the code.
        assertEquals("1,234.50 CHF", eval($$"""${currency(x, code)}""", "x" to 1234.5, "code" to "CHF"))
    }

    @Test
    fun aValueThatIsNotANumberIsATypeMismatch() {
        val text = assertFailsWith<KdrException> { eval($$"${formatNumber(x, 2)}", "x" to "1234") }
        assertEquals(ScriptError.typeMismatch, text.extraData[KdrException.errorCodeKey])
        assertTrue("expects a number" in text.message!!)
        val computed = assertFailsWith<KdrException> { eval($$"${currency(x, code)}", "x" to 1L, "code" to "dollars") }
        assertEquals(ScriptError.typeMismatch, computed.extraData[KdrException.errorCodeKey])
    }

    @Test
    fun aLiteralArgumentThatIsWrongIsRefusedAtTheParse() {
        for (text in listOf(
            $$"${formatNumber(x, 8)}", $$"${formatNumber(x, 1.5)}", $$"""${formatNumber(x, "2")}""",
            $$"""${currency(x, "US")}""", $$"${currency(x)}",
        )) {
            assertEquals(ScriptError.syntaxError, text.analyzeTemplate().issues.single().code, text)
        }
    }

    @Test
    fun roundGivesANumberRoundedAsFormatNumberWrites() {
        fun round(text: String, vararg data: Pair<String, Any?>): Any? = ScriptExpression.parse(text).evaluate(mapOf(*data))
        // A floating-point near miss comes back whole, as a Long an integer property takes (issue #1501).
        assertEquals(3L, round("round(x * 10)", "x" to 0.30000000000000004))
        assertEquals(3L, round("round(2.5)"))
        assertEquals(-3L, round("round(-2.5)"))
        assertEquals(1.01, round("round(x, 2)", "x" to 1.005))
        assertEquals(7L, round("round(x)", "x" to 7L))
        // Past where the evaluator writes an exponent, still a whole number an integer property takes.
        assertEquals(10_000_000_000_000_000L, round("round(x)", "x" to 1.0e16))
        assertEquals("4", eval($$"${round(x)}", "x" to 3.6))
        val e = assertFailsWith<KdrException> { ScriptExpression.parse("round(x, 9)") }
        assertTrue(e.message.orEmpty().contains("'round' takes a count of decimals"))
    }
}
