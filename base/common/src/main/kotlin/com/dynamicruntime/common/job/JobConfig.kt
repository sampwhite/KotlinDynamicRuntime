package com.dynamicruntime.common.job

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.BootCheckRegistry
import com.dynamicruntime.common.startup.bootCheckMode
import com.dynamicruntime.common.startup.modeOverride
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.getOptBool
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Overrides for one [JobProfile], as typed settings rather than string keys (issue #870): what a deployment's
 * config object or a component writes. A null setting is left to the layer beneath.
 */
class JobProfileSettings {
    var platformThreads: Int? = null
    var virtualThreads: Int? = null
    var heartbeatIntervalMs: Long? = null
    var leaseTimeoutMs: Long? = null
    var retryLimit: Int? = null
    var retryBackoffMs: Long? = null
    var syncTaskCap: Int? = null
    var syncHardCap: Int? = null
    var trace: JobTraceLevel? = null
    var traceMaxEntries: Int? = null
    var traceKeepLaunches: Int? = null

    /** The settings made, keyed by their [JPF] names. */
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        JPF.platformThreads to platformThreads,
        JPF.virtualThreads to virtualThreads,
        JPF.heartbeatIntervalMs to heartbeatIntervalMs,
        JPF.leaseTimeoutMs to leaseTimeoutMs,
        JPF.retryLimit to retryLimit,
        JPF.retryBackoffMs to retryBackoffMs,
        JPF.syncTaskCap to syncTaskCap,
        JPF.syncHardCap to syncHardCap,
        JPF.trace to trace?.name,
        JPF.traceMaxEntries to traceMaxEntries,
        JPF.traceKeepLaunches to traceKeepLaunches,
    ).filterValues { it != null }

    companion object {
        /** Every key a profile override may carry. */
        val keys: Set<String> = setOf(
            JPF.platformThreads, JPF.virtualThreads, JPF.heartbeatIntervalMs, JPF.leaseTimeoutMs, JPF.retryLimit,
            JPF.retryBackoffMs, JPF.syncTaskCap, JPF.syncHardCap, JPF.trace, JPF.traceMaxEntries, JPF.traceKeepLaunches,
        )
    }
}

/** The node's job scheduler settings (issue #870). A null setting is left to the layer beneath. */
class JobSchedulerSettings {
    /** Whether the scheduler runs on this node. */
    var enabled: Boolean? = null

    /** How often it looks for a scheduled job whose window it is in. */
    var tickMs: Long? = null

    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>(JSCH.enabled to enabled, JSCH.tickMs to tickMs)
        .filterValues { it != null }

    companion object {
        val keys: Set<String> = setOf(JSCH.enabled, JSCH.tickMs)
    }
}

/** The instance-config names of the scheduler settings. Each name matches its value. */
@Suppress("ConstPropertyName")
object JSCH {
    /** The instance-config entry holding [JobSchedulerSettings]. */
    const val jobScheduler = "jobScheduler"
    const val enabled = "enabled"
    const val tickMs = "tickMs"
}

/**
 * How job configuration is layered (issue #870), lowest first:
 *
 * 1. the **code**: a job's registered [JobProfile], and the scheduler's defaults here;
 * 2. **components**, which may tune what they know about from their `applyInstanceConfig` through
 *    [contributeProfile] and [contributeScheduler];
 * 3. the **deployment's config object** (`customConfig`), whose settings are already in place when components
 *    run -- so a component contributes only what is still unset, key by key, and never overrides a deployment's
 *    deliberate choice;
 * 4. the **launch**, for what a launch may choose (its trace level).
 *
 * Both the deployment and components write the same instance-config entries, `jobProfiles.<name>.<key>` and
 * `jobScheduler.<key>`, which [JobProfile.resolve] and the scheduler read. [check] refuses an entry naming a
 * profile no registered job uses, or a key no profile has: both are written in code, so either is a typo.
 */
object JobConfig {
    /**
     * Contributes settings for profile [name] from a component's `applyInstanceConfig`: each one only where
     * nothing is set yet, so a deployment's choice stands.
     */
    fun contributeProfile(cxt: KdrCxt, name: String, build: JobProfileSettings.() -> Unit) {
        contribute(cxt, "${JPF.jobProfiles}.$name", JobProfileSettings().apply(build).toMap())
    }

    /** Contributes scheduler settings from a component's `applyInstanceConfig`, each only where nothing is set yet. */
    fun contributeScheduler(cxt: KdrCxt, build: JobSchedulerSettings.() -> Unit) {
        contribute(cxt, JSCH.jobScheduler, JobSchedulerSettings().apply(build).toMap())
    }

    private fun contribute(cxt: KdrCxt, prefix: String, settings: Map<String, Any?>) {
        for ((key, value) in settings) {
            val path = "$prefix.$key"
            if (cxt.instanceConfig.get(path) == null) cxt.instanceConfig.put(path, value)
        }
    }

