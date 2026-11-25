package com.dynamicruntime.common.gedra

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** How a unique field's values are compared (issue #1851), the same on the server and in the browser. */
class UniqueValuesTest {
    @Test
    fun valuesDifferingByCaseOrPunctuationAreOne() {
        for (value in listOf("Q3 Budget", "q3-budget ", "  Q3...BUDGET!", "q3\tbudget")) assertEquals("q3 budget", normalizeUnique(value))
        assertEquals("café", normalizeUnique("CAFÉ"))
        assertEquals("予算 3", normalizeUnique("予算（3）"))
        for (value in listOf("", "!!!", " - ")) assertEquals("", normalizeUnique(value))
    }

    @Test
    fun aMarkIsPartOfItsLetterAndAnAstralCharacterIsKept() {
        // Hindi words that differ only in a vowel sign, and a Thai one with two.
        assertEquals("\u0915\u0940", normalizeUnique("\u0915\u0940!"))
        assertNotEquals(normalizeUnique("\u0915\u0940"), normalizeUnique("\u0915\u0941"))
        assertEquals("\u0E17\u0E35\u0E48", normalizeUnique("\u0E17\u0E35\u0E48"))
        // A letter outside the basic plane, an emoji, and an emoji with the selector that only says how to draw it.
        assertEquals("\uD840\uDC0B", normalizeUnique("\uD840\uDC0B"))
        assertEquals("plan \uD83C\uDF89", normalizeUnique("Plan \uD83C\uDF89"))
        assertEquals("\uD83C\uDF89", normalizeUnique("\uD83C\uDF89\uFE0F"))
        // What does not show makes no difference, and a mark or a selector with no letter is nothing to compare.
        assertEquals("budget", normalizeUnique("Bud\u200Bget"))
        assertEquals("", normalizeUnique("\u2764\uFE0F"))
        assertEquals("", normalizeUnique("\u0301"))
    }

    @Test
    fun textWithNothingToCompareIsToldFromNoAnswer() {
        for (value in listOf("!!!", " - ", "\u2764\uFE0F", "\u200B")) assertTrue(lacksUniqueText(value), value)
        for (value in listOf("", "  ", "a!", "\u4E88", "\uD83C\uDF89")) assertFalse(lacksUniqueText(value), value)
    }
}
