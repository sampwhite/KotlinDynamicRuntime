package com.dynamicruntime.script

import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.simulation.DesignDemo
import com.dynamicruntime.common.simulation.designDemoConfig

/** Name of the [designDemo] scenario. */
const val designDemoName = "design-demo"

/**
 * Writes the Design View demo client ([designDemoConfig], a simulation the Design View tests provision too) to the
 * instance and reloads it there (issue #972), as an administrator with the full-scope capability -- the `admin`
 * config surface is how a new client is created over the API. Rerunning rewrites the same bundle, so it is safe to
 * repeat; on an in-memory node it has to be rerun after every restart, since nothing stored survives one.
 */
fun designDemo(cxt: ProbeContext) {
    val config = designDemoConfig()
    val admin = cxt.sessionAt(ROLE.admin)
    val write = admin.sendPostRequest(
        ACEP.bundleWrite,
        mapOf(
            CFEP.client to DesignDemo.client,
            CFEP.name to config.name,
            CFEP.namespaceField to config.namespace,
            CFEP.slots to gedraConfigToEntries(config),
        ),
    )
    println("Write '${DesignDemo.client}/${config.name}': HTTP ${write.statusCode} ${write.errorMessage ?: "ok"}")
    if (!write.isSuccess) return
    val reload = admin.sendPostRequest(ACEP.reload, mapOf(CFEP.client to DesignDemo.client))
    println("Reload '${DesignDemo.client}': HTTP ${reload.statusCode} ${reload.errorMessage ?: "ok"}")
    if (!reload.isSuccess) return
    println()
    println("Sign in as one of its administrators from the browser's console, then reload:")
    println("  fetch('/kda/fixture/becomeUser', {method: 'POST', headers: {'Content-Type': 'application/json'},")
    println("    body: JSON.stringify({email: 'designer@${DesignDemo.client}.example', level: 'admin', client: '${DesignDemo.client}'})})")
}
