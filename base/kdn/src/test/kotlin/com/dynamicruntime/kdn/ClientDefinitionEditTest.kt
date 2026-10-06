package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Editing a client's definition from the Clients page (issue #1026): the presentation fields of its stored
 * definition, written under the config lock and made to take effect as the copy and menu editors' saves are -- live
 * at once, or a draft for a client with a sandbox (issue #930). The operator-only fields (#820) are not inputs at
 * all, so naming one is refused by the input's shape. One booted instance; a client per case.
 */
class ClientDefinitionEditTest : StringSpec({
    // With the extension fixture, for a stored client built on a source template (issue #945).
    val cxt = Startup.mkTestBootCxt("clientDefEdit1026", "clientDefEdit1026", emptyMap(), listOf(ExtensionTemplateComponent()))
    val svc = GedraConfigService.get(cxt)

    /** Defines [client] in stored configuration, published and reloaded; with a sandbox, or on a template, when asked. */
    fun defineClient(client: String, sandbox: Boolean = false, extends: String? = null) {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 10260L }
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = "Client $client", description = "Before.", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                        domainPrefix = client, userLabels = if (extends == null) listOf("reviewer") else emptyList(),
                        sandbox = sandbox, extendsFromClientId = extends,
                    ),
                )
            },
        )
        svc.publish(setup, GedraId.of(GedraConfigType.configDoc, client, "main"))
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun present(client: String): ClientDef = ClientService.get(cxt).present(client)!!

    fun mainPublished(client: String): Boolean? =
        svc.readLatest(cxt.mkSubContext("check", client), GedraId.of(GedraConfigType.configDoc, client, "main"))?.isPublished

    "a client's administrator renames it, and the change is published, live, and read back" {
        val client = "defeditlive"
        defineClient(client)
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

        val saved = admin.postData(UADEP.clientDefinitionSet, mapOf(CLD.name to "  Renamed  ", CLD.userLabels to listOf("auditor", " reviewer ", "auditor")))
        saved[CLD.client] shouldBe client
        saved[COV.configName] shouldBe "main"
        saved[CPY.mode] shouldBe EDM.live
        val stored = saved[CLD.definition].toJsonMapOrEmpty()
        stored[CLD.name] shouldBe "Renamed"
        stored[CLD.userLabels].toJsonListOfStrings() shouldBe listOf("auditor", "reviewer")
        // What was not sent is as it was; what the client runs now is the edit.
        stored[CLD.description] shouldBe "Before."
        present(client).name shouldBe "Renamed"
        present(client).userLabels shouldBe listOf("auditor", "reviewer")
        mainPublished(client) shouldBe true
        admin.getItem(UADEP.clientDefinition)[CLD.client].toJsonMapOrEmpty()[CLD.name] shouldBe "Renamed"

        // A blank clears an optional field; an empty list clears the labels; the name may not be blank.
        val cleared = admin.postData(UADEP.clientDefinitionSet, mapOf(CLD.description to "", CLD.domainPrefix to " ", CLD.userLabels to emptyList<String>()))
        val after = cleared[CLD.definition].toJsonMapOrEmpty()
        after.containsKey(CLD.description) shouldBe false
        after.containsKey(CLD.domainPrefix) shouldBe false
        after.containsKey(CLD.userLabels) shouldBe false
        present(client).description shouldBe null
        admin.expectError(EXC.badInput, UADEP.clientDefinitionSet, mapOf(CLD.name to " "))
        admin.expectError(EXC.badInput, UADEP.clientDefinitionSet, emptyMap())
    }

    "an administrator who sees every client edits one by name, and the retrieve shows the stored definition" {
        val client = "defeditnamed"
        defineClient(client)
        val full = TestUser.createFullAdmin(cxt, "full@$client.test")
        val saved = full.postData(UADEP.clientDefinitionSet, mapOf(CLD.client to client, CLD.name to "Named Edit", CLD.webResourcesId to "pack1"))
        saved[CLD.client] shouldBe client
        saved[CPY.mode] shouldBe EDM.live
        present(client).name shouldBe "Named Edit"
        present(client).webResourcesId shouldBe "pack1"
        // The full administrator's own, source-defined client is untouched.
        present(full.selfClient()!!).name shouldBe ClientService.get(cxt).present(full.selfClient()!!)!!.name
        val read = full.getItem(UADEP.clientDefinition, mapOf(CLD.client to client))
        read[CLD.storedDefinition].toJsonMapOrEmpty()[CLD.name] shouldBe "Named Edit"
        read[CLD.storedDefinitionConfig] shouldBe "main"
        // A source-defined client has no stored definition to start an edit from.
        full.getItem(UADEP.clientDefinition, mapOf(CLD.client to full.selfClient())).containsKey(CLD.storedDefinition) shouldBe false
    }

    "an edit starts from the client's own stored definition, not the template's values merged into what it runs" {
        val client = "defedittpl"
        defineClient(client, extends = ExtensionTemplateComponent.template)
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        val read = admin.getItem(UADEP.clientDefinition)
        // What the client runs carries the template's labels and resources; what it stores carries neither.
        read[CLD.client].toJsonMapOrEmpty()[CLD.userLabels].toJsonListOfStrings() shouldBe listOf("vip")
        read[CLD.client].toJsonMapOrEmpty()[CLD.webResourcesId] shouldBe "tplres"
        read[CLD.storedDefinition].toJsonMapOrEmpty().containsKey(CLD.userLabels) shouldBe false
        read[CLD.storedDefinition].toJsonMapOrEmpty().containsKey(CLD.webResourcesId) shouldBe false
        // Adding a label stores only the client's own; the template's still joins it when the client runs.
        val saved = admin.postData(UADEP.clientDefinitionSet, mapOf(CLD.userLabels to listOf("own")))
        saved[CLD.definition].toJsonMapOrEmpty()[CLD.userLabels].toJsonListOfStrings() shouldBe listOf("own")
        present(client).userLabels shouldBe listOf("vip", "own")
        present(client).webResourcesId shouldBe "tplres"
    }

    "a domain prefix or custom domain another client declares is refused, by whatever write carries it" {
        val client = "defeditdom"
        val other = "defeditdomother"
        defineClient(client)
        defineClient(other)
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        // `other`'s definition carries `domainPrefix = other`; a hostname's case does not make it another's.
        admin.expectError(EXC.badInput, UADEP.clientDefinitionSet, mapOf(CLD.domainPrefix to other)).toString() shouldContain other
        admin.expectError(EXC.badInput, UADEP.clientDefinitionSet, mapOf(CLD.domainPrefix to other.uppercase())).toString() shouldContain other
        val chief = TestUser.create(cxt, "chief@$other.test", level = ROLE.admin, userClient = other)
        chief.postData(UADEP.clientDefinitionSet, mapOf(CLD.customDomain to "other.example.test"))
        // The rule is the stored write's, so a whole-definition write is held to it too.
        shouldThrow<KdrException> {
            svc.writeConfig(
                cxt.mkSubContext("claim", client).also { it.userId = 10262L },
                gedraConfig(cxt, "main", clientNamespace(client), client) {
                    defineClient(
                        ClientDef(
                            clientId = client, name = "Claimant", usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                            enabledEnvironments = setOf(ENV.unit, ENV.local), customDomain = "Other.Example.Test",
                        ),
                    )
                },
            )
        }.message.shouldNotBeNull() shouldContain other
        admin.postData(UADEP.clientDefinitionSet, mapOf(CLD.customDomain to "forms.example.test"))
        chief.expectError(EXC.badInput, UADEP.clientDefinitionSet, mapOf(CLD.customDomain to "forms.example.test")).toString() shouldContain client
        // Its own prefix again is not a collision.
        admin.postData(UADEP.clientDefinitionSet, mapOf(CLD.domainPrefix to client, CLD.description to "kept"))
        present(client).domainPrefix shouldBe client
    }

    "a field only the platform sets is not an input: naming it is refused, not dropped (issue #820)" {
        val client = "defeditoper"
        defineClient(client)
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        admin.expectError(EXC.badInput, UADEP.clientDefinitionSet, mapOf(CLD.name to "X", CLD.audience to ClientAudience.internal.name))
        admin.expectError(EXC.badInput, UADEP.clientDefinitionSet, mapOf(CLD.name to "X", CLD.enabledEnvironments to listOf(ENV.unit)))
        present(client).name shouldBe "Client $client"
    }

    "a source-defined client, a sandbox, a foreign draft, and another administrator's client are refused" {
        val client = "defeditref"
        defineClient(client, sandbox = true)
        val full = TestUser.createFullAdmin(cxt, "full@$client.test")
        // The full administrator's own client is declared in source code: edited there.
        full.expectError(EXC.badInput, UADEP.clientDefinitionSet, mapOf(CLD.client to full.selfClient(), CLD.name to "X"))
            .toString() shouldContain "source code"
        // A sandbox's definition is its parent's.
        full.expectError(EXC.badInput, UADEP.clientDefinitionSet, mapOf(CLD.client to sandboxOf(client), CLD.name to "X"))
            .toString() shouldContain "sandbox"
        // A client administrator names only their own client.
        val other = "defeditother"
        defineClient(other)
        val chief = TestUser.create(cxt, "chief@$other.test", level = ROLE.admin, userClient = other)
        chief.expectError(EXC.notAuthorized, UADEP.clientDefinitionSet, mapOf(CLD.client to client, CLD.name to "X"))
        // Somebody's unpublished change to the definition's configuration would go live with a save: refused.
        svc.writeConfig(
            cxt.mkSubContext("draft", other).also { it.userId = 10261L },
            gedraConfig(cxt, "main", clientNamespace(other), other) {
                defineClient(
                    ClientDef(
                        clientId = other, name = "Drafted $other", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
            },
        )
        chief.expectError(EXC.badInput, UADEP.clientDefinitionSet, mapOf(CLD.name to "X")).toString() shouldContain "unpublished"
        present(other).name shouldBe "Client $other"
    }

    "a client with a sandbox saves a draft its sandbox shows, live once published (issue #930)" {
        val client = "defeditdraft"
        defineClient(client, sandbox = true)
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

        val saved = admin.postData(UADEP.clientDefinitionSet, mapOf(CLD.name to "Draft Name"))
        saved[CPY.mode] shouldBe EDM.draft
        saved[CLD.definition].toJsonMapOrEmpty()[CLD.name] shouldBe "Draft Name"
        // The client runs what it published; its sandbox, derived from the latest, carries the new name.
        present(client).name shouldBe "Client $client"
        present(sandboxOf(client)).name shouldBe "Draft Name (sandbox)"
        mainPublished(client) shouldBe false
        // The retrieve shows both: what the client runs, and the stored draft an edit starts from.
        val read = admin.getItem(UADEP.clientDefinition)
        read[CLD.client].toJsonMapOrEmpty()[CLD.name] shouldBe "Client $client"
        read[CLD.storedDefinition].toJsonMapOrEmpty()[CLD.name] shouldBe "Draft Name"

        admin.postData(CFEP.bundlePublish, mapOf(CFEP.name to "main"))
        admin.postData(CFEP.reload, emptyMap())
        present(client).name shouldBe "Draft Name"
    }
})
