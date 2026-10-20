package com.dynamicruntime.script

import com.dynamicruntime.common.simulation.ListingDemo
import com.dynamicruntime.common.test.SIM

/** Name of the [listingDemo] scenario. */
const val listingDemoName = ListingDemo.simulationName

/**
 * Runs the `listing-demo` simulation (issue #1316): a client holding many forms and users, for reviewing long
 * listings. Arguments are optional and positional: a client suffix, then the form count, then the user count --
 * `kdr-probe listing-demo two 1200 600` makes `listingdemotwo` with 1,200 forms and 600 users. A rerun tops up to the
 * counts; on an in-memory node it has to be rerun after every restart.
 */
fun listingDemo(cxt: ProbeContext) {
    val (suffix, forms, users) = listOf(0, 1, 2).map { cxt.args.getOrNull(it)?.ifBlank { null } }
    val input = buildMap {
        suffix?.let { put(SIM.suffix, it) }
        forms?.let { put(SIM.formCount, it.toIntOrNull() ?: error("The form count is a whole number; '$it' is not.")) }
        users?.let { put(SIM.userCount, it.toIntOrNull() ?: error("The user count is a whole number; '$it' is not.")) }
    }
    runSimulation(cxt, ListingDemo.simulationName, input)
}
