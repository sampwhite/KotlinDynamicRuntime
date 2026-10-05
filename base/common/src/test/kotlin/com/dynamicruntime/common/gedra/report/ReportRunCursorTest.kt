package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.endpoint.CursorToken
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.parseDay
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * An aggregate run's cursor (issue #981): a group key of every kind comes back from a cursor as the key it was -- the
 * same values, of the same types, comparing equal -- and a run's query id follows the report as it was bound, so a
 * cursor does not outlive a change to the report.
 */
class ReportRunCursorTest : StringSpec({
    fun column(id: String, kind: ReportKind, path: String = "meta.gedraId", combine: ReportCombine = ReportCombine.first) =
        BoundColumn(ReportColumn(id, id, path), BoundPath(parseReportPathOrThrow(path), emptyList(), kind, false), combine)

    val groupBy = listOf(
        column("auditor", ReportKind.string),
        column("year", ReportKind.number),
        column("amount", ReportKind.number),
        column("visited", ReportKind.date),
        column("updated", ReportKind.date),
        column("passed", ReportKind.boolean),
        column("missing", ReportKind.string),
    )
    val codec = reportGroupKeyCodec(groupBy)

    fun roundTrip(key: List<Any?>): List<Any?>? {
        val token = CursorToken.encode("q", codec.toValues(key))
        return codec.fromValues(CursorToken.decode(token, "q").valueOrNull().shouldNotBeNull())
    }

    "a key of every kind comes back as the key it was" {
        val key = listOf<Any?>(
            "Smith", 2024L, 12.75, "2024-03-05".parseDay(), "2026-03-01T10:15:00Z".parseDate(), true, null,
        )
        val back = roundTrip(key).shouldNotBeNull()
        back shouldBe key
        back.map { it?.let { v -> v::class } } shouldBe key.map { it?.let { v -> v::class } }
        compareReportKeys(back, key) shouldBe 0
    }

    "values that do not read as the columns' kinds are not a key of this run" {
        codec.fromValues(listOf("Smith")).shouldBeNull()
        codec.fromValues(listOf("Smith", "not a year", 1.0, "2024-03-05", "2026-03-01T10:15:00Z", true, null)).shouldBeNull()
    }

    "the query id follows the report as it was bound" {
        val report = ClientReport("sites", "Sites", groupBy.map { it.column })
        val bound = BoundReport(report, groupBy)
        val id = reportQueryId("acme", bound, ReportMode.aggregate, groupBy.take(1))
        reportQueryId("acme", BoundReport(report, groupBy), ReportMode.aggregate, groupBy.take(1)) shouldBe id
        // Another mode, client or grouping is another query...
        reportQueryId("acme", bound, ReportMode.detail, emptyList()) shouldNotBe reportQueryId("acme", bound, ReportMode.aggregate, emptyList())
        reportQueryId("globex", bound, ReportMode.aggregate, groupBy.take(1)) shouldNotBe id
        // ...and so is the same report bound differently: a column whose kind or combine changed with a reload.
        val rebound = BoundReport(report, listOf(column("auditor", ReportKind.number)) + groupBy.drop(1))
        reportQueryId("acme", rebound, ReportMode.aggregate, rebound.columns.take(1)) shouldNotBe id
        val recombined = BoundReport(report, listOf(column("auditor", ReportKind.string, combine = ReportCombine.max)) + groupBy.drop(1))
        reportQueryId("acme", recombined, ReportMode.aggregate, recombined.columns.take(1)) shouldNotBe id
    }
})
