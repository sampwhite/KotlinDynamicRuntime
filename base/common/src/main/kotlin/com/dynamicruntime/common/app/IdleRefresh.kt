package com.dynamicruntime.common.app

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.ClientService

/**
 * How often the webapp bumps its refresh generation while a tab is visible (issue #146): the deployment's interval
 * ([ACFG.idleBumpIntervalMs], a custom-config tuning, else [APP.defaultIdleBumpIntervalMs]), or a client's own (issue
 * #1308, `ClientDef.refreshIntervalSeconds`), which only the platform sets. Every open tab asks that often, so it is a
 * load on the deployment rather than a client's choice.
 */
object IdleRefresh {
    /** The deployment's interval, in milliseconds. */
    fun deploymentMillis(cxt: KdrCxt): Int = cxt.instanceConfig.get(ACFG.idleBumpIntervalMs) as? Int ?: APP.defaultIdleBumpIntervalMs

    /** The interval for [client]'s people, in milliseconds: its own when it sets one, else the deployment's. */
    fun millisFor(cxt: KdrCxt, client: String): Int =
        ClientService.getOrNull(cxt)?.known(client)?.refreshIntervalSeconds?.let { it * 1000 } ?: deploymentMillis(cxt)
}
