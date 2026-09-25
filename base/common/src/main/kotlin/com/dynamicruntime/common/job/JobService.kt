package com.dynamicruntime.common.job

import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.startup.ServiceInitializer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * Runs this node's batch jobs (issue #869): the registry of job types, the node's job pool, and the launches in
 * progress. Launching goes through [launch]; what a launch does is [JobRun]'s, and who may work on what is
 * [JobStatusRows]'.
 *
 * **The pool and the drivers are this node's first background threads**, created on the first pooled launch and
 * not before, so a node that never runs a job starts none. They are daemon threads, and a JVM shutdown
 * [close]s the service: each run in progress is asked to stop and, given a few seconds, lets its rows go, so
 * another node can take the work up at once instead of waiting out the lease.
 */
class JobService : ServiceInitializer, AutoCloseable {
    override val serviceName: String = JobService.serviceName

    private var defs: Map<String, JobDef> = emptyMap()

    /** The pool's size, settled at boot so the node's configuration is read -- and reported -- up front. */
    private var poolSize: Int = 1
    private val runs = ConcurrentHashMap<String, Pair<JobRun, Thread?>>()

    @Volatile
    private var poolOrNull: ExecutorService? = null
    private val poolLock = Any()

    override fun checkInit(cxt: KdrCxt) {
        poolSize = cxt.getEnvVar(poolThreadsVar)?.toIntOrNull()?.takeIf { it > 0 }
            ?: Runtime.getRuntime().availableProcessors()
        val registered = SchemaService.get(cxt).jobDefs()
        val dupes = registered.groupBy { it.jobType }.filterValues { it.size > 1 }.keys
        if (dupes.isNotEmpty()) {
            throw KdrException("Job types registered more than once: ${dupes.sorted().joinToString(", ")}.")
        }
        defs = registered.associateBy { it.jobType }
    }

    /** The registered job types, by type. */
    fun defs(): Collection<JobDef> = defs.values

    /** The job type [jobType], refusing one nobody registered. */
    fun def(jobType: String): JobDef =
        defs[jobType] ?: throw KdrException("No job type '$jobType' is registered.", code = EXC.notFound)

    /**
     * Launches [jobType] as [kind] under [name] (issue #869), on [clients] -- or every client this node carries
     * -- and runs it in [mode]. A synchronous launch is admitted only for a job that can count its tasks, and
     * only while the count is within its profile's cap; it is refused (400) otherwise, before anything is
     * claimed. A launch whose row another holds live answers [JobLaunchOutcome.lockedOut], and one whose named
     * work is already complete answers [JobLaunchOutcome.alreadyComplete]; neither runs anything.
     */
    fun launch(
        cxt: KdrCxt,
        jobType: String,
        name: String,
        kind: JobLaunchKind = JobLaunchKind.endpoint,
        mode: JobRunMode = JobRunMode.async,
        clients: List<String>? = null,
        workAreas: List<String> = emptyList(),
        redoWindow: Duration? = null,
        dryRun: Boolean = false,
    ): JobLaunchResult {
        val def = def(jobType)
        val profile = def.profile.resolve(cxt)
        val runClients = resolveClients(cxt, clients)
        val params = linkedMapOf(
            JOBP.clients to clients, JOBP.workAreas to workAreas, JOBP.redoWindowHours to redoWindow?.let { it.inWholeMilliseconds / 3_600_000.0 },
            JOBP.mode to mode.name, JOBP.launchedBy to cxt.userProfile.userId,
        )
        val launch = JobLaunch(jobType, kind, name, params, dryRun, redoWindow)
        // The job works as the system user, on a context of its own: not the launching request's, which ends
        // with the request, nor its caller's identity, which is recorded above rather than acted as.
        val jobCxt = KdrCxt.mkSimpleCxt("job", cxt.instanceConfig)
        val counted = if (mode == JobRunMode.sync) admitSync(jobCxt, def, profile, launch, workAreas, runClients) else null
        val lease = when (val claim = JobStatusRows.claimLaunch(jobCxt, launch, profile.leaseTimeout)) {
            is JobClaim.LockedOut -> return JobLaunchResult(
                JobLaunchOutcome.lockedOut,
                "Launch '${claim.launchName}' holds it" + (claim.holder?.let { " on $it" } ?: "") + ".",
            )
            is JobClaim.AlreadyComplete -> return JobLaunchResult(JobLaunchOutcome.alreadyComplete, counts = claim.counts)
            is JobClaim.Claimed -> claim.lease
        }
        val run = JobRun(def, profile, launch, runClients, workAreas, mode, jobCxt, ::pool, counted)
        val key = runKey(jobType, kind, dryRun)
        if (mode != JobRunMode.async) {
            runs[key] = run to null
            try {
                return run.execute(lease)
            } finally {
                runs.remove(key)
            }
        }
        val driver = Thread.ofPlatform().daemon().name("kdr-job-$jobType").unstarted {
            try {
                run.execute(lease)
            } finally {
                runs.remove(key)
            }
        }
        runs[key] = run to driver
        InstanceRegistry.registerForShutdown(this)
        driver.start()
        return JobLaunchResult(JobLaunchOutcome.started)
    }

