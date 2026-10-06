package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.report.RHIS
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.gedra.report.ReportSnapshotTrigger
import com.dynamicruntime.common.home.HMENU
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Reports page's History view, its pure half (issue #1037): the snapshots parsed, the series, and the chart's geometry. */
class ReportHistoryTest {
    private fun column(id: String, kind: ReportKind = ReportKind.string, rollup: ReportCombine? = null) =
        ReportColumnInfo(id, id.replaceFirstChar { it.uppercase() }, "form.x.$id", kind.name, ReportCombine.first.name, rollup?.name, false)

    private val columns = listOf(
        column("year", ReportKind.number),
        column("total", ReportKind.number, ReportCombine.sum),
        column("price", ReportKind.number, ReportCombine.avg),
        column("items", ReportKind.string, ReportCombine.count),
        column("lastAudited", ReportKind.date, ReportCombine.max),
    )

    private fun row(year: Any?, count: Int, total: Double? = null) =
        ReportRunRow(null, mapOf("year" to year), count, mapOf("total" to total))

    private fun snapshot(id: Long, takenAt: String, vararg rows: ReportRunRow, same: Boolean = true, trigger: String = "scheduled") =
        ReportSnapshot(id, takenAt, trigger, null, listOf("year"), columns, rows.toList(), 0, 0, truncated = false, sameDefinition = same)

    private val count = HistoryMetric(RRUN.count, "Forms", additive = true)

    @Test
    fun aSnapshotParsesWithItsRowsAndAPageWithItsCursor() {
        val raw = mapOf(
            RHIS.snapshotId to 7, RHIS.takenAt to "2026-10-05T03:30:00.000Z", RHIS.trigger to ReportSnapshotTrigger.simulated.name,
            RRUN.groupBy to listOf("year"), RRUN.columns to listOf(mapOf(RRUN.columnId to "year", RRUN.kind to "number")),
            RHIS.rows to listOf(mapOf(RRUN.group to mapOf("year" to 2024), RRUN.count to 3, RRUN.values to mapOf("total" to 12.5))),
            RRUN.scanned to 9, RHIS.truncated to true, RHIS.sameDefinition to false,
        )
        val page = parseHistoryPage(mapOf(EP.items to listOf(raw, mapOf("no" to "id")), EP.numAvailable to 4, EP.next to "c1"))
        val s = page.snapshots.single()
        assertEquals(7L, s.snapshotId)
        assertEquals("2026-10-05", s.day)
        assertEquals("simulated", snapshotTriggerText(s.trigger))
        assertEquals(3, s.rows.single().count)
        assertEquals(true, s.truncated)
        assertEquals(false, s.sameDefinition)
        assertEquals(4, page.numAvailable)
        assertEquals("c1", page.next)
        // Asked for by report, with the client only when one is named, and the cursor only when there is one.
        assertEquals(mapOf(RRUN.reportId to "r", EP.limit to reportHistoryPageSize), reportHistoryQuery("r", null, null))
        assertEquals("acme", reportHistoryQuery("r", "acme", "c1")[RRUN.client])
        assertEquals(mapOf(RRUN.reportId to "r"), reportSnapshotBody("r", null))
    }

    @Test
    fun theMetricsAreTheCountThenTheNumericRollupsNotGroupedBy() {
        val metrics = historyMetrics(columns, listOf("year"))
        // The count; the sum and the average of a number; a count of text. Not the grouped-by column, nor a date's maximum.
        assertEquals(listOf(RRUN.count, "total", "price", "items"), metrics.map { it.key })
        assertEquals(listOf(true, true, false, true), metrics.map { it.additive })
        assertEquals("Total (sum)", metrics[1].label)
        assertEquals(12.5, metricValue(row(2024, 3, 12.5), metrics[1]))
        assertEquals(3.0, metricValue(row(2024, 3, 12.5), metrics[0]))
        assertNull(metricValue(row(2024, 3), metrics[1]))
    }

