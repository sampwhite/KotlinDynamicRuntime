package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A client's `schemaDef` named as a type one of its own traits generates (issue #1542): the trait's entry type, or the
 * data type of a trait whose data is written inline. The trait's type replaces it, so the declaration does nothing, and
 * it used to do so without a word. Now the load reports it -- in a unit test, where stored configuration is strict, as a
 * refused reload -- and a write through the endpoints that adds one is refused by the trial. A `schemaDef` a trait
 * refers to by name, and a client's alteration of a global trait's type, are what a client may write, and stay accepted.
 */
class TraitTypeClashTest : StringSpec({
    val cxt = TestInstances.default("traitTypeClash1542")
    val svc = GedraConfigService.get(cxt)

    fun clientDef(client: String) = ClientDef(
        clientId = client, name = client, usageType = ClientUsageType.dev,
        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
    )

    /**
     * Stores [client]'s one config as [build] declares it. Built as a stored config is reassembled, so a type and a
     * trait may share a name, as only a stored row can have them; built as source, the clash refuses the build.
     */
    fun store(client: String, build: GedraConfigBuilder.() -> Unit) {
        val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client, GedraConfigOrigin.stored) {
            defineClient(clientDef(client))
            build()
        }
        svc.writeConfig(cxt.mkSubContext("setup", client), config)
    }

    fun GedraConfigBuilder.ownTrait() =
        trait("OwnEntry", "own", setOf(GedraDataType.formDoc), "The client's own trait.") { property("text", "Some text.") }

    for (generated in listOf("OwnEntry", "OwnData")) {
        "a stored schemaDef named '$generated', a type the client's own trait generates, is reported" {
            val client = "clash${generated.lowercase()}1542"
            store(client) {
                type(generated) { title = "Never shown" }
                ownTrait()
            }
            shouldThrow<KdrException> { GedraConfigReload.reloadClient(cxt, client) }.message.orEmpty().let {
                it shouldContain "'${clientNamespace(client)}.$generated'"
                it shouldContain "the trait 'own' generates"
            }
        }
    }

    "a source config naming a type its own trait generates is refused as it is built" {
        val client = "clashsource1542"
        shouldThrow<KdrException> {
            gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
                type("OwnEntry") { title = "Never shown" }
                ownTrait()
            }
        }.message.orEmpty() shouldContain "the trait 'own' generates"
        // And in the other order, where the type declared second would replace the trait's own.
        shouldThrow<KdrException> {
            gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
                ownTrait()
                type("OwnData") { title = "Replaces the trait's data" }
            }
        }.message.orEmpty() shouldContain "after a trait that generates them"
    }

    "a write through the endpoints that adds such a schemaDef is refused by the trial" {
        val client = "clashwrite1542"
        store(client) { ownTrait() }
        GedraConfigReload.reloadClient(cxt, client)
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        val entry = mapOf(
            CCT.typeName to "${clientNamespace(client)}.OwnEntry",
            CCT.schema to mapOf(SCH.type to SCT.kObject, SCH.title to "Never shown"),
        )
        val edit = mapOf(CFEP.slot to CCT.schemaDef, GED.action to GedraEditAction.addOrReplace.name, GE.data to entry)
        admin.expectError(
            EXC.badInput, CFEP.bundlePatch,
            mapOf(CFEP.configs to listOf(mapOf(CFEP.name to "${client}cfg", CFEP.edits to listOf(edit)))),
        ).toString() shouldContain "the trait 'own' generates"
    }

    "a schemaDef a trait refers to by name, and an alteration of a global trait's type, are accepted" {
        val client = "clashfine1542"
        val nameEntry = SchemaService.get(cxt).storeFor(null).types.keys.first { it.endsWith(".${GT.nameEntry}") }
        store(client) {
            // Named as the trait's data type would be generated, but given to the trait by name: it is the trait's data.
            type("SharedData") {
                type = SCT.kObject
                title = "Shared data"
                property("text", "Some text.")
            }
            trait("SharedEntry", "shared", setOf(GedraDataType.formDoc), "SharedData", "A trait over a shared type.")
            type(nameEntry) { title = "What it is called" }
        }
        GedraConfigReload.reloadClient(cxt, client).issues.isEmpty() shouldBe true
    }
})
