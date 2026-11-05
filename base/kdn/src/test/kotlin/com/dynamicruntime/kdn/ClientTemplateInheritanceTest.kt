package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientExtension
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * What a client built on a template inherits, as the Clients page reads it (issue #1621): the traits the template
 * includes, which the trait catalog names with the template; the template's own traits, listed among the client's
 * own and marked as the template's; and the definition fields the template fills in, which the definition read
 * names. One booted instance with the extension fixture; a stored client per case.
 */
class ClientTemplateInheritanceTest : StringSpec({
    val cxt = TestInstances.extensionTemplate("clientTemplateInheritance1621")
    val svc = GedraConfigService.get(cxt)
    val tpl = ExtensionTemplateComponent.template
    val pub = ExtensionTemplateComponent.publicTemplate

    /** Defines [client] in stored configuration on [template], published and reloaded, with [own] of its definition. */
    fun defineClient(client: String, template: String, own: ClientDef.() -> ClientDef = { this }, sandbox: Boolean = false) {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 16210L }
        val def = ClientDef(
            clientId = client, name = "Client $client", usageType = ClientUsageType.dev, audience = ClientAudience.internal,
            enabledEnvironments = setOf(ENV.unit, ENV.local), extendsFromClientId = template, sandbox = sandbox,
        ).own()
        svc.writeConfig(setup, gedraConfig(cxt, "main", clientNamespace(client), client) { defineClient(def) })
        svc.publish(setup, listOf(GedraId.of(GedraConfigType.configDoc, client, "main")))
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun admin(client: String) = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

    "what the template fills in is what the merge adds, and nothing the client sets itself" {
        val template = ClientDef(
            clientId = "t", name = "T", usageType = ClientUsageType.template, audience = ClientAudience.internal,
            webResourcesId = "pack", sessionHours = 12, refreshIntervalSeconds = 90,
            includedTraits = listOf("a", "b"), userLabels = listOf("vip", "shared"),
        )
        val child = ClientDef(
            clientId = "c", name = "C", usageType = ClientUsageType.dev, audience = ClientAudience.internal,
            sessionHours = 4, includedTraits = listOf("b", "c"), userLabels = listOf("shared"), extendsFromClientId = "t",
        )
        val fills = ClientExtension.templateFills(child, template)
        fills shouldBe mapOf(
            CLD.webResourcesId to "pack",
            CLD.refreshIntervalSeconds to 90,
            CLD.includedTraits to listOf("a"),
            CLD.userLabels to listOf("vip"),
        )
        // Held to the merge: each value it names is the one the merged definition runs with.
        val merged = ClientExtension.mergeDef(child, template)
        merged.webResourcesId shouldBe fills[CLD.webResourcesId]
        merged.refreshIntervalSeconds shouldBe fills[CLD.refreshIntervalSeconds]
        merged.sessionHours shouldBe child.sessionHours
        merged.includedTraits.filterNot { it in child.includedTraits } shouldBe fills[CLD.includedTraits]
        merged.userLabels.filterNot { it in child.userLabels } shouldBe fills[CLD.userLabels]
        // A client that sets everything itself takes nothing.
        val full = child.copy(
            webResourcesId = "own", refreshIntervalSeconds = 30, includedTraits = listOf("a", "b"), userLabels = listOf("vip", "shared"),
        )
        ClientExtension.templateFills(full, template) shouldBe emptyMap()
    }

    "the definition read names what the template fills in, and the stored definition holds none of it" {
        val client = "tplinhdef"
        defineClient(client, tpl, own = { copy(userLabels = listOf("own")) })
        val read = admin(client).getItem(UADEP.clientDefinition)
        val fills = read[CLD.templateFills].toJsonMapOrEmpty()
        fills[CLD.webResourcesId] shouldBe ExtensionTemplateComponent.packageId
        fills[CLD.userLabels].toJsonListOfStrings() shouldContainExactly listOf("vip")
        fills[CLD.includedTraits].toJsonListOfStrings() shouldContainExactly listOf("tplNote")
        // What it runs has the template's; what an edit starts from does not.
        read[CLD.client].toJsonMapOrEmpty()[CLD.userLabels].toJsonListOfStrings() shouldContainExactly listOf("vip", "own")
        read[CLD.storedDefinition].toJsonMapOrEmpty()[CLD.userLabels].toJsonListOfStrings() shouldContainExactly listOf("own")
    }

    "a sandbox of a client on a template is told what the template fills in, as its parent is" {
        val client = "tplinhsbx"
        defineClient(client, tpl, sandbox = true)
        ClientService.get(cxt).templateFills(sandboxOf(client))[CLD.webResourcesId] shouldBe ExtensionTemplateComponent.packageId
    }

    "a client on no template has no fills" {
        val full = TestUser.createFullAdmin(cxt, "full@tplinhnone.test")
        val read = full.getItem(UADEP.clientDefinition, mapOf(CLD.client to ExtensionTemplateComponent.plainClient))
        read.containsKey(CLD.templateFills) shouldBe false
        ClientService.get(cxt).templateFills(ExtensionTemplateComponent.plainClient) shouldBe emptyMap()
    }

    "the trait catalog names the template and what it includes, and lists the template's own traits as its" {
        val client = "tplinhcat"
        defineClient(client, pub)
        val catalog = admin(client).getItem(UADEP.clientTraits)
        catalog[CIT.template] shouldBe pub
        catalog[CIT.templateIncluded].toJsonListOfStrings() shouldContainExactly listOf(GT.name, "pubNote")
        val own = catalog[CIT.ownTraits].toJsonListOfMaps().associateBy { it[CIT.traitId].toOptStr() }
        own.getValue("pubNote")[CIT.fromTemplate] shouldBe pub
        own.getValue("pubNote")[CIT.notEditable].toOptStr().orEmpty() shouldBe
            "From its template, $pub. A trait of this client's own with the same id replaces it whole."
    }

    "a trait of the client's own that replaces the template's is the client's, not the template's" {
        val client = "tplinhown"
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 16211L }
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = "Client $client", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                        extendsFromClientId = pub,
                    ),
                )
                trait("OwnNoteEntry", "pubNote", setOf(GedraDataType.formDoc), "The client's own note.") { property("memo", "A memo.") }
            },
        )
        svc.publish(setup, listOf(GedraId.of(GedraConfigType.configDoc, client, "main")))
        GedraConfigReload.reloadClient(cxt, client)
        val own = admin(client).getItem(UADEP.clientTraits)[CIT.ownTraits].toJsonListOfMaps()
        val note = own.filter { it[CIT.traitId] == "pubNote" }
        note.size shouldBe 1
        note.single()[CIT.fromTemplate].shouldBeNull()
        note.single()[CIT.configName] shouldBe "main"
    }

    "a client on no template has no template in its catalog" {
        val full = TestUser.createFullAdmin(cxt, "full@tplinhcatnone.test")
        val catalog = full.getItem(UADEP.clientTraits, mapOf(CLD.client to ExtensionTemplateComponent.plainClient))
        catalog.containsKey(CIT.template) shouldBe false
        catalog[CIT.ownTraits].toJsonListOfMaps().none { it.containsKey(CIT.fromTemplate) } shouldBe true
        catalog.keys shouldContain CIT.traits
    }
})