    @Test
    fun aDayIsItsLatestSnapshotAndASeriesKeepsGroupsInTheOrderFirstSeen() {
        val snapshots = listOf(
            snapshot(4, "2026-10-03T09:00:00Z", row(2023, 5), row(2024, 2), row(null, 1)),
            // The same day's earlier one: superseded.
            snapshot(3, "2026-10-03T03:30:00Z", row(2023, 4)),
            snapshot(2, "2026-10-02T03:30:00Z", row(2024, 9), row(2023, 1)),
            // Taken under another definition of the report: not charted, and counted.
            snapshot(1, "2026-10-01T03:30:00Z", row(2020, 7), same = false),
        )
        assertEquals(listOf(2L, 4L), latestPerDay(snapshots.filter { it.sameDefinition }).map { it.snapshotId })
        val series = historySeries(snapshots, count)
        assertEquals(listOf("2026-10-02", "2026-10-03"), series.days.map { it.day })
        // 2024 was seen first (the oldest day's first row), and keeps its place though 2023 has since passed it.
        assertEquals(listOf("2024", "2023", "(none)"), series.groups.map { it.label })
        assertEquals(listOf(listOf(9.0, 1.0, null), listOf(2.0, 5.0, 1.0)), series.values)
        assertEquals(1, series.otherDefinitions)
        assertEquals(0, series.otherCount)
        // Another metric changes the heights and nothing about whose place is whose.
        assertEquals(series.groups.map { it.key }, historySeries(snapshots, HistoryMetric("total", "Total (sum)", true)).groups.map { it.key })
    }

    @Test
    fun groupsPastTheLimitFoldIntoOtherOnlyWhenTheMetricAddsUp() {
        val rows = (1..5).map { row(2020 + it, count = it, total = it * 10.0) }
        val one = listOf(snapshot(1, "2026-10-02T03:30:00Z", *rows.toTypedArray()))
        val folded = historySeries(one, count, maxGroups = 3)
        // The three with the most forms on the latest day, in the order first seen; the other two summed.
        assertEquals(listOf("2023", "2024", "2025"), folded.groups.map { it.label })
        assertEquals(2, folded.otherCount)
        assertEquals(listOf<Double?>(3.0), folded.otherValues)
        assertTrue(folded.otherDrawn)
        // An average of groups is not their sum: nothing is drawn for them.
        val average = historySeries(one, HistoryMetric("price", "Price (avg)", additive = false), maxGroups = 3)
        assertEquals(2, average.otherCount)
        assertNull(average.otherValues)
        // A report grouping by nothing has the one group, named for what it is.
        val total = ReportSnapshot(9, "2026-10-02T03:30:00Z", "manual", null, emptyList(), columns, listOf(ReportRunRow(null, emptyMap(), 12, emptyMap())), 12, 0, false, true)
        assertEquals(listOf(historyAllFormsLabel), historySeries(listOf(total), count).groups.map { it.label })
    }

    @Test
    fun theChartLaysOutABarAGroupADayOnCleanTicks() {
        val snapshots = listOf(
            snapshot(1, "2026-10-02T03:30:00Z", row(2023, 26), row(2024, 13)),
            snapshot(2, "2026-10-03T03:30:00Z", row(2023, 124), row(2024, 62)),
        )
        val layout = historyChartLayout(historySeries(snapshots, count), width = 760.0, plotHeight = 240.0)
        assertEquals(0, layout.firstDay)
        assertEquals(listOf("Oct 2", "Oct 3"), layout.slots.map { it.label })
        assertEquals(4, layout.bars.size)
        // Ticks are clean numbers ending past the largest value; bars are never thicker than the cap.
        assertEquals(listOf(0.0, 50.0, 100.0, 150.0), layout.ticks.map { it.value })
        assertTrue(layout.bars.all { it.width == HCH.barMax })
        // Heights are in proportion, from the baseline; neighbours in a day are a gap apart.
        val byValue = layout.bars.associateBy { it.value }
        assertTrue(abs(byValue.getValue(124.0).height / byValue.getValue(62.0).height - 2.0) < 1e-9)
        assertTrue(layout.bars.all { abs(it.y + it.height - layout.baselineY) < 1e-9 })
        val firstDay = layout.bars.filter { it.dayIndex == 0 }.sortedBy { it.x }
        assertTrue(abs(firstDay[1].x - (firstDay[0].x + firstDay[0].width + HCH.barGap)) < 1e-9)
        // The label band is part of the chart's height, so day labels are never clipped.
        assertEquals(layout.plotBottom + HCH.axisBand, layout.height)
        // Rounded at the data end, square at the baseline.
        assertTrue(firstDay[0].path.contains("Q") && firstDay[0].path.endsWith("Z"))
    }

