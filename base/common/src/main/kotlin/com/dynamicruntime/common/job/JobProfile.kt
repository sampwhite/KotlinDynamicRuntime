package com.dynamicruntime.common.job

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How a job runs (issue #869): its threads, its heartbeat and lease, its retries, and its synchronous caps. Jobs
 * that visit the same data in the same way share a profile; a deployment tunes a profile rather than each job.
 *
 * The values here are the code's defaults. A deployment overrides any of them under the instance-config entry
 * `jobProfiles.<name>` -- a map keyed by the [JPF] names, durations in milliseconds -- which [resolve] applies.
 */
class JobProfile(
    val name: String,
    /**
     * How many of the node's job-pool threads the job may use at once: CPU-bound work. Ignored when
     * [virtualThreads] is set.
     */
    val platformThreads: Int = 4,
    /**
     * When above zero, each task runs on a virtual thread of its own, at most this many at once: I/O-bound work
     * against a slow store (S3, say), where a task mostly waits.
     */
    val virtualThreads: Int = 0,
    /** How often the holder renews its leases and records progress. Null takes the environment's default. */
    val heartbeatInterval: Duration? = null,
    /**
     * How long a lease outlives its last heartbeat. A job that goes half this long without finishing a task
     * aborts itself, before its lease can lapse under it.
     */
    val leaseTimeout: Duration = 30.seconds,
    /** How many times a task that throws a retryable failure is retried before it counts as failed. */
    val retryLimit: Int = 3,
    /** The wait before the first retry; each later retry waits that much longer again. */
    val retryBackoff: Duration = 1.seconds,
    /** A synchronous launch whose counted tasks exceed this is refused: synchronous runs are for small demos. */
    val syncTaskCap: Int = 200,
    /** A synchronous run that reaches this many tasks aborts, in case the count it was admitted on was low. */
    val syncHardCap: Int = 250,
    /**
     * How much of a launch the trace records when the launch does not say (issue #879). Null takes the
     * deployment's default: [JobTraceLevel.task] on a test instance, [JobTraceLevel.off] elsewhere.
     */
    val trace: JobTraceLevel? = null,
    /** The most trace entries one launch records; past it, only launch-level entries are. */
    val traceMaxEntries: Int = 10_000,
    /** How many of a job type's launches the trace keeps. */
    val traceKeepLaunches: Int = 20,
) {
    /**
     * The trace level for a launch that asked for [requested] (or nothing): the request, else this profile's, else
     * task level on a test instance and off elsewhere.
     */
    fun traceLevel(cxt: KdrCxt, requested: JobTraceLevel?): JobTraceLevel =
        requested ?: trace ?: if (cxt.instanceConfig.isTestInstance) JobTraceLevel.task else JobTraceLevel.off

    /** [heartbeatInterval], or the environment's default: five seconds in production, two elsewhere. */
    fun heartbeatIn(env: String): Duration = heartbeatInterval ?: if (env == ENV.prod) 5.seconds else 2.seconds

    /** This profile with the deployment's overrides from `jobProfiles.<name>` applied. */
    fun resolve(cxt: KdrCxt): JobProfile {
        val over = cxt.instanceConfig.get("${JPF.jobProfiles}.$name").toJsonMapOrEmpty()
        if (over.isEmpty()) return this
        fun int(key: String, dflt: Int): Int = over[key].toOptLong()?.toInt() ?: dflt
        fun ms(key: String, dflt: Duration?): Duration? = over[key].toOptLong()?.milliseconds ?: dflt
        return JobProfile(
            name = name,
            platformThreads = int(JPF.platformThreads, platformThreads),
            virtualThreads = int(JPF.virtualThreads, virtualThreads),
            heartbeatInterval = ms(JPF.heartbeatIntervalMs, heartbeatInterval),
            leaseTimeout = ms(JPF.leaseTimeoutMs, leaseTimeout) ?: leaseTimeout,
            retryLimit = int(JPF.retryLimit, retryLimit),
            retryBackoff = ms(JPF.retryBackoffMs, retryBackoff) ?: retryBackoff,
            syncTaskCap = int(JPF.syncTaskCap, syncTaskCap),
            syncHardCap = int(JPF.syncHardCap, syncHardCap),
            trace = over[JPF.trace].toOptStr()?.let { s -> JobTraceLevel.entries.firstOrNull { it.name == s } } ?: trace,
            traceMaxEntries = int(JPF.traceMaxEntries, traceMaxEntries),
            traceKeepLaunches = int(JPF.traceKeepLaunches, traceKeepLaunches),
        )
    }
}

/** The instance-config names for [JobProfile] overrides. Each name matches its value. */
@Suppress("ConstPropertyName")
object JPF {
    /** The instance-config entry holding the overrides, one map per profile name. */
    const val jobProfiles = "jobProfiles"

    const val platformThreads = "platformThreads"
    const val virtualThreads = "virtualThreads"
    const val heartbeatIntervalMs = "heartbeatIntervalMs"
    const val leaseTimeoutMs = "leaseTimeoutMs"
    const val retryLimit = "retryLimit"
    const val retryBackoffMs = "retryBackoffMs"
    const val syncTaskCap = "syncTaskCap"
    const val syncHardCap = "syncHardCap"
    const val trace = "trace"
    const val traceMaxEntries = "traceMaxEntries"
    const val traceKeepLaunches = "traceKeepLaunches"
}
