package com.dynamicruntime.common.http.request

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** How a request's `Range`, `If-Range`, and `If-None-Match` are answered, by RFC 9110's rules (issue #1836). */
class ByteRangesTest : StringSpec({
    val etag = ByteRanges.etagOf("abc")

    fun answer(range: String?, size: Long = 1000, ifRange: String? = null, ifNoneMatch: String? = null): RangeAnswer =
        ByteRanges.resolve(range, ifRange, ifNoneMatch, etag, size)

    fun partial(range: String, size: Long = 1000): Pair<Long, Long> {
        val p = answer(range, size).shouldBeInstanceOf<RangeAnswer.Partial>()
        return p.start to p.end
    }

    "no range is the whole content" {
        answer(null) shouldBe RangeAnswer.Full
        answer("  ") shouldBe RangeAnswer.Full
    }

    "a range of first and last byte, an open one, and a suffix" {
        partial("bytes=0-0") shouldBe (0L to 0L)
        partial("bytes=10-19") shouldBe (10L to 19L)
        partial("bytes=990-") shouldBe (990L to 999L)
        partial("bytes=-10") shouldBe (990L to 999L)
        partial("BYTES = 5 - 6") shouldBe (5L to 6L)
        // Cut to the content's end: a last byte past it, and a suffix longer than the content.
        partial("bytes=900-5000") shouldBe (900L to 999L)
        partial("bytes=-5000") shouldBe (0L to 999L)
        answer("bytes=10-19").shouldBeInstanceOf<RangeAnswer.Partial>().length shouldBe 10L
        ByteRanges.contentRange(RangeAnswer.Partial(10, 19), 1000) shouldBe "bytes 10-19/1000"
    }

    "a range starting at or past the end, or a suffix of nothing, cannot be satisfied" {
        answer("bytes=1000-") shouldBe RangeAnswer.Unsatisfiable
        answer("bytes=1000-1001") shouldBe RangeAnswer.Unsatisfiable
        answer("bytes=-0") shouldBe RangeAnswer.Unsatisfiable
        answer("bytes=0-", size = 0) shouldBe RangeAnswer.Unsatisfiable
        answer("bytes=-5", size = 0) shouldBe RangeAnswer.Unsatisfiable
        ByteRanges.unsatisfiedRange(1000) shouldBe "bytes */1000"
    }

    "what this does not read is ignored, never refused: the whole content is sent" {
        for (ignored in listOf(
            "bytes=0-9,20-29", "items=0-9", "bytes 0-9", "bytes=a-b", "bytes=9-1", "bytes=-", "bytes=1-2-3",
            "bytes=+1-2", "bytes=99999999999999999999-", "bytes=0-99999999999999999999",
        )) {
            answer(ignored) shouldBe RangeAnswer.Full
        }
    }

    "a copy named by If-None-Match is not modified, whatever range is asked" {
        answer(null, ifNoneMatch = etag) shouldBe RangeAnswer.NotModified
        answer("bytes=0-9", ifNoneMatch = "\"x\", W/$etag") shouldBe RangeAnswer.NotModified
        answer(null, ifNoneMatch = "*") shouldBe RangeAnswer.NotModified
        answer(null, ifNoneMatch = "\"x\"") shouldBe RangeAnswer.Full
        // Content with no tag is never not modified.
        ByteRanges.resolve(null, null, "*", null, 1000) shouldBe RangeAnswer.Full
    }

    "If-Range keeps a range only for the same bytes, by strong tag" {
        answer("bytes=0-9", ifRange = etag).shouldBeInstanceOf<RangeAnswer.Partial>()
        answer("bytes=0-9", ifRange = "\"other\"") shouldBe RangeAnswer.Full
        answer("bytes=0-9", ifRange = "W/$etag") shouldBe RangeAnswer.Full
        answer("bytes=0-9", ifRange = "Tue, 17 Nov 2026 10:00:00 GMT") shouldBe RangeAnswer.Full
        ByteRanges.resolve("bytes=0-9", etag, null, null, 1000) shouldBe RangeAnswer.Full
    }
})
