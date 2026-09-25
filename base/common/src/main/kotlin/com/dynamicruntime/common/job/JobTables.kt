package com.dynamicruntime.common.job

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.KdrTable
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.TableBuilder
import com.dynamicruntime.common.sql.tableModule

/**
 * The topic the batch-job status rows live in (issue #868). Both of its tables are transactional: every claim,
 * heartbeat and finish is a topic transaction on the row it changes, so each transaction names its table.
 */
const val jobTopic = "job"

/** The batch-job status tables, their columns, and the keys of their `data` blobs (issue #868). Each name matches its value. */
@Suppress("ConstPropertyName")
object JOB {
    /** The launch row: one per job type and [JobLaunchKind]. */
    const val jobStatus = "JobStatus"

    /** The per-client row: one per job type and client, shared by every launch of that type. */
    const val jobClientStatus = "JobClientStatus"

    const val jobType = "jobType"
    const val launchKind = "launchKind"

    /** The [JobRunStatus] name; null on a row nothing has claimed yet. */
    const val runStatus = "runStatus"
    const val launchName = "launchName"
    const val launchTime = "launchTime"
    const val startedAt = "startedAt"
    const val dryRun = "dryRun"
    const val generationId = "generationId"
    const val leaseId = "leaseId"
    const val holder = "holder"
    const val claimedAt = "claimedAt"
    const val heartbeatAt = "heartbeatAt"
    const val abortRequested = "abortRequested"
    const val params = "params"
    const val completed = "completed"
    const val skipped = "skipped"
    const val failed = "failed"
    const val total = "total"
    const val data = "data"

    // Keys inside `data`, and inside one history entry.
    const val aggregate = "aggregate"
    const val history = "history"
    const val endedAt = "endedAt"
    const val end = "end"
    const val reason = "reason"
    const val counts = "counts"
    const val adopted = "adopted"
    const val traceLevel = "traceLevel"

    /** How many ended attempts a row's history keeps, newest last. */
    const val historyLimit = 5
}

/** The job-trace table and its columns (issue #879). Each name matches its value. */
@Suppress("ConstPropertyName")
object JOBT {
    const val jobTrace = "JobTrace"

    /** The database-assigned order of the entries: the order they were written, across runs and nodes. */
    const val traceSeq = "traceSeq"
    const val at = "at"
    const val event = "event"
    const val taskKey = "taskKey"
    const val message = "message"

    /** A retry's number, in a `taskRetry` entry's data. */
    const val attempt = "attempt"
}

/**
 * The batch-job status tables (issue #868): the **launch row**, keyed by job type and launch kind, and the
 * **per-client row**, keyed by job type and client -- each also keyed by whether it tracks a dry run. The per-client row is where a scheduled launch and an
 * endpoint launch of one type meet, which makes it the client-level lease.
 *
 * Rows are reused rather than accumulated: a row is the latest state of its key, with a short history of ended
 * attempts in its `data` blob. Everything but the key is nullable, because a row is first inserted by the
 * transaction machinery holding only its key, and filled in by the claim that follows.
 */
fun jobTables(cxt: KdrCxt): List<KdrTable> =
    tableModule(cxt, namespace = "job", topic = jobTopic) {
        table(JOB.jobStatus, "The state of the latest launch of a job type, per launch kind (#868).") {
            column(JOB.jobType, "The job type, the key its registration is made under.", required = true)
            column(JOB.launchKind, "How the job was launched: scheduled, or from an endpoint.", required = true)
            dryRunColumn()
            statusColumns()
            column(JOB.launchTime, "When the launch whose work this is began; a relaunch by name keeps it.") { dateTime() }
            column(JOB.params, "The launch's parameters.") { type = SCT.kObject }
            column(JOB.abortRequested, "Set by an abort request; the holder's heartbeat picks it up.") { type = SCT.boolean }
            primaryKey(JOB.jobType, JOB.launchKind, JOB.dryRun)
            withTransactions()
        }
        table(JOB.jobClientStatus, "The state of a job type's work on one client (#868).") {
            column(JOB.jobType, "The job type, the key its registration is made under.", required = true)
            dryRunColumn()
            statusColumns()
            column(JOB.startedAt, "When work on this client began in the current generation.") { dateTime() }
            forClient()
            primaryKey(JOB.jobType, PF.client, JOB.dryRun)
            withTransactions()
        }
        // Append-only and never locked: a trace entry is written once, on a connection of its own, and read back
        // in the order the database numbered it (issue #879).
        table(JOBT.jobTrace, "Why a launch did what it did: an append-only record of its decisions (#879).") {
            column(JOBT.traceSeq, "The entry's place in the order entries were written.", required = true, autoIncrement = true) {
                type = SCT.integer
            }
            column(JOB.jobType, "The job type.", required = true)
            column(JOB.launchKind, "The launch kind.", required = true)
            column(JOB.dryRun, "Whether the launch is a dry run.", required = true) { type = SCT.boolean }
            column(JOB.launchName, "The launch's name.", required = true)
            column(JOB.leaseId, "The launch claim this entry was written under, when there was one.")
            column(JOB.holder, "The node that wrote it.")
            column(JOBT.at, "When it happened, on the writing node's clock.", required = true) { dateTime() }
            column(JOBT.event, "What happened: a JobTraceEvent name.", required = true)
            column(PF.client, "The client it concerns, if one.")
            column(JOBT.taskKey, "The task it concerns, if one.")
            column(JOBT.message, "A description, when there is more to say than the event.")
            column(JOB.data, "The event's details.") { type = SCT.kObject }
            primaryKey(JOBT.traceSeq)
            index(JOB.jobType, JOB.launchKind, JOB.dryRun, JOB.launchName)
        }
    }

/**
 * Whether the row tracks a dry run. Part of both keys, so a dry run has rows of its own: it can never reset a real
 * run's row (discarding its generation and counters), and a real run can never adopt one whose work was not done.
 */
private fun TableBuilder.dryRunColumn() {
    column(JOB.dryRun, "Whether the row tracks a dry run, which checks for work but does none.", required = true) {
        type = SCT.boolean
    }
}

/** The columns the launch row and the per-client row share. */
private fun TableBuilder.statusColumns() {
    column(JOB.runStatus, "active, complete or aborted; null before the first claim.")
    column(JOB.launchName, "The name of the launch that holds, or last held, the row.")
    column(JOB.generationId, "The body of work the row tracks; incremented when a claim resets the row.") { type = SCT.integer }
    column(JOB.leaseId, "The current claim's id; every status write is conditional on it (fencing).")
    column(JOB.holder, "The node holding, or that last held, the row.")
    column(JOB.claimedAt, "When the current claim was taken.") { dateTime() }
    column(JOB.heartbeatAt, "The holder's last heartbeat: the lease is live until this plus the timeout.") { dateTime() }
    column(JOB.completed, "Tasks completed in this generation.") { type = SCT.integer }
    column(JOB.skipped, "Tasks skipped in this generation.") { type = SCT.integer }
    column(JOB.failed, "Tasks failed in this generation.") { type = SCT.integer }
    column(JOB.total, "The total number of tasks, when the job can count them up front.") { type = SCT.integer }
    column(JOB.data, "The aggregate across clients (launch row) and the history of ended attempts.") { type = SCT.kObject }
}
