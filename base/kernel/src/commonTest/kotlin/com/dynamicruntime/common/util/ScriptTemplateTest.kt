package com.dynamicruntime.common.util

import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One parse of a template serves its analysis and its evaluation (issue #909, phase B): [analyzeTemplate] reports
 * structure and, given data, the rendered value with every problem at its block, and [evalTemplate] is the
 * throwing form over the same parse, raising exactly what it always raised. In `commonTest`, so the backend and
 * the browser agree.
 */
class ScriptTemplateTest {

    @Test
    fun analyzingWithDataRendersWhatEvaluationRenders() {
        // Three dollars, not the usual two: the template's doubled-prefix escape (`$$`) would itself be
        // interpolation in a `$$"…"` string.
        val text = $$$"Hello ${name}, you owe ${n * 2}. Escaped: $${x} and a lone $ sign."
        val data = mapOf("name" to "Ada", "n" to 21L)
        val report = text.analyzeTemplate(evaluateWith = data)
        assertEquals(text.evalTemplate(data), report.value)
        assertEquals($$"Hello Ada, you owe 42. Escaped: ${x} and a lone $ sign.", report.value)
        assertTrue(report.issues.isEmpty())
        assertEquals(setOf("name", "n"), report.paths.required)
        assertEquals(2, report.blockCount)
        // Structure only: no value, and no data problems -- a missing key is a fact about data not supplied.
        val structure = text.analyzeTemplate()
        assertNull(structure.value)
        assertTrue(structure.issues.isEmpty())
    }

    @Test
    fun everyProblemIsReportedAtItsBlockInDocumentOrder() {
        val text = $$"${a} then ${1 +} then ${b} then ${}"
        val report = text.analyzeTemplate(evaluateWith = mapOf("x" to 1L))
        assertNull(report.value)
        assertEquals(
            listOf(ScriptError.missingKey, ScriptError.syntaxError, ScriptError.missingKey, ScriptError.emptyExpression),
            report.issues.map { it.code },
        )
        assertEquals(listOf(0, 10, 22, 32), report.issues.map { it.offset })
        // Without data only the template's own problems are found.
        assertEquals(listOf(ScriptError.syntaxError, ScriptError.emptyExpression), text.analyzeTemplate().issues.map { it.code })
    }

    // The throwing form stops where it always did: the first problem in document order, whether the template's
    // (a block that will not parse) or the data's (a block that will not evaluate).
    @Test
    fun theThrowingFormRaisesTheFirstProblemInDocumentOrder() {
        val missingFirst = assertFailsWith<KdrException> { $$"${a} ${1 +}".evalTemplate(emptyMap()) }
        assertEquals(ScriptError.missingKey, missingFirst.extraData[KdrException.errorCodeKey])
        val syntaxFirst = assertFailsWith<KdrException> { $$"${1 +} ${a}".evalTemplate(emptyMap()) }
        assertEquals(ScriptError.syntaxError, syntaxFirst.extraData[KdrException.errorCodeKey])
        assertEquals(1, syntaxFirst.extraData[KdrException.lineKey])
    }

    @Test
    fun anUnterminatedBlockEndsTheDocument() {
        val report = $$"ok ${a} then ${b".analyzeTemplate(evaluateWith = mapOf("a" to "A"))
        assertNull(report.value)
        assertEquals(listOf(ScriptError.unterminatedExpression), report.issues.map { it.code })
        assertEquals(2, report.blockCount) // two blocks opened; the second never closed
    }

    @Test
    fun fragmentPullsResolveThroughTheSameRender() {
        val fragments = mapOf("copy.greet" to $$"Hi ${who}", "copy.broken" to $$"Oops ${1 +}")
        val resolver = FragmentResolver { fragments[it] }
        assertEquals("Hi Bo", $$"""${@t("copy.greet")}""".evalTemplate(mapOf("who" to "Bo"), resolver = resolver))
        // A guard absorbs a value the pulled fragment found absent ...
        assertEquals("default", $$"""${@t("copy.greet") ?: "default"}""".evalTemplate(emptyMap(), resolver = resolver))
        // ... but never a fragment that is itself wrong, which is re-reported naming the fragment.
        val broken = assertFailsWith<KdrException> {
            $$"""${@t("copy.broken") ?: "default"}""".evalTemplate(emptyMap(), resolver = resolver)
        }
        assertEquals(ScriptError.syntaxError, broken.extraData[KdrException.errorCodeKey])
        assertEquals("copy.broken", broken.extraData[KdrException.fragmentKey])
        // The report carries a pulled fragment's problem like any other, at the pulling block -- not at the
        // position of the problem inside the fragment's own text (offset 3 in "Hi ${who}").
        val report = $$"""Intro ${@t("copy.greet")}""".analyzeTemplate(evaluateWith = emptyMap(), resolver = resolver)
        val issue = report.issues.single()
        assertEquals(ScriptError.missingKey, issue.code)
        assertEquals(listOf(6, 1, 7), listOf(issue.offset, issue.line, issue.col))
        assertTrue("copy.greet" in issue.message)
    }

    @Test
    fun anIssueReadsAsTheSharedProblem() {
        val problem = $$"x ${a}".analyzeTemplate(evaluateWith = emptyMap()).problems.single()
        assertEquals(ScriptError.missingKey, problem.code)
        assertEquals(2, problem.location?.offset)
        assertEquals(1, problem.location?.line)
        assertEquals(3, problem.location?.col)
        assertEquals(ProblemSeverity.error, problem.severity)
    }
}
