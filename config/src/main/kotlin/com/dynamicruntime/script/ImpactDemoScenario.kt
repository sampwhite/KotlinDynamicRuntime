package com.dynamicruntime.script

import com.dynamicruntime.common.simulation.ImpactDemo
import com.dynamicruntime.common.test.SIM

/** Name of the [impactDemo] scenario. */
const val impactDemoName = ImpactDemo.simulationName

/**
 * Runs the `impact-demo` simulation (issue #935): a client with a sandbox, forms stored under its traits, and an
 * unpublished draft that would strand them, so the client page's publish impact report has something to show. An
 * argument is a client suffix -- `kdr-probe impact-demo two` makes `impactdemotwo`.
 */
fun impactDemo(cxt: ProbeContext) {
    runSimulation(cxt, ImpactDemo.simulationName, cxt.args.firstOrNull()?.let { mapOf(SIM.suffix to it) }.orEmpty())
}
