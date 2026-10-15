package com.dynamicruntime.kdn.confdemo

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.logging.LogStartup
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * Provisions the conference demo as the node starts (issue #1221), where [ConfDemo.provisionAtBootKey] says to -- the
 * `reference` deployment flavor sets it. The demo is written as stored bundles by the `conf-demo` simulation, which
 * only a caller with test access can run, and a reference deployment's database is temporary -- cleared from time to
 * time and recreated empty: without this, a visitor to a fresh one would find no demo. On a database that already
 * holds it, provisioning again rewrites the demo's configuration and keeps its users and forms, as a rerun of the
 * simulation does, so it is safe on every start.
 *
 * The same provisioning the simulation runs, so the demo a visitor finds is the one the documentation describes,
 * with its speaker, organizer and committee member. On a test instance only, as the simulation is: it makes users at
 * fixed addresses, which a real deployment has no business holding.
 */
class ConfDemoBootService : ServiceInitializer {
    override val serviceName: String = "ConfDemoBootService"

    @Volatile
    private var ready = false

    override fun checkReady(cxt: KdrCxt) {
        if (ready) return
        ready = true
        val config = cxt.instanceConfig
        if (config.get(ConfDemo.provisionAtBootKey) != true) return
        if (!config.isTestInstance) {
            LogStartup.warn(cxt, "Not provisioning the conference demo at boot: this is not a test instance.")
            return
        }
        val report = provisionConfDemo(cxt)
        LogStartup.info(cxt, "Provisioned the conference demo at boot: ${report.summary}")
    }
}
