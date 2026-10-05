package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GCI
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.gedra.report.ReportMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Reports page's pure half (issue #1007): the listing and a run parsed from wire maps built with the kernel's own
 * constants, the run's query, the table a run is shown as, a cell's text, the paging line, and the page's links.
 */
class ReportsPageTest {
    private fun column(id: String, kind: ReportKind, combine: ReportCombine = ReportCombine.first, rollup: ReportCombine? = null) =
        buildMap<String, Any?> {
            put(RRUN.columnId, id)
            put(RRUN.label, id.replaceFirstChar { it.uppercase() })
            put(RRUN.path, "form.audit.$id")
            put(RRUN.kind, kind.name)
            put(RRUN.combine, combine.name)
            rollup?.let { put(RRUN.rollup, it.name) }
            put(RRUN.multiValued, combine == ReportCombine.list)
        }

    private val columns = listOf(
        column("auditor", ReportKind.string),
        column("total", ReportKind.number, rollup = ReportCombine.sum),
        column("tags", ReportKind.string, combine = ReportCombine.list, rollup = ReportCombine.count),
        column("visited", ReportKind.date),
    )

    private fun summary(mode: ReportMode, groupBy: List<String> = emptyList(), excluded: Int = 0) = mapOf(
        RRUN.reportId to "audits", RRUN.label to "Audits", RRUN.client to "acme", RRUN.mode to mode.name,
        RRUN.groupBy to groupBy, RRUN.excludeEmpty to listOf("auditor"), RRUN.columns to columns,
        RRUN.scanned to 40, RRUN.excluded to excluded,
    )

    @Test
    fun theListingParsesItsReportsAndTheIssuesThatCostOne() {
        val listing = parseReportListing(
            mapOf(
                EP.items to listOf(
                    mapOf(
                        RRUN.reportId to "audits", RRUN.label to "Audits", RRUN.description to "Every audit.",
                        RRUN.columns to columns, RRUN.groupBy to listOf("auditor"), RRUN.excludeEmpty to emptyList<String>(),
                        RRUN.configName to "acmeClient", RRUN.origin to "source",
                    ),
                    mapOf(RRUN.reportId to "roster", RRUN.label to "Roster", RRUN.columns to columns.take(1), RRUN.template to "tpl"),
                    // A row with no id cannot be opened, so it is dropped rather than listed.
                    mapOf(RRUN.label to "Nameless"),
                ),
                EP.summary to mapOf(
                    RRUN.client to "acme",
                    RRUN.issues to listOf(mapOf(GCI.message to "Report 'old' does not hold up.", GCI.degradedTo to "Dropping it.")),
                ),
            ),
        )
        assertEquals(listOf("audits", "roster"), listing.reports.map { it.reportId })
        val audits = listing.reports[0]
        assertEquals("Every audit.", audits.description)
        assertEquals(4, audits.columns.size)
        assertEquals(ReportCombine.sum.name, audits.columns[1].rollup)
        assertNull(audits.columns[0].rollup)
        assertTrue(audits.columns[2].yieldsList)
        // Grouped by default when it says what to group by; a plain listing otherwise.
        assertEquals(ReportMode.aggregate, audits.defaultMode)
        assertEquals(ReportMode.detail, listing.reports[1].defaultMode)
        assertEquals("tpl", listing.reports[1].template)
        assertEquals(listOf("Report 'old' does not hold up. Dropping it."), listing.issues)
    }

