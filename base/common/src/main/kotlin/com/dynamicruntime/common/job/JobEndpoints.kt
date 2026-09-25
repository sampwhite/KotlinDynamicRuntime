package com.dynamicruntime.common.job

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.ETAG
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchTypeBuilder
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.util.fmt
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toOptDouble
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Duration.Companion.milliseconds

/** Paths and type names of the batch-job operator surface (issue #869). */
@Suppress("ConstPropertyName")
object JOBEP {
    const val launch = "/operator/job/launch"
    const val status = "/operator/job/status"
    const val abort = "/operator/job/abort"
    const val trace = "/operator/job/trace"

    const val rowType = "JobRowInfo"
    const val typeStatusType = "JobTypeStatus"
    const val launchResultType = "JobLaunchInfo"
    const val abortResultType = "JobAbortInfo"
    const val traceEntryType = "JobTraceEntry"
}

/** Field names of the batch-job operator surface. Each name matches its value. */
@Suppress("ConstPropertyName")
object JOBF {
    const val jobType = "jobType"
    const val name = "name"
    const val mode = "mode"
    const val kind = "kind"
    const val clients = "clients"
    const val workAreas = "workAreas"
    const val redoWindowHours = "redoWindowHours"
    const val dryRun = "dryRun"
    const val description = "description"
    const val outcome = "outcome"
    const val reason = "reason"
    const val status = "status"
    const val launches = "launches"
    const val clientRows = "clientRows"
    const val requested = "requested"
    const val trace = "trace"
}

/**
 * The batch-job operator surface (issue #869): launch a job, read how its launches and clients stand, and ask a
 * launch to abort. In the `operator` section, so it takes that section's privileges: a deployment operator.
 *
 * A launch from here is always an **endpoint** launch -- scheduled launches are the scheduler's -- and it runs
 * asynchronously unless it asks for `sync`, which only a job able to count its tasks may do, within its cap.
 */
