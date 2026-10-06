package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.endpoint.CursorKeyCodec
import com.dynamicruntime.common.endpoint.CursorKeys
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.ListPage
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.cursorPageOf
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.GedraDataRow
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.deriveEntryData
import com.dynamicruntime.common.gedra.overseenClient
import com.dynamicruntime.common.gedra.overseenClientField
import com.dynamicruntime.common.gedra.toWireMap
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfPhase
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypesBuilder
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.ReadScopeRules
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

/** The report endpoints' schema names (issue #981). */
@Suppress("ConstPropertyName")
object REP {
    const val namespace = "kdr.report"
    const val columnType = "ReportColumnInfo"
    const val reportType = "ReportInfo"
    const val listSummaryType = "ReportListSummary"
    const val rowType = "ReportRow"
    const val runSummaryType = "ReportRunSummary"

    /** The most forms one run reads; past it the run is refused rather than answered in part. */
    val scanLimitEnvVar = EnvVarDef(
        "KDR_REPORT_SCAN_LIMIT", group = ENVGRP.gedra, defaultDoc = "50000",
        description = "The most forms one report run reads (issue #981). A report runs in memory over the caches, " +
            "so a client with more forms than this is refused with a message naming this variable rather than given " +
            "a report that silently covers part of its forms -- a partial report is worse than none. The default is " +
            "the ceiling the forms listing's in-memory search states (#538).",
    )

    const val defaultScanLimit = 50_000

    const val snapshotType = "ReportSnapshot"
    const val historySummaryType = "ReportHistorySummary"

    /** How many days of one report's snapshots are kept per client (issue #1034); see `ReportSnapshotRows.prune`. */
    val historyKeepEnvVar = EnvVarDef(
        "KDR_REPORT_HISTORY_KEEP", group = ENVGRP.gedra, defaultDoc = "400",
        description = "How many days of one report's snapshots are kept per client (issue #1034). Of each past day " +
            "only the latest snapshot survives, and days beyond this many are deleted as a new snapshot is written, " +
            "oldest first; today's snapshots are all kept until the day is over, so pressing Snapshot now cannot " +
            "push the nightly series out. The default keeps over a year.",
    )

    const val defaultHistoryKeep = 400

    /** The most groups one snapshot stores (issue #1034); a run with more is cut, in key order, and says so. */
    const val historyMaxGroups = 2000
}

/**
 * The report endpoints (issue #981), in the **`clientAdmin`** section and scoped as its other client retrieves are
 * ([overseenClient]): a client's administrator sees their own client, an `allClients` administrator may name any,
 * and a `public` self-administrator oversees none. App-only: a run reads the gedra, states and user caches, which an
 * edge does not carry.
 *
 * - `GET /clientAdmin/reports` -- the client's reports as the registry bound them (#980), each column with its
 *   resolved kind and combine, and the client's report issues.
 * - `GET /clientAdmin/report/run` -- one report over the client's forms, a row per form or per group, paged by
 *   cursor (#976). See [runReport].
 * - `GET /clientAdmin/report/history` and `POST /clientAdmin/report/snapshot` -- a report's stored snapshots, and
 *   taking one now (#1034). Client-wide: an organization-confined administrator is refused ([requireClientWideView]).
 */
