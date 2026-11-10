package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GCI
import com.dynamicruntime.common.gedra.report.RCR
import com.dynamicruntime.common.gedra.report.RDEF
import com.dynamicruntime.common.gedra.report.RHIS
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.ReportChart
import com.dynamicruntime.common.gedra.report.ReportChartKind
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.gedra.report.ReportMode
import com.dynamicruntime.common.gedra.report.ReportPathGroupKind
import com.dynamicruntime.common.gedra.report.ReportSnapshotTrigger
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.schema.SchOption
import com.dynamicruntime.common.util.toJsonListOfMaps
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise

// The Reports page's pure rules (issue #1740 gathered these into one file): the listing and a run, making a report, and
// its history. Each class is the file it used to be, kept whole with its own helpers.

/**
 * The Reports page's pure half (issue #1007): the listing and a run parsed from wire maps built with the kernel's own
 * constants, the run's query, the table a run is shown as, a cell's text, the paging line, and the page's links.
 */
@OptIn(DelicateCoroutinesApi::class)
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
        assertEquals(mapOf<String, Any?>(RRUN.reportId to "audits", EP.limit to listingBatch), plain)

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
        assertEquals("Oct 5, 2026, 10:47 AM GMT-8", reportCellText("2026-10-05T18:47:50.836Z", ReportKind.date.name))
        assertEquals("2024-03-05", reportCellText("2024-03-05", ReportKind.date.name))
        assertEquals("2026-10-05T18:47:50.836Z", reportCellText("2026-10-05T18:47:50.836Z", ReportKind.string.name))
        assertEquals("2022, 2023", reportCellText(listOf(2022, 2023), ReportKind.number.name))
        // Long text keeps its lines, and a list holding any is split by a blank line, so where one ends stays plain.
        assertEquals("Kotlin\nand more", reportCellText("Kotlin\nand more", ReportKind.string.name))
        assertEquals("One\n\nTwo\nlines", reportCellText(listOf("One", "Two\nlines"), ReportKind.string.name))
        assertEquals(true, reportTextHasLines("Two\nlines"))
        assertEquals(false, reportTextHasLines("One, Two"))
        // Nothing is a dash -- except what a group is grouped by, where the row is the forms with no value.
        assertEquals(reportBlank, reportCellText(null, ReportKind.string.name))
        assertEquals(reportBlank, reportCellText(emptyList<Any>(), ReportKind.string.name))
        assertEquals(reportNoGroupValue, reportCellText(null, ReportKind.string.name, ReportCellSource.group))
    }

    @Test
    fun aChoiceReadsAsItsLabelWithItsStoredValueBehindIt() {
        // Issue #1438: a column carries its field's choices, and a cell, a group, or a list shows each value's label;
        // the tooltip says the stored values, which the file holds. Text no choice names is shown, and tipped, as it is.
        val track = parseReportColumns(
            listOf(column("track", ReportKind.string) + (RRUN.choices to listOf(mapOf(RRUN.value to "kotlin", RRUN.label to "Kotlin")))),
        ).single()
        assertEquals(listOf(SchOption("kotlin", "Kotlin")), track.choices)
        assertEquals("Kotlin", reportCellText("kotlin", track.kind, choices = track.choices))
        assertEquals("Kotlin", reportCellText("kotlin", track.kind, ReportCellSource.group, track.choices))
        assertEquals("Kotlin, web", reportCellText(listOf("kotlin", "web"), track.kind, choices = track.choices))
        assertEquals("web", reportCellText("web", track.kind, choices = track.choices))
        assertEquals("kotlin", reportCellTip("kotlin", track.choices))
        assertEquals("kotlin, web", reportCellTip(listOf("kotlin", "web"), track.choices))
        assertNull(reportCellTip("web", track.choices))
        assertNull(reportCellTip("kotlin", emptyList()))
        assertEquals("kotlin", reportCsvCell("kotlin"))
    }

    @Test
    fun theVocabularysClosedSetsReadAsTheFormsPagesSayThem() {
        // A form's status as the listing's chip says it, a workflow's category as the Workflows page heads it.
        assertEquals("Valid", reportCellText("valid", ReportKind.string.name, choices = vocabularyChoices("meta.formStatus", null)))
        assertEquals("Needs Info", reportCellText("needsInfo", ReportKind.string.name,
            choices = vocabularyChoices("meta.coreDetailsStatus", null)))
        assertEquals("Engaged", reportCellText("engaged", ReportKind.string.name, choices = vocabularyChoices("workflow.review.category", null)))
        assertEquals("Not eligible", reportCellText("ineligible", ReportKind.string.name, choices = vocabularyChoices("workflow.review.category", null)))
        // Any other path, and a column read as something other than text, has none.
        assertTrue(vocabularyChoices("meta.createdAt", null).isEmpty())
        assertTrue(vocabularyChoices("workflow.review.approval[signoff].approved", null).isEmpty())
        assertTrue(vocabularyChoices("meta.formStatus", ReportKind.number.name).isEmpty())
        // Read as the backend parses it: a path that does not parse has none, and a trait field named like an attribute
        // is not one.
        assertTrue(vocabularyChoices("meta.", null).isEmpty())
        assertTrue(vocabularyChoices("form.review.category", null).isEmpty())
        // The run's summary gives a status column its labels with no choices on the wire.
        val status = parseReportColumns(listOf(column("status", ReportKind.string) + (RRUN.path to "meta.formStatus"))).single()
        assertEquals("Valid", reportCellText("valid", status.kind, choices = status.choices))
    }

    @Test
    fun theCountsLineSaysWhatTheRunReadAndLeftOut() {
        val read = parseRunPage(mapOf(EP.summary to summary(ReportMode.detail))).summary
        assertEquals("40 forms read", reportCountsText(read))
        val left = parseRunPage(mapOf(EP.summary to summary(ReportMode.detail, excluded = 7))).summary
        assertEquals("40 forms read, 7 left out for a missing value", reportCountsText(left))
    }

    @Test
    fun thePagesLinksAndItsModeInTheAddress() {
        assertEquals("/reports", reportsHref())
        assertEquals("/reports/audits", reportsHref(reportId = "audits"))
        assertEquals("/reports/audits?c=acme&view=grouped", reportsHref("acme", "audits", ReportMode.aggregate))
        assertEquals(ReportMode.aggregate, reportModeOf(reportModeParam(ReportMode.aggregate)))
        assertEquals(ReportMode.detail, reportModeOf(reportModeParam(ReportMode.detail)))
        // Anything else -- absent, or a stale link -- leaves the report to open the way it declares.
        assertNull(reportModeOf(null))
        assertNull(reportModeOf("sideways"))

        // A row's form, on the forms page; told the client when looking across clients.
        assertEquals("/forms?g=gd.fd.acme.a", reportFormHref("gd.fd.acme.a", null))
        assertEquals("/forms?client=acme&g=gd.fd.acme.a", reportFormHref("gd.fd.acme.a", "acme"))
    }

    // --- the download (issue #1008) ---

    private fun runOf(mode: ReportMode, groupBy: List<String>, vararg rows: ReportRunRow) =
        ReportRunPage(rows.toList(), rows.size, null, parseRunPage(mapOf(EP.summary to summary(mode, groupBy))).summary)

    @Test
    fun aDetailRunAsCsvLeadsWithTheFormAndWritesEachKindForASpreadsheet() {
        val csv = reportCsv(
            runOf(
                ReportMode.detail, emptyList(),
                ReportRunRow("gd.fd.acme.a", emptyMap(), null, mapOf("auditor" to "Smith, J", "total" to 52.1428571, "tags" to listOf("a", "b"), "visited" to "2026-10-05T18:47:50.836Z")),
                ReportRunRow("gd.fd.acme.b", emptyMap(), null, mapOf("auditor" to "The \"Lee\"", "total" to 50, "tags" to emptyList<String>(), "visited" to "2024-03-05")),
                ReportRunRow("gd.fd.acme.c", emptyMap(), null, mapOf("auditor" to "two\nlines", "total" to null)),
            ),
        )
        assertEquals(
            listOf(
                "Form,Auditor,Total,Tags,Visited",
                // A comma is quoted; a number is written in full; a list is joined; a timestamp as a spreadsheet reads one.
                "gd.fd.acme.a,\"Smith, J\",52.1428571,a; b,2026-10-05 18:47:50",
                // A quote is doubled; a whole number has no fraction; an empty list and a day.
                "gd.fd.acme.b,\"The \"\"Lee\"\"\",50,,2024-03-05",
                // A line break is kept inside quotes; nothing is an empty field.
                "gd.fd.acme.c,\"two\nlines\",,,",
                "",
            ).joinToString("\r\n"),
            csv,
        )
    }

    @Test
    fun aGroupedRunAsCsvIsHeadedAsThePageIs() {
        val csv = reportCsv(
            runOf(
                ReportMode.aggregate, listOf("auditor"),
                ReportRunRow(null, mapOf("auditor" to "Smith"), 6, mapOf("total" to 345, "tags" to 4)),
                // The group with no value: an empty field, where the page says "(none)".
                ReportRunRow(null, mapOf("auditor" to null), 4, mapOf("total" to 170.5, "tags" to 0)),
            ),
        )
        assertEquals("Auditor,Forms,Total (sum),Tags (count)\r\nSmith,6,345,4\r\n,4,170.5,0\r\n", csv)
        assertEquals("audits-grouped.csv", reportCsvFileName("audits", ReportMode.aggregate))
        assertEquals("kdr-formsByStatus-detail.csv", reportCsvFileName("kdr:formsByStatus", ReportMode.detail))
    }

    @Test
    fun textASpreadsheetWouldRunAsAFormulaIsWrittenAsText() {
        assertEquals("'=SUM(A1:A9)", reportCsvCell("=SUM(A1:A9)"))
        assertEquals("'+1 555 0100", reportCsvCell("+1 555 0100"))
        assertEquals("'-draft-", reportCsvCell("-draft-"))
        assertEquals("'@home", reportCsvCell("@home"))
        // A number is a number, negative or not, and ordinary text is left alone.
        assertEquals("-12.5", reportCsvCell(-12.5))
        assertEquals("a=b", reportCsvCell("a=b"))
        assertEquals("true", reportCsvCell(true))
        // A list is guarded once, as the cell it becomes: its first character is what a spreadsheet reads, and a
        // later element is left as it was entered.
        assertEquals("'=x; y", reportCsvCell(listOf("=x", "y")))
        assertEquals("north; -urgent", reportCsvCell(listOf("north", "-urgent")))
        assertEquals("'-5; 3", reportCsvCell(listOf(-5, 3)))
        assertEquals("Fetched 500 of 1200\u2026", reportDownloadProgressText(500, 1200))
        // Before the first page has said how many there are, no count is claimed.
        assertEquals("Fetching\u2026", reportDownloadProgressText(0, 0))
    }

    @Test
    fun aTimestampIsWrittenAsASpreadsheetReadsOne() {
        assertEquals("2026-10-05 18:47:50", csvTimestamp("2026-10-05T18:47:50.836Z"))
        assertEquals("2026-10-05 18:47:50", csvTimestamp("2026-10-05T18:47:50Z"))
        // A day, a time with another offset, and anything that is not a timestamp are left as they are.
        assertEquals("2024-03-05", csvTimestamp("2024-03-05"))
        assertEquals("2026-10-05T18:47:50+02:00", csvTimestamp("2026-10-05T18:47:50+02:00"))
        assertEquals("soon", csvTimestamp("soon"))
        // Only a date column is rewritten: text that merely looks like a timestamp is somebody's text.
        assertEquals("2026-10-05T18:47:50.836Z", reportCsvCell("2026-10-05T18:47:50.836Z", ReportKind.string.name))
        assertEquals("2026-10-05 18:47:50", reportCsvCell("2026-10-05T18:47:50.836Z", ReportKind.date.name))
        // The page and the file agree on what a whole number is.
        assertEquals("50", wholeNumberTextOrNull(50.0))
        assertNull(wholeNumberTextOrNull(50.5))
        assertNull(wholeNumberTextOrNull(1.5e17))
    }

    private fun pageOf(ids: List<String>, next: String?, total: Int) = ReportRunPage(
        ids.map { ReportRunRow(it, emptyMap(), null, emptyMap()) }, total, next,
        parseRunPage(mapOf(EP.summary to summary(ReportMode.detail))).summary,
    )

    @Test
    fun theWalkFollowsNextToThePageWithNone() = GlobalScope.promise {
        val asked = mutableListOf<String?>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val pages = mapOf(null to pageOf(listOf("a", "b"), "c1", 5), "c1" to pageOf(listOf("c", "d"), "c2", 5), "c2" to pageOf(listOf("e"), null, 5))
        val whole = walkReportRun({ after -> asked.add(after); pages.getValue(after) }, { f, t -> progress.add(f to t) })
        // Each page's `next` is the next page's `after`, starting with none and stopping at the page that hands back none.
        assertEquals(listOf(null, "c1", "c2"), asked)
        assertEquals(listOf("a", "b", "c", "d", "e"), whole?.rows?.map { it.gedraId })
        assertEquals(5, whole?.numAvailable)
        assertNull(whole?.next)
        assertEquals(listOf(2 to 5, 4 to 5, 5 to 5), progress)
    }

    @Test
    fun aWalkNoLongerWantedStopsAndHandsBackNothing() = GlobalScope.promise {
        var asked = 0
        val whole = walkReportRun({ asked++; pageOf(listOf("a"), "more", 9) }, stillWanted = { asked < 2 })
        assertNull(whole)
        assertEquals(2, asked)
    }

    @Test
    fun aWalkThatFailsPartWayFailsWhole() = GlobalScope.promise {
        var failed: Throwable? = null
        try {
            walkReportRun({ after -> if (after == null) pageOf(listOf("a"), "c1", 2) else throw IllegalStateException("gone") })
        } catch (e: Throwable) {
            failed = e
        }
        assertEquals("gone", failed?.message)
        // And a cursor that never ends is cut off rather than walked forever.
        var endless: Throwable? = null
        try {
            walkReportRun({ pageOf(listOf("a"), "again", 1) }, maxPages = 3)
        } catch (e: Throwable) {
            endless = e
        }
        assertTrue(endless is IllegalStateException)
    }
}

