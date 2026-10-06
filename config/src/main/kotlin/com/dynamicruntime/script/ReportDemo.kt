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

/** Name of the [reportHistoryDemo] scenario, and of the simulation it runs -- a literal, as [reportDemoName] is. */
const val reportHistoryDemoName = "report-history-demo"

/**
 * Runs the `report-history-demo` simulation (issue #1036): the report demo's forms, then five days of further acme
 * forms with a snapshot of acme's history reports dated each day, so the Reports page's History view has a series to
 * draw. Needs what [reportDemo] needs. Each run adds the forms again and a newer snapshot for each day.
 */
fun reportHistoryDemo(cxt: ProbeContext) {
    runSimulation(cxt, reportHistoryDemoName)
}