    @Test
    fun aRunPageParsesItsRowsItsCursorAndItsSummary() {
        val page = parseRunPage(
            mapOf(
                EP.items to listOf(mapOf(RRUN.gedraId to "gd.fd.acme.a", RRUN.values to mapOf("auditor" to "Smith", "total" to 50))),
                EP.numAvailable to 32, EP.hasMore to true, EP.next to "1.abc.def", EP.summary to summary(ReportMode.detail, excluded = 3),
            ),
        )
        assertEquals("gd.fd.acme.a", page.rows.single().gedraId)
        assertEquals("Smith", page.rows.single().values["auditor"])
        assertEquals(32, page.numAvailable)
        assertEquals("1.abc.def", page.next)
        assertEquals(ReportMode.detail, page.summary.mode)
        assertEquals(40, page.summary.scanned)
        assertEquals(3, page.summary.excluded)
        // The last page hands back no cursor.
        assertNull(parseRunPage(mapOf(EP.items to emptyList<Any>(), EP.summary to summary(ReportMode.detail))).next)

        val grouped = parseRunPage(
            mapOf(
                EP.items to listOf(mapOf(RRUN.group to mapOf("auditor" to null), RRUN.count to 4, RRUN.values to mapOf("total" to 170))),
                EP.numAvailable to 1, EP.summary to summary(ReportMode.aggregate, listOf("auditor")),
            ),
        )
        assertEquals(ReportMode.aggregate, grouped.summary.mode)
        assertEquals(4, grouped.rows.single().count)
        assertNull(grouped.rows.single().gedraId)
    }

    @Test
    fun theQuerySaysOnlyWhatTheUserSet() {
        // Untouched: nothing but the report, so the endpoint applies the report's own lists.
        val plain = reportRunQuery("audits", null, ReportRunSetup(ReportMode.detail), after = null)
        assertEquals(mapOf<String, Any?>(RRUN.reportId to "audits", EP.limit to reportPageSize), plain)

        // Grouped, for a chosen client, on a later page.
        val grouped = reportRunQuery("audits", "acme", ReportRunSetup(ReportMode.aggregate, listOf("auditor")), after = "cur")
        assertEquals(true, grouped[RRUN.aggregate])
        assertEquals(listOf("auditor"), grouped[RRUN.groupBy])
        assertEquals("acme", grouped[RRUN.client])
        assertEquals("cur", grouped[EP.after])
        assertFalse(grouped.containsKey(RRUN.excludeEmpty))

        // A group-by chosen and then a switch to per form: the detail run takes none, so none is sent.
        assertFalse(reportRunQuery("audits", null, ReportRunSetup(ReportMode.detail, listOf("auditor")), null).containsKey(RRUN.groupBy))

        // Emptied is not absent: an empty list asks for none, where leaving it out asks for the report's own.
        val none = reportRunQuery("audits", null, ReportRunSetup(ReportMode.aggregate, emptyList(), emptyList()), null)
        assertEquals(emptyList<String>(), none[RRUN.groupBy])
        assertEquals(emptyList<String>(), none[RRUN.excludeEmpty])
        // ...and it reaches the wire as a value the endpoint reads as an empty list.
        assertTrue(queryString(none).contains("${RRUN.excludeEmpty}=%5B%5D"))
    }

    @Test
    fun aSetupsSignatureTellsOneWalkFromAnother() {
        val base = ReportRunSetup(ReportMode.aggregate, listOf("auditor"))
        assertEquals(base.signature, ReportRunSetup(ReportMode.aggregate, listOf("auditor")).signature)
        val others = listOf(
            ReportRunSetup(ReportMode.detail, listOf("auditor")),
            ReportRunSetup(ReportMode.aggregate, listOf("total")),
            ReportRunSetup(ReportMode.aggregate),
            ReportRunSetup(ReportMode.aggregate, emptyList()),
            ReportRunSetup(ReportMode.aggregate, listOf("auditor"), emptyList()),
        )
        assertEquals(others.size + 1, (others.map { it.signature } + base.signature).toSet().size)
    }