fun jobOperatorSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "job") {
    type(JOBEP.rowType) {
        type = SCT.kObject
        description = "One batch-job status row: a launch row, or one client's row."
        property(JOB.launchKind, "The launch kind, on a launch row.")
        property(PF.client, "The client, on a client row.")
        property(JOB.runStatus, "active, complete or aborted; absent before the first claim.")
        property(JOB.launchName, "The launch that holds, or last held, the row.")
        property(JOB.launchTime, "When the launch whose work this is began.") { dateTime() }
        property(JOB.startedAt, "When work on the client began in this generation.") { dateTime() }
        property(JOB.dryRun, "Whether the row tracks a dry run.", required = true) { type = SCT.boolean }
        property(JOB.generationId, "The body of work the row tracks.", required = true) { type = SCT.integer }
        property(JOB.holder, "The node holding, or that last held, the row.")
        property(JOB.claimedAt, "When the current claim was taken.") { dateTime() }
        property(JOB.heartbeatAt, "The holder's last heartbeat.") { dateTime() }
        property(JOB.abortRequested, "Whether an abort has been asked for.", required = true) { type = SCT.boolean }
        property(JOB.completed, "Tasks completed.", required = true) { type = SCT.integer }
        property(JOB.skipped, "Tasks that found nothing to do.", required = true) { type = SCT.integer }
        property(JOB.failed, "Tasks that failed while the job went on.", required = true) { type = SCT.integer }
        property(JOB.total, "The total number of tasks, when known.") { type = SCT.integer }
        property(JOB.params, "The launch's parameters, on a launch row.") { openObject() }
        property(JOB.aggregate, "Progress across clients, on a launch row.") { openObject() }
        property(JOB.history, "The last few ended attempts, oldest first.", required = true) {
            type = SCT.array
            items { openObject() }
        }
    }
    type(JOBEP.typeStatusType) {
        type = SCT.kObject
        description = "A registered job type and how its rows stand."
        property(JOBF.jobType, "The job type.", required = true)
        property(JOBF.description, "What the job does.", required = true)
        property(JOBF.launches, "Its launch rows: scheduled and endpoint.", required = true) {
            type = SCT.array
            items { ref(JOBEP.rowType) }
        }
        property(JOBF.clientRows, "Its per-client rows.", required = true) {
            type = SCT.array
            items { ref(JOBEP.rowType) }
        }
    }
    type(JOBEP.launchResultType) {
        type = SCT.kObject
        property(JOBF.outcome, "What became of the launch.", required = true) {
            JobLaunchOutcome.entries.forEach { option(it.name) }
        }
        property(JOBF.reason, "Why a run stopped, or why nothing ran.")
        property(JOB.completed, "Tasks completed.") { type = SCT.integer }
        property(JOB.skipped, "Tasks that found nothing to do.") { type = SCT.integer }
        property(JOB.failed, "Tasks that failed while the job went on.") { type = SCT.integer }
        property(JOB.total, "The total number of tasks, when known.") { type = SCT.integer }
        property(JOBF.status, "The launch row afterwards.") { ref(JOBEP.rowType) }
    }
    type(JOBEP.traceEntryType) {
        type = SCT.kObject
        description = "One entry of a launch's trace."
        property(JOBT.traceSeq, "The entry's place in the order entries were written.", required = true) { type = SCT.integer }
        property(JOB.leaseId, "The launch claim it was written under.")
        property(JOB.holder, "The node that wrote it.")
        property(JOBT.at, "When it happened.") { dateTime() }
        property(JOBT.event, "What happened.") { JobTraceEvent.entries.forEach { option(it.name) } }
        property(PF.client, "The client it concerns.")
        property(JOBT.taskKey, "The task it concerns.")
        property(JOBT.message, "A description.")
        property(JOB.data, "The event's details.") { openObject() }
    }
    type(JOBEP.abortResultType) {
        type = SCT.kObject
        property(JOBF.requested, "Whether there was an active launch to ask.", required = true) { type = SCT.boolean }
    }

    generalEndpoint(
        JOBEP.launch,
        "Launches a batch job from an endpoint, on every client this node carries or the ones named.",
        HttpMethod.POST,
        outputRef = JOBEP.launchResultType,
        inputFields = {
            field(JOBF.jobType, "The job type to launch.", required = true)
            field(
                JOBF.name,
                "The launch's name. Launching again under the same name continues the work already done under it.",
                required = true,
            )
            field(JOBF.mode, "sync runs it before answering (a small job only); async, the default, answers at once.") {
                option(JobRunMode.async.name)
                option(JobRunMode.sync.name)
            }
            field(JOBF.clients, "The clients to run on; all of this node's when absent.") {
                type = SCT.array
                items { type = SCT.string }
            }
            field(JOBF.workAreas, "Areas to restrict the job to, in the job's own terms; everything when absent.") {
                type = SCT.array
                items { type = SCT.string }
            }
            field(
                JOBF.redoWindowHours,
                "Adopt a client's work done by another launch if it began within this many hours before this one.",
            ) { type = SCT.number }
            field(JOBF.dryRun, "Check for work and report it, but do none.") { type = SCT.boolean }
            field(JOBF.trace, "How much of the launch to trace; the job profile's default when absent.") {
                JobTraceLevel.entries.forEach { option(it.name) }
            }
        },
        tags = setOf(ETAG.internal),
    ) { c, request ->
        val jobType = request[JOBF.jobType].toOptStr() ?: throw KdrException.mkInput("A ${JOBF.jobType} is required.")
        val name = request[JOBF.name].toOptStr() ?: throw KdrException.mkInput("A ${JOBF.name} is required.")
        val trace = JobTraceLevel.entries.firstOrNull { it.name == request[JOBF.trace].toOptStr() }
        val dryRun = request[JOBF.dryRun] == true
        val mode = if (request[JOBF.mode].toOptStr() == JobRunMode.sync.name) JobRunMode.sync else JobRunMode.async
        val result = JobService.get(c).launch(
            c, jobType, name, JobLaunchKind.endpoint, mode,
            clients = request[JOBF.clients]?.let { v -> v.toJsonListOrEmpty().mapNotNull { it.toOptStr() } },
            workAreas = request[JOBF.workAreas].toJsonListOrEmpty().mapNotNull { it.toOptStr() },
            redoWindow = request[JOBF.redoWindowHours].toOptDouble()?.let { (it * 3_600_000).toLong().milliseconds },
            dryRun = dryRun,
            trace = trace,
        )
        val out = linkedMapOf<String, Any?>(JOBF.outcome to result.outcome.name)
        result.reason?.let { out[JOBF.reason] = it }
        result.counts?.let { counts -> counts.toJsonMap().forEach { (k, v) -> if (v != null) out[k] = v } }
        JobStatusRows.readLaunch(c, jobType, JobLaunchKind.endpoint, dryRun)?.let { out[JOBF.status] = rowInfo(it) }
        out
    }

    listEndpoint(
        JOBEP.status,
        "Reports how each registered batch job's launches and clients stand, or one job's when named.",
        outputRef = JOBEP.typeStatusType,
        inputFields = {
            field(JOBF.jobType, "Report only this job type.")
            field(JOBF.dryRun, "Report the dry runs' rows instead of the real ones.") {
                type = SCT.boolean
                allowCoerce = true
            }
        },
        noLimit = true,
        tags = setOf(ETAG.internal),
    ) { c, request ->
        val service = JobService.get(c)
        val dryRun = request[JOBF.dryRun] == true
        val defs = request[JOBF.jobType].toOptStr()?.let { listOf(service.def(it)) } ?: service.defs().sortedBy { it.jobType }
        defs.map { def ->
            linkedMapOf(
                JOBF.jobType to def.jobType,
                JOBF.description to def.description,
                JOBF.launches to JobLaunchKind.entries.mapNotNull { JobStatusRows.readLaunch(c, def.jobType, it, dryRun) }
                    .map { rowInfo(it) },
                JOBF.clientRows to JobStatusRows.readClients(c, def.jobType, dryRun).map { rowInfo(it) },
            )
        }
    }

    listEndpoint(
        JOBEP.trace,
        "Reports a launch's trace (issue #879): the decisions it made, in the order they were recorded.",
        outputRef = JOBEP.traceEntryType,
        inputFields = {
            field(JOBF.jobType, "The job type.", required = true)
            field(JOBF.name, "The launch's name.", required = true)
            field(JOBF.kind, "Which launch: the endpoint one (the default) or the scheduled one.") {
                JobLaunchKind.entries.forEach { option(it.name) }
            }
            field(JOBF.dryRun, "The dry run's trace instead of the real one's.") {
                type = SCT.boolean
                allowCoerce = true
            }
        },
        hasMore = true,
        tags = setOf(ETAG.internal),
    ) { c, request ->
        val jobType = request[JOBF.jobType].toOptStr() ?: throw KdrException.mkInput("A ${JOBF.jobType} is required.")
        val name = request[JOBF.name].toOptStr() ?: throw KdrException.mkInput("A ${JOBF.name} is required.")
        val kind = JobLaunchKind.entries.firstOrNull { it.name == request[JOBF.kind].toOptStr() } ?: JobLaunchKind.endpoint
        JobService.get(c).def(jobType)
        JobTraceRows.read(c, jobType, kind, name, request[JOBF.dryRun] == true).map { it.toInfo() }
    }

    generalEndpoint(
        JOBEP.abort,
        "Asks a batch job's active launch to abort; its holder stops at its next heartbeat.",
        HttpMethod.POST,
        outputRef = JOBEP.abortResultType,
        inputFields = {
            field(JOBF.jobType, "The job type whose launch to abort.", required = true)
            field(JOBF.kind, "Which launch: the endpoint one (the default) or the scheduled one.") {
                JobLaunchKind.entries.forEach { option(it.name) }
            }
            field(JOBF.dryRun, "Abort the dry run's launch instead of the real one.") { type = SCT.boolean }
        },
        tags = setOf(ETAG.internal),
    ) { c, request ->
        val jobType = request[JOBF.jobType].toOptStr() ?: throw KdrException.mkInput("A ${JOBF.jobType} is required.")
        val kind = JobLaunchKind.entries.firstOrNull { it.name == request[JOBF.kind].toOptStr() } ?: JobLaunchKind.endpoint
        mapOf(JOBF.requested to JobService.get(c).abort(c, jobType, kind, request[JOBF.dryRun] == true))
    }
}

