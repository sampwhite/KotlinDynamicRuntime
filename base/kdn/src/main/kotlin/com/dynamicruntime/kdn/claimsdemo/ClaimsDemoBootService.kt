package com.dynamicruntime.kdn.claimsdemo

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.user.RegistrationClient

/**
 * Provisions the claims demo as the node starts (issue #1897), where [ClaimsDemo.provisionAtBootKey] says to -- the
 * `reference` deployment flavor sets it, so a visitor to a reference deployment, whose database is cleared from time to
 * time, always finds the demo to join. Provisioning again keeps its users and forms, and rewrites only the bundles that
 * changed (a new release's), so it is safe on every start and adds no revisions when nothing changed. On a test
 * instance only, as the simulation is: it makes users at fixed addresses, which a real deployment has no business
 * holding. And not on a deployment naming a registration client (issue #1867), whose visitors cannot join a demo.
 */
class ClaimsDemoBootService : ServiceInitializer {
    override val serviceName: String = "ClaimsDemoBootService"

    @Volatile
    private var ready = false

    override fun checkReady(cxt: KdrCxt) {
        if (ready) return
        ready = true
        val config = cxt.instanceConfig
        if (config.get(ClaimsDemo.provisionAtBootKey) != true) return
        if (!config.isTestInstance) {
            LogStartup.warn(cxt, "Not provisioning the claims demo at boot: this is not a test instance.")
            return
        }
        // Not where every new user lands in one client of the deployment's own (issue #1867): nobody could join it.
        if (RegistrationClient.skipsDemoAtBoot(cxt, "claims demo")) return
        // A bundle unchanged since the last start is left as it is, so a restart adds no revisions.
        val report = provisionClaimsDemo(cxt, skipUnchanged = true)
        LogStartup.info(cxt, "Provisioned the claims demo at boot: ${report.summary}")
    }
}
