package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientSyncService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigLoadService
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe

/**
 * A sandbox at boot and across nodes (issue #928): a source-defined parent's sandbox carries the parent's source
 * layer beneath its stored one; a persistent node builds sandboxes from its boot load; and a change only the
 * sandbox runs -- a draft on a published-only parent -- reaches a peer by the sandbox's own marker.
 *
 * The multi-node cases share one isolated database with stored-config loading on, and turn node B's config cache
 * off for the reason `GedraConfigSyncTest` gives: these tests drive the services outside any request, so the cache
 * would sit stale.
 */
class SandboxBootAndSyncTest : StringSpec({
    fun traits(node: KdrCxt, client: String): List<String> =
        SchemaService.get(node).gedraTraitsFor(client).map { it.traitId }
    fun asClient(node: KdrCxt, client: String): KdrCxt = node.mkSubContext("sandboxSync", client).also { it.userId = 9400L }

    /** Stores [client]'s config with the named traits; defining it, with a sandbox, unless [define] is false. */
    fun write(node: KdrCxt, client: String, vararg traits: String, define: Boolean = true) {
        val config = gedraConfig(node, "stored", clientNamespace(client), client) {
            if (define) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = true,
                    ),
                )
            }
            for (t in traits) trait("${t}Entry", t, setOf(GedraDataType.formDoc), "The $t trait.") { property("v", "A value.") }
        }
        GedraConfigService.get(node).writeConfig(asClient(node, client), config)
    }

    fun publish(node: KdrCxt, client: String) =
        GedraConfigService.get(node).publish(asClient(node, client), GedraId.of(GedraConfigType.configDoc, client, "stored"))

    // --- a source-defined parent ---------------------------------------------------------------------------

    val source = Startup.mkTestBootCxt("sandboxSource", "sandboxSourceTest", emptyMap(), listOf(SandboxParentComponent()))
    val parent = SandboxParentComponent.clientId

    "a source-defined parent has its sandbox from boot, carrying the parent's source configuration" {
        ClientService.get(source).isPresent(sandboxOf(parent)) shouldBe true
        traits(source, sandboxOf(parent)) shouldContain "srcA"
    }

    // Source first, then stored: the sandbox's layers are the parent's, in the parent's order, so a stored revision
    // it shows sits above the source one exactly as it will for the parent once published.
    "a stored revision layers above the source in the sandbox, and a draft shows only there" {
        write(source, parent, "stoB", define = false)
        GedraConfigReload.reloadClient(source, parent)
        traits(source, sandboxOf(parent)) shouldContain "srcA"
        traits(source, sandboxOf(parent)) shouldContain "stoB"

        publish(source, parent)
        GedraConfigService.get(source).setPublishedOnly(asClient(source, parent), parent, true)
        write(source, parent, "stoB", "stoC", define = false)       // a draft on a published-only parent
        GedraConfigReload.reloadClient(source, parent)
        traits(source, parent) shouldNotContain "stoC"
        traits(source, sandboxOf(parent)) shouldContain "stoC"
        traits(source, sandboxOf(parent)) shouldContain "srcA"
    }

    // --- persistent nodes ------------------------------------------------------------------------------------

    val db = mapOf("KDR_DB_NAME" to "sandboxSyncDb", "KDR_LOAD_STORED_CONFIG" to "true")
    val nodeA = Startup.mkTestBootCxt("sandboxSyncA", "sandboxSyncNodeA", db)
    val nodeB = Startup.mkTestBootCxt("sandboxSyncB", "sandboxSyncNodeB", db)
    GedraConfigService.get(nodeB).configCache = null

    "a change only the sandbox runs reaches a peer by the sandbox's own marker" {
        val client = "sbxsync"
        write(nodeA, client, "syA")
        publish(nodeA, client)
        GedraConfigService.get(nodeA).setPublishedOnly(asClient(nodeA, client), client, true)
        ClientSyncService.get(nodeA).announceReload(nodeA, GedraConfigReload.reloadClient(nodeA, client))
        ClientSyncService.get(nodeB).checkSync(nodeB)
        traits(nodeB, client) shouldContain "syA"
        traits(nodeB, sandboxOf(client)) shouldContain "syA"

        // A draft: the parent consumes nothing new, so its marker stays where it was, and only the sandbox's moves.
        write(nodeA, client, "syA", "syB")
        ClientSyncService.get(nodeA).announceReload(nodeA, GedraConfigReload.reloadClient(nodeA, client))
        Thread.sleep(ClientSyncService.checkThrottleMs + 50)
        ClientSyncService.get(nodeB).checkSync(nodeB)
        traits(nodeB, sandboxOf(client)) shouldContain "syB"
        traits(nodeB, client) shouldNotContain "syB"
    }

    // A node booting after the change builds the sandbox from its boot load, and announces its marker on restart.
    "a persistent node builds the sandbox from what it loads at boot" {
        val client = "sbxsync"
        val nodeC = Startup.mkTestBootCxt("sandboxSyncC", "sandboxSyncNodeC", db)
        traits(nodeC, client) shouldNotContain "syB"
        traits(nodeC, sandboxOf(client)) shouldContain "syB"
        GedraConfigLoadService.get(nodeC).loadedMarkers() shouldContainKey sandboxOf(client)
    }

    // Nothing consumed is not nothing stored. Here the only stored row is a draft on a published-only parent, so no
    // client consumes anything at boot -- and the sandbox must still be built with the draft, as a reload builds it.
    "a node booting with nothing consumed still gives the sandbox its parent's drafts" {
        val draftDb = mapOf("KDR_DB_NAME" to "sandboxDraftDb", "KDR_LOAD_STORED_CONFIG" to "true")
        val first = Startup.mkTestBootCxt("sandboxDraft1", "sandboxDraftNode1", draftDb, listOf(SandboxParentComponent()))
        write(first, parent, "drA", define = false)
        GedraConfigService.get(first).setPublishedOnly(asClient(first, parent), parent, true)

        val second = Startup.mkTestBootCxt("sandboxDraft2", "sandboxDraftNode2", draftDb, listOf(SandboxParentComponent()))
        traits(second, parent) shouldNotContain "drA"
        traits(second, sandboxOf(parent)) shouldContain "drA"
        GedraConfigLoadService.get(second).loadedMarkers() shouldContainKey sandboxOf(parent)
    }
})

/** A fixture component whose source-code client asks for a sandbox (issue #928). */
class SandboxParentComponent : ComponentDefinition {
    override val providerName: String = "sandboxParentFixture"
    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "source", clientNamespace(clientId), clientId) {
            defineClient(
                ClientDef(
                    clientId = clientId, name = "Source parent", usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = true,
                ),
            )
            trait("SrcAEntry", "srcA", setOf(GedraDataType.formDoc), "A source trait.") { property("v", "A value.") }
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        const val clientId = "sbxsrcparent"
    }
}
