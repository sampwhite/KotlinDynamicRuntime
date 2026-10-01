package com.dynamicruntime.multinode

import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.job.JobConfig
import com.dynamicruntime.common.job.JobTraceLevel
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.Presence
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.util.getOptBool
import com.dynamicruntime.common.util.toJsonMapOrEmpty

/** The component's configuration names. Each name matches its value. */
@Suppress("ConstPropertyName")
object MNT {
    /** The instance-config entry holding the component's settings. */
    const val multiNodeTest = "multiNodeTest"

    /** Whether the component is on. */
    const val enabled = "enabled"
}

/**
 * The in-backend half of the multi-node test harness (issue #872): fixtures the harness's scenarios drive, one per
 * subsystem -- the batch-jobs fixture now ([MultiNodeJobs]), the Edge service's later.
 *
 * **Inert unless switched on**, by the `multiNodeTest.enabled` instance-config entry (what a developer's
 * `customConfig` sets, beside any scenario tweaks) or by [enabledVar], a convenience for the harness when it starts
 * nodes itself. Never on a production node, whatever the configuration says. And the module it lives in is left
 * out of the deployable distribution, so this is the second safeguard, not the only one.
 *
 * Its timing defaults are **contributions** (`JobConfig.contributeProfile` / `contributeScheduler`): they fill only
 * what is unset, so a setting in `customConfig` always stands, and retrying a scenario with other settings is an
 * edit and a rerun.
 */
class MultiNodeTestComponent : ComponentDefinition {
    override val providerName: String = "multiNodeTest"

    /** This component's own root (issue #950); its global names live under it. */
    override val ownerRoot: String = "multiNode"

    override fun isLoaded(cxt: KdrCxt): Boolean {
        if (!enabled(cxt)) return false
        if (cxt.instanceConfig.env == ENV.prod) {
            LogStartup.warn(cxt, "The multi-node test component is switched on, but this is a production node; it stays off.")
            return false
        }
        return true
    }

    override fun applyInstanceConfig(cxt: KdrCxt) {
        JobConfig.contributeScheduler(cxt) {
            enabled = true
            tickMs = 1_000
        }
        JobConfig.contributeProfile(cxt, MultiNodeJobs.profileName) {
            platformThreads = 2
            heartbeatIntervalMs = 500
            leaseTimeoutMs = 4_000
            retryBackoffMs = 0
            trace = JobTraceLevel.task
        }
    }

    override fun addSchema(cxt: KdrCxt, collector: SchemaCollector) {
        val appOnly = Presence(roles = setOf(BOOT.app))
        collector.addTables(MultiNodeJobs.tables(cxt), appOnly)
        collector.addModule(MultiNodeJobs.schema(cxt), appOnly)
        MultiNodeJobs.jobs().forEach { collector.addJob(it) }
    }

    companion object {
        /** A convenience switch for the harness, known only to this component, so never read on a deployed node. */
        val enabledVar = EnvVarDef(
            "KDR_MULTI_NODE_TEST", group = ENVGRP.jobs, defaultDoc = "off",
            description = "Switches the multi-node test component on (issue #872), as `multiNodeTest.enabled` in a " +
                "`customConfig` does. Only in development builds: the module is not in the deployable distribution.",
        )

        fun enabled(cxt: KdrCxt): Boolean {
            val configured = cxt.instanceConfig.get(MNT.multiNodeTest).toJsonMapOrEmpty().getOptBool(MNT.enabled)
            return configured ?: (cxt.getEnvBool(enabledVar) == true)
        }
    }
}
