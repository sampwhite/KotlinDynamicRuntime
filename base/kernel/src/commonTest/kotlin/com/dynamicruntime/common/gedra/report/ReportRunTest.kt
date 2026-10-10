package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.parseDay
import com.dynamicruntime.common.util.toStartOfDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A report run's shared half (issue #981): which forms a run leaves out, a detail row's values, and an aggregate
 * run's groups and rollups -- over subjects built by hand, so the JVM and JS are held to one answer.
 */
class ReportRunTest {
    private fun subject(id: String, auditor: Any?, total: Any?, vararg years: Int): ReportSubject {
        val audit = buildMap<String, Any?> {
            auditor?.let { put("auditor", it) }
            total?.let { put("total", it) }
        }
        val entries = listOf(mapOf(GE.traitId to "audit", GE.data to audit)) +
            years.map { mapOf(GE.traitId to "yearly", GE.data to mapOf("year" to it)) }
        return ReportSubject(mapOf(RMETA.gedraId to id), entries, emptyList(), null) { null }
    }

    private fun column(id: String, path: String, kind: ReportKind, multi: Boolean = false, combine: ReportCombine? = null, rollup: ReportCombine? = null) =
        BoundColumn(
            ReportColumn(id, id, path, combine = combine, rollup = rollup),
            BoundPath(parseReportPathOrThrow(path), if (path.contains('[')) listOf("year") else emptyList(), kind, multi),
            combine ?: ReportCombine.defaultFor(multi),
        )

    private val auditor = column("auditor", "form.audit.auditor", ReportKind.string)
    private val total = column("total", "form.audit.total", ReportKind.number, rollup = ReportCombine.sum)
    private val years = column("years", "form.yearly[*].year", ReportKind.number, multi = true, rollup = ReportCombine.count)
    private val report = BoundReport(ClientReport("sites", "Sites", listOf(auditor, total, years).map { it.column }), listOf(auditor, total, years))

    private val subjects = listOf(
        subject("a", "Smith", 10, 2023, 2024),
        subject("b", "Jones", 2.5),
        subject("c", "Smith", 4.5, 2022),
        subject("d", null, 7),
        subject("e", " ", null),
        // `2` and `2.0` are one value; so are text differing only by surrounding space, which the reading trims.
        subject("f", "Smith ", null),
    )

    @Test
    fun aFormWithoutAValueForAnExcludingColumnIsLeftOut() {
        val kept = subjects.filterNot { report.excludes(it, listOf(auditor)) }.map { it.meta[RMETA.gedraId] }
        // A blank is empty, as is no value at all.
        assertEquals(listOf("a", "b", "c", "f"), kept)
        // A count of nothing is a value, so it excludes nothing.
        val counted = column("n", "form.yearly[*].year", ReportKind.number, multi = true, combine = ReportCombine.count)
        assertFalse(subjects.any { report.excludes(it, listOf(counted)) })
    }

    @Test
    fun aDetailRowHasEveryColumnByItsCombine() {
        val values = detailValues(subjects[0], report.columns)
        assertEquals("Smith", values["auditor"])
        assertEquals(10L, values["total"])
        assertEquals(listOf(2023L, 2024L), values["years"])
        assertNull(detailValues(subjects[1], report.columns)["years"])
    }

    @Test
    fun groupsAreInKeyOrderWithNoValueLastAndTheirRollupsComputed() {
        val groups = aggregateReport(subjects, listOf(auditor), listOf(total, years))
        assertEquals(listOf(listOf<Any?>("Jones"), listOf("Smith"), listOf(null)), groups.map { it.key })
        val smith = groups[1]
        // `Smith` and `Smith ` read as one auditor.
        assertEquals(3, smith.count)
        assertEquals(14.5, smith.rollups["total"])
        // A list column rolls up as how many forms have a value.
        assertEquals(2L, smith.rollups["years"])
        // The forms with no auditor -- none at all, and a blank -- are one group, and it comes last.
        assertEquals(2, groups[2].count)
        assertEquals(7L, groups[2].rollups["total"])
        assertEquals(subjects.size, groups.sumOf { it.count })
    }

    @Test
    fun withNothingToGroupByAnAggregateIsOneTotalRow() {
        val groups = aggregateReport(subjects, emptyList(), listOf(total))
        assertEquals(1, groups.size)
        assertEquals(emptyList(), groups.single().key)
        assertEquals(subjects.size, groups.single().count)
        assertEquals(24L, groups.single().rollups["total"])
    }

    @Test
    fun numbersThatAreOneValueAreOneGroup() {
        val year = column("y", "form.audit.total", ReportKind.number)
        val groups = aggregateReport(listOf(subject("a", "x", 2), subject("b", "y", 2.0), subject("c", "z", 3)), listOf(year), emptyList())
        assertEquals(listOf(2, 1), groups.map { it.count })
    }

    // --- exact dates (issue #1086): each date in the form its column declares, for a run that promises one ---

