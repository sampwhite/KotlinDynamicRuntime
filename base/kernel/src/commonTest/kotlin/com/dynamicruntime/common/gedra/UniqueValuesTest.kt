package com.dynamicruntime.common.gedra

import kotlin.test.Test
import kotlin.test.assertEquals

/** How a unique field's values are compared (issue #1851), the same on the server and in the browser. */
class UniqueValuesTest {
    @Test
    fun valuesDifferingByCaseOrPunctuationAreOne() {
        for (value in listOf("Q3 Budget", "q3-budget ", "  Q3...BUDGET!", "q3\tbudget")) assertEquals("q3 budget", normalizeUnique(value))
        assertEquals("café", normalizeUnique("CAFÉ"))
        assertEquals("予算 3", normalizeUnique("予算（3）"))
        for (value in listOf("", "!!!", " - ")) assertEquals("", normalizeUnique(value))
    }
}
