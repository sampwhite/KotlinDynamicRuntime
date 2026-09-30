package com.dynamicruntime.common.schema

import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `g-options` entries may be bare values or `{value, label}` objects, mixed (issue #816). A bare-value list once
 * parsed to an empty closed list that refused everything; an entry that is neither form now fails the parse.
 */
class SchOptionsParseTest {

    private fun fieldType(options: List<Any?>): SchType =
        parseSchemaTypes(
            mapOf(
                "ns.T" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf("v" to mapOf(SCH.type to SCT.string, SCH.options to options)),
                ),
            ),
        ).getValue("ns.T")

    private fun codes(options: List<Any?>, value: String) =
        validate(fieldType(options), mapOf("v" to value)).map { it.code }

    @Test
    fun bareValuesAreChoicesLabeledByThemselves() {
        val options = listOf("a", "b")
        assertEquals(emptyList(), codes(options, "a"))
        assertEquals(emptyList(), codes(options, "b"))
        assertEquals(listOf(SchFailCode.invalidOption), codes(options, "c"))
        val parsed = fieldType(options).properties.getValue("v").valueType.options.orEmpty()
        assertEquals(listOf("a" to "a", "b" to "b"), parsed.map { it.value to it.label })
    }

    @Test
    fun aMixedListWorks() {
        val options = listOf("a", mapOf(SCH.value to "b", SCH.label to "Bee"), mapOf(SCH.value to "c"))
        val parsed = fieldType(options).properties.getValue("v").valueType.options.orEmpty()
        assertEquals(listOf("a" to "a", "b" to "Bee", "c" to "c"), parsed.map { it.value to it.label })
        assertEquals(emptyList(), codes(options, "b"))
        assertEquals(listOf(SchFailCode.invalidOption), codes(options, "Bee"))
    }

    @Test
    fun anEntryInNeitherFormFailsTheParseNamingIt() {
        for (bad in listOf(mapOf(SCH.label to "No value"), listOf("a"), null, mapOf(SCH.value to listOf("a")))) {
            val message = assertFailsWith<KdrException> { fieldType(listOf("ok", bad)) }.message.orEmpty()
            assertTrue("Property 'v'" in message && "entry 2" in message, message)
        }
    }
}
