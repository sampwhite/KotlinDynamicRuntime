package com.dynamicruntime.common.job

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlCxt
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.util.fmt
import com.dynamicruntime.common.util.getOptBool
import com.dynamicruntime.common.util.mkUniqueId
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Duration
import kotlin.time.Instant

/** How a job was launched. The launch row is keyed by it, so a scheduled and an endpoint launch run side by side. */
@Suppress("EnumEntryName")
enum class JobLaunchKind { scheduled, endpoint }

/** Where a status row stands. A row nothing has claimed yet has none. */
@Suppress("EnumEntryName")
enum class JobRunStatus { active, complete, aborted }

/** How one claim on a row ended, as its history records it. */
@Suppress("EnumEntryName")
enum class JobAttemptEnd {
    /** The work finished. */
    complete,

    /** The holder aborted: a fatal error, a requested abort, or being fenced out. */
    aborted,

    /** The holder let go without finishing, on a graceful shutdown; the next claim need not wait out the lease. */
    released,

    /** The holder stopped heartbeating and a later claim found its lease expired. */
    lapsed,
}

/** A row's task counters. [total] is known only for a job that can count its tasks up front. */
class JobCounts(val completed: Long = 0, val skipped: Long = 0, val failed: Long = 0, val total: Long? = null) {
    fun toJsonMap(): Map<String, Any?> = linkedMapOf(
        JOB.completed to completed, JOB.skipped to skipped, JOB.failed to failed, JOB.total to total,
    )

    companion object {
        val zero = JobCounts()

        fun of(row: Map<String, Any?>): JobCounts = JobCounts(
            row[JOB.completed].toOptLong() ?: 0, row[JOB.skipped].toOptLong() ?: 0,
            row[JOB.failed].toOptLong() ?: 0, row[JOB.total].toOptLong(),
        )
    }
}

/**
 * One launch of a job type: what a claim on the launch row is asked for.
 *
 * [name] is the launch's identity for adoption. Launching again with the same name declares that the work already
 * done under it is adopted rather than redone; a scheduled launch's name comes from its scheduled slot, so a node
 * joining the same slot adopts too. [redoWindow], when set, lets a per-client row be adopted even under another
 * launch's name, if its work began within that long before this launch's time -- the "is it old enough to redo"
 * test.
 */
class JobLaunch(
    val jobType: String,
    val kind: JobLaunchKind,
    val name: String,
    val params: Map<String, Any?> = emptyMap(),
    val dryRun: Boolean = false,
    val redoWindow: Duration? = null,
)

/**
 * A claim held on a status row: the launch row when [client] is null, else that client's row. Every write the
 * holder makes afterwards is conditional on [leaseId], so a holder that stalled past its lease and was taken over
 * finds out on its next write instead of overwriting the new holder's state.
 */
class JobLease internal constructor(
    val launch: JobLaunch,
    /** The launch time this claim works under: the original one, when a relaunch by name adopted the row. */
    val launchTime: Instant,
    val client: String?,
    val leaseId: String,
    val generationId: Long,
    /** Whether the claim continued the row's work rather than resetting it. */
    val adopted: Boolean,
    /** The counters as they stood when claimed: zero after a reset, the prior attempt's after an adoption. */
    val counts: JobCounts,
)

/** The answer to a claim. */
sealed interface JobClaim {
    /** The row is now held under [lease]. */
    class Claimed(val lease: JobLease) : JobClaim

    /** Another claim holds the row and its lease is live; nothing was written. */
    class LockedOut(val holder: String?, val launchName: String?, val heartbeatAt: Instant?) : JobClaim

    /** The claim would adopt the row, and its work is already complete; nothing was written. */
    class AlreadyComplete(val generationId: Long, val counts: JobCounts) : JobClaim
}

/** The answer to a heartbeat. A [fenced] holder has lost its row and must stop; nothing was written. */
class JobBeat(val fenced: Boolean, val abortRequested: Boolean)

/** A status row as stored, typed for reading. */
class JobStatusRow(row: Map<String, Any?>) {
    val jobType: String? = row[JOB.jobType].toOptStr()

    /** The launch kind, on a launch row; null on a per-client row. */
    val launchKind: JobLaunchKind? = row[JOB.launchKind].toOptStr()?.let { s -> JobLaunchKind.entries.firstOrNull { it.name == s } }