/**
 * Pure-logic coverage (issue #161) for making a report on the Reports page (issue #1492): the paths read, which value a
 * column of several shows and how a totals report may combine it, what is wrong with a draft, and the request.
 */
class ReportCreateTest {
    private fun path(path: String, label: String, kind: ReportKind, multiValued: Boolean = false, keyed: Boolean = false) = buildMap {
        put(RRUN.path, path)
        put(RRUN.label, label)
        put(RRUN.kind, kind.name)
        put(RRUN.multiValued, multiValued)
        if (keyed) put(RCR.keyFields, listOf(mapOf(RCR.field to "year", RRUN.label to "Year", RRUN.kind to ReportKind.number.name)))
    }

    private val catalog = parseReportPathCatalog(
        mapOf(
            RCR.groups to listOf(
                mapOf(
                    RCR.source to ReportPathGroupKind.form.name, RRUN.label to "The form",
                    RCR.paths to listOf(
                        path("meta.createdAt", "Created", ReportKind.date),
                        path("user.email", "Owner's email", ReportKind.string),
                    ),
                ),
                mapOf(
                    RCR.source to ReportPathGroupKind.trait.name, RCR.sourceId to "talk", RRUN.label to "Talk",
                    RCR.paths to listOf(
                        path("form.talk.level", "Level", ReportKind.string),
                        path("form.talk.minutes", "Minutes", ReportKind.number),
                    ),
                ),
                mapOf(
                    RCR.source to ReportPathGroupKind.trait.name, RCR.sourceId to "yearly", RRUN.label to "Yearly",
                    RCR.paths to listOf(path("form.yearly[*].note", "Note", ReportKind.string, multiValued = true, keyed = true)),
                ),
                // A source this page does not know is left out rather than guessed at.
                mapOf(RCR.source to "somethingNew", RRUN.label to "New", RCR.paths to listOf(path("x.y", "Y", ReportKind.string))),
            ),
        ),
    )
    private val minutes = catalog.path("form.talk.minutes")!!
    private val note = catalog.path("form.yearly[*].note")!!

