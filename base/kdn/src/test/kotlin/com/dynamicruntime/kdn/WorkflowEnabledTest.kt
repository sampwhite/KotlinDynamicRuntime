package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.ImpactKind
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WorkflowPhases
import com.dynamicruntime.common.gedra.workflow.WorkflowService
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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Turning a normal workflow off (issue #1635): a workflow parameter, `enabled`, beside its windows and who sees it. Turned
 * off, the workflow is as if not configured -- outside its lifetime -- so nothing shows it, its stored data is kept, and
 * turning it on again brings it all back. A change that hides engaged forms is refused with the impact report until
 * acknowledged; a workflow of another kind has no switch; and a sandbox's switch is its own. The code's default, and a
 * client turning off a workflow its code declares, are in `WorkflowParamsTest`. A client per case, on the shared
 * default instance.
 */
class WorkflowEnabledTest : StringSpec({
    val cxt = TestInstances.default("workflowEnabled1635")

    fun defineClient(client: String, sandbox: Boolean = false) {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 16350L }
        val svc = GedraConfigService.get(cxt)
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = listOf(GT.name), sandbox = sandbox,
                    ),
                )
                workflow("review", WfEntry.normal) { task("record", "Record it") { trait(GT.name); save("go", "Save", WfSaveKind.edit) } }
                workflow("details", WfEntry.coreDetails) {
                    task("fill", "Fill it in") { trait(GT.name); save("go", "Save", WfSaveKind.edit) }
                }
            },
        )
        svc.publish(setup, listOf(GedraId.of(GedraConfigType.configDoc, client, "main")))
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun admin(client: String): TestUser = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

    fun switchRequest(on: Boolean?, workflowId: String = "review", acknowledge: Boolean = false) = buildMap {
        put(WFD.workflowId, workflowId)
        if (on == null) put(WFP.unset, listOf(WFP.enabled)) else put(WFP.params, mapOf(WFP.enabled to on))
        if (acknowledge) put(IMP.acknowledgeImpact, true)
    }

    fun exists(client: String, workflowId: String = "review"): Boolean =
        WorkflowPhases.of(cxt, client, WorkflowService.get(cxt).forClient(client).workflow(workflowId).shouldNotBeNull().def).exists

    "turned off, a workflow is as if not configured, its data kept; turned on, it all comes back" {
        val client = "wfon1635"
        defineClient(client)
        val chief = admin(client)
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val gid = user.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any?>()))[GDF.gedraId].toOptStr()!!
        user.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "review"))

        fun states() = user.postData(GEP.formDocRecomputeState, mapOf(GDF.gedraId to gid))[GDF.states].toJsonListOfMaps()
        fun shownFor(traitId: String) =
            states().any { it[GE.traitId].toOptStr() == traitId && it[GE.data].toJsonMapOrEmpty()[WFD.workflowId] == "review" }
        fun listed() = user.getItems(GEP.workflowAggregate).any { it[WFD.workflowId] == "review" }
        shownFor(WFS.workflowState) shouldBe true
        listed() shouldBe true

        // Turning it off hides the engaged form's part in it, so it is refused with the impact report until acknowledged.
        val refused = chief.expectError(EXC.badInput, UADEP.clientWorkflowParams, switchRequest(false))
        refused[EP.errorCode] shouldBe IMP.refusedCode
        val finding = refused[EP.extraData].toJsonMapOrEmpty()[IMP.report].toJsonMapOrEmpty()[IMP.findings].toJsonListOfMaps().single()
        finding[IMP.kind] shouldBe ImpactKind.workflowHidden.name
        finding[IMP.sampleIds].toJsonListOfStrings() shouldContain gid
        exists(client) shouldBe true
        chief.postData(UADEP.clientWorkflowParams, switchRequest(false, acknowledge = true))

        // Off: nothing shows it -- the form, the workflow pages, its view, a new engagement -- and nothing is calculated.
        exists(client) shouldBe false
        shownFor(WFS.workflowState) shouldBe false
        shownFor(WFS.workflowEngagement) shouldBe false
        listed() shouldBe false
        user.expectError(EXC.notFound, GEP.workflowView, args = mapOf(GDF.workflowId to "review", GDF.gedraId to gid))
        user.expectError(EXC.badInput, GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "review"))
        // Its stored data is kept: the engagement is still there.
        TestUser.createFullAdmin(cxt, "full@$client.test").getItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid))[GDF.states]
            .toJsonListOfMaps().any { it[GE.traitId].toOptStr() == WFS.workflowEngagement } shouldBe true
        // The client page still lists it, as off.
        val params = chief.getItem(UADEP.clientWorkflows)[CWF.workflows].toJsonListOfMaps()
            .single { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] == "review" }[CWF.params].toJsonMapOrEmpty()
        params[WFP.inForce].toJsonMapOrEmpty()[WFP.enabled] shouldBe false

        // Turned on again -- nothing hidden by that, so no report -- it is all back as it was.
        chief.postData(UADEP.clientWorkflowParams, switchRequest(true))
        exists(client) shouldBe true
        shownFor(WFS.workflowEngagement) shouldBe true
        shownFor(WFS.workflowState) shouldBe true
        listed() shouldBe true
        user.getData(GEP.workflowView, mapOf(GDF.workflowId to "review", GDF.gedraId to gid)).shouldNotBeNull()
    }

    "only a normal workflow has a switch, and it is true or false" {
        val client = "wfonno1635"
        defineClient(client)
        val chief = admin(client)
        chief.expectError(EXC.badInput, UADEP.clientWorkflowParams, switchRequest(false, workflowId = "details")).toString() shouldContain
            "cannot be turned off"
        val notABoolean = mapOf(WFD.workflowId to "review", WFP.params to mapOf(WFP.enabled to "no"))
        chief.expectError(EXC.badInput, UADEP.clientWorkflowParams, notABoolean).toString() shouldContain "not true or false"
        // On is what nothing set means; saying so is the client's own choice, so it saves, and saying it again does not.
        chief.postData(UADEP.clientWorkflowParams, switchRequest(true))
        chief.expectError(EXC.badInput, UADEP.clientWorkflowParams, switchRequest(true)).toString() shouldContain "nothing to save"
        exists(client) shouldBe true
    }

    "a sandbox's switch is its own: turned off there, the client's people still have the workflow" {
        val client = "wfonsbx1635"
        defineClient(client, sandbox = true)
        val sandbox = sandboxOf(client)
        val full = TestUser.createFullAdmin(cxt, "full@$client.test")
        full.postData(UADEP.clientWorkflowParams, switchRequest(false) + (CLD.client to sandbox))
        exists(sandbox) shouldBe false
        exists(client) shouldBe true
        // The client turning it off reaches the sandbox only while the sandbox follows it: here it has its own.
        full.postData(UADEP.clientWorkflowParams, switchRequest(true) + (CLD.client to sandbox))
        full.postData(UADEP.clientWorkflowParams, switchRequest(false) + (CLD.client to client))
        exists(client) shouldBe false
        exists(sandbox) shouldBe true
        // Unset, the sandbox follows its client's choice again.
        full.postData(UADEP.clientWorkflowParams, switchRequest(null) + (CLD.client to sandbox))
        exists(sandbox) shouldBe false
        full.getItem(UADEP.clientWorkflows, mapOf(CLD.client to sandbox))[CWF.workflows].toJsonListOfMaps()
            .single { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] == "review" }[CWF.params].toJsonMapOrEmpty()[WFP.from]
            .toJsonMapOrEmpty()[WFP.enabled].shouldNotBeNull() shouldBe "parent"
        full.getItem(UADEP.clientWorkflows, mapOf(CLD.client to client))[CWF.workflows].toJsonListOfMaps()
            .single { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] == "review" }[CWF.params].toJsonMapOrEmpty()[WFP.from]
            .toJsonMapOrEmpty()[WFP.enabled].shouldBeNull()
    }
})
