package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Only a **present** client gets a schema variant (issue #819). Variants were built from every client definition in
 * the collector, so one the client checks dropped, or one not enabled in this environment, still got a variant built
 * from its rejected definition -- contrary to "nothing can reach them". Each client here declares its own type, so
 * a variant, if built, would carry it.
 */
class ClientVariantPresenceTest : StringSpec({

    val cxt = Startup.mkTestBootCxt(
        "variantPresence", "clientVariantPresenceTest", mapOf(GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name),
    )

    fun store(client: String, def: ClientDef) {
        val config = gedraConfig(cxt, "main", "${client}config", client) {
            defineClient(def)
            type("Own") {
                type = SCT.kObject
                property("text", "Text.")
            }
        }
        val writer = cxt.mkSubContext("presence", client).also { it.userId = 8190L }
        GedraConfigService.get(cxt).writeConfig(writer, config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun def(
        client: String,
        envs: Set<String> = setOf(ENV.unit, ENV.local),
        includedTraits: List<String> = emptyList(),
    ) = ClientDef(
        clientId = client, name = client, usageType = ClientUsageType.production, audience = ClientAudience.customer,
        enabledEnvironments = envs, includedTraits = includedTraits,
    )

    fun hasVariant(client: String): Boolean = "${client}config.Own" in SchemaService.get(cxt).storeFor(client).defs

    "a present client gets its variant" {
        store("presentok819", def("presentok819"))
        hasVariant("presentok819") shouldBe true
    }

    "a client not enabled in this environment gets none" {
        store("elsewhere819", def("elsewhere819", envs = setOf(ENV.local)))
        ClientService.get(cxt).known("elsewhere819").shouldNotBeNull()
        ClientService.get(cxt).present("elsewhere819").shouldBeNull()
        hasVariant("elsewhere819") shouldBe false
    }

    // A customer production client may not include a functional group; the checks drop it (forgiven here, as
    // stored config is outside unit's strict default), and it gets no variant.
    "a client the checks dropped gets none" {
        store("dropped819", def("dropped819", includedTraits = listOf(CLD.allGlobal)))
        ClientService.get(cxt).present("dropped819").shouldBeNull()
        hasVariant("dropped819") shouldBe false
    }

    // A client that stops being present on a reload loses the variant it had.
    "a client that stops being present loses its variant" {
        store("fading819", def("fading819"))
        hasVariant("fading819") shouldBe true
        store("fading819", def("fading819", envs = setOf(ENV.local)))
        hasVariant("fading819") shouldBe false
    }
})
