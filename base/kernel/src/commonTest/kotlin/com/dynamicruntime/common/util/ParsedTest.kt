package com.dynamicruntime.common.util

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.workflow.WfRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/**
 * The non-throwing forms of the simple-value parsers (issue #909, phase A): each returns null (`…OrNull`) or a
 * [Parsed] carrying the problem (`…Result`) for input that fails in a known way, and each throwing form is built
 * on its non-throwing one, so it still throws the same exception with the same message. In `commonTest`, so it
 * holds on the JVM and under Kotlin/JS alike.
 */
class ParsedTest {

    /** The single problem a [Parsed] failed with. */
    private fun Parsed<*>.problem(): Problem {
        assertIs<Parsed.Failed>(this)
        return problems.single()
    }

    // --- dates ---

    @Test
    fun datesParseOrComeBackAsTheirProblem() {
        assertEquals("2026-10-01T12:30:00Z".parseDate(), "2026-10-01T12:30:00Z".parseDateOrNull())
        assertEquals("2026-10-01".parseDate(), "2026-10-01".parseDateOrNull())
        for (bad in listOf("nope", "2026-13-01", "2026-10-01T25:00:00Z", "2026-10-01T12:30:00.1.2Z")) {
            assertNull(bad.parseDateOrNull(), bad)
            assertEquals(ConvProblem.badFormat, bad.parseDateResult().problem().code, bad)
        }
        assertEquals(ConvProblem.blank, "  ".parseDateResult().problem().code)
    }

    @Test
    fun theThrowingDateFormsStillThrowTheSameMessage() {
        val message = assertFailsWith<KdrException> { "nope".parseDate() }.message.orEmpty()
        assertEquals("Date string 'nope' does not follow a recognizable date format.", message)
        assertFailsWith<KdrException> { "2026-10-01T12:00:00Z".parseDay() }
    }

    @Test
    fun daysAreStrictAndTheLenientFormNarrowsATimestamp() {
        assertEquals(LocalDate(2026, 10, 1), "2026-10-01".parseDayOrNull())
        assertNull("2026-10-01T12:00:00Z".parseDayOrNull())
        assertEquals(LocalDate(2026, 10, 1), "2026-10-01T12:00:00Z".parseDayLenientResult().valueOrNull())
        assertEquals(ConvProblem.badFormat, "x".parseDayLenientResult().problem().code)
    }

    // --- JSON ---

    @Test
    fun jsonParsesOrComesBackLocated() {
        assertEquals<Any?>(mapOf("a" to 1L), "{\"a\": 1}".jsonMapOrNull())
        assertNull("null".jsonMapResult().valueOrNull())
        assertIs<Parsed.Ok<*>>("null".jsonMapResult())
        assertNull("{\"a\": }".jsonMapOrNull())
        val problem = "{\n  \"a\": }".jsonMapResult().problem()
        assertEquals(ConvProblem.badFormat, problem.code)
        assertEquals(2, problem.location?.line)
        assertTrue((problem.location?.offset ?: -1) >= 0)
        assertEquals<Any?>(listOf(1L, 2L), "[1, 2]".jsonArrayOrNull())
        assertNull("[1,".jsonArrayOrNull())
        assertNull("{".jsonOrNull())
    }

    // --- numbers and the loose conversions ---

    @Test
    fun numbersReadMalformedAsNullOnlyInTheOrNullForm() {
        assertEquals(12L, "12".toOptLongOrNull())
        assertEquals(1L, " 1.7 ".toOptLongOrNull())
        assertNull("x".toOptLongOrNull())
        assertNull(mapOf("a" to 1).toOptLongOrNull())
        assertNull(null.toOptLongOrNull())
        assertNull("  ".toOptLong()) // absent is null in both forms
        assertEquals(ConvProblem.badFormat, "x".toOptLongResult().problem().code)
        assertEquals(ConvProblem.wrongType, mapOf("a" to 1).toOptLongResult().problem().code)
        assertEquals("Cannot convert 'x' to an integer.", assertFailsWith<KdrException> { "x".toOptLong() }.message)

        assertEquals(2.5, "2.5".toOptDoubleOrNull())
        assertNull("x".toOptDoubleOrNull())
        assertFailsWith<KdrException> { "x".toOptDouble() }

        assertEquals("2026-10-01".parseDate(), "2026-10-01".toOptInstantOrNull())
        assertNull("x".toOptInstantOrNull())
        assertFailsWith<KdrException> { "x".toOptInstant() }
        assertEquals(LocalDate(2026, 10, 1), "2026-10-01".toOptLocalDateOrNull())
        assertNull("x".toOptLocalDateOrNull())
    }

    // --- ids ---

    @Test
    fun gedraIdsParseOrComeBackAsTheirProblem() {
        val id = "gd.fd.acme.u1"
        assertEquals(GedraId.parse(id), GedraId.parseOrNull(id))
        for (bad in listOf("gd.fd.acme", "zz.fd.acme.u1", "gd.fd.ac-me.u1", "gd.fd.acme.")) {
            assertNull(GedraId.parseOrNull(bad), bad)
            assertEquals(ConvProblem.badFormat, GedraId.parseResult(bad).problem().code, bad)
        }
        // The throwing form keeps its contract: bad input, not a conversion fault.
        assertEquals(EXC.badInput, assertFailsWith<KdrException> { GedraId.parse("gd.fd.acme") }.code)
    }

    @Test
    fun workflowReferencesParseOrComeBackAsTheirProblem() {
        val bundle = "gc.cd.acme.main~2"
        assertEquals("$bundle#review", WfRef.parseOrNull("$bundle#review")?.text)
        assertNull(WfRef.parseOrNull("no-separator"))
        assertNull(WfRef.parseOrNull("$bundle#not a name"))
        assertNull(WfRef.parseOrNull("gd.xx.acme.main#review"))
        assertNull(WfRef.parseOrNull(null))
    }

    // --- the shared types ---

    @Test
    fun aProblemBecomesAnExceptionCarryingItsCodeAndLocation() {
        val problem = Problem(ConvProblem.badFormat, "Bad.", ProblemLocation(offset = 4, line = 2, col = 3))
        val ex = problem.toException()
        assertEquals("Bad.", ex.message)
        assertEquals<Any?>(ConvProblem.badFormat, ex.extraData[KdrException.errorCodeKey])
        assertEquals(2, ex.extraData[KdrException.lineKey])
        assertEquals(3, ex.extraData[KdrException.lineColKey])
        assertEquals("error", problem.toJsonMap()[PRB.severity])
    }

    @Test
    fun aLocationComposesUnderAParentPath() {
        assertEquals("entries[0].data", ProblemLocation(path = "data").under("entries[0]").path)
        val textOnly = ProblemLocation(offset = 7).under("label")
        assertEquals("label", textOnly.path)
        assertEquals(7, textOnly.offset)
    }
}
