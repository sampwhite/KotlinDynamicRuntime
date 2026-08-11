package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The free-form map field's two pure halves (issue #251): what text it shows for a value, and what a value it
 * accepts from text. The surrounding component is a textarea and one piece of state, so these two functions
 * are where all the behavior worth pinning down actually lives.
 */
class JsonFieldTest {

    @Test
    fun showsAMapAsPrettyJson() {
        val text = jsonFieldText(mapOf("a" to 1, "b" to "two"))
        assertTrue(text.contains("\"a\""), "should render the keys: $text")
        assertTrue(text.contains("two"), "should render the values: $text")
    }

    @Test
    fun showsNothingForAnAbsentValue() {
        assertEquals("", jsonFieldText(null))
    }

    // The single most important property of the editor: a String is text someone is part-way through typing,
    // so it comes back exactly as it went in. Reformatting it -- even into JSON that means the same thing --
    // moves the caret out from under whoever is typing.
    @Test
    fun passesTextThroughWithoutReformattingIt() {
        for (typed in listOf("{", "{\"a\":1}", "{ \"a\" :   1 }", "not json at all")) {
            assertEquals(typed, jsonFieldText(typed), "text must survive a round trip untouched")
        }
    }

    @Test
    fun acceptsAJsonObject() {
        val parsed = parseJsonField("""{"channel":"excel","fileName":"x.xlsx"}""")
        assertNull(parsed.error)
        val map = assertNotNull(parsed.value as? Map<*, *>)
        assertEquals("excel", map["channel"])
    }

    // Diverges from the request panel deliberately, which reads blank as an empty payload: clearing a *field*
    // is how someone removes an optional value, and `{}` stays available to anyone who means it.
    @Test
    fun blankMeansAbsentRatherThanAnEmptyMap() {
        for (text in listOf("", "   ", "\n")) {
            val parsed = parseJsonField(text)
            assertNull(parsed.error, "clearing a field is not a mistake")
            assertNull(parsed.value, "blank should be absent, not an empty map")
        }
    }

    // On a failure the text becomes the value. That is what keeps the form holding exactly what is on screen,
    // so the request-JSON panel cannot show something the field contradicts -- and it is what gives the kernel
    // a wrong-typed value to report against the same path when Validate runs.
    @Test
    fun keepsTheTextAsTheValueWhenItWillNotParse() {
        val typed = "{\n  \"channel\": \"excel\",\n  fileName: \"x.xlsx\"\n}"
        val parsed = parseJsonField(typed)
        assertEquals(typed, parsed.value, "unparseable text must stay in the form, not be discarded")
        val error = assertNotNull(parsed.error)
        assertTrue(error.contains("line 3"), "should point at the offending line: $error")
    }

    // Valid JSON, but not an object -- so "invalid JSON" would be a lie. The noun has to be the field's, not
    // the request panel's, since this message appears under a single field.
    @Test
    fun rejectsValidJsonThatIsNotAnObject() {
        val parsed = parseJsonField("""["a","b"]""")
        val error = assertNotNull(parsed.error)
        assertTrue(error.contains("JSON object"), "should explain an object is required: $error")
        assertTrue(error.startsWith("The value"), "should name the field's value, not the request: $error")
        assertEquals("""["a","b"]""", parsed.value)
    }
}