    /** The client, on a per-client row; null on a launch row. */
    val client: String? = row[PF.client].toOptStr()
    val runStatus: JobRunStatus? = row[JOB.runStatus].toOptStr()?.let { s -> JobRunStatus.entries.firstOrNull { it.name == s } }
    val launchName: String? = row[JOB.launchName].toOptStr()
    val launchTime: Instant? = row[JOB.launchTime].toOptInstant()
    val startedAt: Instant? = row[JOB.startedAt].toOptInstant()
    val dryRun: Boolean = row.getOptBool(JOB.dryRun) == true
    val generationId: Long = row[JOB.generationId].toOptLong() ?: 0
    val leaseId: String? = row[JOB.leaseId].toOptStr()
    val holder: String? = row[JOB.holder].toOptStr()
    val claimedAt: Instant? = row[JOB.claimedAt].toOptInstant()
    val heartbeatAt: Instant? = row[JOB.heartbeatAt].toOptInstant()
    val abortRequested: Boolean = row.getOptBool(JOB.abortRequested) == true
    val params: Map<String, Any?> = row[JOB.params].toJsonMapOrEmpty()
    val counts: JobCounts = JobCounts.of(row)
    val aggregate: Map<String, Any?> = row[JOB.data].toJsonMapOrEmpty()[JOB.aggregate].toJsonMapOrEmpty()

    /** Ended attempts, oldest first, at most [JOB.historyLimit]. */
    val history: List<Map<String, Any?>> = row[JOB.data].toJsonMapOrEmpty()[JOB.history].toJsonListOfMaps()

    /** Whether a claim holds the row with a lease that has not expired at [now]. */
    fun isLive(now: Instant, timeout: Duration): Boolean {
        val beat = heartbeatAt ?: return false
        return runStatus == JobRunStatus.active && now < beat + timeout
    }
}

/**
 * Claims, heartbeats and finishes on the batch-job status rows (issue #868): the logic core of the job framework,
 * with no threads in it. The runner (issue #869) calls these; everything that decides who may work on what is
 * here, so it can be tested by driving a clock by hand.
 *
 * **Each operation is one topic transaction on the row it changes**, which is what makes a claim safe: two
 * claims on one row are serialized by its lock, so the second sees the first's write rather than the state both
 * started from. Lease times are the node's instance clock: node clocks agree closely enough for leases this long,
 * and it keeps them drivable in tests.
 *
 * **Two numbers, two jobs.** `generationId` names a body of work: a claim that resets the row increments it, one
 * that adopts keeps it, so work signed off against a generation stays signed off across a restart. `leaseId`
 * names one claim: every claim gets a new one, adopting or not, and every later write is conditional on it. The
 * generation alone cannot fence -- a node that takes over a lapsed row by *adopting* it keeps the generation, and
 * a stalled holder waking up would find its generation still current.
 */
object JobStatusRows {
    /**
     * Claims the launch row for [launch]. Locked out while another claim's lease is live. Otherwise the row is
     * **adopted** when it carries the same launch name -- a relaunch continuing its own work, keeping its launch
     * time, generation and counters -- and **reset** for anything else. Adopting a complete row answers
     * [JobClaim.AlreadyComplete] and writes nothing. Any claim clears a pending abort request, which belonged to
     * the attempt before it.
     */
    fun claimLaunch(cxt: KdrCxt, launch: JobLaunch, timeout: Duration): JobClaim =
        inRowTran(cxt, "jobClaimLaunch", JOB.jobStatus, launchKey(launch.jobType, launch.kind, launch.dryRun)) { tran ->
            val now = jobNow(cxt)
            val row = JobStatusRow(tran.row)
            if (row.isLive(now, timeout)) {
                tran.lockedOut(row)
            } else {
                val adopt = row.runStatus != null && row.launchName == launch.name
                if (adopt && row.runStatus == JobRunStatus.complete) {
                    tran.alreadyComplete(row)
                } else {
                    val launchTime = if (adopt) row.launchTime ?: now else now
                    tran.row[JOB.launchTime] = launchTime
                    tran.row[JOB.params] = launch.params
                    tran.row[JOB.abortRequested] = false
                    JobClaim.Claimed(tran.claim(cxt, launch, launchTime, null, row, adopt, now))
                }
            }
        }