    private fun visited(id: String, value: Any?): ReportSubject =
        ReportSubject(mapOf(RMETA.gedraId to id), listOf(mapOf(GE.traitId to "audit", GE.data to mapOf("visitedOn" to value))), emptyList(), null) { null }

    private fun visitedColumn(dayOnly: Boolean, rollup: ReportCombine? = null) = BoundColumn(
        ReportColumn("visited", "Visited", "form.audit.visitedOn", rollup = rollup),
        BoundPath(parseReportPathOrThrow("form.audit.visitedOn"), emptyList(), ReportKind.date, multiValued = false, dayOnly = dayOnly),
        ReportCombine.first,
    )

    // Noon, so the day it falls on is the same whatever zone the server keeps.
    private val day = "2026-03-01".parseDay()
    private val nextDay = "2026-03-02".parseDay()
    private val withinDay = "2026-03-01T12:00:00Z".parseDate()

    @Test
    fun withExactDatesEachDateTakesTheFormItsColumnDeclares() {
        val asDay = visitedColumn(dayOnly = true)
        val asMoment = visitedColumn(dayOnly = false)
        // Unasked, a value is as it was stored, whatever the column declares: that is the shared run, unchanged.
        assertEquals(day, asDay.valueOf(visited("a", "2026-03-01")))
        assertEquals(withinDay, asDay.valueOf(visited("a", "2026-03-01T12:00:00Z")))
        assertEquals(day, asMoment.valueOf(visited("a", "2026-03-01")))
        // Asked: a moment in a day column is its day, a day in a moment column its first instant.
        assertEquals(day, asDay.valueOf(visited("a", "2026-03-01T12:00:00Z"), exactDates = true))
        assertEquals(day.toStartOfDay(), asMoment.valueOf(visited("a", "2026-03-01"), exactDates = true))
        // One already of the form is as it was, and nothing is still nothing.
        assertEquals(day, asDay.valueOf(visited("a", "2026-03-01"), exactDates = true))
        assertEquals(withinDay, asMoment.valueOf(visited("a", "2026-03-01T12:00:00Z"), exactDates = true))
        assertNull(asDay.valueOf(visited("a", null), exactDates = true))
        // A detail row carries the flag to every column.
        assertEquals(mapOf("visited" to day), detailValues(visited("a", "2026-03-01T12:00:00Z"), listOf(asDay), exactDates = true))
        assertEquals(mapOf("visited" to withinDay), detailValues(visited("a", "2026-03-01T12:00:00Z"), listOf(asDay)))
        // Only a date is touched.
        assertEquals("x", reportDateAs("x", dayOnly = true))
        assertEquals(7L, reportDateAs(7L, dayOnly = false))
    }

    @Test
    fun withExactDatesADayAndAMomentWithinItAreOneGroup() {
        val subjects = listOf(visited("a", "2026-03-01"), visited("b", "2026-03-01T12:00:00Z"), visited("c", "2026-03-02"))
        val byDay = visitedColumn(dayOnly = true)
        // As stored, the day and the moment within it are two keys -- which, written out as one day each, would be
        // two rows under the same key.
        assertEquals(3, aggregateReport(subjects, listOf(byDay), emptyList()).size)
        val exact = aggregateReport(subjects, listOf(byDay), emptyList(), exactDates = true)
        assertEquals(listOf(listOf<Any?>(day) to 2, listOf<Any?>(nextDay) to 1), exact.map { it.key to it.count })
        // What is rolled up is brought to the form first too: the latest of these is a day, not the moment.
        val latest = visitedColumn(dayOnly = true, rollup = ReportCombine.max)
        val later = subjects + visited("d", "2026-03-02T12:00:00Z")
        assertEquals(nextDay, aggregateReport(later, emptyList(), listOf(latest), exactDates = true).single().rollups["visited"])
        assertEquals("2026-03-02T12:00:00Z".parseDate(), aggregateReport(later, emptyList(), listOf(latest)).single().rollups["visited"])
    }

    @Test
    fun aValueGoesOnTheWireAsJsonHasAFormFor() {
        assertEquals("2024-01-05", reportWireValue("2024-01-05".parseDay()))
        assertEquals(listOf("2024-01-05", null), reportWireValue(listOf("2024-01-05".parseDay(), null)))
        val instant = "2026-03-01T10:00:00Z".parseDate()
        assertTrue(reportWireValue(instant) === instant)
        assertEquals(7L, reportWireValue(7L))
    }

    @Test
    fun aColumnDescribesHowItWasBound() {
        val described = years.describe()
        assertEquals(ReportKind.number.name, described[RRUN.kind])
        assertEquals(ReportCombine.list.name, described[RRUN.combine])
        assertEquals(ReportCombine.count.name, described[RRUN.rollup])
        assertEquals(true, described[RRUN.multiValued])
        assertFalse(auditor.describe().containsKey(RRUN.rollup))
    }
}
