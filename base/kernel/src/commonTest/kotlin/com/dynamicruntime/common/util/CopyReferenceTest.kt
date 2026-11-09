package com.dynamicruntime.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The copy reference and the roles it lists (issue #1562): every declared role is on the page, and copy writing a role
 * nobody declared, or one on what it does not apply to, is reported while every declared use is not.
 *
 * Theme: #DOCDR.
 */
class CopyReferenceTest {
    @Test
    fun everyRoleIsListedAndThePageIsMadeFromCodeAlone() {
        val page = copyMarkdown()
        for (role in markdownRoles) {
            assertTrue(role.description.isNotBlank() && role.inMail.isNotBlank(), "${role.name} is not described")
            assertTrue("| `.${role.name}` |" in page, "${role.name} is not on the page")
        }
        assertEquals(markdownRoles.size, markdownRoles.map { it.name }.toSet().size, "a role is declared twice")
        assertFalse("$$" + "{" in page, "a substitution was left unmade")
        // Made from code alone, so the same every time: its build id is a hash of this text.
        assertEquals(page, copyMarkdown())
    }

    @Test
    fun aDeclaredRoleWrittenWhereItAppliesIsNoProblem() {
        val copy = "Your code is [123 456]{.code}, for [ada@example.com]{.value}.\n\n" +
            "![Our logo](ref:logo){.float-left .icon width=20} A line beside it.[]{.clear}\n\n" +
            "![A picture](img:kdr:walkPart1){.float-right width=240}"
        assertEquals(emptyList(), copy.roleProblems().map { it.message })
    }

    @Test
    fun aMisspelledOrMisplacedRoleIsReported() {
        val problems = "Send it to [ada@example.com]{.vaule}. [Here]{.float-left}, and ![logo](ref:logo){.code}.".roleProblems()
        assertEquals(
            listOf(MarkdownIssue.unknownRole, MarkdownIssue.misplacedRole, MarkdownIssue.misplacedRole),
            problems.map { it.code },
        )
        assertTrue("'.vaule'" in problems[0].message)
        assertTrue("written on a span, but it applies only to an image" in problems[1].message)
        assertTrue("written on an image, but it applies only to a span" in problems[2].message)
        // A role inside a code span is shown as written, not read, so it is no problem.
        assertEquals(emptyList(), "Write `[text]{.vaule}` for a value.".roleProblems())
    }
}
