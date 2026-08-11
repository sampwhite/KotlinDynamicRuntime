package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.util.toJsonStr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a date looks like when the **frontend** puts it on the wire (issue #255).
 *
 * The backend stamps a timestamp by putting an `Instant` in the response map and letting serialization format
 * it — one place decides the wire format, so no handler can invent its own. The claim worth testing is that
 * the same holds in the other direction, since the validator and the JSON writer are both kernel code the
 * frontend runs unchanged.
 *
 * Tested **on JS specifically**, not taken on faith from the JVM. `Double.toString` already differs between
 * the two platforms (`1.0` against `1`), so "it is in the kernel" is not by itself proof that both sides agree
 * — and a date format that differed between them would be the same class of bug, in the one place both sides
 * are supposed to agree by construction.
 */
class WireDateTest {

    private fun stampType() = parseSchemaTypes(
        mapOf(
            "core.Stamped" to mapOf(
                SCH.type to SCT.kObject,
                SCH.properties to mapOf(
                    "at" to mapOf(SCH.type to SCT.string, SCH.format to SFMT.dateTime),
                    "day" to mapOf(SCH.type to SCT.string, SCH.format to SFMT.date),
                ),
            ),
        ),
    ).getValue("core.Stamped")

    // Coercion turns the text a date picker produced into an Instant, and the JSON writer turns it back into
    // text in the system format — so what leaves the browser is normalized rather than whatever was typed.
    @Test
    fun aDateTimeGoesOnTheWireInTheSystemFormat() {
        val result = coerceAndValidate(stampType(), mapOf("at" to "2021-06-01T10:00:00.123456Z"))
        assertTrue(result.failures.isEmpty(), "should coerce cleanly: ${result.failures}")
        val json = result.value.toJsonStr(compact = true)
        // Milliseconds, UTC, trailing Z -- the extra digits the input carried are gone.
        assertTrue(json.contains("\"2021-06-01T10:00:00.123Z\""), "system format expected, got: $json")
    }

    // An offset form is REFUSED rather than normalized -- the parser takes UTC with a trailing `Z` and nothing
    // else. Pinned because it is easy to assume otherwise (this test originally did): a browser's own
    // `toISOString` always produces `Z`, so the frontend never runs into it, and the limit only surfaces for a
    // third-party client sending `+02:00` and getting a message that does not mention the offset.
    @Test
    fun anOffsetFormIsRefusedRatherThanNormalized() {
        val result = coerceAndValidate(stampType(), mapOf("at" to "2021-06-01T12:00:00+02:00"))
        assertEquals(1, result.failures.size, "expected one failure: ${result.failures}")
        assertEquals(SchFailCode.badValue, result.failures.first().code)
    }

    @Test
    fun aDayOnlyValueKeepsItsDayForm() {
        val result = coerceAndValidate(stampType(), mapOf("day" to "2021-06-01"))
        assertTrue(result.failures.isEmpty(), "should coerce cleanly: ${result.failures}")
        assertEquals("""{"day":"2021-06-01"}""", result.value.toJsonStr(compact = true))
    }
}