    /**
     * Claims [client]'s row for the launch holding [launchLease]. Locked out while another claim's lease is live
     * -- which is how a scheduled and an endpoint launch of one type keep off each other's client. Otherwise the
     * row is adopted when it carries this launch's name, or when the launch has a redo window and the row's work
     * began within it before the launch time; anything else resets it. Adopting a complete row answers
     * [JobClaim.AlreadyComplete] and writes nothing.
     */
    fun claimClient(cxt: KdrCxt, launchLease: JobLease, client: String, timeout: Duration): JobClaim {
        val launch = launchLease.launch
        return inRowTran(cxt, "jobClaimClient", JOB.jobClientStatus, clientKey(launch.jobType, client, launch.dryRun)) { tran ->
            val now = jobNow(cxt)
            val row = JobStatusRow(tran.row)
            if (row.isLive(now, timeout)) {
                tran.lockedOut(row)
            } else {
                val window = launch.redoWindow
                val started = row.startedAt
                val recent = window != null && started != null && started >= launchLease.launchTime - window
                val adopt = row.runStatus != null && (row.launchName == launch.name || recent)
                if (adopt && row.runStatus == JobRunStatus.complete) {
                    tran.alreadyComplete(row)
                } else {
                    if (!adopt) tran.row[JOB.startedAt] = now
                    JobClaim.Claimed(tran.claim(cxt, launch, launchLease.launchTime, client, row, adopt, now))
                }
            }
        }
    }

    /**
     * Renews [lease] and records its progress: [counts], and on the launch row an [aggregate] across clients.
     * Answers whether an abort has been requested. A lease that no longer holds its row -- taken over, or already
     * ended -- is **fenced**: nothing is written, and the holder must stop.
     */
    fun heartbeat(cxt: KdrCxt, lease: JobLease, counts: JobCounts, aggregate: Map<String, Any?>? = null): JobBeat =
        inLeaseTran(cxt, "jobHeartbeat", lease) { tran, row ->
            if (row == null) {
                JobBeat(fenced = true, abortRequested = false)
            } else {
                tran.row[JOB.heartbeatAt] = jobNow(cxt)
                tran.putCounts(counts)
                if (aggregate != null) tran.putData(JOB.aggregate, aggregate)
                JobBeat(fenced = false, abortRequested = row.abortRequested)
            }
        }

    /**
     * Ends [lease] as [end] -- [JobAttemptEnd.complete] marks the row complete, anything else aborted -- with its
     * final [counts], and adds the attempt to the row's history. Answers false, writing nothing, when the lease
     * is fenced.
     */
    fun finish(cxt: KdrCxt, lease: JobLease, end: JobAttemptEnd, counts: JobCounts, reason: String? = null): Boolean {
        if (end == JobAttemptEnd.lapsed) {
            throw KdrException("A lease cannot finish as lapsed; that is recorded by the claim that finds it expired.")
        }
        return inLeaseTran(cxt, "jobFinish", lease) { tran, row ->
            if (row == null) {
                false
            } else {
                val now = jobNow(cxt)
                tran.putCounts(counts)
                tran.row[JOB.runStatus] = (if (end == JobAttemptEnd.complete) JobRunStatus.complete else JobRunStatus.aborted).name
                tran.row[JOB.heartbeatAt] = now
                tran.addHistory(JobStatusRow(tran.row), end, now, reason)
                true
            }
        }
    }

    /**
     * Lets go of [lease] without finishing, on a graceful shutdown: the row is marked aborted, so the next claim
     * adopts or resets it at once instead of waiting out the lease. A crash cannot do this; the lease timeout
     * covers it.
     */
    fun release(cxt: KdrCxt, lease: JobLease, counts: JobCounts): Boolean =
        finish(cxt, lease, JobAttemptEnd.released, counts, "Released on shutdown.")

