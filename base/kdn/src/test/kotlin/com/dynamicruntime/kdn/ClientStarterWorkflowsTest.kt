package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.QUOTA
import com.dynamicruntime.common.gedra.PlanLimits
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A new client's starter workflows (issue #1346): a creation workflow and a survey made from core's templates, each in a
 * configuration of its own, written and published with the definition, which includes the trait they collect -- on the
 * platform's create, a self-serve create, and on request not at all -- and removing one again.
 */
class ClientStarterWorkflowsTest : StringSpec({
    val cxt = TestInstances.default("starterWorkflows1346")
    val operator = TestUser.createFullAdmin(cxt, "operator@starters1346.test")

    fun create(clientId: String, vararg more: Pair<String, Any?>) = operator.postData(
        ADEP.clientCreate,
        mapOf(
            CLD.clientId to clientId, CLD.name to "Client $clientId", CLD.usageType to ClientUsageType.dev.name,
            CLD.audience to ClientAudience.internal.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
        ) + more,
    )

    fun stored(client: String, name: String) =
        GedraConfigService.get(cxt).readLatest(cxt.mkSubContext("read", client), GedraId.of(GedraConfigType.configDoc, client, name))

    "a new client starts with a creation workflow and a survey, made from the templates and published" {
        val client = "starter1346"
        create(client)
        val workflows = WorkflowService.get(cxt).forClient(client).workflows.mapValues { it.value.def }
        workflows.keys shouldContainExactly setOf("newForm", "formDetails")
        workflows.getValue("newForm").entry shouldBe WfEntry.creation
        workflows.getValue("newForm").fromTemplate shouldBe WFT.createForm
        workflows.getValue("formDetails").fromTemplate shouldBe WFT.survey
        // The definition includes the trait they collect, so they are workflows the client may run.
        ClientService.get(cxt).known(client).shouldNotBeNull().includedTraits shouldContain GT.name
        // Each in a configuration of its own, published, holding only the reference.
        val row = stored(client, "${CWF.configPrefix}newForm").shouldNotBeNull()
        row.isPublished shouldBe true
        row.entriesBySlot().getValue(CCT.workflowDef).single()[CCT.definition] shouldBe
            mapOf(WFD.workflowId to "newForm", WFD.fromTemplate to WFT.createForm)
    }

    "a create may ask for no starter workflows" {
        val client = "nostarter1346"
        create(client, CLD.starterWorkflows to false)
        WorkflowService.get(cxt).forClient(client).workflows.keys shouldBe emptySet()
        ClientService.get(cxt).known(client).shouldNotBeNull().includedTraits shouldNotContain GT.name
        stored(client, "${CWF.configPrefix}newForm").shouldBeNull()
    }

    "a self-serve client starts with them, live although it has a sandbox, and counts them against its plan" {
        val person = TestUser.create(cxt, "maker@starters1346.test")
        val client = person.postData(AEP.createOwnClient, mapOf(CLD.name to "Starter Shop"))[CLD.client].toOptStr().shouldNotBeNull()
        WorkflowService.get(cxt).forClient(client).workflows.keys shouldContainExactly setOf("newForm", "formDetails")
        PlanLimits.usageOf(cxt, client, forms = 0, users = 0).shouldNotBeNull().measures.single { it.measure == QUOTA.workflows }.used shouldBe 2
        // Removed as the client's sandbox makes every change: a draft, live for its people until published -- and the
        // page lists what the sandbox runs, so the next edit finds what this one left.
        val removed = person.postData(UADEP.clientWorkflowRemove, mapOf(WFD.workflowId to "formDetails", CPY.mode to EDM.draft))
        removed[CPY.mode] shouldBe EDM.draft
        WorkflowService.get(cxt).forClient(client).workflows.keys shouldContainExactly setOf("newForm", "formDetails")
        person.getItem(UADEP.clientWorkflows)[CWF.workflows].toJsonListOfMaps()
            .map { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] } shouldContainExactly listOf("newForm")
    }

    "a workflow made from a template is removed, and one written whole is not" {
        val client = "starterrm1346"
        create(client)
        val chief = TestUser.create(cxt, "chief@$client.test", level = "admin", userClient = client)
        val removed = chief.postData(UADEP.clientWorkflowRemove, mapOf(WFD.workflowId to "formDetails"))
        removed[CPY.mode] shouldBe EDM.live
        removed[CWF.configName] shouldBe "${CWF.configPrefix}formDetails"
        WorkflowService.get(cxt).forClient(client).workflows.keys shouldContainExactly setOf("newForm")
        stored(client, "${CWF.configPrefix}formDetails").shouldNotBeNull().entriesBySlot().shouldBeEmpty()
        chief.expectError(EXC.notFound, UADEP.clientWorkflowRemove, mapOf(WFD.workflowId to "formDetails"))
        // Its name is free again: the survey template makes it anew, beside the configuration it was in.
        chief.getItem(UADEP.clientWorkflows)[CWF.templates].toString() shouldContain WFT.survey
    }
})
