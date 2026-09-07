package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.SchLayoutField
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-logic coverage for the label/description cascade (issue #586): [layoutCopy] returns the layout's copy
 * (with a `${'$'}{…}` resolved against the field's own data) when a layout addresses the field, and null
 * otherwise -- which is what makes the render site fall back to the schema `title` / `description`, then to the
 * humanized key. The render wiring itself is browser-driven; this pins the rule that decides the words.
 */
class LayoutCopyTest {
    private val type: SchType = parseSchemaTypes(
        mapOf(
            "acme.Q" to mapOf(
                SCH.type to SCT.kObject,
                SCH.title to "Questionnaire",
                SCH.properties to mapOf(
                    "topic" to mapOf(SCH.type to SCT.string, SCH.title to "Schema topic title"),
                    "notes" to mapOf(SCH.type to SCT.string),
                ),
            ),
        ),
    ).getValue("acme.Q")

    private fun optsWith(layout: SchLayout?): FormOpts =
        FormOpts(friendly = true, layouts = layout?.let { mapOf("acme.Q" to it) } ?: emptyMap())

    private val layout = SchLayout(
        fragmentFileId = "acme",
        fields = listOf(
            SchLayoutField("topic", label = "Topic", description = "Pick the subject.", hint = null),
            SchLayoutField("notes", label = "Notes about \${topic}", description = null, hint = null),
        ),
    )

    @Test
    fun theLayoutLabelAndDescriptionOverrideTheSchema() {
        val (label, description) = layoutCopy(type, "topic", emptyMap(), optsWith(layout))
        assertEquals("Topic", label)
        assertEquals("Pick the subject.", description)
    }

    @Test
    fun aSubstitutionResolvesAgainstTheObjectsOwnValues() {
        // `notes` has no description override -> null -> the render falls back to the schema's (none here).
        val (label, description) = layoutCopy(type, "notes", mapOf("topic" to "Travel"), optsWith(layout))
        assertEquals("Notes about Travel", label)
        assertNull(description)
    }

    @Test
    fun noLayoutForTheFieldLeavesBothNullSoTheSchemaTitleWins() {
        // A layout that addresses no such field, and a form with no layouts at all: both fall through.
        assertEquals(null to null, layoutCopy(type, "topic", emptyMap(), optsWith(null)))
        val partial = SchLayout("acme", listOf(SchLayoutField("notes", "Notes", null, null)))
        assertEquals(null to null, layoutCopy(type, "topic", emptyMap(), optsWith(partial)))
    }

    @Test
    fun theWireDocumentingViewIgnoresLayoutsEntirely() {
        // Not friendly (the catalog): layouts are never consulted, so the key/title path is left to decide.
        val (label, description) = layoutCopy(type, "topic", emptyMap(), FormOpts(friendly = false, layouts = mapOf("acme.Q" to layout)))
        assertNull(label)
        assertNull(description)
    }

    @Test
    fun aBrokenSubstitutionFallsBackToTheCopyAsWritten() {
        // A `${'$'}{…}` the data cannot resolve must not blank the label; it shows as written rather than throwing.
        val broken = SchLayout("acme", listOf(SchLayoutField("topic", "Label \${missing.deep.path}", null, null)))
        val (label, _) = layoutCopy(type, "topic", emptyMap(), optsWith(broken))
        assertEquals("Label \${missing.deep.path}", label)
    }
}
