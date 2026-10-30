package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.report.reportDateAs
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchOpts
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.schemaDefs
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * Where a day crosses to a moment, or a moment to its day, in a client's time zone (issue #1115): a day given for a
 * timestamp field, a search's date bound, and a report column brought to its form. Each would fail quietly in the
 * server's zone -- a moment off by hours, a row in the wrong day -- so each is asked with a zone that disagrees with it.
 */
class ClientZoneCrossingTest : StringSpec({
    val cxt = KdrCxt.mkSimpleCxt("zones1115")
    val chicago = TimeZone.of("America/Chicago")
    val tokyo = TimeZone.of("Asia/Tokyo")

    "a day given for a timestamp field begins at midnight in the zone" {
        val rec = parseSchemaTypes(
            schemaDefs(cxt, "zones") {
                type("Rec") {
                    type = SCT.kObject
                    property("at", "When.") { dateTime() }
                }
            },
        ).getValue("zones.Rec")
        fun at(opts: SchOpts) = coerceAndValidate(rec, mapOf("at" to "2026-10-17"), opts).value.toJsonMapOrEmpty()["at"]
        // Chicago on daylight time is five hours behind UTC; the server's zone, eight.
        at(SchOpts(zone = chicago)) shouldBe Instant.parse("2026-10-17T05:00:00Z")
        at(SchOpts()) shouldBe Instant.parse("2026-10-17T08:00:00Z")
    }

    "a search's date bound is the start of its day in the zone" {
        val lower = gedraSearchParams(declaredColumns(listOf(ClientTraitUsage("due", "Due", $$"${x}", UsageKind.date, false))))
            .first { it.role == SearchRole.min }
        val row = mapOf("due" to "2026-10-17T06:00:00.000Z")
        // Past midnight in Chicago (05:00 UTC), before it in the server's zone (08:00 UTC).
        matchesSearch(row, listOf(lower to "2026-10-17"), chicago) shouldBe true
        matchesSearch(row, listOf(lower to "2026-10-17")) shouldBe false
    }

    "a moment brought to a day column is the day it falls on in the zone" {
        val moment = Instant.parse("2026-10-17T03:00:00Z")
        reportDateAs(moment, dayOnly = true, zone = tokyo) shouldBe LocalDate(2026, 10, 17)
        reportDateAs(moment, dayOnly = true) shouldBe LocalDate(2026, 10, 16)
        reportDateAs(LocalDate(2026, 10, 17), dayOnly = false, zone = tokyo) shouldBe Instant.parse("2026-10-16T15:00:00Z")
    }
})
