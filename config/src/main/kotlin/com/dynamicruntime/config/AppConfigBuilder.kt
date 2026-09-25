package com.dynamicruntime.config

import com.dynamicruntime.common.config.KdrConfigData
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.http.request.VariantScenario
import com.dynamicruntime.common.job.JPF
import com.dynamicruntime.common.job.JSCH
import com.dynamicruntime.common.job.JobProfileSettings
import com.dynamicruntime.common.job.JobSchedulerSettings
import com.dynamicruntime.common.sql.DbEnv
import com.dynamicruntime.common.util.toJsonMapOrEmpty

@Suppress("MoveLambdaOutsideParentheses", "unused")
class AppConfigBuilder(cxt: KdrCxt, data: MutableMap<String,Any?>) : KdrConfigData(cxt, data) {
    init {
        // Default the env: keep an existing value if present, otherwise take the
        // KDR_ENV environment variable, otherwise fall back to local. getOrPut
        // reads the env var only when the key is absent, so an existing value wins.
        data.getOrPut(ACFG.env) { cxt.getEnvVar(KdrInstanceConfig.envName) ?: ENV.local }
        // Default inMemoryOnly from KDR_IN_MEMORY_ONLY (else true); an explicit value already present wins.
        data.getOrPut(ACFG.inMemoryOnly) { DbEnv.resolveInMemoryOnly(cxt) }
        data.getOrPut(ACFG.validateResponseSchema, { false })
    }

    // See ENV constants in CxtConstants.
    var env: String by data
    var inMemoryOnly: Boolean by data

    /** When true, endpoint responses are validated against their output schema. Defaults false (on in tests). */
    var validateResponseSchema: Boolean by data

    /**
     * How often (ms) the visible frontend refreshes itself (issue #146). Left unset here, so the app UI-config
     * endpoint applies its own default; set it to exercise the behavior quickly (e.g., a few seconds) without
     * waiting out the one-minute default. This is the intended, "code-side" way to tune a UI value -- as opposed
     * to an env var, which is for ops/environment concerns.
     */
    var idleBumpIntervalMs: Int by data

    /**
     * Named request-misbehavior scenarios for frontend testing (issue #471) -- slow or failed responses a
     * browser can be driven through. Left unset here, so the facility is off by default; a deployment that
     * wants it declares scenarios in its own `customConfig` applier. Carried as live objects (not a string),
     * which is why it is a code-side choice rather than an env var. A real environment refuses to boot with
     * this set. See [VariantScenario] and `VariantBehavior`.
     */
    var testVariantScenarios: List<VariantScenario> by data

    /**
     * Overrides job profile [name]'s settings for this deployment (issue #870): threads, heartbeat, lease,
     * retries, synchronous caps, trace. A deployment's choice outranks what a component contributes, and a
     * profile no registered job uses, or a setting that does not exist, fails the boot's job-config check.
     *
     * ```
     * jobProfile("workflowStates") { leaseTimeoutMs = 60_000; trace = JobTraceLevel.launch }
     * ```
     */
    fun jobProfile(name: String, build: JobProfileSettings.() -> Unit) {
        section(JPF.jobProfiles, name).putAll(JobProfileSettings().apply(build).toMap())
    }

    /** Sets the node's job scheduler for this deployment (issue #870): whether it runs, and how often it looks. */
    fun jobScheduler(build: JobSchedulerSettings.() -> Unit) {
        section(JSCH.jobScheduler).putAll(JobSchedulerSettings().apply(build).toMap())
    }

    /** The mutable map at [path] under [data], created (or copied from a read-only one) as needed. */
    private fun section(vararg path: String): MutableMap<String, Any?> {
        var current: MutableMap<String, Any?> = data
        for (key in path) {
            val existing = current[key].toJsonMapOrEmpty()
            val child = LinkedHashMap(existing)
            current[key] = child
            current = child
        }
        return current
    }
}