package com.dynamicruntime.script

import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.FSR
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.simulation.ListingDemo
import com.dynamicruntime.common.test.SIM
import com.dynamicruntime.common.user.UADEP

/** Name of the [formStatesTiming] scenario. */
const val formStatesTimingName = "form-states-timing"

/** The bundle the scenario writes its workflow into, apart from the demo's own configuration. */
private const val timingBundle = "timing"

/**
 * Times recalculating a client's form states from its page (issue #1412) at several sizes, in the worst case and the
 * best. For each form count -- `kdr-probe form-states-timing 500 1000 2000`, or those three when none are given -- it
 * provisions a listing demo client of that many forms (`listingdemot<count>`), adds a workflow none of its forms has
 * been recalculated against, so that every form is out of date, and times the recalculation; then times it again,
 * when every form is current and the run only looks.
 *
 * Each run adds a workflow of a new name, so a rerun against a persistent node finds every form out of date again
 * rather than timing the cheap case twice. Time it against the database it will run on: an in-memory H2 is
 * considerably faster than a file-backed one, and understates the cost.
 */
fun formStatesTiming(cxt: ProbeContext) {
    val counts = cxt.args.ifEmpty { listOf("500", "1000", "2000") }.map {
        it.toIntOrNull() ?: throw KdrException("A form count is a whole number; '$it' is not.")
    }
    println("Recalculating form states at ${cxt.baseUrl}")
    for (count in counts) {
        val suffix = "t$count"
        val client = ListingDemo.client + suffix
        val provisioned = System.nanoTime()
        val sim = cxt.session("simulation").sendPostRequest(
            SIM.pathRoot + ListingDemo.simulationName,
            mapOf(SIM.suffix to suffix, SIM.formCount to count, SIM.userCount to 20),
        )
        if (!sim.isSuccess) throw KdrException("Provisioning '$client' failed (HTTP ${sim.statusCode}): ${sim.errorMessage}")
        println()
        println("$client: $count forms (provisioned in ${msSince(provisioned)} ms)")

        val admin = cxt.session("chief-$suffix")
        admin.becomeUser("chief@$client.example", ROLE.admin, client = client)
        val workflowId = "timing${System.currentTimeMillis()}"
        val written = admin.sendPostRequest(
            CFEP.bundleWrite,
            mapOf(
                CFEP.name to timingBundle,
                CFEP.namespaceField to clientNamespace(client),
                CFEP.slots to mapOf(CCT.workflowDef to listOf(timingWorkflow(workflowId))),
                CFEP.reloadField to true,
            ),
        )
        if (!written.isSuccess) throw KdrException("Adding workflow '$workflowId' failed (HTTP ${written.statusCode}): ${written.errorMessage}")

        for (case in listOf("every form out of date", "every form current")) {
            val started = System.nanoTime()
            val resp = admin.sendPostRequest(UADEP.clientFormStatesRecalc, mapOf(CLD.client to client))
            val ms = msSince(started)
            if (!resp.isSuccess) throw KdrException("Recalculating '$client' failed (HTTP ${resp.statusCode}): ${resp.errorMessage}")
            val r = resp.results
            println(
                "  ${case.padEnd(22)}: ${ms.toString().padStart(6)} ms  (${r[FSR.outcome]}: ${r[FSR.updated]} updated, " +
                    "${r[FSR.current]} current, ${r[FSR.failed]} failed)",
            )
        }
    }
    println()
    println("Times include the HTTP round trip. Each count is a client of its own, provisioned once; a rerun adds a new")
    println("workflow, so its first recalculation again finds every form out of date.")
}

/** A workflow every form qualifies for: no tests, one task collecting the demo's category. */
private fun timingWorkflow(workflowId: String): Map<String, Any?> = mapOf(
    CCT.workflowId to workflowId,
    CCT.definition to mapOf(
        WFD.workflowId to workflowId,
        WFD.entry to WfEntry.normal.name,
        WFD.label to "Timing ($workflowId)",
        WFD.tasks to listOf(
            mapOf(
                WFD.id to "check",
                WFD.label to "Check the category",
                WFD.traits to listOf(mapOf(WFD.traitId to ListingDemo.category)),
                WFD.saves to listOf(mapOf(WFD.id to "saveCheck", WFD.label to "Save", WFD.kind to WfSaveKind.edit.name)),
            ),
        ),
    ),
)

private fun msSince(start: Long): Long = (System.nanoTime() - start) / 1_000_000
