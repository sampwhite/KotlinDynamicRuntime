package com.dynamicruntime.common.util

import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Clock
import kotlin.time.Instant

class DateUtilTest : StringSpec({

    // --- round trips (mirrors the original DnDateUtil test) -----------------

    "format then parse a full timestamp round-trips exactly" {
        val now = Clock.System.now().truncateToMs()
        now.formatDate().parseDate() shouldBe now
    }

    "the day part parses back to the start of that day" {
        val now = Clock.System.now().truncateToMs()
        now.formatDayPart().parseDate() shouldBe now.toStartOfDay()
    }

    // --- parsing specific inputs --------------------------------------------

    "parses a full timestamp with milliseconds" {
        "2021-06-01T08:00:00.250Z".parseDate() shouldBe Instant.parse("2021-06-01T08:00:00.250Z")
    }

    "parses a timestamp without milliseconds" {
        "2021-06-01T08:00:00Z".parseDate() shouldBe Instant.parse("2021-06-01T08:00:00Z")
    }

    "appends a missing trailing Z before parsing" {
        "2021-06-01T08:00:00".parseDate() shouldBe "2021-06-01T08:00:00Z".parseDate()
    }

    "trims surrounding whitespace before parsing" {
        "  2021-06-01T08:00:00Z  ".parseDate() shouldBe "2021-06-01T08:00:00Z".parseDate()
    }

    "parses a date-only string as the start of day in the server zone" {
        val expected = LocalDate(2021, 6, 1).atStartOfDayIn(serverTimeZone)
        "2021-06-01".parseDate() shouldBe expected
    }

    // --- formatting ---------------------------------------------------------

    "formatDate emits millisecond ISO-8601 in UTC" {
        Instant.parse("2021-06-01T08:00:00.250Z").formatDate() shouldBe "2021-06-01T08:00:00.250Z"
    }

    "formatCookieDate emits an HTTP-date (RFC 1123) string with a zero-padded day and seconds" {
        Instant.parse("2021-06-01T08:00:00Z").formatCookieDate() shouldBe "Tue, 01 Jun 2021 08:00:00 GMT"
    }

    // --- arithmetic ---------------------------------------------------------

    "addDays and addHours shift by exact amounts" {
        val base = Instant.parse("2021-06-01T00:00:00Z")
        base.addDays(1) shouldBe Instant.parse("2021-06-02T00:00:00Z")
        base.addDays(-1) shouldBe Instant.parse("2021-05-31T00:00:00Z")
        base.addHours(6) shouldBe Instant.parse("2021-06-01T06:00:00Z")
        base.addHours(-6) shouldBe Instant.parse("2021-05-31T18:00:00Z")
    }

    // --- error cases --------------------------------------------------------

    "parseDate rejects empty, blank, and malformed strings" {
        shouldThrow<KdrException> { "".parseDate() }
        shouldThrow<KdrException> { "   ".parseDate() }
        shouldThrow<KdrException> { "not-a-date".parseDate() }
        shouldThrow<KdrException> { "2021/06/01".parseDate() }
        shouldThrow<KdrException> { "06-01-2021".parseDate() }
    }

    "parseDate rejects a misplaced fractional-second separator" {
        shouldThrow<KdrException> { "2021-06-01T08:00:0.250Z".parseDate() }
    }
})
