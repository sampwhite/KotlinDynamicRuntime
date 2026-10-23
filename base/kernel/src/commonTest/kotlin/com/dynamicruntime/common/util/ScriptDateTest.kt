package com.dynamicruntime.common.util

import com.dynamicruntime.common.exception.KdrException
import kotlinx.datetime.UtcOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * A template's dates read in the environment its caller hands in (issue #1321): `formatDay` in its zone, and
 * `formatDate`'s styles against its zone and its now. In `commonTest`, so the backend and the browser word a moment
 * alike; the zones are written out here rather than looked up, since the browser's date library has no zone database.
 */
class ScriptDateTest {
    /** Central time: CST, UTC-6, until the 2026 fall-back (Nov 1, 07:00 UTC), and CDT, UTC-5, before it. */
    private val central = object : ScriptZone {
        override val label = "CT"
        override fun offsetAt(at: Instant): UtcOffset =
            UtcOffset(hours = if (at < Instant.parse("2026-11-01T07:00:00Z")) -5 else -6)
    }

    /** India: UTC+5:30, and no name, so labeled by its offset. */
    private val india = ScriptZone.of(UtcOffset(hours = 5, minutes = 30).asFixedZone())

    private val now = Instant.parse("2026-10-17T22:00:00Z")
    private val env = ScriptEnv(central, now)

    private fun UtcOffset.asFixedZone() = kotlinx.datetime.FixedOffsetTimeZone(this)

    private fun eval(text: String, at: Instant, env: ScriptEnv? = this.env): String =
        text.evalTemplate(mapOf("at" to at.formatDate()), env = env)

    @Test
    fun aMomentIsWordedInTheZoneAndLabeledWithIt() {
        assertEquals("Oct 17, 2026, 5:00 PM CT", momentText(now, central))
        // The label is the zone's all year; the offset is the one in force at the moment.
        assertEquals("Dec 1, 2026, 12:05 AM CT", momentText(Instant.parse("2026-12-01T06:05:00Z"), central))
        assertEquals("Oct 18, 2026, 3:30 AM GMT+5:30", momentText(now, india))
        assertEquals("Oct 17, 2026, 10:00 PM GMT", momentText(now, ScriptZone.of(UtcOffset.ZERO.asFixedZone())))
        assertEquals("GMT-8", offsetLabel(UtcOffset(hours = -8)))
    }

    @Test
    fun formatDateTakesAStyleReadAgainstTheEnvironment() {
        val tmpl = $$"""${formatDate(at, "relative")} | ${formatDate(at, "moment")} | ${formatDate(at, "day")}"""
        assertEquals("12m ago | Oct 17, 2026, 4:48 PM CT | 2026-10-17", eval(tmpl, now - 12.minutes))
        assertEquals("in 3h | Oct 17, 2026, 8:00 PM CT | 2026-10-17", eval(tmpl, now + 3.hours))
        // From 48 hours on, relative is the day alone, in the zone: late on the 14th in Chicago, the 15th in UTC.
        val longAgo = Instant.parse("2026-10-15T03:00:00Z")
        assertEquals("2026-10-14", eval($$"""${formatDate(at, "relative")}""", longAgo))
        assertEquals("1d 2h ago", relativeTimeText(now - 1.days - 2.hours, now, central))
        // With no style it is the UTC system timestamp, as it always was.
        assertEquals("2026-10-17T21:48:00.000Z", eval($$"${formatDate(at)}", now - 12.minutes))
    }

    @Test
    fun formatDayIsTheDayInTheEnvironmentsZone() {
        val lateUtc = Instant.parse("2026-10-18T02:00:00Z")
        assertEquals("2026-10-17", eval($$"${formatDay(at)}", lateUtc))
        assertEquals("2026-10-18", eval($$"${formatDay(at)}", lateUtc, ScriptEnv(india, now)))
        // With no environment, the deployment's zone, as before: UTC-8.
        assertEquals("2026-10-17", eval($$"${formatDay(at)}", lateUtc, env = null))
    }

    @Test
    fun aPulledFragmentReadsDatesAsItsPullerDoes() {
        val resolver = FragmentResolver { key -> if (key == "when") $$"""${formatDate(at, "moment")}""" else null }
        val text = $$"""Due ${@t("when")}."""
        val rendered = text.evalTemplate(mapOf("at" to now.formatDate()), resolver = resolver, env = env)
        assertEquals("Due Oct 17, 2026, 5:00 PM CT.", rendered)
    }

    @Test
    fun aStyleThatIsNotOneIsRefused() {
        // A literal one, when the template is parsed, with no data at hand -- as a misspelled function is.
        val parsed = $$"""${formatDate(at, "relatve")}""".analyzeTemplate()
        assertEquals(ScriptError.syntaxError, parsed.issues.single().code)
        assertTrue("\"relative\", \"moment\", \"day\"" in parsed.issues.single().message)
        // Too many arguments is the arity check's.
        assertEquals(ScriptError.syntaxError, $$"""${formatDate(at, "day", 1)}""".analyzeTemplate().issues.single().code)
        // A computed one, when it is evaluated.
        val computed = assertFailsWith<KdrException> {
            $$"${formatDate(at, style)}".evalTemplate(mapOf("at" to now.formatDate(), "style" to "weekly"), env = env)
        }
        assertEquals(ScriptError.typeMismatch, computed.extraData[KdrException.errorCodeKey])
    }
}