    /**
     * Asks the launch of [jobType] as [kind] to abort. Recorded on its row, so the holder's next heartbeat picks
     * it up whichever node it runs on; a run on this node is also told at once. Answers whether there was an
     * active launch to ask.
     */
    fun abort(cxt: KdrCxt, jobType: String, kind: JobLaunchKind = JobLaunchKind.endpoint, dryRun: Boolean = false): Boolean {
        def(jobType)
        runs[runKey(jobType, kind, dryRun)]?.first?.stop("Abort requested.")
        return JobStatusRows.requestAbort(cxt, jobType, kind, dryRun)
    }

    /** Stops every run on this node and lets its rows go; called on JVM shutdown. */
    override fun close() {
        val active = runs.values.toList()
        active.forEach { it.first.shutDown() }
        active.mapNotNull { it.second }.forEach { runCatching { it.join(shutdownGraceMs) } }
        poolOrNull?.shutdownNow()
    }

    private fun resolveClients(cxt: KdrCxt, clients: List<String>?): List<String> {
        val service = ClientService.get(cxt)
        if (clients == null) return service.clients.keys.filter { service.isPresent(it) }.sorted()
        val unknown = clients.filterNot { service.isPresent(it) }
        if (unknown.isNotEmpty()) {
            throw KdrException.mkInput("This node carries no client ${unknown.joinToString(", ") { "'$it'" }}.")
        }
        return clients.distinct()
    }

    /** Counts a synchronous launch's tasks and refuses it when it cannot be counted or is over the cap. */
    private fun admitSync(
        cxt: KdrCxt,
        def: JobDef,
        profile: JobProfile,
        launch: JobLaunch,
        workAreas: List<String>,
        clients: List<String>,
    ): Long {
        val counter = def.countTasks ?: throw KdrException.mkInput(
            "Job '${def.jobType}' cannot count its tasks ahead of time, so it cannot run synchronously; launch it " +
                "asynchronously.",
        )
        val total = clients.sumOf { client ->
            counter(JobRunCxt(cxt.mkSubContext("job", client), launch, workAreas, client, 0) { null }, client).toLong()
        }
        if (total > profile.syncTaskCap) {
            throw KdrException.mkInput(
                "Job '${def.jobType}' has $total tasks, more than a synchronous run may do " +
                    "(${profile.syncTaskCap}); launch it asynchronously.",
            )
        }
        return total
    }

    private fun pool(): ExecutorService {
        poolOrNull?.let { return it }
        synchronized(poolLock) {
            poolOrNull?.let { return it }
            val n = AtomicInteger()
            val created = Executors.newFixedThreadPool(poolSize) { r ->
                Thread(r, "kdr-job-pool-${n.incrementAndGet()}").also { it.isDaemon = true }
            }
            poolOrNull = created
            InstanceRegistry.registerForShutdown(this)
            return created
        }
    }

    private fun runKey(jobType: String, kind: JobLaunchKind, dryRun: Boolean) = "$jobType/${kind.name}/$dryRun"

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "JobService"

        /** How long a shutdown waits for each run to let its rows go. */
        private const val shutdownGraceMs = 5_000L

        val poolThreadsVar = EnvVarDef(
            "KDR_JOB_POOL_THREADS", group = ENVGRP.jobs, defaultDoc = "the number of processors",
            description = "The size of the node's batch-job thread pool, which every job's CPU-bound tasks " +
                "share (a job's profile says how many of them it may use at once). Created on the first pooled " +
                "launch, so a node that runs no jobs starts no threads.",
        )

        fun get(cxt: KdrCxt): JobService = cxt.instanceConfig.get(serviceName) as? JobService
            ?: throw KdrException("$serviceName is not registered.")
    }
}

/** Keys of a launch's recorded parameters. Each name matches its value. */
@Suppress("ConstPropertyName")
object JOBP {
    const val clients = "clients"
    const val workAreas = "workAreas"
    const val redoWindowHours = "redoWindowHours"
    const val mode = "mode"
    const val launchedBy = "launchedBy"
}
