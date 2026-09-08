package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchFailure
import com.dynamicruntime.common.schema.SchLayout
import com.dynamicruntime.common.schema.SchLayoutField
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-logic coverage for the layout copy cascade (issues #586, #587): [layoutCopy] returns a [LayoutCopy] with
 * the layout's `label` / `description` (resolved against the field's own data) and `hint` (resolved against the
 * field's **bounds** context, `${'$'}{min}` / `${'$'}{max}`) when a layout addresses the field, else null — which is
 * what makes the render site fall back to the schema's `title` / `description` and the derived bound hint. The
 * render wiring is browser-driven; this pins the rule that decides the words.
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
                    // A bounded numeric field, for the hint's bounds context.
                    "year" to mapOf(SCH.type to SCT.integer, SCH.minimum to 2000, SCH.maximum to 2100),
                ),
            ),
        ),
    ).getValue("acme.Q")

    private fun optsWith(layout: SchLayout?): FormOpts =
        FormOpts(friendly = true, layouts = layout?.let { mapOf("acme.Q" to it) } ?: emptyMap())

    private val layout = SchLayout(
        fragmentFileId = "acme",
        label = null,
        fields = listOf(
            SchLayoutField("topic", label = "Topic", description = "Pick the subject.", hint = null),
            SchLayoutField("notes", label = $$"Notes about ${topic}", description = null, hint = null),
            SchLayoutField("year", label = null, description = null, hint = $$"Any year from ${min} to ${max}."),
        ),
    )

    @Test
    fun theLayoutLabelAndDescriptionOverrideTheSchema() {
        val copy = layoutCopy(type, "topic", emptyMap(), optsWith(layout))!!
        assertEquals("Topic", copy.label)
        assertEquals("Pick the subject.", copy.description)
        assertNull(copy.hint)
    }

    @Test
    fun aLabelSubstitutionResolvesAgainstTheObjectsOwnValues() {
        // `notes` overrides only the label; description falls through (null).
        val copy = layoutCopy(type, "notes", mapOf("topic" to "Travel"), optsWith(layout))!!
        assertEquals("Notes about Travel", copy.label)
        assertNull(copy.description)
    }

    @Test
    fun theHintResolvesAgainstTheFieldsBoundsContext() {
        // `${min}` / `${max}` come from the field's own minimum/maximum, not the object's values.
        val copy = layoutCopy(type, "year", emptyMap(), optsWith(layout))!!
        assertEquals("Any year from 2000 to 2100.", copy.hint)
        assertNull(copy.label)
    }

    @Test
    fun noLayoutForTheFieldReturnsNullSoTheSchemaWins() {
        // A layout that addresses no such field, and a form with no layouts at all: both fall through.
        assertNull(layoutCopy(type, "topic", emptyMap(), optsWith(null)))
        val partial = SchLayout("acme", null, listOf(SchLayoutField("notes", "Notes", null, null)))
        assertNull(layoutCopy(type, "topic", emptyMap(), optsWith(partial)))
    }

    @Test
    fun theWireDocumentingViewIgnoresLayoutsEntirely() {
        // Not friendly (the catalog): layouts are never consulted, so the key/title path is left to decide.
        assertNull(layoutCopy(type, "topic", emptyMap(), FormOpts(friendly = false, layouts = mapOf("acme.Q" to layout))))
    }

    @Test
    fun aBrokenSubstitutionFallsBackToTheCopyAsWritten() {
        // A `${'$'}{…}` the data cannot resolve must not blank the label; it shows as written rather than throwing.
        val broken = SchLayout("acme", null, listOf(SchLayoutField("topic", $$"Label ${missing.deep.path}", null, null)))
        assertEquals($$"Label ${missing.deep.path}", layoutCopy(type, "topic", emptyMap(), optsWith(broken))!!.label)
    }

    // --- the error override (issue #588): resolved per failure, over its code's params ---

    private val withErrors = SchLayout(
        fragmentFileId = "acme",
        label = null,
        fields = listOf(
            SchLayoutField(
                "topic", label = null, description = null, hint = null,
                errors = mapOf(
                    SchFailCode.invalidOption.name to $$"""We don't cover "${value}". Choose one of: ${options}.""",
                    SCH.errorDefault to $$"Something is wrong with ${field}.",
                ),
            ),
        ),
    )

    @Test
    fun theErrorOverrideResolvesAgainstTheFailuresParams() {
        val copy = layoutCopy(type, "topic", emptyMap(), optsWith(withErrors))
        val prop = type.properties.getValue("topic")
        val invalid = SchFailure(
            "topic", SchFailCode.invalidOption, "built-in wording",
            options = listOf(SchOption("news", "News"), SchOption("sport", "Sport")),
        )
        // The code-specific message wins, with the offending value and the option labels substituted in.
        assertEquals(
            """We don't cover "weather". Choose one of: News, Sport.""",
            layoutErrorMessage(copy, prop, "topic", "weather", invalid),
        )
        // A code with no entry of its own falls to `default`, which may name only the field.
        val other = SchFailure("topic", SchFailCode.badValue, "built-in wording")
        assertEquals("Something is wrong with topic.", layoutErrorMessage(copy, prop, "topic", "x", other))
    }

    @Test
    fun noErrorOverrideForTheFieldOrNoLayoutReturnsNull() {
        val prop = type.properties.getValue("topic")
        val invalid = SchFailure("topic", SchFailCode.invalidOption, "built-in wording")
        // A layout field with no error map, and no copy at all: both fall through to the built-in message.
        val noErrors = SchLayout("acme", null, listOf(SchLayoutField("topic", "Topic", null, null)))
        assertNull(layoutErrorMessage(layoutCopy(type, "topic", emptyMap(), optsWith(noErrors)), prop, "topic", "x", invalid))
        assertNull(layoutErrorMessage(null, prop, "topic", "x", invalid))
    }
}
