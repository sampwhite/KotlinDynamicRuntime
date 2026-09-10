package com.dynamicruntime.webapp

import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.schema.LAYSTR
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-logic coverage for the form-error strings (issue #641): [formErrorStrings] returns the first override any
 * of the form's rendered trait layouts sets, else the page's default; and [formTraitLayouts] reaches those trait
 * layouts through the form's real type tree -- the envelope's entries/edits union, each branch's `data` type,
 * keyed into the delivered layouts. The second is the wiring the render rests on: a form's own type carries no
 * layout, so keying the override off it (the first cut of this change) silently never resolved.
 */
class FormErrorStringsTest {
    private fun layoutWith(strings: Map<String, String>) = SchLayout(null, null, emptyList(), strings)

    @Test
    fun noLayoutsFallBackToDefaults() {
        val (summary, hint) = formErrorStrings(emptyList(), "S-default", "H-default")
        assertEquals("S-default", summary)
        assertEquals("H-default", hint)
    }

    @Test
    fun aLayoutWithNoStringsFallsBack() {
        val (summary, hint) = formErrorStrings(listOf(layoutWith(emptyMap())), "S-default", "H-default")
        assertEquals("S-default", summary)
        assertEquals("H-default", hint)
    }

    @Test
    fun firstOverrideWinsPerKey() {
        // Summary only on the first layout, hint only on the second: each key takes the first layout that sets it.
        val (summary, hint) = formErrorStrings(
            listOf(
                layoutWith(mapOf(LAYSTR.formErrorSummary to "S-override")),
                layoutWith(mapOf(LAYSTR.formErrorHint to "H-override")),
            ),
            "S-default", "H-default",
        )
        assertEquals("S-override", summary)
        assertEquals("H-override", hint)
    }

    // A form-document type: an `entries` array of a two-trait union, each branch carrying a `data` of its trait
    // data type -- the same shape the create/edit forms render, and where the only layouts a form has live.
    private fun formDocDefs(): Map<String, Any?> = mapOf(
        "t.NameData" to mapOf(SCH.type to SCT.kObject),
        "t.NameEntry" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                GE.traitId to mapOf(SCH.type to SCT.string, SCH.const to "name"),
                GE.data to mapOf(SCH.dRef to "t.NameData"),
            ),
        ),
        "t.ExpenseData" to mapOf(SCH.type to SCT.kObject),
        "t.ExpenseEntry" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                GE.traitId to mapOf(SCH.type to SCT.string, SCH.const to "expenseReport"),
                GE.data to mapOf(SCH.dRef to "t.ExpenseData"),
            ),
        ),
        "t.Union" to mapOf(
            SCH.oneOf to listOf(mapOf(SCH.dRef to "t.NameEntry"), mapOf(SCH.dRef to "t.ExpenseEntry")),
            SCH.discriminator to mapOf(SCH.propertyName to GE.traitId),
        ),
        "t.FormDoc" to mapOf(
            SCH.type to SCT.kObject,
            SCH.properties to mapOf(
                GDF.entries to mapOf(SCH.type to SCT.array, SCH.items to mapOf(SCH.dRef to "t.Union")),
            ),
        ),
    )

    @Test
    fun reachesTraitLayoutsThroughTheFormType() {
        val formType = parseSchemaTypes(formDocDefs()).getValue("t.FormDoc")
        // A client set the hint on their expense trait's data-type layout; nothing on the name trait.
        val layouts = mapOf("t.ExpenseData" to layoutWith(mapOf(LAYSTR.formErrorHint to "Check the expense fields.")))
        val reached = formTraitLayouts(formType, layouts)
        // Both branches' data types are visited, in union order; only the expense one has a layout.
        assertEquals(1, reached.size)
        val (_, hint) = formErrorStrings(reached, "S-default", "H-default")
        assertEquals("Check the expense fields.", hint)
    }

    @Test
    fun noTraitLayoutMeansTheDefault() {
        val formType = parseSchemaTypes(formDocDefs()).getValue("t.FormDoc")
        val reached = formTraitLayouts(formType, emptyMap())
        assertEquals(emptyList(), reached)
        assertEquals("H-default", formErrorStrings(reached, "S-default", "H-default").second)
    }
}
