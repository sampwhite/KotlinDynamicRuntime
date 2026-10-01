package com.dynamicruntime.common.cfact

import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A cfact expression as a report (issue #909): a malformed one comes back as coded, located problems rather than
 * a throw, the throwing form raises the first of them, and an evaluation needs no catch. The grammar itself is
 * covered by `CFactParserTest`; this is the report around it. In `commonTest`, so backend and browser agree.
 */
class CFactAnalysisTest {
    private val allowed = setOf("app", "edge", "loggedIn")

    @Test
    fun aSoundExpressionReportsItsNamesAndOptionallyItsValue() {
        val report = CFactParser.analyze("app,~edge", allowed)
        assertTrue(report.problems.isEmpty())
        assertEquals("app,~edge", report.predicate?.render())
        assertEquals(setOf("app", "edge"), report.names)
        assertNull(report.value) // not evaluated
        assertEquals(true, CFactParser.analyze("app,~edge", allowed, evaluateWith = setOf("app")).value)
        assertEquals(false, CFactParser.analyze("app,~edge", allowed, evaluateWith = setOf("app", "edge")).value)
    }

    @Test
    fun eachKindOfFaultHasItsCodeAndPosition() {
        fun fault(expr: String) = CFactParser.analyze(expr, allowed).problems.single().let { it.code to it.location?.offset }
        assertEquals(CFactError.blank to 0, fault("  "))
        assertEquals(CFactError.unknownLiteral to 4, fault("app,#nevr"))
        assertEquals(CFactError.mixedOperators to 8, fault("app,edge|loggedIn"))
        assertEquals(CFactError.missingOperand to 4, fault("app,"))
        assertEquals(CFactError.missingClose to 4, fault("(app"))
        assertEquals(CFactError.nameExpected to 0, fault(",app"))
        assertEquals(CFactError.unexpectedCharacter to 3, fault("app)"))
        // The position is in the expression as given, leading spaces and all.
        assertEquals(CFactError.unknownName to 2, fault("  admn"))
    }

    @Test
    fun everyUnknownNameIsReportedAndThenTheSyntaxFault() {
        val report = CFactParser.analyze("admn|~logdIn|(edge", allowed, evaluateWith = setOf("edge"))
        assertEquals(
            listOf(CFactError.unknownName, CFactError.unknownName, CFactError.missingClose),
            report.problems.map { it.code },
        )
        assertEquals(listOf(0, 6, 18), report.problems.map { it.location?.offset })
        assertEquals(setOf("admn", "logdIn", "edge"), report.names)
        assertNull(report.predicate)
        assertNull(report.value) // a broken expression is never evaluated
    }

    @Test
    fun theThrowingFormRaisesTheFirstProblemAsBadInput() {
        val e = assertFailsWith<KdrException> { CFactParser.parse("admn,(edge", allowed) }
        assertTrue("'admn' is not a registered cfact" in e.message.orEmpty())
        assertEquals<Any?>(CFactError.unknownName, e.extraData[KdrException.errorCodeKey])
    }

    @Test
    fun anOmittedExpressionIsAlwaysAndHasNothingToReport() {
        val report = analyzeCFactOrAlways(null, allowed, evaluateWith = emptySet())
        assertEquals(CFACT.alwaysName, report.predicate?.render())
        assertTrue(report.problems.isEmpty() && report.names.isEmpty())
        assertEquals(true, report.value)
    }
}