    @Test
    fun readsThePathsBySource() {
        assertEquals(listOf("The form", "Talk", "Yearly"), catalog.groups.map { it.label })
        assertEquals("Talk", catalog.groupOf("form.talk.level")?.label)
        assertEquals(listOf("year"), note.keyFields.map { it.field })
        assertEquals(ReportKind.number, note.keyFields.single().kind)
        assertTrue(note.multiValued)
        assertNull(catalog.path("x.y"))
    }

    @Test
    fun aColumnOfSeveralValuesSaysWhichAndThatDecidesHowItCombines() {
        assertEquals(emptyList(), valuePicks(minutes))
        assertEquals(ValuePick.entries, valuePicks(note))
        // A new column of several shows all of them: a list, which is totalled only by a count.
        val all = newReportColumn(1, note)
        assertEquals(ValuePick.all, all.pick)
        assertTrue(reportColumnIsList(all))
        assertEquals(listOf(ReportCombine.count), rollupChoices(all, note))
        assertNull(all.rollup)
        // How many is a number, which totals by default.
        val count = all.withPick(ValuePick.count, note)
        assertEquals(ReportKind.number, reportColumnKind(count, note))
        assertEquals(ReportCombine.sum, count.rollup)
        // One by its key starts with a blank value per key field.
        assertEquals(listOf(""), all.withPick(ValuePick.byKey, note).key)
        // A number totals by default, a date takes the latest, text nothing.
        assertEquals(ReportCombine.sum, newReportColumn(2, minutes).rollup)
        assertEquals(ReportCombine.max, newReportColumn(3, catalog.path("meta.createdAt")!!).rollup)
        assertNull(newReportColumn(4, catalog.path("form.talk.level")!!).rollup)
        assertEquals("Latest", rollupText(ReportCombine.max, ReportKind.date))
        assertEquals("Highest", rollupText(ReportCombine.max, ReportKind.number))
    }

