package com.dynamicruntime.script

import com.dynamicruntime.common.simulation.DesignDemo
import com.dynamicruntime.common.test.SIM

/** Name of the [designDemo] scenario. */
const val designDemoName = DesignDemo.simulationName

/**
 * Runs the `design-demo` simulation (issues #972, #997): the Design View demo client, defined in data, and an
 * administrator and a requester to sign in as. An argument is a client suffix -- `kdr-probe design-demo two` makes
 * `designdemotwo`, a fresh copy for a review that should not start from the last one's edits. Rerunning rewrites the
 * client's configuration; on an in-memory node it has to be rerun after every restart.
 */
fun designDemo(cxt: ProbeContext) {
    runSimulation(cxt, DesignDemo.simulationName, cxt.args.firstOrNull()?.let { mapOf(SIM.suffix to it) }.orEmpty())
}
