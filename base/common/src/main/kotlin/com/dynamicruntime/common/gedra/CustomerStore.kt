package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.util.toOptStr

/**
 * The customers the platform makes over the API (issue #1107), kept in one configuration of `hub`'s,
 * [CUST.configName]. Each change is one customer's entry patched under `hub`'s lock -- so two changes cannot overwrite
 * each other -- judged by the write's trial as any configuration write is, then published and loaded: `hub` reloaded
 * on this node and announced to the rest. So a change is live, or refused in the trial's words, by the time it
 * returns.
 *
 * The caller is the platform's: nothing here asks who is acting.
 */
object CustomerStore {
    /** Adds [def], or replaces the stored customer of its id; returns it as stored. */
    fun put(cxt: KdrCxt, def: CustomerDef): Map<String, Any?> {
        val entry = def.toInfo()
        change(cxt) { entries -> entries.filterNot { it.idIs(def.customerId) } + entry }
        return entry
    }

    /** Removes the stored customer [customerId], if there is one. */
    fun remove(cxt: KdrCxt, customerId: String) {
        change(cxt) { entries -> entries.filterNot { it.idIs(customerId) } }
    }

    private fun Map<String, Any?>.idIs(customerId: String) = this[CUST.customerId].toOptStr() == customerId

    private fun change(cxt: KdrCxt, edit: (List<Map<String, Any?>>) -> List<Map<String, Any?>>) {
        val hub = cxt.mkSubContext("customerStore", CL.hub)
        val svc = GedraConfigService.get(hub)
        val configId = GedraId.of(GedraConfigType.configDoc, CL.hub, CUST.configName)
        fun patch() = svc.patchConfig(hub, configId, trial = true) { current ->
            current + (CCT.customerDef to edit(current[CCT.customerDef].orEmpty()))
        }
        val written = if (svc.readLatest(hub, configId) != null) {
            patch()
        } else {
            val slots = mapOf(CCT.customerDef to edit(emptyList()))
            val config = reassembleForWrite(hub, CUST.configName, clientNamespace(CL.hub), CL.hub, slots)
            try {
                svc.writeConfig(hub, config, trial = true, mustBeNew = true)
            } catch (e: KdrException) {
                // Another change made the configuration first: this one goes onto it.
                if (e.code != EXC.conflict) throw e
                patch()
            }
        }
        // Published, so it loads whatever `hub`'s tier.
        svc.publish(hub, written.configId, trial = true)
        val reload = GedraConfigReload.reloadClient(cxt, CL.hub)
        ClientSyncService.get(cxt).announceReload(cxt, reload)
    }
}