    @Test
    fun aTotalsReportGroupsByItsFirstColumnWithOneValueUntilChosen() {
        val draft = ReportDraft(name = "Talks").withShape(ReportShape.totals)
            .withColumn(newReportColumn(1, note))
            .withColumn(newReportColumn(2, catalog.path("form.talk.level")!!))
            .withColumn(newReportColumn(3, minutes))
        assertEquals(2, draft.groupBy)
        assertEquals(listOf(2, 3), groupableColumns(draft).map { it.id })
        // Removed, the grouping goes with it; a column made a list is no longer grouped by.
        assertNull(draft.withoutColumn(2).groupBy)
        val noted = draft.copy(groupBy = 1).withColumnChanged(1) { it.withPick(ValuePick.first, note) }
        assertEquals(1, noted.groupBy)
        assertNull(noted.withColumnChanged(1) { it.withPick(ValuePick.all, note) }.groupBy)
        // A row per form groups by nothing.
        assertNull(draft.withShape(ReportShape.perForm).groupBy)
    }

    @Test
    fun saysWhatIsWrongWithADraft() {
        assertEquals(listOf("Give the report a name.", "Add at least one column."), reportDraftProblems(ReportDraft()))
        val twice = ReportDraft(name = "R").withColumn(newReportColumn(1, minutes)).withColumn(newReportColumn(2, minutes))
        assertEquals(listOf("Two columns are headed “Minutes”; give each its own."), reportDraftProblems(twice))
        val keyed = ReportDraft(name = "R").withColumn(newReportColumn(1, note).withPick(ValuePick.byKey, note))
        assertEquals(1, reportDraftProblems(keyed).size)
        assertTrue(reportDraftProblems(keyed).single().startsWith("Say which entry “Note” shows"))
        val listOnly = ReportDraft(name = "R").withShape(ReportShape.totals).withColumn(newReportColumn(1, note))
        assertEquals(listOf("Choose the column to group by."), reportDraftProblems(listOnly))
    }

    @Test
    fun theRequestSendsPathsAsOfferedWithTotalsOnlyForATotalsReport() {
        val draft = ReportDraft(name = " Talks by level ", description = " ")
            .withColumn(newReportColumn(1, catalog.path("form.talk.level")!!))
            .withColumn(newReportColumn(2, minutes).copy(mustHaveValue = true))
            .withColumn(newReportColumn(3, note).withPick(ValuePick.byKey, note).copy(key = listOf(" 2024 "), label = "2024"))
            .withShape(ReportShape.totals)
            .copy(history = true)
        val request = reportCreateRequest(null, draft, EDM.live)
        assertFalse(RRUN.client in request)
        assertFalse(RDEF.description in request)
        assertEquals("Talks by level", request[RDEF.label])
        assertEquals(true, request[RDEF.history])
        assertEquals(EDM.live, request[CPY.mode])
        val columns = request[RDEF.columns].toJsonListOfMaps()
        // The grouped-by column carries no rollup; a key is sent for the server to write into the path.
        assertEquals(mapOf(RDEF.label to "Level", RDEF.path to "form.talk.level", RCR.groupedBy to true), columns[0])
        assertEquals(
            mapOf(RDEF.label to "Minutes", RDEF.path to "form.talk.minutes", RDEF.rollup to "sum", RCR.mustHaveValue to true),
            columns[1],
        )
        assertEquals(mapOf(RDEF.label to "2024", RDEF.path to "form.yearly[*].note", RCR.key to listOf("2024")), columns[2])
        // A row per form sends no grouping, rollups or history.
        val perForm = reportCreateRequest("acme", draft.withShape(ReportShape.perForm), EDM.draft)
        assertEquals("acme", perForm[RRUN.client])
        assertFalse(RDEF.history in perForm)
        assertTrue(perForm[RDEF.columns].toJsonListOfMaps().none { RDEF.rollup in it || RCR.groupedBy in it })
    }

    @Test
    fun aTotalsReportsChartMeasuresTheFormsOrANumericRollupAndMarksItsColumn() {
        val created = newReportColumn(3, catalog.path("meta.createdAt")!!)
        val draft = ReportDraft(name = "Talks")
            .withShape(ReportShape.totals)
            .withColumn(newReportColumn(1, catalog.path("form.talk.level")!!))
            .withColumn(newReportColumn(2, minutes))
            .withColumn(created)
        // The forms, then the minutes' total; not the grouped-by level, nor the latest date, which is not a number.
        assertEquals(ReportCombine.max, created.rollup)
        val measures = reportDraftMeasures(draft, catalog)
        assertEquals(listOf(RRUN.count to "Forms", "2" to "Minutes (Total)"), measures.map { it.key to it.label })
        assertTrue(measures.all { it.additive })
        // No chart asked for sends none.
        assertFalse(RDEF.chart in reportCreateRequest(null, draft, EDM.live, catalog))

        val pie = draft.copy(chart = ReportChartKind.pie, chartMeasure = 2)
        val request = reportCreateRequest(null, pie, EDM.live, catalog)
        assertEquals(ReportChartKind.pie.name, request[RDEF.chart])
        assertEquals(listOf(null, true, null), request[RDEF.columns].toJsonListOfMaps().map { it[RCR.charted] })
        assertEquals(emptyList(), reportDraftProblems(pie, catalog))

        // An average adds up to nothing: offered for a bar, a problem for a pie.
        val averaged = pie.withColumnChanged(2) { it.copy(rollup = ReportCombine.avg) }
        assertEquals("Minutes (Average)", reportDraftMeasure(averaged, catalog).label)
        assertFalse(reportDraftMeasure(averaged, catalog).additive)
        assertEquals(
            listOf("A pie shows shares of a whole: chart the forms, or a column combined by its total or how many."),
            reportDraftProblems(averaged, catalog),
        )
        assertEquals(emptyList(), reportDraftProblems(averaged.copy(chart = ReportChartKind.bar), catalog))

        // A measured column that can no longer be measured -- removed, or grouped by -- leaves the chart counting forms,
        // and no column is marked.
        for (gone in listOf(pie.withoutColumn(2), pie.copy(groupBy = 2))) {
            assertEquals(RRUN.count, reportDraftMeasure(gone, catalog).key)
            assertTrue(reportCreateRequest(null, gone, EDM.live, catalog)[RDEF.columns].toJsonListOfMaps().none { RCR.charted in it })
        }
        // A row per form has no groups to chart, and sends none.
        val perForm = reportCreateRequest(null, pie.withShape(ReportShape.perForm), EDM.live, catalog)
        assertFalse(RDEF.chart in perForm)
        assertTrue(perForm[RDEF.columns].toJsonListOfMaps().none { RCR.charted in it })
        assertEquals(listOf(RRUN.count), reportDraftMeasures(pie.withShape(ReportShape.perForm), catalog).map { it.key })
    }

