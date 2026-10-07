package com.dynamicruntime.common.gedra.report

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.endpoint.CursorKeyCodec
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.util.parseDateOrNull
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

/**
 * One stored snapshot (issue #1034): the row's own columns, and the run it holds ([data]: the grouping, the columns as
 * bound, and the rows in the run endpoint's shape).
 */
class ReportSnapshotRow(
    val snapshotId: Long,
    val client: String,
    val reportId: String,
    val takenAt: Instant,
    val trigger: String,
    val launchName: String?,
    val queryId: String?,
    val scanned: Int,
    val excluded: Int,
    val groupCount: Int,
    val data: Map<String, Any?>,
) {
    /**
     * The snapshot as the endpoints answer it: the row's facts with [data] spread beside them, and whether it was
     * taken under the report's definition as it is bound now ([currentQueryId]) -- a series spanning a change of
     * grouping or columns is not one question, and a chart should say so.
     */
    fun toWireMap(currentQueryId: String): Map<String, Any?> = buildMap {
        put(RHIS.snapshotId, snapshotId)
        put(RHIS.sameDefinition, queryId == currentQueryId)
        put(RRUN.reportId, reportId)
        put(RRUN.client, client)
        put(RHIS.takenAt, takenAt)
        put(RHIS.trigger, trigger)
        launchName?.let { put(RHIS.launchName, it) }
        put(RRUN.groupBy, data[RRUN.groupBy].toJsonListOfStrings())
        put(RRUN.columns, data[RRUN.columns].toJsonListOfMaps())
        put(RHIS.rows, data[RHIS.rows].toJsonListOfMaps())
        put(RRUN.scanned, scanned)
        put(RRUN.excluded, excluded)
        put(RHIS.truncated, data[RHIS.truncated] == true)
    }

    companion object {
        /** A row as the table returns it. */
        fun of(row: Map<String, Any?>): ReportSnapshotRow = ReportSnapshotRow(
            snapshotId = row[RHT.snapshotSeq].toOptLong() ?: throw KdrException("A report snapshot row has no ${RHT.snapshotSeq}."),
            client = row[PF.client].toOptStr() ?: "",
            reportId = row[RRUN.reportId].toOptStr() ?: "",
            takenAt = row[RHT.takenAt].toOptInstant() ?: throw KdrException("A report snapshot row has no ${RHT.takenAt}."),
            trigger = row[RHT.trigger].toOptStr() ?: ReportSnapshotTrigger.manual.name,
            launchName = row[RHT.launchName].toOptStr(),
            queryId = row[RHT.queryId].toOptStr(),
            scanned = row[RHT.scanned].toOptLong()?.toInt() ?: 0,
            excluded = row[RHT.excluded].toOptLong()?.toInt() ?: 0,
            groupCount = row[RHT.groupCount].toOptLong()?.toInt() ?: 0,
            data = row[RHT.data].toJsonMapOrEmpty(),
        )
    }
}

/**
 * How a snapshot's place travels in a history cursor (issue #1034): its moment and its sequence, since a backdated
 * snapshot (a simulation's) can sit before an earlier-written one, so neither alone orders the listing.
 */
val reportSnapshotKeyCodec: CursorKeyCodec<Pair<Instant, Long>> = CursorKeyCodec(
    toValues = { (at, seq) -> listOf(at.toString(), seq) },
    fromValues = { values ->
        val at = values.getOrNull(0).toOptStr()?.parseDateOrNull()
        val seq = values.getOrNull(1).toOptLong()
        if (values.size == 2 && at != null && seq != null) at to seq else null
    },
)

/** Newest first: the order the history lists and pages in. */
val reportSnapshotOrder: Comparator<Pair<Instant, Long>> =
    compareByDescending<Pair<Instant, Long>> { it.first }.thenByDescending { it.second }

