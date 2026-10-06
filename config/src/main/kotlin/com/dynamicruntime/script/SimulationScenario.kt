package com.dynamicruntime.script

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.test.SIM
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Runs the simulation [name] on the instance (issue #997) and prints its report: what it did, who to sign in as and
 * where to start. The provisioning is the server's -- a probe scenario that runs a simulation is only this call, so
 * the probe is never a second way of setting a scenario up. Fails the run when the call does, for instance on a node
 * that does not offer the simulation (not a test instance, or without the component that declares it).
 */
fun runSimulation(cxt: ProbeContext, name: String, input: Map<String, Any?> = emptyMap()) {
    val resp = cxt.session("simulation").sendPostRequest(SIM.pathRoot + name, input)
    if (!resp.isSuccess) {
        throw KdrException(
            "Simulation '$name' failed (HTTP ${resp.statusCode}): ${resp.errorMessage ?: resp.rawBody.take(200)}. " +
                "Is ${cxt.baseUrl} a test instance that offers it?",
        )
    }
    val report = resp.body[EP.results].toJsonMapOrEmpty()
    println(report[SIM.summary].toOptStr().orEmpty())
    val start = report[SIM.startPage].toOptStr()
    for (user in report[SIM.users].toJsonListOfMaps()) {
        println()
        println("${user[SIM.email]} -- ${user[SIM.purpose]}.")
        println("To sign in as them, from the browser's console, then reload${start?.let { " (or open #$it)" }.orEmpty()}:")
        val body = buildMap {
            put(SIM.email, user[SIM.email])
            put(SIM.level, user[SIM.level])
            put(SIM.client, user[SIM.client])
            user[SIM.capabilities].toJsonListOfStrings().takeIf { it.isNotEmpty() }?.let { put(SIM.capabilities, it) }
        }
        val json = body.entries.joinToString(", ") { (k, v) ->
            if (v is List<*>) "$k: [${v.joinToString(", ") { "'$it'" }}]" else "$k: '$v'"
        }
        println("  fetch('/kda/fixture/becomeUser', {method: 'POST', headers: {'Content-Type': 'application/json'},")
        println("    body: JSON.stringify({$json})})")
    }
    println()
    println("Or run and sign in from the Simulations page: ${cxt.baseUrl}/wa/#page=${HMENU.pageSimulations}")
}