    @Test
    fun readsTheOfferFromTheListing() {
        val sandboxed = parseReportCreateOffer(mapOf(RCR.canCreate to false, RCR.createFrom to "acme:sandbox"))
        assertFalse(sandboxed.canCreate)
        assertEquals("acme:sandbox", sandboxed.createFrom)
        assertTrue(parseReportCreateOffer(mapOf(RCR.canCreate to true, RCR.createsDraft to true)).createsDraft)
    }

    @Test
    fun offersToRemoveOnlyAReportTheClientStoresWhereItIsEdited() {
        fun report(stored: Boolean, template: String? = null) =
            ReportInfo("talks", "Talks", null, emptyList(), emptyList(), emptyList(), "reports", template, stored = stored)
        val here = ReportCreateOffer(canCreate = true, createsDraft = false, createFrom = null)
        assertTrue(reportRemovable(report(stored = true), here))
        assertFalse(reportRemovable(report(stored = false), here))
        assertFalse(reportRemovable(report(stored = true, template = "base"), here))
        val elsewhere = ReportCreateOffer(canCreate = false, createsDraft = false, createFrom = "acme:sandbox")
        assertFalse(reportRemovable(report(stored = true), elsewhere))
        assertEquals(
            "Remove the report “Talks” from acme's sandbox? Its stored snapshots are kept.",
            reportRemoveQuestion(report(stored = true), "acme:sandbox"),
        )
        assertEquals(mapOf(RRUN.reportId to "talks", CPY.mode to EDM.draft), reportRemoveRequest(null, "talks", EDM.draft))
    }
}

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

    private val count = ReportMeasure(RRUN.count, "Forms", additive = true)

    @Test
    fun aGroupIsNamedByTheNewestSnapshotWhoseColumnCarriesItsChoices() {
        // Issue #1438: a snapshot taken before columns carried choices names nothing by its label, and must not name the
        // group by its stored value for the life of the series.
        val track = ReportColumnInfo("track", "Track", "form.x.track", ReportKind.string.name, ReportCombine.first.name, null, false)
        val labeled = ReportColumnInfo(
            "track", "Track", "form.x.track", ReportKind.string.name, ReportCombine.first.name, null, false,
            choices = listOf(SchOption("kotlin", "Kotlin")),
        )
        fun taken(id: Long, at: String, column: ReportColumnInfo) = ReportSnapshot(
            id, at, "scheduled", null, listOf("track"), listOf(column), listOf(ReportRunRow(null, mapOf("track" to "kotlin"), 3, emptyMap())),
            3, 0, truncated = false, sameDefinition = true,
        )
        val series = historySeries(listOf(taken(2, "2026-10-02T12:30:00Z", labeled), taken(1, "2026-10-01T12:30:00Z", track)), count)
        assertEquals(listOf("Kotlin"), series.groups.map { it.label })
    }

    @Test
    fun aSnapshotParsesWithItsRowsAndAPageWithItsCursor() {
        val raw = mapOf(
            RHIS.snapshotId to 7, RHIS.takenAt to "2026-10-05T12:30:00.000Z", RHIS.trigger to ReportSnapshotTrigger.simulated.name,
            RRUN.groupBy to listOf("year"), RRUN.columns to listOf(mapOf(RRUN.columnId to "year", RRUN.kind to "number")),
            RHIS.rows to listOf(mapOf(RRUN.group to mapOf("year" to 2024), RRUN.count to 3, RRUN.values to mapOf("total" to 12.5))),
            RRUN.scanned to 9, RHIS.truncated to true, RHIS.sameDefinition to false,
        )
        val page = parseHistoryPage(mapOf(EP.items to listOf(raw, mapOf("no" to "id")), EP.numAvailable to 4, EP.next to "c1"))
        val s = page.snapshots.single()
        assertEquals(7L, s.snapshotId)
        assertEquals("2026-10-05", s.day)
        // A day is the official one (UTC-8, issue #1115), not UTC's: 03:30 UTC is the evening before.
        assertEquals("2026-10-04", snapshot(8, "2026-10-05T03:30:00.000Z").day)
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
        val metrics = reportMeasures(columns, listOf("year"))
        // The count; the sum and the average of a number; a count of text. Not the grouped-by column, nor a date's maximum.
        assertEquals(listOf(RRUN.count, "total", "price", "items"), metrics.map { it.key })
        assertEquals(listOf(true, true, false, true), metrics.map { it.additive })
        assertEquals("Total (sum)", metrics[1].label)
        assertEquals(12.5, measureValue(row(2024, 3, 12.5), metrics[1]))
        assertEquals(3.0, measureValue(row(2024, 3, 12.5), metrics[0]))
        assertNull(measureValue(row(2024, 3), metrics[1]))
    }

    @Test
    fun aDayIsItsLatestSnapshotAndASeriesKeepsGroupsInTheOrderFirstSeen() {
        val snapshots = listOf(
            snapshot(4, "2026-10-03T20:00:00Z", row(2023, 5), row(2024, 2), row(null, 1)),
            // The same day's earlier one: superseded.
            snapshot(3, "2026-10-03T12:30:00Z", row(2023, 4)),
            snapshot(2, "2026-10-02T12:30:00Z", row(2024, 9), row(2023, 1)),
            // Taken under another definition of the report: not charted, and counted.
            snapshot(1, "2026-10-01T12:30:00Z", row(2020, 7), same = false),
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
        val byTotal = historySeries(snapshots, ReportMeasure("total", "Total (sum)", true))
        assertEquals(series.groups.map { it.key }, byTotal.groups.map { it.key })
    }

    @Test
    fun groupsPastTheLimitFoldIntoOtherOnlyWhenTheMetricAddsUp() {
        val rows = (1..5).map { row(2020 + it, count = it, total = it * 10.0) }
        val one = listOf(snapshot(1, "2026-10-02T12:30:00Z", *rows.toTypedArray()))
        val folded = historySeries(one, count, maxGroups = 3)
        // The three with the most forms on the latest day, in the order first seen; the other two summed.
        assertEquals(listOf("2023", "2024", "2025"), folded.groups.map { it.label })
        assertEquals(2, folded.otherCount)
        assertEquals(listOf<Double?>(3.0), folded.otherValues)
        assertTrue(folded.otherDrawn)
        // An average of groups is not their sum: nothing is drawn for them.
        val average = historySeries(one, ReportMeasure("price", "Price (avg)", additive = false), maxGroups = 3)
        assertEquals(2, average.otherCount)
        assertNull(average.otherValues)
        // A report grouping by nothing has the one group, named for what it is.
        val total = ReportSnapshot(9, "2026-10-02T12:30:00Z", "manual", null, emptyList(), columns, listOf(ReportRunRow(null, emptyMap(), 12, emptyMap())), 12, 0, false, true)
        assertEquals(listOf(reportAllFormsLabel), historySeries(listOf(total), count).groups.map { it.label })
    }

    @Test
    fun theChartLaysOutABarAGroupADayOnCleanTicks() {
        val snapshots = listOf(
            snapshot(1, "2026-10-02T12:30:00Z", row(2023, 26), row(2024, 13)),
            snapshot(2, "2026-10-03T12:30:00Z", row(2023, 124), row(2024, 62)),
        )
        val layout = historyChartLayout(historySeries(snapshots, count), width = 760.0, plotHeight = 240.0)
        assertEquals(0, layout.firstDay)
        assertEquals(listOf("Oct 2", "Oct 3"), layout.slots.map { it.label })
        assertEquals(4, layout.bars.size)
        // Ticks are clean numbers ending past the largest value; bars are never thicker than the cap.
        assertEquals(listOf(0.0, 50.0, 100.0, 150.0), layout.ticks.map { it.value })
        assertTrue(layout.bars.all { it.width == CHT.barMax })
        // Heights are in proportion, from the baseline; neighbours in a day are a gap apart.
        val byValue = layout.bars.associateBy { it.value }
        assertTrue(abs(byValue.getValue(124.0).height / byValue.getValue(62.0).height - 2.0) < 1e-9)
        assertTrue(layout.bars.all { abs(it.y + it.height - layout.baselineY) < 1e-9 })
        val firstDay = layout.bars.filter { it.dayIndex == 0 }.sortedBy { it.x }
        assertTrue(abs(firstDay[1].x - (firstDay[0].x + firstDay[0].width + CHT.barGap)) < 1e-9)
        // The label band is part of the chart's height, so day labels are never clipped.
        assertEquals(layout.plotBottom + CHT.axisBand, layout.height)
        // Rounded at the data end, square at the baseline.
        assertTrue(firstDay[0].path.contains("Q") && firstDay[0].path.endsWith("Z"))
    }

    @Test
    fun onlyTheLatestDaysThatFitAreDrawnAndAMissingValueDrawsNothing() {
        val many = (1..60).map { d -> snapshot(d.toLong(), "2026-${if (d <= 30) "09" else "10"}-${((d - 1) % 30 + 1).toString().padStart(2, '0')}T12:30:00Z", row(2023, d), row(2024, d)) }
        val layout = historyChartLayout(historySeries(many, count), width = 760.0)
        assertTrue(layout.firstDay > 0)
        assertEquals(60 - layout.firstDay, layout.slots.size)
        assertEquals(layout.firstDay, historyDaysNotDrawn(layout))
        assertTrue(layout.bars.all { it.width >= CHT.barMin })
        // Labels are thinned to fit, and the latest day keeps its own.
        assertTrue(layout.slots.count { it.label != null } < layout.slots.size)
        assertEquals("Oct 30", layout.slots.last().label)
        // A group with no value on a day has no bar there, and a negative value hangs below the baseline.
        val gappy = listOf(snapshot(1, "2026-10-02T12:30:00Z", row(2023, 1, -40.0), row(2024, 1)))
        val sums = historyChartLayout(historySeries(gappy, ReportMeasure("total", "Total (sum)", true)))
        assertEquals(1, sums.bars.size)
        assertEquals(sums.baselineY, sums.bars.single().y)
        assertTrue(sums.ticks.first().value < 0)
    }

    @Test
    fun theKeyboardEntersAtTheLatestDayAndTheArrowsStepBarByBar() {
        val snapshots = listOf(
            snapshot(1, "2026-10-02T12:30:00Z", row(2023, 26), row(2024, 13)),
            snapshot(2, "2026-10-03T12:30:00Z", row(2023, 124), row(2024, 62)),
        )
        val bars = historyChartLayout(historySeries(snapshots, count)).bars
        // One Tab stop: the latest day's first bar.
        assertEquals(2, historyBarEntry(bars))
        assertEquals(1, bars[historyBarEntry(bars)].dayIndex)
        assertEquals(-1, historyBarEntry(emptyList()))
        // The arrows go to the neighbour, across days; at either end the key is the browser's again.
        assertEquals(1, chartStep(2, bars.size, HKEY.left))
        assertEquals(3, chartStep(2, bars.size, HKEY.right))
        assertNull(chartStep(0, bars.size, HKEY.left))
        assertNull(chartStep(3, bars.size, HKEY.right))
        assertEquals(0, chartStep(2, bars.size, HKEY.home))
        assertEquals(3, chartStep(2, bars.size, HKEY.end))
        assertNull(chartStep(0, bars.size, HKEY.home))
        assertNull(chartStep(3, bars.size, HKEY.end))
        assertNull(chartStep(2, bars.size, "Tab"))
        assertNull(chartStep(0, 0, HKEY.end))
    }

    @Test
    fun numbersTicksAndLinksReadAsTheChartWritesThem() {
        assertEquals("1,480", chartNumberText(1480.0))
        assertEquals("-12,345.50", chartNumberText(-12345.5))
        assertEquals("62", chartNumberText(62.0))
        assertEquals(50.0, niceTickStep(124.0))
        assertEquals(10.0, niceTickStep(26.0))
        assertEquals(0.1, niceTickStep(0.4))
        // There is no half a form.
        assertEquals(1.0, niceTickStep(2.0, wholeOnly = true))
        // The chart follows its pane between its narrowest and widest, in whole pixels.
        assertEquals(486.0, chartWidthFor(486.7))
        assertEquals(CHT.maxWidth, chartWidthFor(1400.0))
        assertEquals(CHT.minWidth, chartWidthFor(120.0))
        assertEquals("Oct 2", historyDayLabel("2026-10-02"))
        assertEquals("not a day", historyDayLabel("not a day"))
        assertEquals("2026-13-02", historyDayLabel("2026-13-02"))
        assertEquals("2026-10-xx", historyDayLabel("2026-10-xx"))
        assertEquals(true, reportViewIsHistory(HMENU.reportViewHistory))
        assertEquals(false, reportViewIsHistory(reportModeGrouped))
        assertEquals(null, reportModeOf(HMENU.reportViewHistory))
        assertEquals("/reports/expensesByYear?${HP.reportView}=${HMENU.reportViewHistory}", reportsHref(null, "expensesByYear", history = true))
        assertEquals("No snapshots yet.", historySummaryText(emptyList()))
        assertTrue(historySummaryText(listOf(snapshot(1, "2026-10-02T12:30:00Z"), snapshot(2, "2026-10-03T12:30:00Z", trigger = "manual"))).startsWith("2 snapshots over 2 days; the latest "))
    }
}

