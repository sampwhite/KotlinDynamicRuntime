package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.gedra.GCI
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.ReportCombine
import com.dynamicruntime.common.gedra.report.ReportKind
import com.dynamicruntime.common.gedra.report.ReportMode
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import kotlin.math.floor
import kotlin.math.roundToLong

/*
 * The Reports page's data (issue #1007): what the report endpoints (#981) answer with, parsed, and everything the
 * page derives from it that needs no React -- the run's query, the table's columns, a cell's text, the paging line.
 * Pure, and covered under `jsNodeTest` (`ReportsPageTest`); the components are in `ReportsPage.kt`.
 */

/** One column of a report as the listing and a run's summary describe it: what it reads and how it was bound. */
class ReportColumnInfo(
    val columnId: String,
    val label: String,
    val path: String,
    /** A [ReportKind] name: how the column's values read. */
    val kind: String,
    /** A [ReportCombine] name: how a form's several values were made one. */
    val combine: String,
    /** A [ReportCombine] name when the column is rolled up across a group's forms; null when it is not. */
    val rollup: String?,
    val multiValued: Boolean,
) {
    /** Whether the column's value is a list, which cannot be grouped by. */
    val yieldsList: Boolean get() = combine == ReportCombine.list.name || combine == ReportCombine.distinct.name
}

/** One named report a client may run. */
class ReportInfo(
    val reportId: String,
    val label: String,
    val description: String?,
    val columns: List<ReportColumnInfo>,
    /** The columns a grouped run groups by unless told otherwise. */
    val groupBy: List<String>,
    /** The columns a form must have a value for unless told otherwise. */
    val excludeEmpty: List<String>,
    val configName: String,
    /** The template the report was copied from, for a client built on one. */
    val template: String?,
    /** Whether the nightly job stores snapshots of the report (issue #1033). */
    val history: Boolean = false,
) {
    /** How the report opens: grouped when it declares what to group by, otherwise one row per form. */
    val defaultMode: ReportMode get() = if (groupBy.isEmpty()) ReportMode.detail else ReportMode.aggregate
}

/** A client's reports, and the report problems that cost it one -- so a report that is missing is explained. */
class ReportListing(val reports: List<ReportInfo>, val issues: List<String>)

fun parseReportColumns(raw: Any?): List<ReportColumnInfo> = raw.toJsonListOfMaps().mapNotNull { c ->
    val id = c[RRUN.columnId].toOptStr() ?: return@mapNotNull null
    ReportColumnInfo(
        columnId = id,
        label = c[RRUN.label].toOptStr() ?: id,
        path = c[RRUN.path].toOptStr().orEmpty(),
        kind = c[RRUN.kind].toOptStr() ?: ReportKind.string.name,
        combine = c[RRUN.combine].toOptStr() ?: ReportCombine.first.name,
        rollup = c[RRUN.rollup].toOptStr(),
        multiValued = c[RRUN.multiValued] == true,
    )
}

/** The reports listing's envelope ([UADEP.reports]) as a [ReportListing]; a row with no id is dropped. */
fun parseReportListing(envelope: Map<String, Any?>): ReportListing = ReportListing(
    reports = envelope[EP.items].toJsonListOfMaps().mapNotNull { r ->
        val id = r[RRUN.reportId].toOptStr() ?: return@mapNotNull null
        ReportInfo(
            reportId = id,
            label = r[RRUN.label].toOptStr() ?: id,
            description = r[RRUN.description].toOptStr(),
            columns = parseReportColumns(r[RRUN.columns]),
            groupBy = r[RRUN.groupBy].toJsonListOfStrings(),
            excludeEmpty = r[RRUN.excludeEmpty].toJsonListOfStrings(),
            configName = r[RRUN.configName].toOptStr().orEmpty(),
            template = r[RRUN.template].toOptStr(),
            history = r[RRUN.history] == true,
        )
    },
    issues = envelope[EP.summary].toJsonMapOrEmpty()[RRUN.issues].toJsonListOfMaps().mapNotNull { issue ->
        issue[GCI.message].toOptStr()?.let { "$it ${issue[GCI.degradedTo].toOptStr().orEmpty()}".trim() }
    },
)