    /**
     * Asks the live launch of [jobType] launched as [kind] to abort; its holder's next heartbeat picks it up.
     * Answers whether there was an active launch to ask. The lease is not touched, so asking does not keep a dead
     * launch alive.
     */
    fun requestAbort(cxt: KdrCxt, jobType: String, kind: JobLaunchKind, dryRun: Boolean = false): Boolean {
        // Read first: the transaction would insert the row if it were missing, and a job that never ran should
        // not gain an empty one because somebody asked it to stop.
        if (readLaunch(cxt, jobType, kind, dryRun)?.runStatus != JobRunStatus.active) return false
        return inRowTran(cxt, "jobRequestAbort", JOB.jobStatus, launchKey(jobType, kind, dryRun)) { tran ->
            if (JobStatusRow(tran.row).runStatus != JobRunStatus.active) {
                tran.noWrite()
                false
            } else {
                tran.row[JOB.abortRequested] = true
                true
            }
        }
    }

    /** The launch row of [jobType] launched as [kind] (a dry run's, when [dryRun]), or null if none has been written. */
    fun readLaunch(cxt: KdrCxt, jobType: String, kind: JobLaunchKind, dryRun: Boolean = false): JobStatusRow? =
        readRow(cxt, JOB.jobStatus, launchKey(jobType, kind, dryRun))