/** A grouped run's bar chart and pie (issue #1772), their pure half: which chart is in force, the groups drawn, and the geometry. */
@OptIn(DelicateCoroutinesApi::class)
class ReportChartTest {
    private fun column(id: String, kind: ReportKind = ReportKind.string, rollup: ReportCombine? = null) =
        ReportColumnInfo(id, id.replaceFirstChar { it.uppercase() }, "form.x.$id", kind.name, ReportCombine.first.name, rollup?.name, false)

    private val columns = listOf(
        column("auditor"),
        column("total", ReportKind.number, ReportCombine.sum),
        column("price", ReportKind.number, ReportCombine.avg),
    )

    private fun report(chart: ReportChart? = null, groupBy: List<String> = listOf("auditor")) = ReportInfo(
        "audits", "Audits", null, columns, groupBy, emptyList(), "main", null, chart = chart,
    )

    private val grouped = ReportRunSetup(ReportMode.aggregate)
    private val summary = ReportRunSummary(ReportMode.aggregate, listOf("auditor"), emptyList(), columns, 0, 0)

    private fun row(auditor: String?, count: Int, total: Double? = null, price: Double? = null) =
        ReportRunRow(null, mapOf("auditor" to auditor), count, mapOf("total" to total, "price" to price))

    private fun measure(key: String) = reportMeasures(columns, listOf("auditor")).first { it.key == key }

