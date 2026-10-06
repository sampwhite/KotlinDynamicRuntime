package com.dynamicruntime.script

/**
 * Name of the [reportDemo] scenario, and of the simulation it runs. A literal here: the simulation is declared by
 * `sample`, which `config` sits below in the module graph and cannot see.
 */
const val reportDemoName = "report-demo"

/**
 * Runs the `report-demo` simulation (issues #1005, #997): forms in the sample's acme and globex for its reports to
 * show, and administrators to look at them as. Needs a test instance that loads the sample (`local`/`dev`, or
 * `KDR_LOAD_SAMPLE=true`), the only place it is offered. Each run adds forms; on an in-memory node, rerun after a
 * restart.
 */
fun reportDemo(cxt: ProbeContext) {
    runSimulation(cxt, reportDemoName)
}
