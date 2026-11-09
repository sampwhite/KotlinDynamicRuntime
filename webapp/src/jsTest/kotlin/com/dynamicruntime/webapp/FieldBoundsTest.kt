package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A field's bounds said in words, from copy (issue #1693): [boundHintText] for each kind of field and each end it
 * declares, a client's rewording, and the placeholder ([typeHint]) left only where a value's shape cannot be guessed.
 */
class FieldBoundsTest {
    /** The value type of a field of [fieldSchema]. */
    private fun type(fieldSchema: Map<String, Any?>): SchType = parseSchemaTypes(
        mapOf("t.T" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("f" to fieldSchema))),
    ).getValue("t.T").properties.getValue("f").valueType

    private fun hint(vararg schema: Pair<String, Any?>, words: FieldBoundWords = FieldBoundWords()) =
        boundHintText(type(mapOf(*schema)), words)

    private val opts = listOf(mapOf(SCH.value to "a", SCH.label to "A"), mapOf(SCH.value to "b", SCH.label to "B"))

    @Test
    fun aTextFieldsLengthReadsAsCharacters() {
        assertEquals("Up to 128 characters", hint(SCH.type to SCT.string, SCH.maxLength to 128))
        assertEquals("From 50 to 600 characters", hint(SCH.type to SCT.string, SCH.minLength to 50, SCH.maxLength to 600))
        assertEquals("At least 3 characters", hint(SCH.type to SCT.string, SCH.minLength to 3))
        // One character is said as one.
        assertEquals("At least 1 character", hint(SCH.type to SCT.string, SCH.minLength to 1))
        // Equal ends say the one length.
        assertEquals("Exactly 6 characters", hint(SCH.type to SCT.string, SCH.minLength to 6, SCH.maxLength to 6))
        assertEquals("Exactly 1 character", hint(SCH.type to SCT.string, SCH.minLength to 1, SCH.maxLength to 1))
        assertNull(hint(SCH.type to SCT.string))
    }

    @Test
    fun aNumbersValueReadsAsItsRange() {
        assertEquals("From 20 to 90", hint(SCH.type to SCT.integer, SCH.minimum to 20, SCH.maximum to 90))
        assertEquals("At least 0", hint(SCH.type to SCT.integer, SCH.minimum to 0))
        assertEquals("Up to 2.5", hint(SCH.type to SCT.number, SCH.maximum to 2.5))
        // An end that leaves out the bound itself says so.
        assertEquals("More than 0", hint(SCH.type to SCT.number, SCH.exclusiveMinimum to 0))
        assertEquals("Less than 10", hint(SCH.type to SCT.number, SCH.exclusiveMaximum to 10))
        assertEquals("More than 0 and up to 10", hint(SCH.type to SCT.number, SCH.exclusiveMinimum to 0, SCH.maximum to 10))
        assertEquals("From 0 to less than 10", hint(SCH.type to SCT.number, SCH.minimum to 0, SCH.exclusiveMaximum to 10))
        assertEquals("More than 0 and less than 10", hint(SCH.type to SCT.number, SCH.exclusiveMinimum to 0, SCH.exclusiveMaximum to 10))
    }

    @Test
    fun aListOfChoicesReadsAsHowManyToChoose() {
        val choices = mapOf(SCH.type to SCT.string, SCH.options to opts)
        assertEquals("Choose up to 3", hint(SCH.type to SCT.array, SCH.items to choices, SCH.maxItems to 3))
        assertEquals("Choose at least 1", hint(SCH.type to SCT.array, SCH.items to choices, SCH.minItems to 1))
        assertEquals("Choose 1 to 2", hint(SCH.type to SCT.array, SCH.items to choices, SCH.minItems to 1, SCH.maxItems to 2))
        assertEquals("Choose 2", hint(SCH.type to SCT.array, SCH.items to choices, SCH.minItems to 2, SCH.maxItems to 2))
    }

    @Test
    fun anyOtherListOrAMapReadsAsEntries() {
        val strings = mapOf(SCH.type to SCT.string)
        assertEquals("Up to 4 entries", hint(SCH.type to SCT.array, SCH.items to strings, SCH.maxItems to 4))
        assertEquals("At least 1 entry", hint(SCH.type to SCT.array, SCH.items to strings, SCH.minItems to 1))
        assertEquals("From 2 to 5 entries", hint(SCH.type to SCT.array, SCH.items to strings, SCH.minItems to 2, SCH.maxItems to 5))
        assertEquals("Exactly 3 entries", hint(SCH.type to SCT.array, SCH.items to strings, SCH.minItems to 3, SCH.maxItems to 3))
        assertEquals(
            "Up to 6 entries",
            hint(SCH.type to SCT.kObject, SCH.additionalProperties to strings, SCH.maxProperties to 6),
        )
    }

    @Test
    fun aClientRewordsTheLinesAndAMistakeFallsBackToTheShippedOne() {
        val words = FieldBoundWords.of(
            Copy(
                mapOf(
                    HFRAG.fieldBoundsNs to mapOf(
                        FieldBound.textAtMost.name to $$"No more than ${max} letters",
                        FieldBound.numberBetween.name to $$"${min}–${max}",
                        // Not a template that evaluates: its parameter is not one the line is given.
                        FieldBound.numberAtLeast.name to $$"At least ${minimum}",
                    ),
                ),
            ),
        )
        assertEquals("No more than 128 letters", hint(SCH.type to SCT.string, SCH.maxLength to 128, words = words))
        assertEquals("1–5", hint(SCH.type to SCT.integer, SCH.minimum to 1, SCH.maximum to 5, words = words))
        assertEquals("At least 2", hint(SCH.type to SCT.integer, SCH.minimum to 2, words = words))
        // A line the client leaves alone is as shipped.
        val choices = mapOf(SCH.type to SCT.string, SCH.options to opts)
        assertEquals("Choose up to 3", hint(SCH.type to SCT.array, SCH.items to choices, SCH.maxItems to 3, words = words))
    }

    @Test
    fun onlyADateOrAFileHasAPlaceholder() {
        assertNull(typeHint(type(mapOf(SCH.type to SCT.string))))
        assertNull(typeHint(type(mapOf(SCH.type to SCT.integer))))
        assertNull(typeHint(type(mapOf(SCH.type to SCT.number))))
        assertEquals(SFMT.date, typeHint(type(mapOf(SCH.type to SCT.string, SCH.format to SFMT.date))))
        assertEquals("file", typeHint(type(mapOf(SCH.type to SCT.string, SCH.format to SFMT.binary))))
    }
}
