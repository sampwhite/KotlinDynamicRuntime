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
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Who may build a client on which (issue #1567): a platform operator on any code-defined base, a customer's
 * administrator only on one marked for public use. (A person's own client is `SelfServeFromTemplateTest`'s.) Over the
 * template fixture's shared instance, with a customer of this issue's own.
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
        sourceOf(CLD.delegatedCreateTypeName) shouldBe CLD.publicTemplateOptions
    }
})
