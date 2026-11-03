package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
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

    "a platform operator builds a client on a code client that is no template, and on an unmarked template" {
        val built = listOf("plainkid1567" to ExtensionTemplateComponent.plainClient, "tplkid1567" to ExtensionTemplateComponent.template)
        for ((id, base) in built) {
            operator.postData(ADEP.clientCreate, platformBody(id, base))[CLD.present] shouldBe true
            ClientService.get(cxt).present(id).shouldNotBeNull().extendsFromClientId shouldBe base
        }
    }

    "a customer's administrator builds only on a base marked for public use" {
        val made = "${root}_pub"
        val body = mapOf(
            CLD.clientId to made, CLD.name to "Public based", CLD.extendsFromClientId to ExtensionTemplateComponent.publicTemplate,
        )
        rootAdmin.postData(UADEP.clientCreate, body)[CLD.present] shouldBe true
        ClientService.get(cxt).present(made).shouldNotBeNull().extendsFromClientId shouldBe ExtensionTemplateComponent.publicTemplate
        val refused = listOf(ExtensionTemplateComponent.template, ExtensionTemplateComponent.plainClient, ExtensionTemplateComponent.retiredTemplate)
        for (base in refused) {
            // Signed in at the root again: a create moves nobody, but each case starts where the customer's are made.
            val back = TestUser.create(cxt, "admin@bases1567.test", userClient = root)
            val body = mapOf(CLD.clientId to "${root}_no", CLD.name to "No", CLD.extendsFromClientId to base)
            back.expectError(EXC.badInput, UADEP.clientCreate, body).toString() shouldContain "'$base' is not a client yours can be built on"
        }
        ClientService.get(cxt).known("${root}_no") shouldBe null
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
    }
})