    /** Whether the scheduler runs: as configured, else everywhere but a unit-test instance. */
    fun schedulerEnabled(cxt: KdrCxt): Boolean =
        scheduler(cxt).getOptBool(JSCH.enabled) ?: (cxt.instanceConfig.env != ENV.unit)

    /** How often the scheduler looks: as configured, else a minute in production, ten seconds elsewhere. */
    fun tickInterval(cxt: KdrCxt): Duration =
        scheduler(cxt)[JSCH.tickMs].toOptLong()?.milliseconds
            ?: if (cxt.instanceConfig.env == ENV.prod || cxt.instanceConfig.env == ENV.integration) 60.seconds else 10.seconds

    private fun scheduler(cxt: KdrCxt): Map<String, Any?> = cxt.instanceConfig.get(JSCH.jobScheduler).toJsonMapOrEmpty()

    /**
     * The boot check on job configuration (issue #870): an override naming a profile no registered job uses, a
     * key no profile has, or a trace level that does not exist. Strict outside production, a warning in it;
     * [checkEnvVar] overrides the mode.
     */
    fun check(cxt: KdrCxt, defs: Collection<JobDef>) {
        val mode = bootCheckMode(cxt, modeOverride(cxt, checkEnvVar), prodMode = BootCheckMode.warn)
        if (mode == BootCheckMode.off) return
        val findings = mutableListOf<String>()
        val used = defs.map { it.profile.name }.toSet()
        for ((name, value) in cxt.instanceConfig.get(JPF.jobProfiles).toJsonMapOrEmpty()) {
            if (name !in used) findings.add("Job profile '$name' is configured, but no registered job uses it.")
            for ((key, setting) in value.toJsonMapOrEmpty()) {
                if (key !in JobProfileSettings.keys) {
                    findings.add("Job profile '$name' sets '$key', which is not a profile setting.")
                } else if (key == JPF.trace && JobTraceLevel.entries.none { it.name == setting.toOptStr() }) {
                    findings.add("Job profile '$name' sets trace to '$setting', which is not a trace level.")
                }
            }
        }
        for (key in scheduler(cxt).keys) {
            if (key !in JobSchedulerSettings.keys) findings.add("The job scheduler setting '$key' does not exist.")
        }
        findings.addAll(scheduleFindings(cxt, defs))
        BootCheckRegistry.get(cxt).record(BCHKJ.jobConfig, checkEnvVar.name, mode, findings)
        if (findings.isEmpty()) return
        if (mode == BootCheckMode.strict) {
            throw KdrException("Job configuration is invalid: ${findings.joinToString(" ")}")
        }
        LogJob.warn(cxt) { "Job configuration problems: ${findings.joinToString(" ")}" }
    }

    /**
     * What is wrong with the registered schedules on this node: an interval under a minute where this is not a
     * test instance (a sub-minute schedule is a test's, and on a real node more likely a typo that would hammer
     * it), and -- where the scheduler runs -- slots or windows shorter than its tick, which it could miss
     * between one look and the next.
     */
    private fun scheduleFindings(cxt: KdrCxt, defs: Collection<JobDef>): List<String> {
        val out = mutableListOf<String>()
        val tick = tickInterval(cxt)
        val ticking = schedulerEnabled(cxt)
        for (def in defs) {
            val schedule = def.schedule ?: continue
            val spacing = schedule.minSpacing()
            if (spacing < minRealInterval && !cxt.instanceConfig.isTestInstance) {
                out.add("Job '${def.jobType}' is scheduled every $spacing; under $minRealInterval is only for test instances.")
            }
            if (ticking && spacing < tick) {
                out.add("Job '${def.jobType}' has slots $spacing apart, closer than the scheduler's tick of $tick.")
            }
            if (ticking && schedule.window < tick) {
                out.add("Job '${def.jobType}' has a window of ${schedule.window}, shorter than the scheduler's tick of $tick.")
            }
        }
        return out
    }

    /** The shortest slot spacing a node that is not a test instance accepts. */
    val minRealInterval: Duration = 1.minutes

    val checkEnvVar = EnvVarDef(
        "KDR_JOB_CONFIG_CHECK", group = ENVGRP.jobs, defaultDoc = "strict, except warn in production",
        description = "The mode of the boot check on job configuration (`strict`, `warn` or `off`): an override " +
            "naming a job profile no registered job uses, or a setting no profile has -- both written in code, so " +
            "either is a typo that would otherwise be silently ignored -- and a schedule a node cannot honor: " +
            "slots under a minute apart outside a test instance, or slots or a window shorter than the scheduler's tick.",
    )
}

/** The job boot check's name. */
@Suppress("ConstPropertyName")
object BCHKJ {
    const val jobConfig = "jobConfig"
}