/** What a run was: its mode, what it grouped by and required, its columns, and how many forms it read and left out. */
class ReportRunSummary(
    val mode: ReportMode,
    val groupBy: List<String>,
    val excludeEmpty: List<String>,
    val columns: List<ReportColumnInfo>,
    val scanned: Int,
    val excluded: Int,
)

/**
 * One row of a run: a form ([gedraId], with every column in [values]) in a detail run; a group ([group]'s grouped-by
 * values, [count] forms, the rolled-up columns in [values]) in a grouped one.
 */
class ReportRunRow(val gedraId: String?, val group: Map<String, Any?>, val count: Int?, val values: Map<String, Any?>)

/** One page of a run. [next] is the cursor of the page after it, null on the last. */
class ReportRunPage(val rows: List<ReportRunRow>, val numAvailable: Int, val next: String?, val summary: ReportRunSummary)

/** One row of a run off the wire: a run's page holds them, and so does a stored snapshot (issue #1037). */
fun parseRunRow(row: Map<String, Any?>): ReportRunRow = ReportRunRow(
    gedraId = row[RRUN.gedraId].toOptStr(),
    group = row[RRUN.group].toJsonMapOrEmpty(),
    count = (row[RRUN.count] as? Number)?.toInt(),
    values = row[RRUN.values].toJsonMapOrEmpty(),
)

/** The run endpoint's envelope ([UADEP.reportRun]) as a [ReportRunPage]. */
fun parseRunPage(envelope: Map<String, Any?>): ReportRunPage {
    val summary = envelope[EP.summary].toJsonMapOrEmpty()
    return ReportRunPage(
        rows = envelope[EP.items].toJsonListOfMaps().map { parseRunRow(it) },
        numAvailable = (envelope[EP.numAvailable] as? Number)?.toInt() ?: 0,
        next = envelope[EP.next].toOptStr(),
        summary = ReportRunSummary(
            mode = if (summary[RRUN.mode].toOptStr() == ReportMode.aggregate.name) ReportMode.aggregate else ReportMode.detail,
            groupBy = summary[RRUN.groupBy].toJsonListOfStrings(),
            excludeEmpty = summary[RRUN.excludeEmpty].toJsonListOfStrings(),
            columns = parseReportColumns(summary[RRUN.columns]),
            scanned = (summary[RRUN.scanned] as? Number)?.toInt() ?: 0,
            excluded = (summary[RRUN.excluded] as? Number)?.toInt() ?: 0,
        ),
    )
}

/**
 * How a run is set up on the page: its [mode], and the columns to group by and to require. A null list means "the
 * report's own", which is what the endpoint applies when the input is absent -- so a setup the user never touched
 * sends nothing and follows the report as its configuration changes.
 */
class ReportRunSetup(val mode: ReportMode, val groupBy: List<String>? = null, val excludeEmpty: List<String>? = null) {
    /** What tells one run's pages from another's: a cursor belongs to exactly one of these. */
    val signature: String get() = "${mode.name}|${groupBy?.joinToString(",") ?: "-"}|${excludeEmpty?.joinToString(",") ?: "-"}"
}

/**
 * The setup in force for the open report. The **mode is the URL's**: the hash's `view` when it names one, else the way
 * the report declares it opens ([defaultMode], null while the report is not yet known) -- so the page always shows
 * what its address says, and a link reproduces it. The lists are the session's: what the user set on this report
 * ([groupBy], [excludeEmpty]), null until they did.
 */
fun reportSetupInForce(hashView: String?, defaultMode: ReportMode?, groupBy: List<String>?, excludeEmpty: List<String>?): ReportRunSetup =
    ReportRunSetup(reportModeOf(hashView) ?: defaultMode ?: ReportMode.detail, groupBy, excludeEmpty)

/**
 * What one walk of a run is: the client, the report and the setup. A cursor, and the rows on screen, belong to
 * exactly one of these -- so rows fetched for one are never shown under another, and a cursor is never sent to one.
 */
