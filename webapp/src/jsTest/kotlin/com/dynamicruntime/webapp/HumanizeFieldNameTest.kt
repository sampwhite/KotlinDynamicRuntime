package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage (issue #161) for [humanizeFieldName], the friendly-form fallback that turns a wire key
 * into a readable label when a field declares no `title` (issue #408).
 */
class HumanizeFieldNameTest {

    @Test
    fun camelCaseBecomesASentence() {
        assertEquals("Expense report", humanizeFieldName("expenseReport"))
        assertEquals("Per item amount", humanizeFieldName("perItemAmount"))
        assertEquals("Site visit", humanizeFieldName("siteVisit"))
    }

    @Test
    fun aSingleWordIsJustCapitalized() {
        assertEquals("Year", humanizeFieldName("year"))
        assertEquals("Notes", humanizeFieldName("notes"))
    }

    @Test
    fun aTrailingCapitalStartsANewWord() {
        // The case that keeps "id" from reading as part of the word before it.
        assertEquals("Trait id", humanizeFieldName("traitId"))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", humanizeFieldName(""))
    }
}
