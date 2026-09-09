package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientSyncService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain

/**
 * Multi-node config sync (issue #618): a change reloaded on one node reaches the others through the shared
 * ClientSyncTracking row. Two booted instances share one isolated database -- two "nodes" -- with stored-config
 * loading on. Node A reloads a client and announces; node B, which booted before the change, learns from the
 * shared row on its next sync check that it is behind and catches up.
 *
 * Node B's config cache is turned off so its reload reads the shared database directly. In production node B's
 * cache stays coherent because a real reload happens inside a request, which publishes the cache-state row that
 * every node reads on a 250ms throttle (issue #615); this test drives the services directly, outside any
 * request, so that publish never happens and the cache would sit stale until its 30s floor. Nulling the cache
 * isolates what #618 adds -- read the shared marker, notice you are behind, reload -- from #615's cross-node
 * cache timing, which the reload converges to anyway.
 */
class GedraConfigSyncTest : StringSpec({
    val db = mapOf("KDR_DB_NAME" to "cfgSyncDb", "KDR_LOAD_STORED_CONFIG" to "true")
    val nodeA: KdrCxt = Startup.mkTestBootCxt("cfgSyncA", "cfgSyncNodeA", db)
    val nodeB: KdrCxt = Startup.mkTestBootCxt("cfgSyncB", "cfgSyncNodeB", db)
    // Node B reads config straight from the shared database, for the reason in the spec doc.
    GedraConfigService.get(nodeB).configCache = null

    val client = "syncclient"
    fun asClient(node: KdrCxt): KdrCxt = node.mkSubContext("sync", client).also { it.userId = 15000L }
    fun traitsOn(node: KdrCxt): List<String> = SchemaService.get(node).gedraTraitsFor(client).map { it.traitId }

    fun writeAndAnnounceOn(node: KdrCxt, vararg traits: String) {
        val config = gedraConfig(node, "${client}cfg", "${client}config", client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            for (t in traits) trait("${t}Entry", t, setOf(GedraDataType.formDoc), "The $t trait.") { property("v", "A value.") }
        }
        GedraConfigService.get(node).writeConfig(asClient(node), config)
        val result = GedraConfigReload.reloadClient(node, client)
        // What the reload endpoint does after applying a change: announce so peers learn they are behind.
        ClientSyncService.get(node).announceAndMark(node, client, result.marker)
    }

    "a change reloaded on one node is picked up by another node's sync check" {
        writeAndAnnounceOn(nodeA, "syncAlpha")
        traitsOn(nodeA) shouldContain "syncAlpha"          // the node that made the change runs it
        traitsOn(nodeB) shouldNotContain "syncAlpha"       // the peer booted before it and has not seen it

        ClientSyncService.get(nodeB).checkSync(nodeB)       // reads the shared row, notices it is behind, reloads
        traitsOn(nodeB) shouldContain "syncAlpha"

        // A second revision propagates the same way, once the throttle window passes.
        writeAndAnnounceOn(nodeA, "syncAlpha", "syncBeta")
        Thread.sleep(ClientSyncService.checkThrottleMs + 50)
        ClientSyncService.get(nodeB).checkSync(nodeB)
        traitsOn(nodeB) shouldContain "syncBeta"
    }

    "a node does not reload a client it is already current with" {
        // nodeB is current after the first test. Advance the throttle and check again: nothing new to pull, and
        // the client stays exactly as it was (no spurious reload storm from re-reading its own marker).
        Thread.sleep(ClientSyncService.checkThrottleMs + 50)
        val before = traitsOn(nodeB)
        ClientSyncService.get(nodeB).checkSync(nodeB)
        traitsOn(nodeB) shouldContainAll before
    }
})