fun reportWalkKey(client: String?, reportId: String?, setup: ReportRunSetup): String =
    "${client.orEmpty()}|${reportId.orEmpty()}|${setup.signature}"

/** The page size a run is shown at: the forms listing's. */
const val reportPageSize = 25

/**
 * The run endpoint's query for [reportId] under [setup]. [client] is sent only when given -- an `allClients`
 * administrator's choice; a client's own administrator sends none. `groupBy` rides only on a grouped run, which is
 * the only kind that takes one. A list the user **emptied** is sent as an empty list, not left out: absent means
 * "the report's own", and empty means none.
 */
fun reportRunQuery(reportId: String, client: String?, setup: ReportRunSetup, after: String?, limit: Int = reportPageSize): Map<String, Any?> =
    buildMap {
        put(RRUN.reportId, reportId)
        client?.let { put(RRUN.client, it) }
        if (setup.mode == ReportMode.aggregate) {
            put(RRUN.aggregate, true)
            setup.groupBy?.let { put(RRUN.groupBy, it) }
        }
        setup.excludeEmpty?.let { put(RRUN.excludeEmpty, it) }
        after?.let { put(EP.after, it) }
        put(EP.limit, limit)
    }

/** Where a table column's value is read from in a row. */
@Suppress("EnumEntryName")
enum class ReportCellSource {
    /** A column's value: the form's, or in a grouped run the group's rollup. */
    value,

    /** A grouped-by column's value: what the group is. */
    group,

    /** How many forms the group holds. */
    count,
}

/** One column of the table a run is shown as. */
class ReportTableColumn(val key: String, val label: String, val kind: String, val source: ReportCellSource) {
    val numeric: Boolean get() = kind == ReportKind.number.name
}

/** The heading of the count column of a grouped run. */
const val reportCountLabel = "Forms"

/**
 * The table's columns for a run. A detail run has the report's columns as they are. A grouped run has what it grouped
 * by, then how many forms each group holds, then each rolled-up column headed with its rollup -- "Total (sum)" -- with
 * the kind the rollup yields (a count of anything is a number); a rolled-up column the run is grouped by is left out. Grouped by nothing, it is the count and the rollups:
 * the one total row.
 */
fun reportTableColumns(summary: ReportRunSummary): List<ReportTableColumn> {
    if (summary.mode == ReportMode.detail) {
        return summary.columns.map { ReportTableColumn(it.columnId, it.label, it.kind, ReportCellSource.value) }
    }
    val byId = summary.columns.associateBy { it.columnId }
    val grouped = summary.groupBy.mapNotNull { byId[it] }.map { ReportTableColumn(it.columnId, it.label, it.kind, ReportCellSource.group) }
    val count = ReportTableColumn(RRUN.count, reportCountLabel, ReportKind.number.name, ReportCellSource.count)
    // Not a column the run is grouped by: every form of a group has the group's value, so its rollup would repeat it.
    val rolled = summary.columns.filter { it.rollup != null && it.columnId !in summary.groupBy }.map {
        val kind = if (it.rollup == ReportCombine.count.name) ReportKind.number.name else it.kind
        ReportTableColumn(it.columnId, "${it.label} (${it.rollup})", kind, ReportCellSource.value)
    }
    return grouped + count + rolled
}

/** [row]'s value for [column], as it arrived. */
fun reportCellValue(row: ReportRunRow, column: ReportTableColumn): Any? = when (column.source) {
    ReportCellSource.value -> row.values[column.key]
    ReportCellSource.group -> row.group[column.key]
    ReportCellSource.count -> row.count
}

/** What a cell with no value shows, and what a group with no value for its column is called. */
const val reportBlank = "—"
const val reportNoGroupValue = "(none)"

/**
 * A cell's text. Nothing is a dash -- or, for what a group is grouped by, "(none)", since that row *is* the forms
 * with no value. A list is its elements joined; a boolean is Yes or No; a number is whole or to two places; a date
 * that carries a time is shown as the app shows timestamps, and a day as it is written.
 */
