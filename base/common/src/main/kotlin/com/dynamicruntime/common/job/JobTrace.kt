package com.dynamicruntime.common.job

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.util.fmt
import com.dynamicruntime.common.util.getOptBool
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Instant

/** How much of a launch the trace records (issue #879). */
@Suppress("EnumEntryName")
enum class JobTraceLevel {
    /** Nothing. */
    off,

    /** The launch's decisions: claims, adoptions and resets, busy clients, why it stopped, how it ended. */
    launch,

    /** Those, and every task: its outcome, its retries, and what a failure named. */
    task,
}

/** What a trace entry records. Each carries the lowest [JobTraceLevel] that records it. */
@Suppress("EnumEntryName")
enum class JobTraceEvent(val level: JobTraceLevel) {
    /** The launch row was claimed; `data` says whether it was adopted, and its generation. */
    launchClaimed(JobTraceLevel.launch),

    /** Another launch held the launch row live; nothing ran. */
    launchLockedOut(JobTraceLevel.launch),

    /** A launch of this name had already finished its work; nothing ran. */
    launchAlreadyComplete(JobTraceLevel.launch),

    /** A client's row was claimed; `data` says whether it was adopted, and its generation. */
    clientClaimed(JobTraceLevel.launch),

    /** Another launch held a client's row live, so the client was skipped. */
    clientBusy(JobTraceLevel.launch),

    /** A client's work under this name was already complete, so it was skipped. */
    clientAlreadyComplete(JobTraceLevel.launch),

    /** A client's row was ended; `data` holds how, and its counters. */
    clientEnded(JobTraceLevel.launch),

    /** Something decided the run must stop; `message` is why. Recorded once, for the first reason. */
    stopping(JobTraceLevel.launch),

    /** The launch ended; `data` holds its outcome and counters. */
    launchEnded(JobTraceLevel.launch),

    /** A task did its work. */
    taskDone(JobTraceLevel.task),

    /** A task found nothing to do. */
    taskNothingToDo(JobTraceLevel.task),

    /** A task threw a retryable failure and will be tried again. */
    taskRetry(JobTraceLevel.task),

    /** A task failed and the job went on; `data` holds the scenario and resource the failure named. */
    taskFailed(JobTraceLevel.task),

    /** A task threw the abort variant. */
    taskAborted(JobTraceLevel.task),

    /** A job's own note, from `JobRunCxt.trace`. */
    jobNote(JobTraceLevel.task),

    /** The launch reached its profile's cap on entries; nothing more at task level is recorded for it. */
    truncated(JobTraceLevel.launch),
}

/** One trace entry, as read back. */
class JobTraceEntry(row: Map<String, Any?>) {
    val traceSeq: Long = row[JOBT.traceSeq].toOptLong() ?: 0
    val jobType: String? = row[JOB.jobType].toOptStr()
    val launchKind: String? = row[JOB.launchKind].toOptStr()
    val dryRun: Boolean = row.getOptBool(JOB.dryRun) == true
    val launchName: String? = row[JOB.launchName].toOptStr()
    val leaseId: String? = row[JOB.leaseId].toOptStr()
    val holder: String? = row[JOB.holder].toOptStr()
    val at: Instant? = row[JOBT.at].toOptInstant()
    val event: JobTraceEvent? = row[JOBT.event].toOptStr()?.let { s -> JobTraceEvent.entries.firstOrNull { it.name == s } }
    val client: String? = row[PF.client].toOptStr()
    val taskKey: String? = row[JOBT.taskKey].toOptStr()
    val message: String? = row[JOBT.message].toOptStr()
    val data: Map<String, Any?> = row[JOB.data].toJsonMapOrEmpty()

