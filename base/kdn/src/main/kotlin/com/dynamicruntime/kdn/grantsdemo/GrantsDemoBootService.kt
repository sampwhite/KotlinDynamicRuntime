package com.dynamicruntime.kdn.grantsdemo

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * Provisions the grants demo as the node starts (issue #1799), where [GrantsDemo.provisionAtBootKey] says to -- the
 * `reference` deployment flavor sets it, so a visitor to a reference deployment, whose database is cleared from time to
 * time, always finds the demo to join. Provisioning again keeps its users and forms, and rewrites only the bundles that
 * changed (a new release's), so it is safe on every start and adds no revisions when nothing changed. On a test
 * instance only, as the simulation is: it makes users at fixed addresses, which a real deployment has no business
 * holding.
 */
class GrantsDemoBootService : ServiceInitializer {
    override val serviceName: String = "GrantsDemoBootService"

    @Volatile
    private var ready = false

    override fun checkReady(cxt: KdrCxt) {
        if (ready) return
        ready = true
        val config = cxt.instanceConfig
        if (config.get(GrantsDemo.provisionAtBootKey) != true) return
        if (!config.isTestInstance) {
            LogStartup.warn(cxt, "Not provisioning the grants demo at boot: this is not a test instance.")
            return
        }
        // A bundle unchanged since the last start is left as it is, so a restart adds no revisions.
        val report = provisionGrantsDemo(cxt, skipUnchanged = true)
        LogStartup.info(cxt, "Provisioned the grants demo at boot: ${report.summary}")
    }
}