fun reportCellText(value: Any?, kind: String, source: ReportCellSource = ReportCellSource.value): String = when (value) {
    null -> if (source == ReportCellSource.group) reportNoGroupValue else reportBlank
    is List<*> -> if (value.isEmpty()) reportBlank else value.joinToString(", ") { reportCellText(it, kind) }
    is Boolean -> if (value) "Yes" else "No"
    is Number -> reportNumberText(value.toDouble())
    is String -> if (kind == ReportKind.date.name) formatTimestamp(value) else value
    else -> value.toString()
}

/** Past this a number is not held exactly, and is shown as it prints rather than as digits it does not have. */
const val reportExactLimit = 9.0e15

/** [value] as a whole number's digits when it is one within [reportExactLimit]; null otherwise. The page and the file share it. */
fun wholeNumberTextOrNull(value: Double): String? =
    if (value == floor(value) && value >= -reportExactLimit && value <= reportExactLimit) value.toLong().toString() else null

/**
 * A number as a cell shows it: whole when it is, otherwise rounded to two places. One too large to be held exactly
 * -- past what the rounding below can carry -- is shown as the number prints, rather than run through it into garbage.
 */
fun reportNumberText(value: Double): String {
    if (value.isNaN() || value < -reportExactLimit || value > reportExactLimit) return value.toString()
    wholeNumberTextOrNull(value)?.let { return it }
    val cents = (value * 100).roundToLong()
    val sign = if (cents < 0) "-" else ""
    val abs = if (cents < 0) -cents else cents
    return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}

/**
 * The paging line's count: which rows are on screen, of how many. [before] is how many rows the pages already walked
 * held -- a cursor says where the next page starts, not how far in it is, so the page counts as it goes.
 */
fun reportRangeText(before: Int, onPage: Int, numAvailable: Int, mode: ReportMode): String {
    val noun = if (mode == ReportMode.aggregate) "group" else "form"
    return when {
        numAvailable == 0 -> "No ${noun}s"
        onPage == 0 -> "No more ${noun}s"
        before == 0 && onPage >= numAvailable -> if (numAvailable == 1) "1 $noun" else "$numAvailable ${noun}s"
        else -> "${noun.replaceFirstChar { it.uppercase() }}s ${before + 1}–${before + onPage} of $numAvailable"
    }
}

/** How many forms the run read, and how many it left out for lacking a required value. */
fun reportCountsText(summary: ReportRunSummary): String {
    val read = if (summary.scanned == 1) "1 form read" else "${summary.scanned} forms read"
    return if (summary.excluded == 0) read else "$read, ${summary.excluded} left out for a missing value"
}

/** The Reports page's mode in the hash: only the grouped mode is written, so a plain link is a report's default. */
const val reportModeGrouped = "grouped"
const val reportModeDetail = "detail"

/** The mode a hash's `view` names, or null when it names none -- the report's own default then applies. */
fun reportModeOf(hashValue: String?): ReportMode? = when (hashValue) {
    reportModeGrouped -> ReportMode.aggregate
    reportModeDetail -> ReportMode.detail
    else -> null
}

fun reportModeParam(mode: ReportMode): String = if (mode == ReportMode.aggregate) reportModeGrouped else reportModeDetail

/**
 * Whether a hash's `view` asks for the report's **history** (issue #1037) rather than a run of it: its stored
 * snapshots, charted. Not a run mode -- the run endpoint knows nothing of it -- so it is asked beside [reportModeOf],
 * which answers null for it.
 */
fun reportViewIsHistory(hashValue: String?): Boolean = hashValue == HMENU.reportViewHistory

/**
 * The link to the Reports page: [client]'s reports (the caller's own when null), open at [reportId] when given, in
 * [mode] when given -- absent, the report opens the way it declares -- or, with [history], on its History view.
 */
fun reportsHref(client: String? = null, reportId: String? = null, mode: ReportMode? = null, history: Boolean = false): String = hashHref(
    reportsHashParams(client, reportId, if (history) HMENU.reportViewHistory else mode?.let { reportModeParam(it) }),
)

