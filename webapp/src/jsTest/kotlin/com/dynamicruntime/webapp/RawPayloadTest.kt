package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parsing of hand-edited request JSON (issue #191). The interesting cases are all failure cases — a payload
 * spliced by hand is routinely half-finished when it is first looked at — so most of this is about whether the
 * message tells the person where they broke it.
 */
class RawPayloadTest {

    @Test
    fun parsesAnObjectIntoValues() {
        val parse = parseRawPayload("""{"name":"widget","score":3.5,"active":true}""")
        assertNull(parse.error)
        val values = assertNotNull(parse.values)
        assertEquals("widget", values["name"])
        assertEquals(3, values.size)
    }

    @Test
    fun keepsNestedStructureIntact() {
        val parse = parseRawPayload("""{"input":{"address":{"street":"1 Main St"}},"tags":["a","b"]}""")
        assertNull(parse.error)
        val values = assertNotNull(parse.values)
        val input = values["input"] as Map<*, *>
        val address = input["address"] as Map<*, *>
        assertEquals("1 Main St", address["street"])
        assertEquals(2, (values["tags"] as List<*>).size)
    }

    // Clearing the box to start over is a normal editing move, not a mistake to report.
    @Test
    fun blankTextIsAnEmptyPayloadRatherThanAnError() {
        for (text in listOf("", "   ", "\n\n")) {
            val parse = parseRawPayload(text)
            assertNull(parse.error, "blank text should not be an error")
            assertEquals(emptyMap(), parse.values)
        }
    }

    @Test
    fun reportsWhereMalformedJsonBroke() {
        // An unquoted key several lines in -- what you get pasting a JavaScript object literal instead of JSON,
        // and the reason the line number earns its keep on a long payload.
        val parse = parseRawPayload("{\n  \"name\": \"widget\",\n  score: 3.5\n}")
        assertNull(parse.values)
        val error = assertNotNull(parse.error)
        assertTrue(error.startsWith("Invalid JSON"), "should name the problem: $error")
        assertTrue(error.contains("line 3"), "should point at the offending line: $error")
    }

    // The parser treats commas as whitespace and does not police their placement, so neither a trailing comma
    // nor a missing one derails a splice. Asserted so that leniency is a known property rather than a surprise
    // to whoever next wonders why an obviously wrong payload sailed through.
    @Test
    fun commaPlacementIsNotPoliced() {
        val trailing = parseRawPayload("{\n  \"name\": \"widget\",\n}")
        assertNull(trailing.error)
        assertEquals("widget", assertNotNull(trailing.values)["name"])

        val missing = parseRawPayload("{\n  \"name\": \"widget\"\n  \"score\": 3.5\n}")
        assertNull(missing.error)
        assertEquals(2, assertNotNull(missing.values).size)
    }

    @Test
    fun reportsAnUnfinishedPaste() {
        val parse = parseRawPayload("""{"name":"widget",""")
        assertNull(parse.values)
        assertTrue(assertNotNull(parse.error).startsWith("Invalid JSON"))
    }

    // A form's values are a set of named fields, so a bare array (or scalar) has nowhere to land. Worth its own
    // message: "invalid JSON" would be a lie, since it parses perfectly well.
    @Test
    fun aNonObjectPayloadSaysSoRatherThanClaimingBadSyntax() {
        val parse = parseRawPayload("""["a","b"]""")
        assertNull(parse.values)
        val error = assertNotNull(parse.error)
        assertTrue(error.contains("JSON object"), "should explain an object is required: $error")
    }
}
