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

/** The run endpoint's envelope ([UADEP.reportRun]) as a [ReportRunPage]. */
fun parseRunPage(envelope: Map<String, Any?>): ReportRunPage {
    val summary = envelope[EP.summary].toJsonMapOrEmpty()
    return ReportRunPage(
        rows = envelope[EP.items].toJsonListOfMaps().map { row ->
            ReportRunRow(
                gedraId = row[RRUN.gedraId].toOptStr(),
                group = row[RRUN.group].toJsonMapOrEmpty(),
                count = (row[RRUN.count] as? Number)?.toInt(),
                values = row[RRUN.values].toJsonMapOrEmpty(),
            )
        },
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

/**
 * A number as a cell shows it: whole when it is, otherwise rounded to two places. One too large to be held exactly
 * -- past what the rounding below can carry -- is shown as the number prints, rather than run through it into garbage.
 */
fun reportNumberText(value: Double): String {
    if (value.isNaN() || value < -9.0e15 || value > 9.0e15) return value.toString()
    if (value == floor(value)) return value.toLong().toString()
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
 * The link to the Reports page: [client]'s reports (the caller's own when null), open at [reportId] when given, in
 * [mode] when given -- absent, the report opens the way it declares.
 */
fun reportsHref(client: String? = null, reportId: String? = null, mode: ReportMode? = null): String = hashHref(
    buildList {
        add(HP.page to HMENU.pageReports)
        client?.let { add(HP.client to it) }
        reportId?.let { add(HP.report to it) }
        mode?.let { add(HP.reportView to reportModeParam(it)) }
    },
)

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

/** The report endpoints (issue #981), each a fetch and a pure parse. */
object ReportsApi {
    /** [client]'s reports -- the caller's own client's when null -- and its report issues. */
    suspend fun list(client: String?): ReportListing = parseReportListing(
        Http.getApi(UADEP.reports + queryString(client?.let { mapOf(RRUN.client to it) } ?: emptyMap())),
    )

    /** One page of a run, starting after the cursor [after] -- the previous page's `next` -- or at the start. */
    suspend fun run(reportId: String, client: String?, setup: ReportRunSetup, after: String?, limit: Int = reportPageSize): ReportRunPage =
        parseRunPage(Http.getApi(UADEP.reportRun + queryString(reportRunQuery(reportId, client, setup, after, limit))))
}