/** The Reports page's hash for [client], [reportId] and a `view` value: what a link carries and what the page writes. */
fun reportsHashParams(client: String?, reportId: String?, view: String?): List<Pair<String, String>> = buildList {
    add(HP.page to HMENU.pageReports)
    client?.let { add(HP.client to it) }
    reportId?.let { add(HP.report to it) }
    view?.let { add(HP.reportView to it) }
}

/**
 * The link from a detail row to its form, on the forms page. For an administrator looking across clients the forms
 * page is told which client, as the Clients page's links tell it. It carries no `from`: the forms page owns its
 * hash and its ways home (see "The forms listing across clients" in `webapp/CLAUDE.md`), so its own back link is to
 * the forms listing, and the way back to the report is the browser's Back, which returns to the report that was open
 * (at its first page: the walk's cursor does not outlive leaving the page).
 */
fun reportFormHref(gedraId: String, client: String?): String = hashHref(
    listOf(HP.page to HMENU.pageForms) + (client?.let { listOf(EI.client to it) } ?: emptyList()) + listOf(HP.gedra to gedraId),
)

// --- the whole run as a file (issue #1008) ------------------------------------------------------------------------

/** The page size the download walks at: large, since a grouped or filtered run re-reads every form per page. */
const val reportDownloadPageSize = 500

/** The heading of a detail download's leading column: the form's id, where the page shows an "Open" link. */
const val reportCsvFormLabel = "Form"

/**
 * Every row of a run, fetched by walking its cursor (issue #1008): [fetchPage] is asked for the first page (null) and
 * then for the page after each `next`, until a page hands back none. [onProgress] hears how many rows are in hand
 * and how many the run says there are, after each page. [stillWanted] is asked before each fetch and after it: once
 * it answers false -- the user changed the report or a control -- the walk stops and answers null, and what it had
 * fetched is dropped. A failure of any page propagates: a run that cannot be fetched whole is not handed over in part.
 *
 * Returns the first page's summary with all the rows. A cursor that never ends is cut off at [maxPages] and thrown
 * as a fault, rather than walked forever.
 */
suspend fun walkReportRun(
    fetchPage: suspend (after: String?) -> ReportRunPage,
    onProgress: (fetched: Int, total: Int) -> Unit = { _, _ -> },
    stillWanted: () -> Boolean = { true },
    maxPages: Int = 10_000,
): ReportRunPage? {
    val rows = ArrayList<ReportRunRow>()
    var first: ReportRunPage? = null
    var after: String? = null
    var pages = 0
    while (true) {
        if (!stillWanted()) return null
        val page = fetchPage(after)
        if (!stillWanted()) return null
        if (first == null) first = page
        rows.addAll(page.rows)
        onProgress(rows.size, page.numAvailable)
        after = page.next ?: return ReportRunPage(rows, rows.size, null, first.summary)
        if (++pages >= maxPages) throw IllegalStateException("The report's paging did not end after $maxPages pages.")
    }
}

/**
 * A run as CSV (RFC 4180): a header row of the table's column labels, then a row per form or per group, lines ended
 * by CRLF. A detail run leads with the form's id. What the page shows for people is written here for a spreadsheet:
 * a number in full, a timestamp as `yyyy-MM-dd HH:mm:ss` in UTC ([csvTimestamp]) and a day as written, a boolean as
 * `true` or `false`, a list joined with "; ", and nothing -- a blank cell, a group with no value -- as an empty field.
 */
fun reportCsv(run: ReportRunPage): String {
    val columns = reportTableColumns(run.summary)
    val detail = run.summary.mode == ReportMode.detail
    val lines = ArrayList<String>()
    lines.add(((if (detail) listOf(reportCsvFormLabel) else emptyList()) + columns.map { it.label }).joinToString(",") { csvField(it) })
    for (row in run.rows) {
        val cells = (if (detail) listOf(row.gedraId.orEmpty()) else emptyList()) +
            columns.map { reportCsvCell(reportCellValue(row, it), it.kind) }
        lines.add(cells.joinToString(",") { csvField(it) })
    }
    return lines.joinToString("\r\n", postfix = "\r\n")
}