    /** This entry as the operator surface shows it: the date formatted, and an absent value left out. */
    fun toInfo(): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>(JOBT.traceSeq to traceSeq)
        fun put(key: String, value: Any?) {
            if (value != null) out[key] = value
        }
        put(JOB.leaseId, leaseId)
        put(JOB.holder, holder)
        put(JOBT.at, at?.fmt())
        put(JOBT.event, event?.name)
        put(PF.client, client)
        put(JOBT.taskKey, taskKey)
        put(JOBT.message, message)
        if (data.isNotEmpty()) out[JOB.data] = data
        return out
    }
}

/**
 * Collects one launch's trace entries and writes them (issue #879). A run [record]s as it goes and [flush]es at
 * each heartbeat and at its end: one batched write per heartbeat rather than one per task.
 *
 * **Never part of the work's transaction.** A flush writes on a sub-context of its own, which takes its own
 * connection -- `mkSubContext` never carries its parent's session -- so an entry is written, and stays written,
 * whatever transaction the caller has open and however it ends.
 *
 * **Best effort.** A flush that fails is logged and its entries dropped; a trace never fails or stalls the job it
 * describes. [record] is thread-safe, since a pooled run's tasks record from their own threads.
 */
class JobTracer(
    private val cxt: KdrCxt,
    val level: JobTraceLevel,
    private val launch: JobLaunch,
    /** The most entries the launch records; past it, only launch-level entries are. */
    private val maxEntries: Int,
    /** How many of the job type's launches the table keeps; older ones are pruned on the final flush. */
    private val keepLaunches: Int,
    /** Writes a batch; the table by default. A test can substitute one that fails. */
    private val writer: (KdrCxt, List<Map<String, Any?>>) -> Unit = JobTraceRows::write,
) {
    private val lock = Any()
    private val pending = ArrayList<Map<String, Any?>>()
    private var recorded = 0
    private var truncated = false

    /** The launch claim entries are written under, once there is one. */
    @Volatile
    var leaseId: String? = null

    /** Whether [event] would be recorded at this tracer's level. */
    fun records(event: JobTraceEvent): Boolean = level != JobTraceLevel.off && event.level <= level

    /** Buffers an entry for [event], when the level records it. */
    fun record(
        event: JobTraceEvent,
        client: String? = null,
        taskKey: String? = null,
        message: String? = null,
        data: Map<String, Any?>? = null,
    ) {
        if (!records(event)) return
        synchronized(lock) {
            if (event.level == JobTraceLevel.task && recorded >= maxEntries) {
                if (!truncated) {
                    truncated = true
                    pending.add(entry(JobTraceEvent.truncated, null, null, "Reached the cap of $maxEntries entries.", null))
                }
                return
            }
            recorded++
            pending.add(entry(event, client, taskKey, message, data))
        }
    }

    /** Writes what has been buffered, and on the [final] flush prunes the job type's older launches. */
    fun flush(final: Boolean = false) {
        if (level == JobTraceLevel.off) return
        val batch = synchronized(lock) { pending.toList().also { pending.clear() } }
        val traceCxt = cxt.mkSubContext("jobTrace")
        try {
            if (batch.isNotEmpty()) writer(traceCxt, batch)
            if (final) JobTraceRows.prune(traceCxt, launch.jobType, keepLaunches)
        } catch (e: Exception) {
            LogJob.warn(cxt) { "Dropped ${batch.size} trace entries for job '${launch.jobType}': ${e.message}" }
        }
    }

    private fun entry(
        event: JobTraceEvent,
        client: String?,
        taskKey: String?,
        message: String?,
        data: Map<String, Any?>?,
    ): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>(
            JOB.jobType to launch.jobType,
            JOB.launchKind to launch.kind.name,
            JOB.dryRun to launch.dryRun,
            JOB.launchName to launch.name,
            JOB.leaseId to leaseId,
            JOB.holder to cxt.instanceConfig.instanceName,
            JOBT.at to Instant.fromEpochMilliseconds(cxt.instanceNow().toEpochMilliseconds()),
            JOBT.event to event.name,
            PF.client to client,
            JOBT.taskKey to taskKey,
            JOBT.message to message,
        )
        if (data != null) out[JOB.data] = data
        return out
    }
}