    @Test
    fun theModeIsTheAddressesAndTheListsAreTheSessions() {
        // The address names a mode: that is the mode, whatever the report declares.
        assertEquals(ReportMode.detail, reportSetupInForce(reportModeDetail, ReportMode.aggregate, null, null).mode)
        assertEquals(ReportMode.aggregate, reportSetupInForce(reportModeGrouped, ReportMode.detail, null, null).mode)
        // It names none: the report opens the way it declares, and per form while it is not yet known.
        assertEquals(ReportMode.aggregate, reportSetupInForce(null, ReportMode.aggregate, null, null).mode)
        assertEquals(ReportMode.detail, reportSetupInForce(null, null, null, null).mode)
        // The lists ride along untouched, absent and emptied told apart.
        val set = reportSetupInForce(null, ReportMode.aggregate, listOf("auditor"), emptyList())
        assertEquals(listOf("auditor"), set.groupBy)
        assertEquals(emptyList(), set.excludeEmpty)
        assertNull(reportSetupInForce(null, ReportMode.aggregate, null, null).groupBy)
    }

    @Test
    fun aWalkIsOneClientOneReportAndOneSetup() {
        val setup = ReportRunSetup(ReportMode.aggregate, listOf("auditor"))
        val key = reportWalkKey("acme", "audits", setup)
        assertEquals(key, reportWalkKey("acme", "audits", ReportRunSetup(ReportMode.aggregate, listOf("auditor"))))
        val others = listOf(
            reportWalkKey("globex", "audits", setup),
            reportWalkKey("acme", "roster", setup),
            reportWalkKey(null, "audits", setup),
            reportWalkKey("acme", "audits", ReportRunSetup(ReportMode.detail, listOf("auditor"))),
            reportWalkKey("acme", "audits", ReportRunSetup(ReportMode.aggregate)),
        )
        assertEquals(others.size + 1, (others + key).toSet().size)
    }

    @Test
    fun aDetailRunsTableIsTheReportsColumns() {
        val table = reportTableColumns(parseRunPage(mapOf(EP.summary to summary(ReportMode.detail))).summary)
        assertEquals(listOf("auditor", "total", "tags", "visited"), table.map { it.key })
        assertEquals(listOf("Auditor", "Total", "Tags", "Visited"), table.map { it.label })
        assertTrue(table.all { it.source == ReportCellSource.value })
        assertEquals(listOf(false, true, false, false), table.map { it.numeric })
    }

    @Test
    fun aGroupedRunsTableIsItsGroupsACountAndTheRollups() {
        val table = reportTableColumns(parseRunPage(mapOf(EP.summary to summary(ReportMode.aggregate, listOf("auditor")))).summary)
        assertEquals(listOf("Auditor", reportCountLabel, "Total (sum)", "Tags (count)"), table.map { it.label })
        assertEquals(
            listOf(ReportCellSource.group, ReportCellSource.count, ReportCellSource.value, ReportCellSource.value),
            table.map { it.source },
        )
        // A count of text is a number, and is aligned as one.
        assertEquals(listOf(false, true, true, true), table.map { it.numeric })

        // Grouped by nothing: the count and the rollups -- the one total row.
        val total = reportTableColumns(parseRunPage(mapOf(EP.summary to summary(ReportMode.aggregate))).summary)
        assertEquals(listOf(reportCountLabel, "Total (sum)", "Tags (count)"), total.map { it.label })

        // Grouped by a column that is also rolled up: its rollup would repeat the group's own value, so it is left out.
        val byTotal = reportTableColumns(parseRunPage(mapOf(EP.summary to summary(ReportMode.aggregate, listOf("total")))).summary)
        assertEquals(listOf("Total", reportCountLabel, "Tags (count)"), byTotal.map { it.label })

        val row = ReportRunRow(null, mapOf("auditor" to "Smith"), 6, mapOf("total" to 345, "tags" to 4))
        assertEquals(listOf<Any?>("Smith", 6, 345, 4), table.map { reportCellValue(row, it) })
    }

