package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.parseDay
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
