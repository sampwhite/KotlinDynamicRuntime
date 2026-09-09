package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientSyncService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

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

    "a published-only toggle propagates even though it makes the consumed revisions older" {
        // The tier toggle (#617) is the case a content-only marker misses: switching a client to published-only
        // makes it consume an OLDER revision, so nothing in a content-date marker moves, and a monotonic-max
        // announce would carry nothing to peers. The fix folds the tier row's own date into the marker.
        val tc = "tiersync"
        fun tcClient(node: KdrCxt) = node.mkSubContext("tiersync", tc).also { it.userId = 16000L }
        fun tcTraits(node: KdrCxt) = SchemaService.get(node).gedraTraitsFor(tc).map { it.traitId }
        fun writeTc(node: KdrCxt, vararg traits: String) {
            val config = gedraConfig(node, "${tc}cfg", "${tc}config", tc) {
                defineClient(
                    ClientDef(
                        clientId = tc, name = tc, usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
                for (t in traits) trait("${t}Entry", t, setOf(GedraDataType.formDoc), "The $t trait.") { property("v", "A value.") }
            }
            GedraConfigService.get(node).writeConfig(tcClient(node), config)
        }
        fun reloadAnnounce(node: KdrCxt) {
            val r = GedraConfigReload.reloadClient(node, tc)
            ClientSyncService.get(node).announceAndMark(node, tc, r.marker)
        }

        // v1 published (tierP), then v2 unpublished adds tierQ. Free tier consumes the latest, so tierQ is live.
        writeTc(nodeA, "tierP")
        GedraConfigService.get(nodeA).publish(tcClient(nodeA), GedraId.of(GedraConfigType.configDoc, tc, "${tc}cfg"))
        writeTc(nodeA, "tierP", "tierQ")
        reloadAnnounce(nodeA)
        tcTraits(nodeA) shouldContain "tierQ"

        Thread.sleep(ClientSyncService.checkThrottleMs + 50)
        ClientSyncService.get(nodeB).checkSync(nodeB)
        tcTraits(nodeB) shouldContain "tierQ"          // B has caught up to the latest

        // A toggles published-only and reloads: it now consumes the published v1, dropping tierQ. The consumed
        // content got older, so ONLY the tier row's date makes this a change B can see.
        GedraConfigService.get(nodeA).setPublishedOnly(tcClient(nodeA), tc, true)
        reloadAnnounce(nodeA)
        tcTraits(nodeA) shouldNotContain "tierQ"

        Thread.sleep(ClientSyncService.checkThrottleMs + 50)
        ClientSyncService.get(nodeB).checkSync(nodeB)
        tcTraits(nodeB) shouldNotContain "tierQ"       // B followed the toggle, not just content dates
    }

    "only endpoints that consume client config carry the sync opt-in" {
        // The dispatcher runs checkSync (issue #618) only for an endpoint marked needsClientConfig, so the
        // trigger is opt-in rather than a blanket per-request cost. The form surface, which validates against a
        // client's configured schema, opts in; health, which does not, stays off.
        val endpoints = nodeA.getSchema().endpoints
        endpoints["/gedra/formDoc/create:POST"]?.needsClientConfig shouldBe true
        endpoints["/gedra/formDoc:GET"]?.needsClientConfig shouldBe true
        endpoints["/health:GET"]?.needsClientConfig shouldBe false
        // The per-client copy of a config-consuming endpoint carries the flag too (else a client-dynamic path
        // would sync nothing). "tiersync" is a varying client by now, so its copies exist.
        endpoints["/gedra/tiersync/formDoc/create:POST"]?.needsClientConfig shouldBe true
    }

    "a client a peer adds is resolvable on another node after it syncs" {
        // The regression the miss-path sync guards against (issue #611/#618): a client that did not exist when
        // node B booted. Its per-client endpoints are absent until B pulls the peer's change -- which the
        // dispatcher now does on a lookup miss, and which checkSync effects here directly.
        val nc = "arrivalco"
        fun ncClient(node: KdrCxt) = node.mkSubContext("arrival", nc).also { it.userId = 17000L }
        val config = gedraConfig(nodeA, "${nc}cfg", "${nc}config", nc) {
            defineClient(
                ClientDef(
                    clientId = nc, name = nc, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            trait("arrivalEntry", "arrival", setOf(GedraDataType.formDoc), "The arrival trait.") { property("v", "A value.") }
        }
        GedraConfigService.get(nodeA).writeConfig(ncClient(nodeA), config)
        val result = GedraConfigReload.reloadClient(nodeA, nc)
        ClientSyncService.get(nodeA).announceAndMark(nodeA, nc, result.marker)

        val ncKey = "/gedra/$nc/formDoc/create:POST"
        nodeA.getSchema().endpoints[ncKey]?.needsClientConfig shouldBe true   // A minted the copy on its reload
        nodeB.getSchema().endpoints[ncKey] shouldBe null                      // B has never seen this client

        Thread.sleep(ClientSyncService.checkThrottleMs + 50)
        ClientSyncService.get(nodeB).checkSync(nodeB)
        nodeB.getSchema().endpoints[ncKey]?.needsClientConfig shouldBe true   // now present after the sync
    }
})
