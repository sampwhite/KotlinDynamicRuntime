package com.dynamicruntime.common.job

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.JobHandling
import com.dynamicruntime.common.exception.KdrException

/**
 * A job type, as a component registers it (issue #869) through `SchemaCollector.addJob`.
 *
 * A job's work is divided by **client** and then into **tasks**, each an atomic piece of work named by a string
 * key -- typically the id of the resource it is for. The runner takes the clients one at a time, asks [tasks] for
 * a client's keys, and runs [runTask] on each, spreading a client's tasks across the job pool.
 *
 * **Doing a task twice is the job's to prevent.** A client's row adopted after a failure resumes that client from
 * its task list's start, so a task body should find out cheaply that it has nothing to do (and answer
 * [JobTaskResult.nothingToDo]), or sign its work off against [JobRunCxt.generationId] where finding out is not
 * cheap.
 */
class JobDef(
    /** The key the job is registered, launched, and tracked under. */
    val jobType: String,
    val description: String,
    val profile: JobProfile,
    /** The keys of the tasks to run for a client, in order; empty for a client the job has nothing for. */
    val tasks: (JobRunCxt, String) -> List<String>,
    /** Runs one task, named by its key. Throws a [KdrException], with a [JobHandling] where it matters, to fail. */
    val runTask: (JobRunCxt, String) -> JobTaskResult,
    /**
     * Counts a client's tasks before anything runs, for a job that can do so cheaply (from a cache, say). Only a
     * job that can count may be launched synchronously, since the count is what a synchronous launch is admitted
     * on.
     */
    val countTasks: ((JobRunCxt, String) -> Int)? = null,
    /** When the job runs on its own (issue #870); null for a job that runs only when launched. */
    val schedule: JobSchedule? = null,
)

/** What a task did. A failure is not a result: it is thrown. */
@Suppress("EnumEntryName")
enum class JobTaskResult {
    /** The task did its work: counted as completed. */
    done,

    /** The task found nothing needed doing: counted as skipped. */
    nothingToDo,
}

/**
 * What a job's code is handed: the context to work in, the launch it belongs to, and the means to notice it is
 * being stopped. One per client, bound to that client.
 */
class JobRunCxt internal constructor(
    /** A context bound to [client] (or the job's own context, before any client), acting as the system user. */
    val cxt: KdrCxt,
    val launch: JobLaunch,
    /** Free-form areas a launch may name to restrict what the job does; empty means everything. */
    val workAreas: List<String>,
    /** The client being worked, or null when counting ahead of a claim has no client row yet. */
    val client: String?,
    /** The generation of [client]'s row this work belongs to; zero before the row is claimed. */
    val generationId: Long,
    private val tracer: JobTracer?,
    private val stopping: () -> String?,
) {
    /** Whether the launch is a dry run: it checks for work and reports it, but does none. */
    val dryRun: Boolean get() = launch.dryRun

    /** Why the run is stopping, or null while it is not. */
    val stopReason: String? get() = stopping()

    /**
     * Adds a note of the job's own to the launch's trace (issue #879), recorded at task level: why a task decided
     * what it did, in terms only the job knows. Buffered and written by the run later, never in the caller's
     * transaction, so it is kept even when the work it describes rolls back.
     */
    fun trace(message: String, taskKey: String? = null, data: Map<String, Any?>? = null) {
        tracer?.record(JobTraceEvent.jobNote, client, taskKey, message, data)
    }

    /**
     * Throws the abort variant if the run is stopping. A task that does more than one read, or a long stretch of
     * CPU work, calls this between them, so a stop does not wait for the task to run to its end.
     */
    fun checkAbort() {
        val reason = stopping() ?: return
        throw KdrException.mkJob("The job is stopping: $reason", JobHandling.abortJob, JOBR.stopping)
    }
}

/** Scenario codes the runner itself puts on the failures it raises. Each name matches its value. */
@Suppress("ConstPropertyName")
object JOBR {
    /** A task noticed the run stopping (a requested abort, a fenced lease, a stall) through `checkAbort`. */
    const val stopping = "stopping"
}