    @Test
    fun onlyTheLatestDaysThatFitAreDrawnAndAMissingValueDrawsNothing() {
        val many = (1..60).map { d -> snapshot(d.toLong(), "2026-${if (d <= 30) "09" else "10"}-${((d - 1) % 30 + 1).toString().padStart(2, '0')}T03:30:00Z", row(2023, d), row(2024, d)) }
        val layout = historyChartLayout(historySeries(many, count), width = 760.0)
        assertTrue(layout.firstDay > 0)
        assertEquals(60 - layout.firstDay, layout.slots.size)
        assertEquals(layout.firstDay, historyDaysNotDrawn(layout))
        assertTrue(layout.bars.all { it.width >= HCH.barMin })
        // Labels are thinned to fit, and the latest day keeps its own.
        assertTrue(layout.slots.count { it.label != null } < layout.slots.size)
        assertEquals("Oct 30", layout.slots.last().label)
        // A group with no value on a day has no bar there, and a negative value hangs below the baseline.
        val gappy = listOf(snapshot(1, "2026-10-02T03:30:00Z", row(2023, 1, -40.0), row(2024, 1)))
        val sums = historyChartLayout(historySeries(gappy, HistoryMetric("total", "Total (sum)", true)))
        assertEquals(1, sums.bars.size)
        assertEquals(sums.baselineY, sums.bars.single().y)
        assertTrue(sums.ticks.first().value < 0)
    }

    @Test
    fun theKeyboardEntersAtTheLatestDayAndTheArrowsStepBarByBar() {
        val snapshots = listOf(
            snapshot(1, "2026-10-02T03:30:00Z", row(2023, 26), row(2024, 13)),
            snapshot(2, "2026-10-03T03:30:00Z", row(2023, 124), row(2024, 62)),
        )
        val bars = historyChartLayout(historySeries(snapshots, count)).bars
        // One Tab stop: the latest day's first bar.
        assertEquals(2, historyBarEntry(bars))
        assertEquals(1, bars[historyBarEntry(bars)].dayIndex)
        assertEquals(-1, historyBarEntry(emptyList()))
        // The arrows go to the neighbour, across days; at either end the key is the browser's again.
        assertEquals(1, historyBarStep(2, bars.size, HKEY.left))
        assertEquals(3, historyBarStep(2, bars.size, HKEY.right))
        assertNull(historyBarStep(0, bars.size, HKEY.left))
        assertNull(historyBarStep(3, bars.size, HKEY.right))
        assertEquals(0, historyBarStep(2, bars.size, HKEY.home))
        assertEquals(3, historyBarStep(2, bars.size, HKEY.end))
        assertNull(historyBarStep(0, bars.size, HKEY.home))
        assertNull(historyBarStep(3, bars.size, HKEY.end))
        assertNull(historyBarStep(2, bars.size, "Tab"))
        assertNull(historyBarStep(0, 0, HKEY.end))
    }

    @Test
    fun numbersTicksAndLinksReadAsTheChartWritesThem() {
        assertEquals("1,480", historyNumberText(1480.0))
        assertEquals("-12,345.50", historyNumberText(-12345.5))
        assertEquals("62", historyNumberText(62.0))
        assertEquals(50.0, niceTickStep(124.0))
        assertEquals(10.0, niceTickStep(26.0))
        assertEquals(0.1, niceTickStep(0.4))
        // There is no half a form.
        assertEquals(1.0, niceTickStep(2.0, wholeOnly = true))
        // The chart follows its pane between its narrowest and widest, in whole pixels.
        assertEquals(486.0, historyChartWidth(486.7))
        assertEquals(HCH.maxWidth, historyChartWidth(1400.0))
        assertEquals(HCH.minWidth, historyChartWidth(120.0))
        assertEquals("Oct 2", historyDayLabel("2026-10-02"))
        assertEquals("not a day", historyDayLabel("not a day"))
        assertEquals("2026-13-02", historyDayLabel("2026-13-02"))
        assertEquals("2026-10-xx", historyDayLabel("2026-10-xx"))
        assertEquals(true, reportViewIsHistory(HMENU.reportViewHistory))
        assertEquals(false, reportViewIsHistory(reportModeGrouped))
        assertEquals(null, reportModeOf(HMENU.reportViewHistory))
        assertTrue(reportsHref(null, "expensesByYear", history = true).endsWith("${HP.report}=expensesByYear&${HP.reportView}=${HMENU.reportViewHistory}"))
        assertEquals("No snapshots yet.", historySummaryText(emptyList()))
        assertTrue(historySummaryText(listOf(snapshot(1, "2026-10-02T03:30:00Z"), snapshot(2, "2026-10-03T03:30:00Z", trigger = "manual"))).startsWith("2 snapshots over 2 days; the latest "))
    }
}