    @Test
    fun theListingCarriesAReportsDeclaredChart() {
        val listing = parseReportListing(
            mapOf(
                EP.items to listOf(
                    mapOf(RRUN.reportId to "a", RRUN.chart to mapOf(RDEF.kind to "pie")),
                    mapOf(RRUN.reportId to "b", RRUN.chart to mapOf(RDEF.kind to "bar", RDEF.measure to "total")),
                    mapOf(RRUN.reportId to "c"),
                    // A kind this page does not draw is no chart, rather than a fault.
                    mapOf(RRUN.reportId to "d", RRUN.chart to mapOf(RDEF.kind to "donut")),
                ),
            ),
        )
        val charts = listing.reports.associate { it.reportId to it.chart }
        assertEquals(ReportChartKind.pie, charts["a"]?.kind)
        assertNull(charts["a"]?.measure)
        assertEquals("total", charts["b"]?.measure)
        assertNull(charts["c"])
        assertNull(charts["d"])
    }

    @Test
    fun theAddressDecidesTheChartAndTheReportsOwnFillsInWhereItIsSilent() {
        val pie = report(ReportChart(ReportChartKind.pie))
        // Only a grouped run has groups to draw.
        assertNull(reportChartInForce(null, null, pie, ReportRunSetup(ReportMode.detail)))
        // Nothing in the address: the report's own.
        reportChartInForce(null, null, pie, grouped)!!.let {
            assertEquals(ReportChartKind.pie, it.kind)
            assertEquals(RRUN.count, it.measure.key)
        }
        // None declared: the table alone.
        assertNull(reportChartInForce(null, null, report(), grouped)!!.kind)
        // The address's word wins, the table included.
        assertNull(reportChartInForce(reportChartTable, null, pie, grouped)!!.kind)
        reportChartInForce("bar", "total", pie, grouped)!!.let {
            assertEquals(ReportChartKind.bar, it.kind)
            assertEquals("total", it.measure.key)
        }
        // Grouped otherwise than the report groups: its own chart is not assumed to fit.
        assertNull(reportChartInForce(null, null, pie, ReportRunSetup(ReportMode.aggregate, listOf("price")))!!.kind)
        // A measure the run does not offer -- here one the user groups by -- falls back to the count.
        val byTotal = ReportRunSetup(ReportMode.aggregate, listOf("total"))
        assertEquals(RRUN.count, reportChartInForce("bar", "total", pie, byTotal)!!.measure.key)
        // A pie of an average is drawn as bars: its slices would be shares of no whole.
        assertEquals(ReportChartKind.bar, reportChartInForce("pie", "price", pie, grouped)!!.kind)
        // A choice is written whole, so it overrides the report's own measure too.
        assertEquals("pie" to RRUN.count, reportChartParams(ReportChartChoice(ReportChartKind.pie, measure(RRUN.count))))
        assertEquals(reportChartTable to "total", reportChartParams(ReportChartChoice(null, measure("total"))))
        assertEquals(
            listOf(
                HP.page to HMENU.pageReports, HP.report to "audits", HP.reportView to "grouped", HP.reportChart to "bar",
                HP.reportMeasure to "total",
            ),
            reportsRouteParams(null, "audits", "grouped", "bar", "total"),
        )
    }

    @Test
    fun aPieKeepsTheLargestInRunOrderFoldsTheRestAndLeavesOutWhatIsNoShare() {
        val rows = listOf(row("none", 0), row("neg", 3, total = -5.0), row(null, 2, total = null)) +
            (1..9).map { row("a$it", it, total = it * 10.0) }
        val data = reportChartData(rows, summary, ReportChartKind.pie, measure("total"))
        // No value, zero, or below zero is no share: three groups left out.
        assertEquals(3, data.leftOut)
        assertEquals(12, data.read)
        // Nine left, past eight slices: the seven largest kept, in the run's order, and two folded into "Other".
        assertEquals(listOf("a3", "a4", "a5", "a6", "a7", "a8", "a9", "Other (2 groups)"), data.entries.map { it.label })
        assertEquals(30.0, data.entries.last().value)
        assertEquals((0..6).toList() + chartOtherIndex, data.entries.map { it.colorIndex })
        assertEquals(2, data.otherCount)
        assertEquals(0, data.notShown)
    }

