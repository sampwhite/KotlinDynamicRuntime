package com.dynamicruntime.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The expressions reference (issue #1560): every built-in function says what it gives, names each argument it takes,
 * and shows an example that renders as the page says, and the page lists every one.
 *
 * Theme: #DOCDR.
 */
class ScriptReferenceTest {
    @Test
    fun everyFunctionDescribesItselfAndItsExampleRendersAsThePageSays() {
        val page = expressionsMarkdown()
        for (fn in scriptFunctions.values) {
            assertTrue(fn.description.isNotBlank(), "${fn.name} has no description")
            assertEquals(fn.maxArgs, fn.args.size, "${fn.name} names ${fn.args.size} arguments but takes up to ${fn.maxArgs}")
            // Run in the deployment's zone, fixed and with no daylight saving, so a date's example reads the same anywhere.
            assertEquals(fn.exampleResult, "\${${fn.example}}".evalTemplate(emptyMap(), env = ScriptEnv.deployment()), fn.name)
            assertTrue("| `${fn.name}(" in page, "${fn.name} is not on the page")
        }
        // An optional argument is marked as one.
        assertTrue("`formatDate(date, style?)`" in page)
        // Made from code alone, so the same every time: its build id is a hash of this text.
        assertEquals(page, expressionsMarkdown())
    }
}