/** Writes, reads and prunes the report-snapshot table (issue #1034). Append-only, on the caller's own session. */
object ReportSnapshotRows {
    /** Inserts [row] -- its [PF.client] already set -- and returns the sequence the table gave it. */
    fun write(cxt: KdrCxt, row: Map<String, Any?>): Long {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, reportHistoryTopic)
        val table = table(cxt)
        val stmt = SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
        val data = LinkedHashMap(row)
        SqlTopicUtil.prepForStdExecute(cxt, table, data)
        val counter = LongArray(1)
        sqlCxt.sqlDb.withSession(cxt) { sqlCxt.sqlDb.executeStatementGetCounterBack(cxt, stmt, data, counter) }
        return counter[0]
    }

    /** The snapshots of [reportId] for [client], newest first ([reportSnapshotOrder]). */
    fun list(cxt: KdrCxt, client: String, reportId: String): List<ReportSnapshotRow> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, reportHistoryTopic)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qReportSnapshotsByReport", table(cxt).columns,
            "select * from t:${RHT.reportSnapshot} where c:${PF.client} = :${PF.client} and c:${RRUN.reportId} = :${RRUN.reportId} " +
                "order by c:${RHT.takenAt} desc, c:${RHT.snapshotSeq} desc",
        )
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) { rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(PF.client to client, RRUN.reportId to reportId)) }
        return rows.filter { it[PF.enabled] == true }.map { ReportSnapshotRow.of(it) }
    }

    /** The snapshot [snapshotId], or null when there is none for [client]. */
    fun read(cxt: KdrCxt, client: String, snapshotId: Long): ReportSnapshotRow? {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, reportHistoryTopic)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qReportSnapshotById", table(cxt).columns,
            "select * from t:${RHT.reportSnapshot} where c:${PF.client} = :${PF.client} and c:${RHT.snapshotSeq} = :${RHT.snapshotSeq}",
        )
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) { row = sqlCxt.sqlDb.queryOneEnabled(cxt, stmt, mapOf(PF.client to client, RHT.snapshotSeq to snapshotId)) }
        return row?.let { ReportSnapshotRow.of(it) }
    }

    /** Whether the launch [launchName] already took a snapshot of [reportId] for [client]: what keeps a resumed launch from a second. */
    fun exists(cxt: KdrCxt, client: String, reportId: String, launchName: String): Boolean {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, reportHistoryTopic)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qReportSnapshotSeqByLaunch", table(cxt).columns,
            "select c:${RHT.snapshotSeq}, c:${PF.enabled} from t:${RHT.reportSnapshot} where c:${PF.client} = :${PF.client} " +
                "and c:${RRUN.reportId} = :${RRUN.reportId} and c:${RHT.launchName} = :${RHT.launchName}",
        )
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) {
            rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(PF.client to client, RRUN.reportId to reportId, RHT.launchName to launchName))
        }
        return rows.any { it[PF.enabled] == true }
    }

    /** The place of every snapshot of [reportId] for [client] -- its moment and sequence, no data -- newest first. */
    fun keys(cxt: KdrCxt, client: String, reportId: String): List<Pair<Instant, Long>> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, reportHistoryTopic)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qReportSnapshotKeysByReport", table(cxt).columns,
            "select c:${RHT.snapshotSeq}, c:${RHT.takenAt}, c:${PF.enabled} from t:${RHT.reportSnapshot} " +
                "where c:${PF.client} = :${PF.client} and c:${RRUN.reportId} = :${RRUN.reportId} " +
                "order by c:${RHT.takenAt} desc, c:${RHT.snapshotSeq} desc",
        )
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) { rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(PF.client to client, RRUN.reportId to reportId)) }
        return rows.filter { it[PF.enabled] == true }.mapNotNull { row ->
            val at = row[RHT.takenAt].toOptInstant() ?: return@mapNotNull null
            val seq = row[RHT.snapshotSeq].toOptLong() ?: return@mapNotNull null
            at to seq
        }
    }

    /**
     * Prunes [reportId]'s snapshots for [client] to what the history keeps (issue #1034): of each day before [today]'s,
     * only the latest snapshot -- what the chart shows of a day -- and at most [keepDays] days in all, the oldest days
     * going first. Today's snapshots are all kept until the day is over, so a day of pressing Snapshot now can never
     * push the nightly series out; a day counts once however many it holds. Reads the keys alone, never the data.
     */
    fun prune(cxt: KdrCxt, client: String, reportId: String, keepDays: Int, today: Instant) {
        val todayKey = dayOf(today)
        val byDay = keys(cxt, client, reportId).groupBy { dayOf(it.first) }
        val doomed = mutableListOf<Long>()
        for ((day, keysOfDay) in byDay) {
            // Newest first within the day: the first is the day's survivor.
            if (day < todayKey) doomed.addAll(keysOfDay.drop(1).map { it.second })
        }
        val days = byDay.keys.sortedDescending()
        for (day in days.drop(keepDays)) doomed.addAll(byDay.getValue(day).map { it.second })
        if (doomed.isEmpty()) return
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, reportHistoryTopic)
        val delete = SqlStmtUtil.prepareSql(
            sqlCxt, "dReportSnapshot", table(cxt).columns,
            "delete from t:${RHT.reportSnapshot} where c:${PF.client} = :${PF.client} and c:${RHT.snapshotSeq} = :${RHT.snapshotSeq}",
        )
        sqlCxt.sqlDb.withSession(cxt) {
            for (seq in doomed.distinct()) sqlCxt.sqlDb.executeStatement(cxt, delete, mapOf(PF.client to client, RHT.snapshotSeq to seq))
        }
    }

    /** The UTC day [at] falls in, as a day count: how snapshots are told to be of one day, the way a job's slots are. */
    fun dayOf(at: Instant): Long = Math.floorDiv(at.toEpochMilliseconds(), dayMs)

    private const val dayMs = 24L * 60 * 60 * 1000

    private fun table(cxt: KdrCxt) = cxt.getGlobalSchema().tables[RHT.reportSnapshot]
        ?: throw KdrException("${RHT.reportSnapshot} table is not registered in the schema store.")
}

