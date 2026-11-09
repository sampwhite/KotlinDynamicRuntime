package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.util.Parsed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The report paths reference (issue #1563): every name a path may end in outside a trait's data says what it holds,
 * every combine says what it gives, and the page lists each one.
 *
 * Theme: #DOCDR.
 */
class ReportPathReferenceTest {
    @Test
    fun everyAttributeAndCombineDescribesItselfAndIsListed() {
        val page = reportPathsMarkdown()
        val attrs = RWF.attrs.values + RWF.approvalAttrs.values + RUSR.attrs.values + RMETA.attrs.values + RENV.attrs.values
        for (attr in attrs) {
            assertTrue(attr.description.isNotBlank(), "${attr.name} has no description")
            assertTrue("| `${attr.name}` |" in page, "${attr.name} is not on the page")
        }
        // A multi-valued attribute says so.
        assertTrue("| `${RUSR.labels}` | Text, several |" in page)
        for (combine in ReportCombine.entries) {
            assertTrue(combine.description.isNotBlank(), "${combine.name} has no description")
            assertTrue("| `${combine.name}` | ${combine.description} |" in page, "${combine.name} is not on the page")
        }
        assertFalse("$$" + "{" in page, "a substitution was left unmade")
        // Made from code alone, so the same every time: its build id is a hash of this text.
        assertEquals(page, reportPathsMarkdown())
    }

    @Test
    fun thePagesExamplePathsParse() {
        // Every path the page writes as an example is one the parser reads, so the page cannot teach a broken one.
        val examples = Regex("`((?:form|workflow|user|meta)\\.[^`<>]+)`").findAll(reportPathsMarkdown()).map { it.groupValues[1] }.toList()
        assertTrue(examples.size > 8, "found only $examples")
        for (text in examples) {
            assertTrue(parseReportPath(text) is Parsed.Ok, "'$text' does not parse")
        }
    }
}
