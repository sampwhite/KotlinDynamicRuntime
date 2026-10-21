package com.dynamicruntime.common.simulation

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * Provisions the Design View demo as the node starts (issue #1248), where [DesignDemo.provisionAtBootKey] says to --
 * the `reference` deployment flavor sets it, so a visitor there finds `designdemo` to join, as `confdemo` is found
 * (#1221). The demo is otherwise written only by the `design-demo` simulation, which only a caller with test access can
 * run, and a reference deployment's database is cleared from time to time and recreated empty. Provisioning again on a
 * database that holds it rewrites its configuration and keeps its users and forms, as a rerun of the simulation does,
 * so it is safe on every start.
 *
 * On a test instance only, as the simulation is: it makes users at fixed `designdemo.example` addresses, which a real
 * deployment has no business holding.
 */
class DesignDemoBootService : ServiceInitializer {
    override val serviceName: String = "DesignDemoBootService"

    @Volatile
    private var ready = false

    override fun checkReady(cxt: KdrCxt) {
        if (ready) return
        ready = true
        val config = cxt.instanceConfig
        if (config.get(DesignDemo.provisionAtBootKey) != true) return
        if (!config.isTestInstance) {
            LogStartup.warn(cxt, "Not provisioning the Design View demo at boot: this is not a test instance.")
            return
        }
        val report = provisionDesignDemo(cxt)
        LogStartup.info(cxt, "Provisioned the Design View demo at boot: ${report.summary}")
    }
}
