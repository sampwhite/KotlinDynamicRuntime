package com.dynamicruntime.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Markdown renderer as a report (issue #909): the same HTML, the document's structure, and every degradation
 * as a warning at its line. In `commonTest`, so backend and browser agree.
 */
class MarkdownReportTest {
    private val doc = """
        # Guide

        See [setup](#setup), [nowhere](#missing) and [bad](javascript:alert(1)).

        ## Setup

        ```kotlin
        val x = "<div>"
        ```

        Title
        =====

        - one
          - two

        Some <b>bold</b> and a `<code>` span, and [ref][1].

        | a | b |
        | - | - |
        | 1 |
    """.trimIndent()

    @Test
    fun theHtmlIsRenderMarkdownsAndTheStructureIsReported() {
        val report = doc.analyzeMarkdown()
        assertEquals(doc.renderMarkdown(), report.html)
        assertEquals(listOf("guide" to 1, "setup" to 5), report.headings.map { it.slug to it.line })
        assertEquals(listOf(1, 2), report.headings.map { it.level })
        assertEquals(listOf("#setup", "#missing", "javascript:alert(1"), report.links.map { it.target })
        assertEquals(listOf(false, false, true), report.links.map { it.inert })
        assertEquals(listOf("kotlin" to 7), report.codeBlocks.map { it.language to it.line })
    }

    @Test
    fun eachDegradationIsAWarningAtItsLine() {
        val problems = doc.analyzeMarkdown().problems
        assertEquals(
            listOf(
                MarkdownIssue.inertLink to 3, MarkdownIssue.brokenAnchor to 3, MarkdownIssue.setextHeading to 12,
                MarkdownIssue.nestedList to 15, MarkdownIssue.rawHtml to 17, MarkdownIssue.referenceLink to 17,
                MarkdownIssue.raggedRow to 21,
            ),
            problems.map { it.code to it.location?.line },
        )
        assertTrue(problems.all { it.severity == ProblemSeverity.warning })
    }

    @Test
    fun aRelativeLinkTheResolverLeftAloneIsReported() {
        val hooks = MarkdownHooks(resolveUrl = { if (it == "known.md") "#page=doc&d=known" else it })
        val problems = "[a](known.md) and [b](other.md) and [c](https://x.test)".analyzeMarkdown(hooks).problems
        assertEquals(listOf(MarkdownIssue.unresolvedLink), problems.map { it.code })
        assertTrue("'other.md'" in problems.single().message)
    }
}