fun reportSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, REP.namespace) {
    columnInfoType()
    type(REP.reportType) {
        type = SCT.kObject
        description = "A named report one client may run, as the configuration declared it and as its paths were bound."
        property(RRUN.reportId, "The report's id: what a run names.", required = true)
        property(RRUN.label, "What the report is called.", required = true)
        property(RRUN.description, "What the report shows.")
        property(RRUN.columns, "The columns, in order, each with how its path was bound.", required = true) {
            type = SCT.array
            items { ref(REP.columnType) }
        }
        property(RRUN.groupBy, "The columns a grouped run groups by unless it names others.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        property(RRUN.excludeEmpty, "The columns a form must have a value for unless the run names others.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        property(RRUN.configName, "The configuration that declares the report.", required = true)
        property(RRUN.origin, "Whether that configuration is in source code or stored.", required = true) {
            options(GedraConfigOrigin.entries)
        }
        property(RRUN.template, "The template the report came from, for a client's copy of its template's report.")
        property(RRUN.history, "Whether the nightly job stores snapshots of the report (issue #1033).", required = true) { type = SCT.boolean }
    }
    type(REP.listSummaryType) {
        type = SCT.kObject
        description = "Beside a client's reports: the problems that cost reports, so a missing one is explained."
        property(RRUN.client, "The client.", required = true)
        property(RRUN.issues, "Report problems found in the client's configuration, and in the global one.", required = true) {
            type = SCT.array
            items { ref(CLD.configIssueTypeQualified) }
        }
    }
    type(REP.rowType) {
        type = SCT.kObject
        description = "One row of a report run: a form in a detail run, a group of forms in an aggregate one."
        property(RRUN.gedraId, "The form, in a detail run.")
        property(RRUN.group, "The grouped-by columns' values, by column id, in an aggregate run.") {
            type = SCT.kObject
            additionalProperties = true
        }
        property(RRUN.count, "How many forms are in the group, in an aggregate run.") { type = SCT.integer }
        property(
            RRUN.values,
            "The columns' values by column id: each column's value for the form, or -- in an aggregate run -- the " +
                "rollups of the columns that declare one. A column with nothing to show is null.",
            required = true,
        ) {
            type = SCT.kObject
            additionalProperties = true
        }
    }
    type(REP.runSummaryType) {
        type = SCT.kObject
        description = "What a run was: the report, its mode and columns, and how many forms it read and left out."
        property(RRUN.reportId, "The report run.", required = true)
        property(RRUN.label, "What the report is called.", required = true)
        property(RRUN.client, "The client whose forms were read.", required = true)
        property(RRUN.mode, "One row per form, or one per group.", required = true) { options(ReportMode.entries) }
        property(RRUN.groupBy, "The columns grouped by, in an aggregate run.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        property(RRUN.excludeEmpty, "The columns a form had to have a value for.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        property(RRUN.columns, "The report's columns, each with how its path was bound.", required = true) {
            type = SCT.array
            items { ref(REP.columnType) }
        }
        property(RRUN.scanned, "How many of the client's forms the run read.", required = true) { type = SCT.integer }
        property(RRUN.excluded, "How many of them were left out for a missing value.", required = true) { type = SCT.integer }
    }

    listEndpoint(
        UADEP.reports,
        "The named reports one client may run (issue #981) -- the global ones and the client's own -- each column with " +
            "the kind, combine and multiplicity its path was bound to, and where the report was declared. The summary " +
            "carries the report problems that dropped a report, so one that is missing is explained.",
        outputRef = REP.reportType,
        noLimit = true,
        summaryRef = REP.listSummaryType,
        inputFields = { overseenClientField(RRUN.client, "The client whose reports to list; the caller's own when absent.") },
        needsClientConfig = true,
    ) { c, request ->
        val client = overseenClient(c, request[RRUN.client].toOptStr())
        val items = ReportService.get(c).forClient(client).reports.values.map { describeReport(it) }
        val issues = ClientConfigIssues.get(c).let { reg ->
            reg.issuesFor(client) + if (client == GID.globalClient) emptyList() else reg.issuesFor(GID.globalClient)
        }.filter { it.elementKind == GCEL.report }
        ListPage(items, items.size, hasMore = false, summary = mapOf(RRUN.client to client, RRUN.issues to issues.map { it.toWireMap() }))
    }

    type(REP.snapshotType) {
        type = SCT.kObject
        description = "One snapshot of a report's grouped run over a client's forms (issue #1034): when and what took it, and the run's rows."
        property(RHIS.snapshotId, "The snapshot's id.", required = true) { type = SCT.integer }
        property(RRUN.reportId, "The report.", required = true)
        property(RRUN.client, "The client whose forms were read.", required = true)
        property(RHIS.takenAt, "When the run was taken.", required = true) { dateTime() }
        property(RHIS.trigger, "What took it.", required = true) { options(ReportSnapshotTrigger.entries) }
        property(RHIS.launchName, "The job launch that took it; absent for one taken by hand.")
        property(RRUN.groupBy, "The columns the run grouped by: the report's own.", required = true) {
            type = SCT.array
            items { type = SCT.string }
        }
        property(RRUN.columns, "The report's columns as they were bound when the snapshot was taken.", required = true) {
            type = SCT.array
            items { ref(REP.columnType) }
        }
        property(RHIS.rows, "The run's rows: a group of forms each, in key order.", required = true) {
            type = SCT.array
            items { ref(REP.rowType) }
        }
        property(RRUN.scanned, "How many of the client's forms the run read.", required = true) { type = SCT.integer }
        property(RRUN.excluded, "How many of them were left out for a missing value.", required = true) { type = SCT.integer }
        property(RHIS.truncated, "Whether the rows were cut at the stored limit.", required = true) { type = SCT.boolean }
        property(
            RHIS.sameDefinition,
            "Whether the snapshot was taken under the report's definition as it is bound now: the same grouping and " +
                "columns. A series spanning a change is not one question, and a chart should say so.",
            required = true,
        ) { type = SCT.boolean }
    }
    type(REP.historySummaryType) {
        type = SCT.kObject
        description = "Beside a report's snapshots: the report, and how many snapshots it has."
        property(RRUN.reportId, "The report.", required = true)
        property(RRUN.label, "What the report is called.", required = true)
        property(RRUN.client, "The client.", required = true)
        property(RRUN.history, "Whether the nightly job snapshots the report.", required = true) { type = SCT.boolean }
        property(RHIS.numSnapshots, "How many snapshots the report has for the client.", required = true) { type = SCT.integer }
    }

    listEndpoint(
        UADEP.reportHistory,
        "A report's stored snapshots for a client (issue #1034), newest first and paged by cursor: each the report's " +
            "grouped run -- its own grouping, over the whole client -- as it was when taken, by the nightly job for a " +
            "report that asks for history or by someone pressing Snapshot now. Client-wide: refused for an " +
            "administrator confined to an organization, whose own runs would narrow what a snapshot shows.",
        outputRef = REP.snapshotType,
        cursorPaged = true,
        summaryRef = REP.historySummaryType,
        inputFields = {
            field(RRUN.reportId, "The report whose snapshots to list.", required = true)
            overseenClientField(RRUN.client)
        },
        needsClientConfig = true,
    ) { c, request ->
        val client = overseenClient(c, request[RRUN.client].toOptStr())
        requireClientWideView(c)
        val reportId = request[RRUN.reportId].toOptStr() ?: throw KdrException.mkInput("A ${RRUN.reportId} is required.")
        // A report since removed from the configuration still has its snapshots: listed under its id, so they are
        // not stranded; a report nobody has, and nothing was stored for, is a 404.
        val bound = ReportService.get(c).forClient(client).report(reportId)?.bound
        val rows = ReportSnapshotRows.list(c, client, reportId)
        if (bound == null && rows.isEmpty()) throw KdrException("Client '$client' has no report '$reportId'.", code = EXC.notFound)
        val currentQueryId = bound?.let { reportQueryId(client, it, ReportMode.aggregate, it.defaultGroupBy()) } ?: ""
        val summary = mapOf(
            RRUN.reportId to reportId,
            RRUN.label to (bound?.report?.label ?: reportId),
            RRUN.client to client,
            RRUN.history to (bound?.report?.history ?: false),
            RHIS.numSnapshots to rows.size,
        )
        cursorPageOf(
            request, listOf("reportHistory", client, reportId).joinToString("|"), rows,
            { it.takenAt to it.snapshotId }, reportSnapshotOrder, reportSnapshotKeyCodec, summary,
        ) { page -> page.map { it.toWireMap(currentQueryId) } }
    }

    generalEndpoint(
        UADEP.reportSnapshot,
        "Takes a snapshot of a report now (issue #1034): its grouped run -- the report's own grouping and excluded " +
            "columns, over the whole client -- stored as it is at this moment, and returned. Any grouped report may " +
            "be snapshotted by hand; the nightly job takes only those that ask for history. Client-wide: refused for " +
            "an administrator confined to an organization. Refused past ${REP.scanLimitEnvVar.name} forms, as a run is.",
        HttpMethod.POST,
        outputRef = REP.snapshotType,
        inputFields = {
            field(RRUN.reportId, "The report to snapshot.", required = true)
            overseenClientField(RRUN.client)
        },
        needsClientConfig = true,
    ) { c, request ->
        val client = overseenClient(c, request[RRUN.client].toOptStr())
        requireClientWideView(c)
        val bound = boundReport(c, client, request)
        val taken = ReportHistoryWriter.snapshot(c, client, bound, ReportSnapshotTrigger.manual)
        taken.toWireMap(currentQueryId = taken.queryId ?: "")
    }

    listEndpoint(
        UADEP.reportRun,
        "Runs one named report over a client's forms (issue #981). A detail run has a row per form, in form-id order; " +
            "an aggregate run a row per group of the grouped-by columns' values, in their order with no value last, " +
            "and one total row when it groups by nothing. Paged by cursor: send each page's `next` back as `after` " +
            "until a page has none. Every form that exists for the whole walk is returned exactly once whatever is " +
            "edited meanwhile, and one created during it appears at the end; a group is never skipped or repeated, " +
            "though its count can change between pages. A client with more forms than ${REP.scanLimitEnvVar.name} is " +
            "refused.",
        outputRef = REP.rowType,
        cursorPaged = true,
        summaryRef = REP.runSummaryType,
        inputFields = {
            field(RRUN.reportId, "The report to run.", required = true)
            overseenClientField(RRUN.client, "The client whose forms to read; the caller's own when absent.")
            field(RRUN.aggregate, "A row per group rather than per form.") { type = SCT.boolean }
            field(RRUN.groupBy, "The columns an aggregate run groups by; the report's own when absent.") {
                type = SCT.array
                allowCoerce = true
                items { type = SCT.string }
            }
            field(
                RRUN.excludeEmpty,
                "The columns a form must have a value for to be in the run; the report's own when absent, none when empty.",
            ) {
                type = SCT.array
                allowCoerce = true
                emptyIsAbsent = false
                items { type = SCT.string }
            }
        },
        needsClientConfig = true,
    ) { c, request -> runReport(c, request) }
}

private fun SchTypesBuilder.columnInfoType() {
    type(REP.columnType) {
        type = SCT.kObject
        description = "One column of a report, and how its path was bound."
        property(RRUN.columnId, "The column's id within its report.", required = true)
        property(RRUN.label, "The column's heading.", required = true)
        property(RRUN.path, "The report path it reads.", required = true)
        property(RRUN.kind, "The kind of the column's value.", required = true) { options(ReportKind.entries) }
        property(RRUN.combine, "How the path's several values are made one within a form.", required = true) {
            options(ReportCombine.entries)
        }
        property(RRUN.rollup, "How the column is combined across a group's forms, when it is rolled up.") {
            options(ReportCombine.entries)
        }
        property(RRUN.multiValued, "Whether the path may read several values from one form.", required = true) {
            type = SCT.boolean
        }
    }
}

/** A report as the listing describes it. */
private fun describeReport(declared: ReportDeclared): Map<String, Any?> {
    val report = declared.bound.report
    return buildMap {
        put(RRUN.reportId, report.reportId)
        put(RRUN.label, report.label)
        report.description?.let { put(RRUN.description, it) }
        put(RRUN.columns, declared.bound.columns.map { it.describe() })
        put(RRUN.groupBy, report.groupBy)
        put(RRUN.excludeEmpty, report.excludeEmpty)
        put(RRUN.configName, declared.bundle.name)
        put(RRUN.origin, declared.bundle.origin.name)
        declared.bundle.inheritedFrom?.let { put(RRUN.template, it) }
        put(RRUN.history, report.history)
    }
}

/**
 * One page of a report run (issue #981), in memory over the caches as the forms listing is.
 *
 * - **What is read.** The client's live forms, by id ascending -- the order a detail run returns, and an id never
 *   changes, which is what lets a cursor promise each form exactly once. Each join is paid only when a column needs
 *   it: the entries (with derived fields computed on read) for a form path, the form's states for a workflow path or
 *   the form's status and cfacts, the owners' accounts for a user path.
 * - **Detail, nothing excluded**: only the page's forms are read.
 * - **Detail with `excludeEmpty`**: every form is read for the excluding columns, those without a value dropped,
 *   and the page's forms read in full. `numAvailable` is what is left.
 * - **Aggregate**: every form is read for the grouped and rolled-up columns, then grouped; the groups are paged by
 *   their key values, and `numAvailable` is the number of groups.
 *
 * Above [REP.scanLimitEnvVar] forms the run is refused, naming the variable. What is read is [reportScope]'s.
 */
fun runReport(c: KdrCxt, request: Map<String, Any?>): ListPage {
    val client = overseenClient(c, request[RRUN.client].toOptStr())
    val bound = boundReport(c, client, request)
    val reportId = bound.reportId
    val aggregate = request[RRUN.aggregate] == true
    val askedGroupBy = namesIn(request[RRUN.groupBy])
    if (!aggregate && !askedGroupBy.isNullOrEmpty()) {
        throw KdrException.mkInput("${RRUN.groupBy} applies to an aggregate run; ask for one with ${RRUN.aggregate}=true.")
    }
    val groupBy = if (aggregate) columnsNamed(bound, askedGroupBy ?: bound.report.groupBy, RRUN.groupBy) else emptyList()
    groupBy.firstOrNull { it.yieldsList }?.let {
        throw KdrException.mkInput("Column '${it.columnId}' holds a list (combine ${it.combine.name}), so it cannot be grouped by.")
    }
    val excludeEmpty = columnsNamed(bound, namesIn(request[RRUN.excludeEmpty]) ?: bound.report.excludeEmpty, RRUN.excludeEmpty)

    val scope = reportScope(c, client)
    val mode = if (aggregate) ReportMode.aggregate else ReportMode.detail
    val queryId = reportQueryId(client, bound, mode, groupBy)
    fun summary(scanned: Int, excluded: Int) = mapOf(
        RRUN.reportId to reportId,
        RRUN.label to bound.report.label,
        RRUN.client to client,
        RRUN.mode to mode.name,
        RRUN.groupBy to groupBy.map { it.columnId },
        RRUN.excludeEmpty to excludeEmpty.map { it.columnId },
        RRUN.columns to bound.columns.map { it.describe() },
        RRUN.scanned to scanned,
        RRUN.excluded to excluded,
    )

    if (aggregate) {
        val run = aggregateReportRun(c, client, bound, groupBy, excludeEmpty, scope)
        return cursorPageOf(
            request, queryId, run.groups, { it.key }, ::compareReportKeys, reportGroupKeyCodec(groupBy), summary(run.scanned, run.excluded),
        ) { page -> page.map { aggregateRowMap(groupBy, it) } }
    }

    val ids = scannableIds(c, client, scope)
    val reader = SubjectReader(c, client, scope)
    // Detail: the ids the run pages over -- every form, or those with a value in each excluding column.
    val (pagedIds, excluded) = if (excludeEmpty.isEmpty()) {
        ids to 0
    } else {
        // Excluded counts the forms read and left out; one deleted since its id was listed is neither.
        val read = reader.read(ids, excludeEmpty)
        val kept = read.filterNot { bound.excludes(it, excludeEmpty) }.map { it.meta[RMETA.gedraId] as String }
        kept to read.size - kept.size
    }
    return cursorPageOf(request, queryId, pagedIds, { it }, naturalOrder(), CursorKeys.string, summary(ids.size, excluded)) { page ->
        reader.read(page, bound.columns).map { subject ->
            mapOf(
                RRUN.gedraId to subject.meta[RMETA.gedraId],
                RRUN.values to detailValues(subject, bound.columns).mapValues { reportWireValue(it.value) },
            )
        }
    }
}

/**
 * How far a run reads (issue #981): the client named, for an administrator who may see every client; otherwise the
 * caller's own scope, which [overseenClient] has already held to their client -- and which, for an administrator with
 * a primary organization, is that organization. A report shows no form the caller could not open in the forms
 * listing, nor an owner they could not see in the user administration.
 */
fun reportScope(c: KdrCxt, client: String): ReadScope =
    if (AdminRules.canSeeAllClients(c)) ReadScope.ofClient(client) else ReadScopeRules.forCaller(c)

/** The report [request] names for [client], bound -- a 400 for no name, a 404 for a report the client does not have. */
private fun boundReport(c: KdrCxt, client: String, request: Map<String, Any?>): BoundReport {
    val reportId = request[RRUN.reportId].toOptStr() ?: throw KdrException.mkInput("A ${RRUN.reportId} is required.")
    return ReportService.get(c).forClient(client).report(reportId)?.bound
        ?: throw KdrException("Client '$client' has no report '$reportId'.", code = EXC.notFound)
}

/**
 * Refuses a caller whose administration is confined to an organization (issue #1034): report history is computed
 * over the whole client, so showing it to them would show totals their own runs narrow away, and taking one would
 * let them. An administrator who sees every client, or a client's unconfined one, passes.
 */
fun requireClientWideView(c: KdrCxt) {
    if (AdminRules.canSeeAllClients(c)) return
    val org = ReadScopeRules.forCaller(c).org ?: return
    throw KdrException(
        "Report history is client-wide, and your administration is confined to organization '$org'.",
        code = EXC.notAuthorized,
    )
}

/**
 * The ids a run over [scope] reads, in id order -- or a 400 past [REP.scanLimitEnvVar]: a report covering part of a
 * client's forms would mislead, so none is given. What every run, and every snapshot, starts from.
 */
fun scannableIds(c: KdrCxt, client: String, scope: ReadScope): List<String> {
    val ids = GedraDataService.get(c).liveGedraIdsInScope(c, GedraDataType.formDoc, scope).sorted()
    val scanLimit = c.getEnvVar(REP.scanLimitEnvVar)?.trim()?.toIntOrNull() ?: REP.defaultScanLimit
    if (ids.size > scanLimit) {
        throw KdrException.mkInput(
            "Client '$client' has ${ids.size} forms, more than the $scanLimit a report run reads " +
                "(${REP.scanLimitEnvVar.name}). A report covering part of them would mislead, so none is given.",
        )
    }
    return ids
}

/** What an aggregate run read and made (issue #1034): its groups, and the forms scanned and left out. */
class AggregateRun(val groups: List<ReportGroup>, val scanned: Int, val excluded: Int)

/**
 * An aggregate run of [bound] over [scope]'s forms of [client] (issues #981, #1034): every form read for the
 * [groupBy], rolled-up and [excludeEmpty] columns, the forms missing an excluding value left out, the rest grouped.
 * The run endpoint pages the groups; a snapshot stores them.
 */
fun aggregateReportRun(
    c: KdrCxt,
    client: String,
    bound: BoundReport,
    groupBy: List<BoundColumn>,
    excludeEmpty: List<BoundColumn>,
    scope: ReadScope,
): AggregateRun {
    val ids = scannableIds(c, client, scope)
    val rolled = bound.columns.filter { it.column.rollup != null }
    val read = SubjectReader(c, client, scope).read(ids, groupBy + rolled + excludeEmpty)
    val kept = read.filterNot { bound.excludes(it, excludeEmpty) }
    return AggregateRun(aggregateReport(kept, groupBy, rolled), ids.size, read.size - kept.size)
}

/** One group as the run endpoint's row and a snapshot's: its key by column id, its count, and its rollups. */
fun aggregateRowMap(groupBy: List<BoundColumn>, group: ReportGroup): Map<String, Any?> = mapOf(
    RRUN.group to groupBy.indices.associate { groupBy[it].columnId to reportWireValue(group.key[it]) },
    RRUN.count to group.count,
    RRUN.values to group.rollups.mapValues { reportWireValue(it.value) },
)

/**
 * What makes two runs the same query, for their cursors (issue #981): the client, the report, the mode and the
 * grouping -- and the report **as bound**, each column's path, kind, combine and rollup. A cursor taken before a reload
 * changed the report is then another query's, refused with a 400, rather than a key of the old definition read as one
 * of the new and resumed at a place that means nothing there.
 */
fun reportQueryId(client: String, bound: BoundReport, mode: ReportMode, groupBy: List<BoundColumn>): String =
    listOf(
        "report", client, bound.reportId, mode.name, groupBy.joinToString(",") { it.columnId },
        bound.columns.map { it.describe() }.toJsonStr(compact = true),
    ).joinToString("|")

/** The column ids a list-valued input names, blanks dropped; null when the input is absent. */
private fun namesIn(value: Any?): List<String>? =
    (value as? List<*>)?.mapNotNull { it.toOptStr()?.trim()?.ifEmpty { null } }

/** The columns of [report] that [ids] name, in order; an id naming none is a 400 naming the input. */
private fun columnsNamed(report: BoundReport, ids: List<String>, input: String): List<BoundColumn> =
    ids.distinct().map {
        report.column(it) ?: throw KdrException.mkInput(
            "$input names '$it', which is not a column of report '${report.reportId}'; its columns are " +
                "${report.columns.joinToString(", ") { c -> c.columnId }}.",
        )
    }

/**
 * How an aggregate run's group key travels in a cursor: each value as JSON -- a day and an instant as their text -- and
 * read back as the grouped-by column's kind, so a key comes back as the values it was made of. A cursor whose values
 * do not read as the columns' kinds is not a key of this run.
 */
fun reportGroupKeyCodec(groupBy: List<BoundColumn>): CursorKeyCodec<List<Any?>> = CursorKeyCodec(
    toValues = { key -> key.map { if (it is Instant) it.toString() else reportWireValue(it) } },
    fromValues = { values -> readGroupKey(values, groupBy) },
)

private fun readGroupKey(values: List<Any?>, groupBy: List<BoundColumn>): List<Any?>? {
    if (values.size != groupBy.size) return null
    return values.mapIndexed { i, v -> if (v == null) null else coerceReportValue(v, groupBy[i].kind) ?: return null }
}

/**
 * Reads forms as report subjects for one run (issue #981): their rows, and -- only when a column needs them -- their
 * entries with derived fields computed, their states and their owners. Each workflow's phase is taken once, at one
 * moment, for the whole run, so a run straddling a window's edge does not read its early forms under one phase and its
 * later ones under another.
 */
private class SubjectReader(val c: KdrCxt, val client: String, val scope: ReadScope) {
    private val data = GedraDataService.get(c)
    private val now = c.instanceNow()
    private val workflows by lazy { WorkflowService.get(c).forClient(client) }
    private val phases = HashMap<String, WfPhase?>()

    private fun phaseOf(id: String): WfPhase? = phases.getOrPut(id) {
        workflows.workflow(id)?.def?.takeIf { it.entry == WfEntry.normal }?.phaseAt(now)?.takeIf { it.exists }
    }

    /** The subjects of the forms [ids] names that still exist, in [ids]' order, with what [columns] read. */
    fun read(ids: List<String>, columns: List<BoundColumn>): List<ReportSubject> {
        if (ids.isEmpty()) return emptyList()
        val paths = columns.map { it.path.path }
        val needEntries = paths.any { it is FormPath }
        val needStates = paths.any { it is WorkflowPath || (it is MetaPath && it.attrName in stateMetaAttrs) }
        val needOwners = paths.any { it is UserPath }
        val rows = data.readGedras(c, GedraDataType.formDoc, ids, scope)
        val states = if (needStates) data.readStates(c, rows.values.map { it.gedraId }, scope) else emptyMap()
        val owners = if (needOwners) UserService.get(c).queryUsersByIds(c, rows.values.map { it.userId }, scope) else emptyMap()
        return ids.mapNotNull { id ->
            val row = rows[id] ?: return@mapNotNull null
            ReportSubject(
                meta = metaOf(row),
                entries = if (needEntries) deriveEntryData(c, row.kind, row.entries, row.client) else emptyList(),
                states = states[id].orEmpty(),
                owner = owners[row.userId]?.let(::ownerOf),
                phaseOf = ::phaseOf,
            )
        }
    }

    private fun metaOf(row: GedraDataRow): Map<String, Any?> = mapOf(
        RMETA.gedraId to row.gedraId.fullId,
        RMETA.client to row.client,
        RMETA.org to row.org,
        RMETA.ownerId to row.userId,
        RMETA.createdAt to row.createdAt,
        RMETA.updatedAt to row.updatedAt,
    )

    private fun ownerOf(user: AuthUserRow): Map<String, Any?> = mapOf(
        RUSR.userId to user.userId,
        RUSR.name to user.name,
        RUSR.publicName to user.publicName(),
        RUSR.email to user.primaryId,
        RUSR.persona to user.persona,
        RUSR.org to user.org,
        RUSR.labels to user.labels,
        RUSR.isEntity to user.isEntity,
        RUSR.enabled to user.enabled,
    )

    companion object {
        /** The `meta` attributes read from the form's states rather than its row. */
        val stateMetaAttrs = setOf(RMETA.formStatus, RMETA.surveyStatus, RMETA.cfacts)
    }
}