/** An object whose keys are its own business: a launch's parameters, its aggregate, a history entry. */
private fun SchTypeBuilder.openObject() {
    type = SCT.kObject
    additionalProperties = true
}

/** [row] as the operator surface shows it: dates formatted, and a value that is absent left out. */
private fun rowInfo(row: JobStatusRow): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>()
    fun put(key: String, value: Any?) {
        if (value != null) out[key] = value
    }
    put(JOB.launchKind, row.launchKind?.name)
    put(PF.client, row.client)
    put(JOB.runStatus, row.runStatus?.name)
    put(JOB.launchName, row.launchName)
    put(JOB.launchTime, row.launchTime?.fmt())
    put(JOB.startedAt, row.startedAt?.fmt())
    put(JOB.dryRun, row.dryRun)
    put(JOB.generationId, row.generationId)
    put(JOB.holder, row.holder)
    put(JOB.claimedAt, row.claimedAt?.fmt())
    put(JOB.heartbeatAt, row.heartbeatAt?.fmt())
    put(JOB.abortRequested, row.abortRequested)
    row.counts.toJsonMap().forEach { (k, v) -> put(k, v) }
    if (row.launchKind != null) put(JOB.params, row.params)
    if (row.launchKind != null) put(JOB.aggregate, row.aggregate)
    put(JOB.history, row.history)
    return out
}