    @Test
    fun aBarChartIsOneColourAndLeavesWhatCannotBeAddedToTheTable() {
        val rows = (1..22).map { row("a$it", it, price = it.toDouble()) }
        val data = reportChartData(rows, summary, ReportChartKind.bar, measure("price"))
        assertEquals(20, data.entries.size)
        assertTrue(data.entries.none { it.isOther })
        assertEquals(2, data.notShown)
        assertTrue(data.entries.all { it.colorIndex == 0 })
        assertEquals("a3", data.entries.first().label)
        assertTrue(reportChartNotes(data, 22).single().startsWith("2 more groups not shown"))
        // The count adds up: past twenty, "Other" is the last bar.
        val counted = reportChartData(rows, summary, ReportChartKind.bar, measure(RRUN.count))
        assertEquals(chartOtherLabel(3), counted.entries.last().label)
        assertEquals(6.0, counted.entries.last().value)
        // A run read only in part says so.
        assertEquals("The chart reads the first 22 of 3,000 groups.", reportChartNotes(counted, 3000).first())
    }

    @Test
    fun barsAreRowsFromZeroWithTheirNamesAboveAndCleanTicks() {
        val data = reportChartData(
            listOf(row("a", 1, total = 150.0), row("b", 1, total = -50.0), row("c", 1, total = 40.0)), summary,
            ReportChartKind.bar, measure("total"),
        )
        val layout = barChartLayout(data, width = 476.0)
        assertEquals(3, layout.bars.size)
        assertEquals(listOf(-50.0, 0.0, 50.0, 100.0, 150.0), layout.ticks.map { it.value })
        assertEquals(400.0, layout.plotRight)
        assertEquals(layout.ticks[1].at, layout.baselineX)
        // A row a bar, each under the last; a negative bar grows left from zero, a positive one right.
        assertEquals(listOf(0.0, 1.0, 2.0).map { HBAR.marginTop + it * HBAR.rowHeight }, layout.bars.map { it.rowTop })
        assertTrue(abs(layout.bars[0].x - layout.baselineX) < 1e-9)
        assertTrue(abs(layout.bars[1].x + layout.bars[1].width - layout.baselineX) < 1e-9)
        assertEquals(layout.plotBottom + HBAR.axisBand, layout.height)
        // Rows need less room than days side by side: in a phone's 271-pixel card the bars are laid out at 271, not 320.
        assertEquals(271.0, chartWidthFor(271.6, chartRowsMinWidth))
        assertEquals(CHT.minWidth, chartWidthFor(271.6))
        // A name too long for the plot is cut with an ellipsis; a short one is left whole.
        assertEquals("Short", chartFitText("Short", 100.0))
        assertEquals("abcdefghi…", chartFitText("abcdefghijklmnopqrstuvwxyz", 65.0))
    }

    @Test
    fun aPieRunsClockwiseFromTwelveAndOneSliceIsTheWholeCircle() {
        val data = reportChartData(listOf(row("a", 1), row("b", 3)), summary, ReportChartKind.pie, measure(RRUN.count))
        val layout = pieChartLayout(data, width = 600.0)
        assertEquals(pieMaxDiameter / 2, layout.r)
        assertEquals(listOf(0.25, 0.75), layout.slices.map { it.share })
        // The first slice starts at twelve o'clock and runs a quarter turn: to three o'clock.
        val (x, y) = pointOnCircle(layout.cx, layout.cy, layout.r, PI / 2)
        val fromTwelve = "M${layout.cx},${layout.cy} L${layout.cx},${layout.cy - layout.r} A"
        assertTrue(layout.slices[0].path.startsWith(fromTwelve), layout.slices[0].path)
        assertTrue(abs(x - (layout.cx + layout.r)) < 1e-9 && abs(y - layout.cy) < 1e-9)
        // The larger slice takes the long way round.
        assertTrue(" 0 1 1 " in layout.slices[1].path, layout.slices[1].path)
        // One group is the whole: an arc from a point to itself draws nothing, so it is the circle in two halves.
        val whole = pieChartLayout(reportChartData(listOf(row("a", 4)), summary, ReportChartKind.pie, measure(RRUN.count)))
        assertEquals(2, whole.slices.single().path.count { it == 'A' })
        assertEquals(listOf("0%", "<1%", "25%", "100%"), listOf(0.0, 0.004, 0.25, 1.0).map { chartShareText(it) })
    }

    @Test
    fun theArrowsStepDownARowOfBarsAndLeaveUpAndDownToTheBrowserOnADayChart() {
        assertEquals(1, chartStep(0, 3, HKEY.down, vertical = true))
        assertEquals(1, chartStep(2, 3, HKEY.up, vertical = true))
        assertNull(chartStep(2, 3, HKEY.down, vertical = true))
        assertNull(chartStep(0, 3, HKEY.down))
        assertEquals(1, chartStep(0, 3, HKEY.right))
    }

    @Test
    fun aNameIsCutByItsLettersWidthsSoCapitalsStayInsideThePlot() {
        fun width(text: String) = text.sumOf { chartCharWidth(it) }
        // Capitals run wider than lower case: the same count of letters is cut sooner.
        val capitals = chartFitText("MAINTENANCE WORKSHOP NORTH WING", 120.0)
        val lower = chartFitText("maintenance workshop north wing", 120.0)
        assertTrue(capitals.endsWith(chartEllipsis) && width(capitals) <= 120.0, capitals)
        assertTrue(capitals.length < lower.length, "$capitals / $lower")
        // A name that fits is left whole, and the chart asks for every group it reads in one request.
        assertEquals("Lee", chartFitText("Lee", 30.0))
        assertEquals(chartMaxGroups, reportRunQuery("audits", null, ReportRunSetup(ReportMode.aggregate), null, chartMaxGroups)[EP.limit])
    }
}
