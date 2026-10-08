package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GU
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * A client with a Shadow Sandbox runs only what it publishes **after a restart too** (issues #930, #935). Its tier
 * comes from its published definition asking for a sandbox, not from a toggle, and the boot once read only the
 * toggle -- so a restarted node ran the client's drafts, which made the publish impact report empty, since the
 * running configuration already was the candidate. Two boots over one database: the second is the restart.
 */
class SandboxBootTierTest : StringSpec({
    val db = mapOf("KDR_DB_NAME" to "sbxBootTierDb", "KDR_LOAD_STORED_CONFIG" to "true")
    val client = "sbxboot"

    fun known(node: KdrCxt, client: String, traitId: String): Boolean =
        SchemaService.get(node).storeFor(client).types["${GCFG.globalNamespace}.${GU.unionName(GedraDataType.formDoc)}"]
            ?.variants?.isKnown(traitId) == true

    "a restarted node runs the published revision of a client whose definition asks for a sandbox; the sandbox its draft" {
        // Its own instance (issue #1075): the first of two boots over its own database (`KDR_DB_NAME`); the restart's
        // boot-time load is under test.
        val first = Startup.mkTestBootCxt("sbxBootA", "sbxBootNodeA", db)
        val svc = GedraConfigService.get(first)
        val setup = first.mkSubContext("setup", client).also { it.userId = 9930L }
        fun write(name: String, build: GedraConfigBuilder.() -> Unit) =
            svc.writeConfig(setup, gedraConfig(first, name, clientNamespace(client), client, build = build))
        fun publish(name: String) = svc.publish(setup, GedraId.of(GedraConfigType.configDoc, client, name))

        write("main") {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = true,
                ),
            )
        }
        write("traits") {
            trait("MemoEntry", "memo", setOf(GedraDataType.formDoc), "A memo.") { property("text", "What it says.") }
            trait("ExtraEntry", "extra", setOf(GedraDataType.formDoc), "A note.") { property("note", "A note.") }
        }
        publish("main")
        publish("traits")
        // The draft drops `extra`.
        write("traits") {
            trait("MemoEntry", "memo", setOf(GedraDataType.formDoc), "A memo.") { property("text", "What it says.") }
        }
        GedraConfigReload.reloadClient(first, client)
        known(first, client, "extra") shouldBe true
        known(first, sandboxOf(client), "extra") shouldBe false

        // The restart: a fresh boot reads the same database.
        val second = Startup.mkTestBootCxt("sbxBootB", "sbxBootNodeB", db)
        known(second, client, "extra") shouldBe true
        known(second, sandboxOf(client), "extra") shouldBe false
    }
})