/**
 * Takes a snapshot of a report (issue #1034): its grouped run -- **its own** grouping and its own excluded columns,
 * never a viewer's, since a history is comparable only when every snapshot asks the same question -- over the
 * **whole client** ([ReadScope.ofClient]), stored in the run endpoint's row shape, and the report's older snapshots
 * pruned ([ReportSnapshotRows.prune]) to one a day for [REP.historyKeepEnvVar] days. The nightly job and the
 * Reports page's button both come here.
 *
 * A report grouping by nothing is snapshotted as its one total row: a total over time is a chart too. The rows are
 * cut at [REP.historyMaxGroups], in key order, with the cut recorded, so a report with a group per form cannot grow a
 * row without bound.
 */
object ReportHistoryWriter {
    /**
     * Snapshots [bound] for [client] as [trigger] took it -- under [launchName] for the job -- at [takenAt], which
     * only a simulation sets to anything but now, with at most [maxGroups] groups stored. Returns the stored row,
     * read back before the report's older snapshots are pruned, since a backdated one may itself be what pruning drops.
     */
    fun snapshot(
        cxt: KdrCxt,
        client: String,
        bound: BoundReport,
        trigger: ReportSnapshotTrigger,
        launchName: String? = null,
        takenAt: Instant = cxt.instanceNow(),
        maxGroups: Int = REP.historyMaxGroups,
    ): ReportSnapshotRow {
        // Bound to the client (a job's context already is; an allClients administrator's is their own client's), so
        // the run reads the client's forms and the row is stamped as the client's.
        val clientCxt = if (cxt.client == client) cxt else cxt.mkSubContext("reportSnapshot", client)
        val groupBy = bound.defaultGroupBy()
        val run = aggregateReportRun(clientCxt, client, bound, groupBy, bound.defaultExcludeEmpty(), ReadScope.ofClient(client))
        val rows = run.groups.take(maxGroups).map { aggregateRowMap(groupBy, it) }
        val data = buildMap {
            put(RRUN.groupBy, groupBy.map { it.columnId })
            put(RRUN.columns, bound.columns.map { it.describe() })
            put(RHIS.rows, rows)
            if (run.groups.size > maxGroups) put(RHIS.truncated, true)
        }
        val row = linkedMapOf<String, Any?>(
            PF.client to client,
            RRUN.reportId to bound.reportId,
            RHT.takenAt to takenAt,
            RHT.trigger to trigger.name,
            RHT.launchName to launchName,
            RHT.queryId to reportQueryId(client, bound, ReportMode.aggregate, groupBy),
            RHT.scanned to run.scanned,
            RHT.excluded to run.excluded,
            RHT.groupCount to run.groups.size,
            RHT.data to data,
        )
        val id = ReportSnapshotRows.write(clientCxt, row)
        val written = ReportSnapshotRows.read(clientCxt, client, id)
            ?: throw KdrException("Report snapshot $id of '${bound.reportId}' for client '$client' was not read back after writing.")
        ReportSnapshotRows.prune(clientCxt, client, bound.reportId, historyKeepDays(cxt), cxt.instanceNow())
        return written
    }

    /** How many days of one report's snapshots are kept per client: [REP.historyKeepEnvVar], or its default. */
    fun historyKeepDays(cxt: KdrCxt): Int =
        cxt.getEnvVar(REP.historyKeepEnvVar)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: REP.defaultHistoryKeep
}
