package com.dynamicruntime.webapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The duck-typed file check behind the request-JSON panel (issue #260).
 *
 * It is asked about every value in a form's payload, so what it has to get right is everything that is *not* a
 * file — and null above all, since `typeof null` is "object" in JavaScript and a check written as a plain type
 * test therefore clears its first term and throws on the second.
 */
class BrowserFileTest {

    /** A stand-in for what the file picker emits: named, sized, sliceable. */
    private fun fakeFile(): dynamic = js("({ name: 'report.csv', size: 1024, slice: function () { return null; } })")

    // The regression. A null reaches here from any key the form does not already hold, which on a fresh form
    // is every key in a pasted payload -- so this one value decides whether "Apply to form" works at all.
    @Test
    fun nullIsNotAFile() {
        assertFalse(isBrowserFile(null))
    }

    @Test
    fun aPickedFileIsAFile() {
        assertTrue(isBrowserFile(fakeFile()))
    }

    // The ordinary inhabitants of a form's values. Note the map and the list: they are the ones that share
    // null's "object" type answer, so they are what the remaining terms of the check exist to separate out.
    @Test
    fun formValuesAreNotFiles() {
        for (v in listOf("widget", 3, 3.5, true, emptyMap<String, Any?>(), mapOf("name" to "widget"), listOf(1, 2))) {
            assertFalse(isBrowserFile(v), "should not be a file: $v")
        }
    }

    // Half a file is not a file: a payload can hold an object carrying a `name` of its own, and that must not
    // be mistaken for something to attach to a multipart request.
    @Test
    fun anObjectThatMerelyHasANameIsNotAFile() {
        assertFalse(isBrowserFile(js("({ name: 'report.csv' })")))
        assertFalse(isBrowserFile(js("({ name: 'report.csv', size: 1024 })")))
    }

    // The panel's text for a payload carrying a JSON null -- what a shared link's `v=` can hold. This is the
    // second place the null crash surfaced, and it fired during the URL restore, so the endpoint page loaded
    // with its carried input silently missing.
    @Test
    fun payloadTextRendersANullValueRatherThanThrowing() {
        val text = payloadText(mapOf("limit" to null, "name" to "widget"))
        val reparsed = assertNotNull(parseRawPayload(text).values, "the panel's own text should parse back")
        assertTrue(reparsed.containsKey("limit"), "the null-valued key should survive: $text")
        assertEquals("widget", reparsed["name"])
    }

    @Test
    fun payloadTextShowsAPickedFileAsALabel() {
        val text = payloadText(mapOf("upload" to fakeFile()))
        assertTrue(text.contains("report.csv"), "should name the chosen file: $text")
        assertTrue(text.contains("1024"), "should give its size: $text")
    }
}
