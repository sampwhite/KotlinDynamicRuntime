package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A value that had to be coerced is held to the same checks as one that arrived as what it became, and the
 * validate-only and coerce paths reach the same verdict (issue #815). Every case runs one input through both
 * paths and requires identical failures -- the split this guards against is exactly one path checking what the
 * other skips. In `commonTest`, so it holds on the JVM and under Kotlin/JS alike.
 */
class SchCoercionParityTest {

    /** One type, `ns.T`, whose single property `v` has [body]. */
    private fun fieldType(body: Map<String, Any?>): SchType =
        parseSchemaTypes(
            mapOf("ns.T" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("v" to body))),
        ).getValue("ns.T")

    /** The failure codes for `{v: value}`, after checking that validate-only and coerce mode report the same. */
    private fun codes(body: Map<String, Any?>, value: Any?): List<SchFailCode> {
        val type = fieldType(body)
        val data = mapOf("v" to value)
        val validated = validate(type, data)
        assertEquals(validated, coerceAndValidate(type, data).failures, "validate-only and coerce disagree")
        return validated.map { it.code }
    }

    private fun options(vararg values: String) = values.map { mapOf(SCH.value to it) }

    @Test
    fun aCoercedValueMeetsTheConst() {
        val two = mapOf(SCH.type to SCT.integer, SCH.const to 2L)
        assertEquals(listOf(SchFailCode.invalidOption), codes(two, "3"))
        assertEquals(emptyList(), codes(two, "2"))
        assertEquals(2L, coerceAndValidate(fieldType(two), mapOf("v" to "2")).value.let { (it as Map<*, *>)["v"] })
    }

    @Test
    fun aCoercedValueMeetsAClosedOptionList() {
        val choice = mapOf(SCH.type to SCT.string, SCH.allowCoerce to true, SCH.options to options("1", "2"))
        assertEquals(listOf(SchFailCode.invalidOption), codes(choice, 7L))
        assertEquals(emptyList(), codes(choice, 1L))
        // An open list is suggestions, coerced or not.
        assertEquals(emptyList(), codes(choice + (SCH.openOptions to true), 7L))
    }

    // A list parsed from a string is validated in full when it re-enters the validator; its bounds were once
    // checked a second time on the way out, reporting the same failure twice.
    @Test
    fun aContainerCoercedFromAStringReportsEachFailureOnce() {
        val list = mapOf(SCH.type to SCT.array, SCH.allowCoerce to true, SCH.minItems to 2L)
        assertEquals(listOf(SchFailCode.belowMinimum), codes(list, "a"))
        assertEquals(emptyList(), codes(list, "a,b"))
    }

    // Validate-only builds no output map, so an object element came back as it arrived, and two elements equal
    // only once coerced were duplicates in one mode and not the other.
    @Test
    fun uniqueItemsJudgesCoercedElementsInBothModes() {
        val items = mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("n" to mapOf(SCH.type to SCT.integer)))
        val list = mapOf(SCH.type to SCT.array, SCH.uniqueItems to true, SCH.items to items)
        assertEquals(listOf(SchFailCode.duplicateItem), codes(list, listOf(mapOf("n" to "5"), mapOf("n" to 5L))))
        // Validate-only still hands back the input untouched.
        val input = mapOf("v" to listOf(mapOf("n" to "5"), mapOf("n" to 6L)))
        assertEquals(emptyList(), validate(fieldType(list), input))
    }

    @Test
    fun optionsOnADeclaredNonStringOrADateAreRefused() {
        for (body in listOf(
            mapOf(SCH.type to SCT.integer, SCH.options to options("1")),
            mapOf(SCH.type to SCT.boolean, SCH.options to options("true")),
            mapOf(SCH.type to SCT.string, SCH.format to SFMT.date, SCH.options to options("2026-01-01")),
        )) {
            val message = assertFailsWith<KdrException> { fieldType(body) }.message.orEmpty()
            assertTrue("Property 'v'" in message && "'${SCH.options}'" in message, message)
        }
        // An untyped field may carry a list: the list itself says the value is text.
        assertEquals(emptyList(), codes(mapOf(SCH.options to options("a")), "a"))
        assertEquals(listOf(SchFailCode.invalidOption), codes(mapOf(SCH.options to options("a")), 1L))
    }

    // A date is validated by parsing and returns before `const` is compared, so a `const` there checked nothing.
    @Test
    fun constOnADateIsRefused() {
        val message = assertFailsWith<KdrException> {
            fieldType(mapOf(SCH.type to SCT.string, SCH.format to SFMT.date, SCH.const to "2026-01-01"))
        }.message.orEmpty()
        assertTrue("'${SCH.const}'" in message, message)
    }
}
