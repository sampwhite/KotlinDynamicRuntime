package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.LAYSTR
import com.dynamicruntime.common.schema.SchLayout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage for the form-error string resolution (issue #641): [formErrorStrings] returns the edited
 * type's layout override for the summary heading and the hint when the layout sets it, else the page's own
 * default. This is the rule the render rests on -- which words show; the render wiring (and the debug gate that
 * chooses summary-list vs hint) is browser-driven.
 */
class FormErrorStringsTest {
    private fun layoutWith(strings: Map<String, String>) = SchLayout(null, null, emptyList(), strings)

    @Test
    fun noLayoutFallsBackToDefaults() {
        val (summary, hint) = formErrorStrings(null, "S-default", "H-default")
        assertEquals("S-default", summary)
        assertEquals("H-default", hint)
    }

    @Test
    fun emptyStringsFallsBackToDefaults() {
        val (summary, hint) = formErrorStrings(layoutWith(emptyMap()), "S-default", "H-default")
        assertEquals("S-default", summary)
        assertEquals("H-default", hint)
    }

    @Test
    fun overrideSummaryLeavesHintDefault() {
        val (summary, hint) = formErrorStrings(
            layoutWith(mapOf(LAYSTR.formErrorSummary to "S-override")), "S-default", "H-default",
        )
        assertEquals("S-override", summary)
        assertEquals("H-default", hint)
    }

    @Test
    fun overrideBoth() {
        val (summary, hint) = formErrorStrings(
            layoutWith(mapOf(LAYSTR.formErrorSummary to "S-override", LAYSTR.formErrorHint to "H-override")),
            "S-default", "H-default",
        )
        assertEquals("S-override", summary)
        assertEquals("H-override", hint)
    }
}
