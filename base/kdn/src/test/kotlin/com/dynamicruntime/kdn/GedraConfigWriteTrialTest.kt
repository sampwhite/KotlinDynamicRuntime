package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.gedra.workflow.SVY
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.user.TestUser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A write is refused when a trial reload of its client would find a problem the client did not already have (issue
 * #843): stored configuration is strict at write, where a person is at the keyboard to fix it, and forgiving once
 * stored. Before, a write only checked that the rows reassembled, so B3 (an unregistered options source) and B4 (an
 * unknown included trait) were stored without complaint and surfaced only at load.
 *
 * Driven over the `/admin` config endpoints, which is where the trial runs. The instance is at the forgiving
 * stored-config mode so a deliberately flawed config can be *seeded* through the service (which does not trial) to
 * show that a pre-existing problem does not block an unrelated write.
 */
class GedraConfigWriteTrialTest : StringSpec({

    val cxt = Startup.mkTestBootCxt(
        "cfgTrial", "gedraConfigWriteTrialTest", mapOf(GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name),
    )
    val admin = TestUser.createFullAdmin(cxt, "chief@trial843.test")

    fun clientDef(client: String, includedTraits: List<String> = emptyList()) = ClientDef(
        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
        enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = includedTraits,
    )

    fun config(client: String, name: String, build: GedraConfigBuilder.() -> Unit): GedraConfig =
        gedraConfig(cxt, name, "${client}config", client, build = build)

    /** The admin bundle-write request for [config]. */
    fun writeBody(config: GedraConfig): Map<String, Any?> = mapOf(
        CFEP.client to config.gedraId.client, CFEP.name to config.name, CFEP.namespaceField to config.namespace,
        CFEP.slots to gedraConfigToEntries(config),
    )

    fun stored(client: String): Int = admin.getItems(ACEP.bundles, mapOf(CFEP.client to client)).size

    "an unregistered options source is refused at write, and nothing is stored (B3)" {
        val client = "trial843src"
        val bad = config(client, "main") {
            defineClient(clientDef(client))
            type("Pick") {
                type = SCT.kObject
                property("pick", "From nowhere.") { optionsSource("noSuchOptionsSource843") }
            }
        }
        val refused = admin.expectError(EXC.badInput, ACEP.bundleWrite, writeBody(bad))
        refused[EP.errorMessage].toString() shouldContain "noSuchOptionsSource843"
        stored(client) shouldBe 0
    }

    "an included trait that does not exist is refused at write (B4)" {
        val client = "trial843inc"
        val bad = config(client, "main") { defineClient(clientDef(client, includedTraits = listOf("noSuchTrait843"))) }
        admin.expectError(EXC.badInput, ACEP.bundleWrite, writeBody(bad))[EP.errorMessage].toString() shouldContain
            "noSuchTrait843"
        stored(client) shouldBe 0
    }

    "a sound config is written as before" {
        val client = "trial843ok"
        val good = config(client, "main") {
            defineClient(clientDef(client))
            trait("OkEntry", "${client}Trait", setOf(GedraDataType.formDoc), "A sound trait.") {
                property("text", "Text.")
            }
        }
        admin.postData(ACEP.bundleWrite, writeBody(good))
        stored(client) shouldBe 1
    }

    "a patch that would introduce a problem is refused, and the stored revision stands" {
        val client = "trial843patch"
        admin.postData(ACEP.bundleWrite, writeBody(config(client, "main") { defineClient(clientDef(client)) }))
        val refused = admin.expectError(
            EXC.badInput, ACEP.bundlePatch,
            mapOf(
                CFEP.client to client, CFEP.name to "main",
                CFEP.edits to listOf(
                    mapOf(
                        CFEP.slot to CCT.cfactDef, GED.action to GedraEditAction.addOrMerge.name,
                        GE.data to mapOf(
                            CCT.name to SVY.surveyComplete, CCT.group to "trial843", CCT.description to "Redeclared.",
                        ),
                    ),
                ),
            ),
        )
        refused[EP.errorMessage].toString() shouldContain SVY.surveyComplete
        val bundle = admin.getItem(ACEP.bundle, mapOf(CFEP.client to client, CFEP.name to "main"))
        (bundle[CFEP.slots] as Map<*, *>).containsKey(CCT.cfactDef) shouldBe false
    }

    // A problem the client already has -- here a stored config seeded without a trial and forgiven at load -- does
    // not block a write that adds nothing new, or fixing one of two broken configs would be refused over the other.
    "a problem the client already has does not block an unrelated write" {
        val client = "trial843pre"
        val seeded = config(client, "broken") {
            defineClient(clientDef(client))
            type("Pick") {
                type = SCT.kObject
                property("pick", "From nowhere.") { optionsSource("noSuchOptionsSource843b") }
            }
        }
        val writer: KdrCxt = cxt.mkSubContext("trialSeed", client).also { it.userId = 8430L }
        GedraConfigService.get(cxt).writeConfig(writer, seeded)
        GedraConfigReload.reloadClient(cxt, client).issues.size shouldBe 1

        val unrelated = config(client, "extra") {
            trait("ExtraEntry", "${client}Extra", setOf(GedraDataType.formDoc), "Another trait.") {
                property("text", "Text.")
            }
        }
        admin.postData(ACEP.bundleWrite, writeBody(unrelated))
        stored(client) shouldBe 2
    }
})
