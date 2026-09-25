package com.dynamicruntime.common.job

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.JobHandling
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.logging.KdrLogger
import com.dynamicruntime.common.util.toOptStr
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Instant

/** Topic logger for batch jobs (issue #869). */
object LogJob : KdrLogger("job")

/** How a launch runs. */
@Suppress("EnumEntryName")
enum class JobRunMode {
    /** On the calling thread, one task at a time: a small demo run that answers when it is done. */
    sync,

    /** On a driver thread of its own, with tasks spread across the job pool; the launch answers at once. */
    async,

    /**
     * As [async], but driven from the calling thread, which waits: the pooled path for a caller that wants its
     * answer -- a test exercising the pool, or a script. Not subject to the synchronous caps.
     */
    pooledOnCaller,
}

/** What became of a launch. */
@Suppress("EnumEntryName")
enum class JobLaunchOutcome {
    /** An async launch has started; its status row reports how it goes. */
    started,

    /** The run finished every client. */
    completed,

    /** The run stopped before finishing; relaunching with the same name picks up where it stopped. */
    aborted,

    /** A launch of this name already finished its work; nothing ran. */
    alreadyComplete,

    /** Another launch holds the launch row and its lease is live; nothing ran. */
    lockedOut,
}

/** The answer to a launch: its outcome, why it stopped when it did, and its counters when it ran. */
class JobLaunchResult(val outcome: JobLaunchOutcome, val reason: String? = null, val counts: JobCounts? = null)

/**
 * One launch in progress (issue #869): the loop that claims each client in turn, runs its tasks, heartbeats, and
 * finishes the rows. The same loop serves every [JobRunMode]; what differs is only where tasks run -- inline on
 * the driving thread, or on a pool -- and which thread drives.
 *
 * **Stopping is cooperative.** Anything that decides the run must stop -- a requested abort, a fenced lease, a
 * stall, a task's abort variant, the hard cap, a shutdown -- records a reason; the loop stops dispatching, lets
 * the tasks in flight notice (they check through [JobRunCxt.checkAbort]) and finish, and ends the rows. The first
 * reason recorded is the one reported.
 *
 * All the bookkeeping -- counters, leases, heartbeats -- happens on the driving thread; a task hands back an
 * outcome rather than touching shared state, so nothing here needs a lock beyond the stop reason.
 */