    /** Every client row [jobType] has (a dry run's, when [dryRun]), ordered by client. */
    fun readClients(cxt: KdrCxt, jobType: String, dryRun: Boolean = false): List<JobStatusRow> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, jobTopic)
        val table = cxt.getSchema().tables[JOB.jobClientStatus]
            ?: throw KdrException("${JOB.jobClientStatus} table is not registered in the schema store.")
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qJobClientsByType", table.columns,
            "select * from t:${JOB.jobClientStatus} where c:${JOB.jobType} = :${JOB.jobType} " +
                "and c:${JOB.dryRun} = :${JOB.dryRun} order by c:${PF.client}",
        )
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) {
            rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, mapOf(JOB.jobType to jobType, JOB.dryRun to dryRun))
        }
        return rows.map { JobStatusRow(it) }
    }

    /** [client]'s row for [jobType] (a dry run's, when [dryRun]), or null if none has been written. */
    fun readClient(cxt: KdrCxt, jobType: String, client: String, dryRun: Boolean = false): JobStatusRow? =
        readRow(cxt, JOB.jobClientStatus, clientKey(jobType, client, dryRun))

    // --- internals -------------------------------------------------------------------------------------------

    /**
     * The node's instance clock, at the millisecond precision the rows store: a lease's times then equal what a
     * read of its row gives back, rather than differing in digits the database dropped.
     */
    private fun jobNow(cxt: KdrCxt): Instant = Instant.fromEpochMilliseconds(cxt.instanceNow().toEpochMilliseconds())

    private fun launchKey(jobType: String, kind: JobLaunchKind, dryRun: Boolean): Map<String, Any?> =
        mapOf(JOB.jobType to jobType, JOB.launchKind to kind.name, JOB.dryRun to dryRun)

    private fun clientKey(jobType: String, client: String, dryRun: Boolean): Map<String, Any?> =
        mapOf(JOB.jobType to jobType, PF.client to client, JOB.dryRun to dryRun)

    private fun readRow(cxt: KdrCxt, tableName: String, key: Map<String, Any?>): JobStatusRow? {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, jobTopic)
        val table = cxt.getSchema().tables[tableName]
            ?: throw KdrException("$tableName table is not registered in the schema store.")
        var row: Map<String, Any?>? = null
        sqlCxt.sqlDb.withSession(cxt) {
            row = sqlCxt.sqlDb.queryOneStatement(cxt, SqlTopicUtil.mkTableSelectStmt(sqlCxt, table), key)
        }
        return row?.let { JobStatusRow(it) }
    }

    /**
     * Runs [block] as a topic transaction on the row [key] names in [tableName], handing it the row as it stands
     * under the lock (inserted holding only its key, when there was none). The row is written back afterwards
     * unless the block calls [RowTran.noWrite].
     */
    private fun <T> inRowTran(
        cxt: KdrCxt,
        tranName: String,
        tableName: String,
        key: Map<String, Any?>,
        block: (RowTran) -> T,
    ): T {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, jobTopic)
        val out = ArrayList<T>(1)
        SqlTopicTranProvider.executeTopicTran(sqlCxt, tranName, null, key, tranTableName = tableName) {
            out.clear()
            out.add(block(RowTran(sqlCxt)))
        }
        return out.single()
    }

    /**
     * As [inRowTran], on [lease]'s row, handing [block] the row only while [lease] still holds it -- an active row
     * carrying its lease id -- and null otherwise, in which case nothing is written.
     */
    private fun <T> inLeaseTran(cxt: KdrCxt, tranName: String, lease: JobLease, block: (RowTran, JobStatusRow?) -> T): T {
        val jobType = lease.launch.jobType
        val client = lease.client
        val (tableName, key) = if (client == null) {
            JOB.jobStatus to launchKey(jobType, lease.launch.kind, lease.launch.dryRun)
        } else {
            JOB.jobClientStatus to clientKey(jobType, client, lease.launch.dryRun)
        }
        return inRowTran(cxt, tranName, tableName, key) { tran ->
            val row = JobStatusRow(tran.row)
            val holds = row.leaseId == lease.leaseId && row.runStatus == JobRunStatus.active
            if (!holds) tran.noWrite()
            block(tran, row.takeIf { holds })
        }
    }

    /** One transaction's view of its row: the mutable row data, and the writes a claim makes to it. */
    private class RowTran(private val sqlCxt: SqlCxt) {
        val row: MutableMap<String, Any?> get() = sqlCxt.tranData

        /** Suppresses the write-back: the transaction changed nothing. */
        fun noWrite() {
            sqlCxt.tranAlreadyDone = true
        }

        fun lockedOut(row: JobStatusRow): JobClaim {
            noWrite()
            return JobClaim.LockedOut(row.holder, row.launchName, row.heartbeatAt)
        }

        fun alreadyComplete(row: JobStatusRow): JobClaim {
            noWrite()
            return JobClaim.AlreadyComplete(row.generationId, row.counts)
        }

        /**
         * Takes the row for a new lease. A row still marked active here has an expired lease (a live one locked
         * the claim out), so that attempt is recorded as [JobAttemptEnd.lapsed] first. A reset starts a new
         * generation with zeroed counters; an adoption keeps both.
         */
        fun claim(
            cxt: KdrCxt,
            launch: JobLaunch,
            launchTime: Instant,
            client: String?,
            prior: JobStatusRow,
            adopt: Boolean,
            now: Instant,
        ): JobLease {
            if (prior.runStatus == JobRunStatus.active) {
                addHistory(prior, JobAttemptEnd.lapsed, prior.heartbeatAt ?: now, "The lease expired without a heartbeat.")
            }
            val generationId = if (adopt) prior.generationId else prior.generationId + 1
            val counts = if (adopt) prior.counts else JobCounts.zero
            val leaseId = cxt.mkUniqueId()
            row[JOB.runStatus] = JobRunStatus.active.name
            row[JOB.launchName] = launch.name
            row[JOB.generationId] = generationId
            row[JOB.leaseId] = leaseId
            row[JOB.holder] = cxt.instanceConfig.instanceName
            row[JOB.claimedAt] = now
            row[JOB.heartbeatAt] = now
            putCounts(counts)
            return JobLease(launch, launchTime, client, leaseId, generationId, adopt, counts)
        }

        fun putCounts(counts: JobCounts) {
            row[JOB.completed] = counts.completed
            row[JOB.skipped] = counts.skipped
            row[JOB.failed] = counts.failed
            row[JOB.total] = counts.total
        }

        fun putData(key: String, value: Any?) {
            val data = LinkedHashMap(row[JOB.data].toJsonMapOrEmpty())
            data[key] = value
            row[JOB.data] = data
        }

        /** Appends [attempt]'s ending to the history, keeping the newest [JOB.historyLimit]. */
        fun addHistory(attempt: JobStatusRow, end: JobAttemptEnd, endedAt: Instant, reason: String?) {
            val entry = linkedMapOf(
                JOB.leaseId to attempt.leaseId,
                JOB.holder to attempt.holder,
                JOB.launchName to attempt.launchName,
                JOB.generationId to attempt.generationId,
                JOB.claimedAt to attempt.claimedAt?.fmt(),
                JOB.endedAt to endedAt.fmt(),
                JOB.end to end.name,
                JOB.reason to reason,
                JOB.counts to attempt.counts.toJsonMap(),
            )
            putData(JOB.history, (attempt.history + entry).takeLast(JOB.historyLimit))
        }
    }
}