    @Test
    fun aCellReadsByItsKind() {
        assertEquals("Smith", reportCellText("Smith", ReportKind.string.name))
        assertEquals("2024", reportCellText(2024, ReportKind.number.name))
        assertEquals("52.14", reportCellText(52.1428571, ReportKind.number.name))
        assertEquals("-0.50", reportCellText(-0.5, ReportKind.number.name))
        assertEquals("12.50", reportCellText(12.5, ReportKind.number.name))
        // Past what a number holds exactly it is shown as it prints, not rounded into nonsense.
        assertEquals(1.5e17.toString(), reportCellText(1.5e17, ReportKind.number.name))
        assertEquals("9000000000000000", reportCellText(9.0e15, ReportKind.number.name))
        assertEquals("Yes", reportCellText(true, ReportKind.boolean.name))
        assertEquals("No", reportCellText(false, ReportKind.boolean.name))
        // A timestamp as the app shows them; a day as it is written; text that merely looks like a date left alone.
        assertEquals("2026-10-05 18:47 UTC", reportCellText("2026-10-05T18:47:50.836Z", ReportKind.date.name))
        assertEquals("2024-03-05", reportCellText("2024-03-05", ReportKind.date.name))
        assertEquals("2026-10-05T18:47:50.836Z", reportCellText("2026-10-05T18:47:50.836Z", ReportKind.string.name))
        assertEquals("2022, 2023", reportCellText(listOf(2022, 2023), ReportKind.number.name))
        // Nothing is a dash -- except what a group is grouped by, where the row is the forms with no value.
        assertEquals(reportBlank, reportCellText(null, ReportKind.string.name))
        assertEquals(reportBlank, reportCellText(emptyList<Any>(), ReportKind.string.name))
        assertEquals(reportNoGroupValue, reportCellText(null, ReportKind.string.name, ReportCellSource.group))
    }

    @Test
    fun thePagingLineCountsAsTheWalkGoes() {
        assertEquals("Forms 1–25 of 32", reportRangeText(0, 25, 32, ReportMode.detail))
        assertEquals("Forms 26–32 of 32", reportRangeText(25, 7, 32, ReportMode.detail))
        assertEquals("5 groups", reportRangeText(0, 5, 5, ReportMode.aggregate))
        assertEquals("1 form", reportRangeText(0, 1, 1, ReportMode.detail))
        assertEquals("No forms", reportRangeText(0, 0, 0, ReportMode.detail))
        // Every remaining form was deleted while the walk stood on the page before.
        assertEquals("No more forms", reportRangeText(25, 0, 25, ReportMode.detail))

        val read = parseRunPage(mapOf(EP.summary to summary(ReportMode.detail))).summary
        assertEquals("40 forms read", reportCountsText(read))
        val left = parseRunPage(mapOf(EP.summary to summary(ReportMode.detail, excluded = 7))).summary
        assertEquals("40 forms read, 7 left out for a missing value", reportCountsText(left))
    }

    @Test
    fun thePagesLinksAndItsModeInTheHash() {
        assertEquals("#page=reports", reportsHref())
        assertEquals("#page=reports&rpt=audits", reportsHref(reportId = "audits"))
        assertEquals("#page=reports&c=acme&rpt=audits&view=grouped", reportsHref("acme", "audits", ReportMode.aggregate))
        assertEquals(ReportMode.aggregate, reportModeOf(reportModeParam(ReportMode.aggregate)))
        assertEquals(ReportMode.detail, reportModeOf(reportModeParam(ReportMode.detail)))
        // Anything else -- absent, or a stale link -- leaves the report to open the way it declares.
        assertNull(reportModeOf(null))
        assertNull(reportModeOf("sideways"))

        // A row's form, on the forms page; told the client when looking across clients.
        assertEquals("#page=forms&g=gd.fd.acme.a", reportFormHref("gd.fd.acme.a", null))
        assertEquals("#page=forms&client=acme&g=gd.fd.acme.a", reportFormHref("gd.fd.acme.a", "acme"))
    }
}