internal class JobRun(
    private val def: JobDef,
    private val profile: JobProfile,
    val launch: JobLaunch,
    private val clients: List<String>,
    private val workAreas: List<String>,
    private val mode: JobRunMode,
    /** The context the run works in: the system user, never whoever launched it. */
    private val cxt: KdrCxt,
    /** The node's job pool, for pooled work on platform threads. */
    private val pool: () -> ExecutorService,
    /** The task total counted before a synchronous launch was admitted; null when not counted. */
    private val countedTotal: Long?,
) {
    private val lock = Any()
    private var stopReason: String? = null

    @Volatile
    private var shuttingDown = false

    private val heartbeatInterval: Duration = profile.heartbeatIn(cxt.instanceConfig.env)
    private lateinit var launchLease: JobLease
    private var launchCounts = JobCounts.zero
    private var launchFenced = false
    private var clientLease: JobLease? = null
    private var clientCounts = JobCounts.zero
    private var clientFenced = false
    private var lastBeat: Instant = Instant.DISTANT_PAST
    private var lastProgress: Instant = Instant.DISTANT_PAST
    private var tasksRun = 0
    private var clientsDone = 0
    private val busyClients = mutableListOf<String>()

    /** Why the run is stopping, or null while it is not. */
    fun stopReason(): String? = synchronized(lock) { stopReason }

    /** Asks the run to stop for [reason]; the first reason asked for is the one kept. */
    fun stop(reason: String) {
        synchronized(lock) { if (stopReason == null) stopReason = reason }
    }

    /** Stops the run for a node shutdown: it lets its rows go (released) rather than ending them aborted. */
    fun shutDown() {
        shuttingDown = true
        stop("The node is shutting down.")
    }

    /** Runs the launch that [lease] holds to its end, and ends its rows. */
    fun execute(lease: JobLease): JobLaunchResult {
        launchLease = lease
        launchCounts = if (countedTotal != null) lease.counts.withTotal(countedTotal) else lease.counts
        lastBeat = now()
        lastProgress = lastBeat
        try {
            for (client in clients) {
                if (stopReason() != null) break
                when (val claim = JobStatusRows.claimClient(cxt, lease, client, profile.leaseTimeout)) {
                    is JobClaim.LockedOut -> busyClients.add(client)
                    is JobClaim.AlreadyComplete -> clientsDone++
                    is JobClaim.Claimed -> runClient(claim.lease)
                }
            }
        } catch (e: Exception) {
            LogJob.error(cxt, e) { "Job '${def.jobType}' launch '${launch.name}' failed outside any task." }
            stop("The run failed: ${e.message}")
        }
        return end()
    }

    private fun runClient(lease: JobLease) {
        val client = lease.client ?: return
        clientLease = lease
        clientCounts = lease.counts
        clientFenced = false
        lastProgress = now()
        val runCxt = JobRunCxt(cxt.mkSubContext("job", client), launch, workAreas, client, lease.generationId) { stopReason() }
        try {
            val keys = def.tasks(runCxt, client)
            clientCounts = clientCounts.withTotal(keys.size.toLong())
            runTasks(runCxt, keys)
        } catch (e: Exception) {
            LogJob.error(cxt, e) { "Job '${def.jobType}' failed listing or running client '$client'." }
            stop("The run failed on client '$client': ${e.message}")
        }
        val reason = stopReason()
        if (!clientFenced) {
            when {
                shuttingDown -> JobStatusRows.release(cxt, lease, clientCounts)
                reason == null -> JobStatusRows.finish(cxt, lease, JobAttemptEnd.complete, clientCounts)
                else -> JobStatusRows.finish(cxt, lease, JobAttemptEnd.aborted, clientCounts, reason)
            }
        }
        if (reason == null) clientsDone++
        clientLease = null
    }

    private fun runTasks(runCxt: JobRunCxt, keys: List<String>) {
        val dispatcher = mkDispatcher(runCxt)
        dispatcher.use { dispatcher ->
            val pending = keys.iterator()
            var inFlight = 0
            var stoppedAtNanos = 0L
            while (true) {
                while (stopReason() == null && inFlight < dispatcher.limit && pending.hasNext()) {
                    dispatcher.submit(pending.next())
                    inFlight++
                }
                if (inFlight == 0) break
                val outcome = dispatcher.poll(heartbeatInterval)
                // The heartbeat comes before the outcome is recorded, so its stall check measures the gap since
                // the *previous* task finished: a task that took half the lease is itself the stall.
                heartbeatIfDue()
                if (outcome != null) {
                    inFlight--
                    record(outcome)
                }
                if (stopReason() != null) {
                    // A task that never notices the stop cannot be forced to end; after a lease's length the run
                    // stops waiting for it and ends its rows anyway.
                    if (stoppedAtNanos == 0L) stoppedAtNanos = System.nanoTime()
                    if (System.nanoTime() - stoppedAtNanos > profile.leaseTimeout.inWholeNanoseconds) {
                        LogJob.warn(cxt) { "Job '${def.jobType}' abandoned $inFlight task(s) still running after it stopped." }
                        break
                    }
                }
            }
        }
    }

    /** Folds one task's [outcome] into the counters, and stops the run when it calls for that. */
    private fun record(outcome: TaskOutcome) {
        when (outcome.kind) {
            TaskKind.done -> count(JobCounts(completed = 1))
            TaskKind.nothingToDo -> count(JobCounts(skipped = 1))
            TaskKind.failed -> {
                count(JobCounts(failed = 1))
                val e = outcome.error
                val scenario = (e as? KdrException)?.extraData?.get(KdrException.scenarioKey).toOptStr()
                LogJob.warn(cxt) {
                    "Job '${def.jobType}' task '${outcome.key}' failed" +
                        (if (scenario != null) " ($scenario)" else "") + ": ${e?.message}"
                }
            }
            TaskKind.abort -> stop(outcome.error?.message ?: "A task aborted the job.")
            TaskKind.stopped -> {}
        }
        if (outcome.kind != TaskKind.stopped) {
            tasksRun++
            lastProgress = now()
        }
        if (mode == JobRunMode.sync && tasksRun >= profile.syncHardCap) {
            stop("A synchronous run reached its hard cap of ${profile.syncHardCap} tasks.")
        }
    }

    private fun count(delta: JobCounts) {
        clientCounts = clientCounts.plus(delta)
        launchCounts = launchCounts.plus(delta)
    }

    /**
     * Renews both leases when a heartbeat is due, recording progress. A fenced lease stops the run -- the row is
     * no longer this run's -- as does a requested abort, and so does a run that has gone half its lease without
     * finishing a task, before its lease can lapse under it.
     */
    private fun heartbeatIfDue() {
        val now = now()
        if (now - lastBeat < heartbeatInterval) return
        lastBeat = now
        val lease = clientLease
        if (lease != null && !clientFenced && JobStatusRows.heartbeat(cxt, lease, clientCounts).fenced) {
            clientFenced = true
            stop("Client '${lease.client}' was taken over by another launch; this run was fenced out.")
        }
        if (!launchFenced) {
            val beat = JobStatusRows.heartbeat(cxt, launchLease, launchCounts, aggregate())
            if (beat.fenced) {
                launchFenced = true
                stop("The launch was taken over by another; this run was fenced out.")
            } else if (beat.abortRequested) {
                stop("Abort requested.")
            }
        }
        if (now - lastProgress >= profile.leaseTimeout / 2) {
            stop("Too slow: no task finished in ${now - lastProgress}, half the lease timeout or more.")
        }
    }

    private fun aggregate(): Map<String, Any?> = linkedMapOf(
        JOBA.clients to clients.size,
        JOBA.clientsDone to clientsDone,
        JOBA.currentClient to clientLease?.client,
        JOBA.busyClients to busyClients.toList(),
    )

    /** Ends the launch row: complete only when every client finished and nothing stopped the run. */
    private fun end(): JobLaunchResult {
        val stopped = stopReason()
        val reason = stopped ?: if (busyClients.isNotEmpty()) {
            "Clients busy under another launch: ${busyClients.joinToString(", ")}. Relaunch with the same name to " +
                "finish them."
        } else {
            null
        }
        if (!launchFenced) {
            JobStatusRows.heartbeat(cxt, launchLease, launchCounts, aggregate())
            when {
                shuttingDown -> JobStatusRows.release(cxt, launchLease, launchCounts)
                reason == null -> JobStatusRows.finish(cxt, launchLease, JobAttemptEnd.complete, launchCounts)
                else -> JobStatusRows.finish(cxt, launchLease, JobAttemptEnd.aborted, launchCounts, reason)
            }
        }
        val outcome = if (reason == null) JobLaunchOutcome.completed else JobLaunchOutcome.aborted
        return JobLaunchResult(outcome, reason, launchCounts)
    }

    private fun now(): Instant = Instant.fromEpochMilliseconds(cxt.instanceNow().toEpochMilliseconds())

    // --- running one task ------------------------------------------------------------------------------------

    /** Runs [key]'s task, retrying a retryable failure with backoff, and answers what became of it. */
    private fun attempt(runCxt: JobRunCxt, key: String): TaskOutcome {
        var retries = 0
        while (true) {
            if (stopReason() != null) return TaskOutcome(key, TaskKind.stopped)
            try {
                val result = def.runTask(runCxt, key)
                return TaskOutcome(key, if (result == JobTaskResult.done) TaskKind.done else TaskKind.nothingToDo)
            } catch (e: KdrException) {
                when (e.jobHandling) {
                    JobHandling.abortJob -> return TaskOutcome(key, TaskKind.abort, e)
                    JobHandling.retryTask -> {
                        if (retries >= profile.retryLimit) return TaskOutcome(key, TaskKind.failed, e)
                        retries++
                        Thread.sleep((profile.retryBackoff * retries).inWholeMilliseconds)
                    }
                    // Unclassified is treated as a skip: one unexpected record should not stop the whole job.
                    JobHandling.skipTask, null -> return TaskOutcome(key, TaskKind.failed, e)
                }
            } catch (e: Exception) {
                return TaskOutcome(key, TaskKind.failed, e)
            }
        }
    }

    private fun mkDispatcher(runCxt: JobRunCxt): Dispatcher = when {
        mode == JobRunMode.sync -> InlineDispatcher(runCxt)
        profile.virtualThreads > 0 ->
            PoolDispatcher(runCxt, Executors.newVirtualThreadPerTaskExecutor(), profile.virtualThreads, owned = true)
        else -> PoolDispatcher(runCxt, pool(), profile.platformThreads, owned = false)
    }

    /** Where a client's tasks run: [submit] one, [poll] for the next that finished. */
    private interface Dispatcher : AutoCloseable {
        /** How many tasks may be in flight at once. */
        val limit: Int

        fun submit(key: String)

        /** The next finished task's outcome, waiting at most [wait]; null if none finished in time. */
        fun poll(wait: Duration): TaskOutcome?

        override fun close() {}
    }

    /** Runs each task as it is submitted, on the driving thread. */
    private inner class InlineDispatcher(private val runCxt: JobRunCxt) : Dispatcher {
        private val done = ArrayDeque<TaskOutcome>()
        override val limit: Int = 1

        override fun submit(key: String) {
            done.addLast(attempt(runCxt, key))
        }

        override fun poll(wait: Duration): TaskOutcome? = done.removeFirstOrNull()
    }

    /** Runs tasks on [executor], at most [limit] at once; shuts the executor down afterwards when [owned]. */
    private inner class PoolDispatcher(
        private val runCxt: JobRunCxt,
        private val executor: ExecutorService,
        override val limit: Int,
        private val owned: Boolean,
    ) : Dispatcher {
        private val completions = ExecutorCompletionService<TaskOutcome>(executor)

        override fun submit(key: String) {
            completions.submit { attempt(runCxt, key) }
        }

        override fun poll(wait: Duration): TaskOutcome? =
            completions.poll(wait.inWholeMilliseconds, TimeUnit.MILLISECONDS)?.get()

        override fun close() {
            if (owned) executor.shutdownNow()
        }
    }
}

/** Keys of the launch row's aggregate. Each name matches its value. */
@Suppress("ConstPropertyName")
object JOBA {
    const val clients = "clients"
    const val clientsDone = "clientsDone"
    const val currentClient = "currentClient"
    const val busyClients = "busyClients"
}

@Suppress("EnumEntryName")
private enum class TaskKind { done, nothingToDo, failed, abort, stopped }

private class TaskOutcome(val key: String, val kind: TaskKind, val error: Exception? = null)

private fun JobCounts.plus(o: JobCounts): JobCounts =
    JobCounts(completed + o.completed, skipped + o.skipped, failed + o.failed, total)

private fun JobCounts.withTotal(t: Long): JobCounts = JobCounts(completed, skipped, failed, t)
