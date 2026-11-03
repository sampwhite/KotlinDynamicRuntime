package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientCreate
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.report.ReportService
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A self-serve client started from a code-defined template: offered on the home page, extending the template, its
 * New form the template's until it writes its own. Over the template fixture's shared instance, whose customer
 * template is offered, and whose internal and retired ones are not.
 */
class SelfServeFromTemplateTest : StringSpec({
    val tpl = ExtensionTemplateComponent.publicTemplate
    val cxt = TestInstances.extensionTemplate("selfServeTemplate")

    fun state(user: TestUser) = user.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()

    "the home page offers a customer template to a person who has made no client, and not once they have" {
        val person = TestUser.create(cxt, "offered@selftpl.test")
        val offered = state(person)[HFLD.selfServeTemplates].toJsonListOfMaps()
        // Neither the internal template nor the retired one.
        offered.map { it[HFLD.id] } shouldBe listOf(tpl)
        offered.single()[HFLD.label] shouldBe "Public template"
        val client = person.postData(AEP.createOwnClient, mapOf(CLD.name to "Offered"))[CLD.client].toOptStr().shouldNotBeNull()
        val again = state(TestUser.create(cxt, "offered@selftpl.test", userClient = CL.public))
        again[HFLD.selfServeClient] shouldBe client
        again[HFLD.selfServeTemplates] shouldBe null
    }

    "a client started from a template extends it, makes forms with its creation workflow, and runs no starters" {
        val person = TestUser.create(cxt, "started@selftpl.test")
        val result = person.postData(AEP.createOwnClient, mapOf(CLD.name to "Started", CLD.extendsFromClientId to tpl))
        val client = result[CLD.client].toOptStr().shouldNotBeNull()
        result[CLD.definition].toJsonMapOrEmpty()[CLD.extendsFromClientId] shouldBe tpl
        ClientService.get(cxt).present(client).shouldNotBeNull().extendsFromClientId shouldBe tpl
        // The template's New form, in the client and in its sandbox.
        for (scope in listOf(client, sandboxOf(client))) {
            WorkflowService.get(cxt).forClient(scope).creation.shouldNotBeNull().def.workflowId shouldBe
                ExtensionTemplateComponent.creationWorkflow
        }
        // Its definition was written, and no starter workflow beside it: one would have replaced the template's.
        val bound = cxt.mkSubContext("selfServeTemplate", client)
        val written = GedraConfigService.get(cxt).listConfigs(bound).map { it.gedraId.baseId }
        written shouldContain CLD.definitionConfigName
        written.none { it.startsWith(CWF.configPrefix) } shouldBe true
        // The starter report names the form, a trait the template includes.
        val report = ReportService.get(cxt).forClient(client).report(ClientCreate.starterReport).shouldNotBeNull()
        report.bound.columns.map { it.columnId } shouldContain "name"
    }

    "a client writing its own creation workflow replaces the template's, in its sandbox until published" {
        val person = TestUser.create(cxt, "own@selftpl.test")
        val client = person.postData(AEP.createOwnClient, mapOf(CLD.name to "Own", CLD.extendsFromClientId to tpl))[CLD.client]
            .toOptStr().shouldNotBeNull()
        val writer = cxt.mkSubContext("selfServeTemplateWrite", client).also { it.userId = 9451L }
        val config = gedraConfig(cxt, CWF.configPrefix + "ownCreate", clientNamespace(client), client) {
            workflow("ownCreate", WfEntry.creation) { task("n", "A note first") { trait("pubNote"); save("c", "Create") } }
        }
        GedraConfigService.get(cxt).writeConfig(writer, config, trial = true)
        GedraConfigReload.reloadClient(cxt, client)
        WorkflowService.get(cxt).forClient(sandboxOf(client)).creation.shouldNotBeNull().def.workflowId shouldBe "ownCreate"
        // Unpublished, so the client itself still makes forms with the template's.
        WorkflowService.get(cxt).forClient(client).creation.shouldNotBeNull().def.workflowId shouldBe
            ExtensionTemplateComponent.creationWorkflow
    }

    "only a customer template in use may be started from, and a refused start leaves the person's client to make" {
        val person = TestUser.create(cxt, "refused@selftpl.test")
        val configured = TestUser.create(cxt, "maker@selftpl.test").postData(AEP.createOwnClient, mapOf(CLD.name to "Maker"))[CLD.client]
            .toOptStr().shouldNotBeNull()
        // A code-defined client that is not a template, an internal template, a retired one, and a configured client.
        val refused = listOf(
            ExtensionTemplateComponent.child, ExtensionTemplateComponent.template, ExtensionTemplateComponent.retiredTemplate, configured,
        )
        for (id in refused) {
            person.expectError(400, AEP.createOwnClient, mapOf(CLD.name to "Copy", CLD.extendsFromClientId to id))
                .toString() shouldContain "not a template a client can start from"
        }
        // Refused before the person's one client was claimed.
        person.postData(AEP.createOwnClient, mapOf(CLD.name to "Mine"))[CLD.client].toOptStr().shouldNotBeNull()
    }

    "the platform create's choices take the internal template too, but never the retired one" {
        val ids = ClientService.get(cxt).templateClients().map { it.clientId }
        ids shouldContain ExtensionTemplateComponent.template
        ids shouldContain tpl
        ids.contains(ExtensionTemplateComponent.retiredTemplate) shouldBe false
    }
})
