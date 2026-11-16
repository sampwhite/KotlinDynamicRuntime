package com.dynamicruntime.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fenced divs and the roles that go with them (issue #1800): `::: {.panel}` boxes whole blocks, `::: {.center}` centers
 * them, and `[x]{.accent}` and `[x]{.muted}` color a phrase. A fence with no partner shows as written and is reported,
 * as a misused role is. And the check a client's mail styles are held to.
 */
class MarkdownFencedDivTest {

    @Test
    fun aPanelHoldsWholeBlocks() {
        val html = "Before.\n\n::: {.panel}\nInside, *with* emphasis.\n\n- one\n- two\n:::\n\nAfter.".renderMarkdown()
        assertEquals(
            "<p>Before.</p>\n<div class=\"md-panel\">\n<p>Inside, <em>with</em> emphasis.</p>\n<ul>\n<li>one</li>\n" +
                "<li>two</li>\n</ul>\n</div>\n<p>After.</p>\n",
            html,
        )
    }

    @Test
    fun aClosingFenceEndsTheParagraphBeforeIt() {
        assertEquals(
            "<div class=\"md-center\">\n<p>One line, then another.</p>\n</div>\n",
            "::: {.center}\nOne line,\nthen another.\n:::".renderMarkdown(),
        )
    }

    @Test
    fun rolesCombineAndDivsNest() {
        assertEquals(
            "<div class=\"md-panel md-center\">\n<p>Both.</p>\n</div>\n",
            "::: {.panel .center}\nBoth.\n:::".renderMarkdown(),
        )
        assertEquals(
            "<div class=\"md-panel\">\n<p>Outer.</p>\n<div class=\"md-center\">\n<p>Inner.</p>\n</div>\n</div>\n",
            ":::: {.panel}\nOuter.\n\n::: {.center}\nInner.\n:::\n::::".renderMarkdown(),
        )
    }

    @Test
    fun aCodeFenceInsideADivClosesNothing() {
        val html = "::: {.panel}\n```\n:::\n```\n:::".renderMarkdown()
        assertEquals("<div class=\"md-panel\">\n<pre><code>:::</code></pre>\n</div>\n", html)
    }

    @Test
    fun aCommentInsideADivClosesNothing() {
        assertEquals(
            "<div class=\"md-panel\">\n<p>Text.</p>\n</div>\n",
            "::: {.panel}\n<!--\n:::\n-->\nText.\n:::".renderMarkdown(),
        )
    }

    @Test
    fun aDivNeedsARole() {
        // Braces with no role open nothing, so the closing fence is the one reported.
        assertEquals(listOf(MarkdownIssue.unmatchedFence), "::: {width=600}\nWide?\n:::".roleProblems().map { it.code })
        assertTrue("\n::: {.center}\nX\n:::".opensFencedDiv())
        assertTrue(!"- A footer\n::: {.center}".opensFencedDiv())
    }

    @Test
    fun theTextPartIsTheBlocksAlone() {
        assertEquals(
            "Before.\n\nInside.\n\nAfter, quietly.",
            "Before.\n\n::: {.panel}\nInside.\n:::\n\nAfter, [quietly]{.muted}.".renderMarkdownText(),
        )
    }

    @Test
    fun aFenceWithNoPartnerShowsAsWrittenAndIsReported() {
        // Never closed: the opening line is text, and what follows renders as it would without it.
        assertEquals("<p>::: {.panel}</p>\n<p>Still here.</p>\n", "::: {.panel}\n\nStill here.".renderMarkdown())
        // A closing with nothing open.
        assertEquals("<p>:::</p>\n", ":::".renderMarkdown())
        val unclosed = "Line one.\n\n::: {.panel}\nNever closed.".roleProblems()
        assertEquals(listOf(MarkdownIssue.unmatchedFence), unclosed.map { it.code })
        assertEquals(3, unclosed.single().location?.line)
        assertEquals(listOf(MarkdownIssue.unmatchedFence), "Text.\n:::".roleProblems().map { it.code })
    }

    @Test
    fun aRoleWrittenWhereItDoesNotApplyIsReported() {
        val problems = "[Boxed?]{.panel}\n\n::: {.accent}\nColored?\n:::".roleProblems()
        assertEquals(listOf(MarkdownIssue.misplacedRole, MarkdownIssue.misplacedRole), problems.map { it.code })
        assertTrue(problems[0].message.contains("applies only to a fenced div"), problems[0].message)
        assertTrue(problems[1].message.contains("written on a fenced div"), problems[1].message)
        // A div's role is placed on the line it opens on, though its blocks come after.
        assertEquals(3, problems[1].location?.line)
        assertEquals(emptyList(), "::: {.panel .center}\n[Due]{.accent} and [aside]{.muted}.\n:::".roleProblems())
    }

    @Test
    fun aStyleThatLoadsOrHidesSomethingIsAProblem() {
        assertNull(inlineStyleProblem(""))
        assertNull(inlineStyleProblem("background-color: #eef5f0; border: 1px solid rgb(201, 220, 207); font-size: small;"))
        assertNull(inlineStyleProblem("font-family: \"Segoe UI\", Georgia, serif; padding: 4px 16px"))
        // A semicolon inside a quoted value does not end the declaration.
        assertNull(inlineStyleProblem("font-family: \"Acme; Sans\", serif; color: #222222;"))
        for (bad in listOf(
            "background: url(https://tracker.example/p.gif)",
            "width: expression(alert(1))",
            "display: none",
            "position: absolute; top: 0",
            "visibility: hidden",
            "opacity: 0",
            "font-size: 0px",
            "font-size: 0",
            "font: 0/0 a",
            "max-height: 0; overflow: hidden",
            "line-height: 0",
            "color: transparent",
            "text-indent: -9999px",
            "behavior: url(x.htc)",
            "background: u\\72l(x)",
            "color: red /* hidden */",
            "color: red; }",
            "@import 'x.css'",
            "not a declaration",
        )) {
            assertTrue(inlineStyleProblem(bad) != null, "Expected a problem with: $bad")
        }
    }
}