/** Writes, reads and prunes the job trace (issue #879). */
object JobTraceRows {
    /** Inserts [entries] in order, on [cxt]'s own session. */
    fun write(cxt: KdrCxt, entries: List<Map<String, Any?>>) {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, jobTopic)
        val table = table(cxt)
        val stmt = SqlTopicUtil.mkTableInsertStmt(sqlCxt, table)
        sqlCxt.sqlDb.withSession(cxt) {
            for (entry in entries) {
                val data = LinkedHashMap(entry)
                SqlTopicUtil.prepForStdExecute(cxt, table, data)
                sqlCxt.sqlDb.executeStatement(cxt, stmt, data)
            }
        }
    }

    /** The trace of [launchName], launched as [kind] (a dry run's, when [dryRun]), in the order it was written. */
    fun read(cxt: KdrCxt, jobType: String, kind: JobLaunchKind, launchName: String, dryRun: Boolean = false): List<JobTraceEntry> {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, jobTopic)
        val stmt = SqlStmtUtil.prepareSql(
            sqlCxt, "qJobTraceByLaunch", table(cxt).columns,
            "select * from t:${JOBT.jobTrace} where c:${JOB.jobType} = :${JOB.jobType} and " +
                "c:${JOB.launchKind} = :${JOB.launchKind} and c:${JOB.dryRun} = :${JOB.dryRun} and " +
                "c:${JOB.launchName} = :${JOB.launchName} order by c:${JOBT.traceSeq}",
        )
        val args = mapOf(JOB.jobType to jobType, JOB.launchKind to kind.name, JOB.dryRun to dryRun, JOB.launchName to launchName)
        var rows: List<Map<String, Any?>> = emptyList()
        sqlCxt.sqlDb.withSession(cxt) { rows = sqlCxt.sqlDb.queryStatement(cxt, stmt, args) }
        return rows.map { JobTraceEntry(it) }
    }

    /**
     * Keeps the trace of [jobType]'s [keep] most recently written launches, deleting entries older than the
     * earliest of theirs. An older launch's entries written after that point survive with them, which bounds
     * the table all the same.
     */
    fun prune(cxt: KdrCxt, jobType: String, keep: Int) {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, jobTopic)
        val columns = table(cxt).columns
        val recent = SqlStmtUtil.prepareSql(
            sqlCxt, "qJobTraceLaunches", columns,
            // Each launch's first entry, most recently written launch first. The aggregate is named as the column
            // it aggregates, so it reads back as that field with that field's type.
            "select min(c:${JOBT.traceSeq}) as c:${JOBT.traceSeq} from t:${JOBT.jobTrace} " +
                "where c:${JOB.jobType} = :${JOB.jobType} " +
                "group by c:${JOB.launchKind}, c:${JOB.dryRun}, c:${JOB.launchName} order by max(c:${JOBT.traceSeq}) desc",
        )
        val delete = SqlStmtUtil.prepareSql(
            sqlCxt, "dJobTraceBefore", columns,
            "delete from t:${JOBT.jobTrace} where c:${JOB.jobType} = :${JOB.jobType} and c:${JOBT.traceSeq} < :${JOBT.traceSeq}",
        )
        sqlCxt.sqlDb.withSession(cxt) {
            val launches = sqlCxt.sqlDb.queryStatement(cxt, recent, mapOf(JOB.jobType to jobType))
            if (launches.size <= keep) return@withSession
            val cutoff = launches.take(keep).mapNotNull { it[JOBT.traceSeq].toOptLong() }.minOrNull() ?: return@withSession
            sqlCxt.sqlDb.executeStatement(cxt, delete, mapOf(JOB.jobType to jobType, JOBT.traceSeq to cutoff))
        }
    }

    private fun table(cxt: KdrCxt) = cxt.getSchema().tables[JOBT.jobTrace]
        ?: throw KdrException("${JOBT.jobTrace} table is not registered in the schema store.")
}
