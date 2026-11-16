package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Who may build a client on which (issues #1567, #1820): a platform operator on any code-defined base, a customer's
 * administrator on one marked for public use or on a code-defined one of the customer's own by its id. (A person's own
 * client is `SelfServeFromTemplateTest`'s.) Over the template fixture's shared instance, with a customer of this issue's
 * own, and the fixture's customer of code.
 */
class ClientBaseRulesTest : StringSpec({
    val cxt = TestInstances.extensionTemplate("clientBases1567")
    val operator = TestUser.createFullAdmin(cxt, "operator@bases1567.test")
    val root = "bases1567"
    operator.postData(
        ADEP.clientCreate,
        mapOf(
            CLD.clientId to root, CLD.name to "Bases", CLD.usageType to ClientUsageType.production.name,
            CLD.audience to ClientAudience.customer.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
            CLD.newCustomer to mapOf(CUST.name to "Bases Co"),
        ),
    )
    val rootAdmin = TestUser.create(cxt, "admin@bases1567.test", level = ROLE.admin, userClient = root)

    fun platformBody(clientId: String, base: String) = mapOf(
        CLD.clientId to clientId, CLD.name to "Built $clientId", CLD.usageType to ClientUsageType.dev.name,
        CLD.audience to ClientAudience.internal.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
        CLD.extendsFromClientId to base,
    )

    fun creation(client: String) = WorkflowService.get(cxt).forClient(client).creation?.def?.workflowId
    fun coreDetails(client: String) = WorkflowService.get(cxt).forClient(client).coreDetails?.def?.workflowId

    "a platform operator builds on a code client that is no template, with the starters its base lacks" {
        // A plain code client and an unmarked template, neither with a New form or a core details workflow: both starters come.
        val built = listOf("plainkid1567" to ExtensionTemplateComponent.plainClient, "tplkid1567" to ExtensionTemplateComponent.template)
        for ((id, base) in built) {
            operator.postData(ADEP.clientCreate, platformBody(id, base))[CLD.present] shouldBe true
            ClientService.get(cxt).present(id).shouldNotBeNull().extendsFromClientId shouldBe base
            creation(id).shouldNotBeNull()
            coreDetails(id).shouldNotBeNull()
        }
        // A base with a New form keeps it: only the core details workflow's starter is added beside it.
        operator.postData(ADEP.clientCreate, platformBody("pubkid1567", ExtensionTemplateComponent.publicTemplate))
        creation("pubkid1567") shouldBe ExtensionTemplateComponent.creationWorkflow
        coreDetails("pubkid1567").shouldNotBeNull()
    }

    "a customer's administrator builds only on a base marked for public use" {
        val made = "${root}_pub"
        val body = mapOf(
            CLD.clientId to made, CLD.name to "Public based", CLD.extendsFromClientId to ExtensionTemplateComponent.publicTemplate,
        )
        rootAdmin.postData(UADEP.clientCreate, body)[CLD.present] shouldBe true
        ClientService.get(cxt).present(made).shouldNotBeNull().extendsFromClientId shouldBe ExtensionTemplateComponent.publicTemplate
        // The base's New form, not a starter's.
        creation(made) shouldBe ExtensionTemplateComponent.creationWorkflow
        val refused = listOf(ExtensionTemplateComponent.template, ExtensionTemplateComponent.plainClient, ExtensionTemplateComponent.retiredTemplate)
        for (base in refused) {
            val refusedBody = mapOf(CLD.clientId to "${root}_no", CLD.name to "No", CLD.extendsFromClientId to base)
            rootAdmin.expectError(EXC.badInput, UADEP.clientCreate, refusedBody).toString() shouldContain
                "'$base' is not a client yours can be built on"
        }
        ClientService.get(cxt).known("${root}_no") shouldBe null
    }

    "a write by anyone but the platform may not move a client onto a base that is not public" {
        // The backstop where every stored write passes: a signed-in client administrator writing their own
        // definition, here straight through the service rather than an endpoint.
        val client = "${root}_pub"
        val writer = cxt.mkSubContext("baseMove1567", client).also {
            it.userProfile = UserProfile(authId = "admin-$client", userId = 15671L, client = client, roles = setOf(ROLE.user, ROLE.admin))
        }
        val def = ClientService.get(cxt).present(client).shouldNotBeNull()
        val moved = gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(def.copy(extendsFromClientId = ExtensionTemplateComponent.plainClient))
        }
        val refusal = shouldThrow<KdrException> { GedraConfigService.get(cxt).writeConfig(writer, moved, trial = true) }
        refusal.message.shouldNotBeNull() shouldContain "is not a client yours can be built on"
    }

    "the bases each creator may name: every code base for the platform, the marked ones in use for anyone else" {
        val clients = ClientService.get(cxt)
        clients.publicTemplates().map { it.clientId } shouldBe listOf(ExtensionTemplateComponent.publicTemplate)
        val bases = clients.baseClients().map { it.clientId }
        for (base in listOf(ExtensionTemplateComponent.template, ExtensionTemplateComponent.publicTemplate)) bases shouldContain base
        bases shouldContain ExtensionTemplateComponent.plainClient
        // Not one retired, nor one built on another.
        bases.contains(ExtensionTemplateComponent.retiredTemplate) shouldBe false
        bases.contains(ExtensionTemplateComponent.child) shouldBe false
        // Each create's form draws its choices from the list for its creator.
        val defs = SchemaService.get(cxt).storeFor(null).defs
        fun sourceOf(typeName: String) = defs.entries.single { it.key.endsWith(".$typeName") }.value.toJsonMapOrEmpty()[SCH.properties]
            .toJsonMapOrEmpty()[CLD.extendsFromClientId].toJsonMapOrEmpty()[SCH.optionsSource]
        sourceOf(CLD.createTypeName) shouldBe CLD.templateOptions
        sourceOf(CLD.delegatedCreateTypeName) shouldBe CLD.customerTemplateOptions
    }

    // The fixture's customer of code, with its root and a code-defined client of its own by its id (issue #1820).
    val cuRoot = ExtensionTemplateComponent.customerRoot
    val cuKit = ExtensionTemplateComponent.customerKit
    val cuAdmin = TestUser.create(cxt, "admin@tplcu1820.test", level = ROLE.admin, userClient = cuRoot)

    "a customer's administrator also builds on a code-defined client of the customer's own, its root included" {
        val built = listOf("${cuRoot}_kit1820" to cuKit, "${cuRoot}_root1820" to cuRoot, "${cuRoot}_pub1820" to ExtensionTemplateComponent.publicTemplate)
        for ((made, base) in built) {
            cuAdmin.postData(UADEP.clientCreate, mapOf(CLD.clientId to made, CLD.name to "Built $made", CLD.extendsFromClientId to base))[
                CLD.present,
            ] shouldBe true
            ClientService.get(cxt).present(made).shouldNotBeNull().extendsFromClientId shouldBe base
        }
        // Not a code client that is neither public nor the customer's, nor one retired, nor a client defined only in
        // stored configuration -- another customer's root, here.
        val refused = listOf(ExtensionTemplateComponent.template, ExtensionTemplateComponent.plainClient, ExtensionTemplateComponent.retiredTemplate, root)
        for (base in refused) {
            val body = mapOf(CLD.clientId to "${cuRoot}_no1820", CLD.name to "No", CLD.extendsFromClientId to base)
            cuAdmin.expectError(EXC.badInput, UADEP.clientCreate, body).toString() shouldContain "or one of the customer '$cuRoot''s own"
        }
        ClientService.get(cxt).known("${cuRoot}_no1820") shouldBe null
        // And another customer's administrator may not build on this customer's.
        val across = mapOf(CLD.clientId to "${root}_kit1820", CLD.name to "No", CLD.extendsFromClientId to cuKit)
        rootAdmin.expectError(EXC.badInput, UADEP.clientCreate, across).toString() shouldContain "'$cuKit' is not a client yours can be built on"
    }

    "a client built on the customer's own takes its definition in code, and nothing configured on it" {
        // Stored configuration on the customer's code-defined client: the client has it, and one built on it does not.
        val edits = gedraConfig(cxt, "edits1820", "${clientNamespace(cuKit)}.edits1820", cuKit) {
            trait("KitStoredEntry", "kitStored", setOf(GedraDataType.formDoc), "Configured on the kit.") { property("s", "S.") }
        }
        GedraConfigService.get(cxt).writeConfig(cxt, edits)
        GedraConfigReload.reloadClient(cxt, cuKit)
        fun traitIds(client: String) = SchemaService.get(cxt).gedraTraitsFor(client).map { it.traitId }
        traitIds(cuKit) shouldContain "kitStored"
        val made = "${cuRoot}_over1820"
        cuAdmin.postData(UADEP.clientCreate, mapOf(CLD.clientId to made, CLD.name to "Over", CLD.extendsFromClientId to cuKit))[
            CLD.present,
        ] shouldBe true
        traitIds(made) shouldContain "kitNote"
        traitIds(made) shouldNotContain "kitStored"
    }

    "the write backstop holds a customer's administrator to the same bases" {
        // One built on the root, which includes no traits, so a move changes nothing else the trial load would judge.
        val client = "${cuRoot}_root1820"
        val writer = cxt.mkSubContext("baseMove1820", client).also {
            it.userProfile = UserProfile(authId = "admin-$cuRoot", userId = 18201L, client = cuRoot, roles = setOf(ROLE.user, ROLE.admin))
        }
        val def = ClientService.get(cxt).present(client).shouldNotBeNull()
        fun movedOnto(base: String) = gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(def.copy(extendsFromClientId = base))
        }
        // The customer's own: judged and not stored.
        GedraConfigService.get(cxt).writeConfig(writer, movedOnto(cuKit), trial = true, store = false)
        val refusal = shouldThrow<KdrException> {
            GedraConfigService.get(cxt).writeConfig(writer, movedOnto(ExtensionTemplateComponent.plainClient), trial = true, store = false)
        }
        refusal.message.shouldNotBeNull() shouldContain "is not a client yours can be built on"
        ClientService.get(cxt).present(client).shouldNotBeNull().extendsFromClientId shouldBe cuRoot
    }

    "each caller is offered the bases its create admits, and the platform the customers" {
        fun values(choices: Map<String, Any?>, field: String) = choices[field].toJsonListOfMaps().mapNotNull { it[SCH.value].toOptStr() }
        val forCustomer = cuAdmin.getItem(UADEP.clientCreateChoices)
        forCustomer[CLD.asPlatform] shouldBe false
        forCustomer[CLD.customerId] shouldBe cuRoot
        values(forCustomer, CLD.templateChoices) shouldContainExactlyInAnyOrder listOf(ExtensionTemplateComponent.publicTemplate, cuRoot, cuKit)
        forCustomer.containsKey(CLD.customerChoices) shouldBe false
        // A customer with no code-defined client of its own is offered the public ones alone.
        values(rootAdmin.getItem(UADEP.clientCreateChoices), CLD.templateChoices) shouldBe listOf(ExtensionTemplateComponent.publicTemplate)
        // The platform: every base, and every customer.
        val forPlatform = operator.getItem(UADEP.clientCreateChoices)
        forPlatform[CLD.asPlatform] shouldBe true
        values(forPlatform, CLD.templateChoices) shouldContainExactlyInAnyOrder ClientService.get(cxt).baseClients().map { it.clientId }
        val customers = values(forPlatform, CLD.customerChoices)
        customers shouldContain cuRoot
        customers shouldContain root
        // The choices list beside the create's input type agrees, asked as the same caller.
        val provider = SchemaCollector.get(cxt).shouldNotBeNull().optionsProviders[CLD.customerTemplateOptions].shouldNotBeNull()
        val asCuAdmin = cxt.mkSubContext("options1820", cuRoot).also {
            it.userProfile = UserProfile(authId = "admin-$cuRoot", userId = 18202L, client = cuRoot, roles = setOf(ROLE.user, ROLE.admin))
        }
        provider(asCuAdmin, CLD.extendsFromClientId).map { it.value } shouldContainExactlyInAnyOrder values(forCustomer, CLD.templateChoices)
        // Anyone who is not a customer's root administrator is refused, as the create refuses them.
        val kidAdmin = TestUser.create(cxt, "admin@kid1820.test", level = ROLE.admin, userClient = "${cuRoot}_kit1820")
        kidAdmin.expectError(EXC.notAuthorized, UADEP.clientCreateChoices).toString() shouldContain "is no customer's root"
        values(forPlatform, CLD.templateChoices) shouldNotContain ExtensionTemplateComponent.retiredTemplate
    }
})