/**
 * One value of a column of [kind] as a CSV cell's text, before quoting. The formula guard ([csvSafeText]) is applied
 * to the **cell**, once -- only a cell's first character can start a formula, so a list's later elements are left as
 * they were entered -- and to anything but a lone number or boolean, which a spreadsheet must read as what it is.
 */
fun reportCsvCell(value: Any?, kind: String = ReportKind.string.name): String {
    val text = csvRawText(value, kind)
    return if (value is Number || value is Boolean) text else csvSafeText(text)
}

private fun csvRawText(value: Any?, kind: String): String = when (value) {
    null -> ""
    is List<*> -> value.joinToString("; ") { csvRawText(it, kind) }
    is Boolean -> value.toString()
    is Number -> value.toDouble().let { d -> wholeNumberTextOrNull(d) ?: d.toString() }
    is String -> if (kind == ReportKind.date.name) csvTimestamp(value) else value
    else -> value.toString()
}

/**
 * A UTC timestamp as a spreadsheet takes one: `2026-10-05T19:50:31.543Z` written `2026-10-05 19:50:31`. The ISO form
 * -- its `T`, its fraction, its `Z` -- is one spreadsheets commonly leave as text, where the plain form is the one
 * they are documented to read as a date and time. The time is still UTC; the file cannot say so per cell, and
 * `reporting.md` does. Anything else -- a day, a time with another offset -- is written as it is.
 */
fun csvTimestamp(text: String): String =
    if (text.length >= 20 && text[10] == 'T' && text.endsWith("Z") && text[4] == '-' && text[13] == ':') {
        text.substring(0, 10) + " " + text.substring(11, 19)
    } else {
        text
    }

/**
 * Text made safe to open in a spreadsheet: a cell beginning `=`, `+`, `-`, `@`, a tab or a carriage return is read
 * by Excel and its kin as a **formula**, and a report's text is whatever somebody typed into a form. Such a value
 * gets a leading apostrophe, which a spreadsheet shows as the text it is. Numbers are written as numbers and never
 * come through here, so a negative amount stays a number.
 */
fun csvSafeText(text: String): String =
    if (text.isNotEmpty() && text[0] in "=+-@\t\r") "'$text" else text

/** One field as RFC 4180 writes it: quoted when it holds a comma, a quote or a line break, its quotes doubled. */
fun csvField(text: String): String =
    if (text.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text

/** The name a run is saved under: the report and how it was run. */
fun reportCsvFileName(reportId: String, mode: ReportMode): String =
    "${reportId.replace(':', '-')}-${reportModeParam(mode)}.csv"

/** The download button's text while a walk is under way: how far it has got, once a page has said how far there is. */
fun reportDownloadProgressText(fetched: Int, total: Int): String =
    if (total <= 0) "Fetching\u2026" else "Fetched $fetched of $total\u2026"

/** The report endpoints (issue #981), each a fetch and a pure parse. */
object ReportsApi {
    /** [client]'s reports -- the caller's own client's when null -- and its report issues. */
    suspend fun list(client: String?): ReportListing = parseReportListing(
        Http.getApi(UADEP.reports + queryString(client?.let { mapOf(RRUN.client to it) } ?: emptyMap())),
    )

    /** One page of a run, starting after the cursor [after] -- the previous page's `next` -- or at the start. */
    suspend fun run(reportId: String, client: String?, setup: ReportRunSetup, after: String?, limit: Int = reportPageSize): ReportRunPage =
        parseRunPage(Http.getApi(UADEP.reportRun + queryString(reportRunQuery(reportId, client, setup, after, limit))))

    /** The whole of a run, walked from its first page in large pages (issue #1008); null when no longer wanted. */
    suspend fun runAll(
        reportId: String,
        client: String?,
        setup: ReportRunSetup,
        onProgress: (fetched: Int, total: Int) -> Unit,
        stillWanted: () -> Boolean,
    ): ReportRunPage? = walkReportRun({ after -> run(reportId, client, setup, after, reportDownloadPageSize) }, onProgress, stillWanted)
}
