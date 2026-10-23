package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.ImpactKind
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WfTaskSlot
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
import com.dynamicruntime.common.gedra.workflow.WfTraitRef
import com.dynamicruntime.common.gedra.workflow.WfWindow
import com.dynamicruntime.common.gedra.workflow.WfWindows
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * A normal workflow's time windows set from the client page (issue #1121): written into the overlay of a workflow made
 * from a template and into the definition of one written whole, the refusals, and the impact finding for a change that
 * would, now, freeze or hide forms engaged with the workflow. A client per case, on the shared default instance.
 */
class ClientWorkflowWindowsTest : StringSpec({
    val cxt = TestInstances.default("clientWorkflowWindows1121")

    fun defineClient(client: String) {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 11210L }
        val svc = GedraConfigService.get(cxt)
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = listOf(GT.name), timeZone = "America/Chicago",
                    ),
                )
                // A normal workflow written whole, beside a survey, which has no windows.
                workflow("review", WfEntry.normal) { task("record", "Record it") { trait(GT.name); save("go", "Save", WfSaveKind.edit) } }
                workflow("details", WfEntry.survey) { task("fill", "Fill it in") { trait(GT.name); save("go", "Save", WfSaveKind.edit) } }
            },
        )
        svc.publish(setup, listOf(GedraId.of(GedraConfigType.configDoc, client, "main")))
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun admin(client: String): TestUser = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

    fun windowsRequest(workflowId: String, windows: WfWindows, acknowledge: Boolean = false) = buildMap {
        put(WFD.workflowId, workflowId)
        put(CWF.windows, windows.toInfo())
        if (acknowledge) put(IMP.acknowledgeImpact, true)
    }

    fun storedDefinition(client: String, config: String, workflowId: String) =
        GedraConfigService.get(cxt).readLatest(cxt.mkSubContext("read", client), GedraId.of(GedraConfigType.configDoc, client, config))
            .shouldNotBeNull().entriesBySlot().getValue(CCT.workflowDef).single { it[CCT.workflowId] == workflowId }[CCT.definition].toJsonMapOrEmpty()

    // To the second: a stored moment keeps milliseconds, so a finer one would not read back equal.
    val now = Instant.fromEpochSeconds(cxt.instanceNow().epochSeconds)

    "windows are set where a workflow is stored: in a written-whole definition, and in a template-made overlay" {
        val client = "wfwin1121"
        defineClient(client)
        val chief = admin(client)
        val read = chief.getItem(UADEP.clientWorkflows)
        read[CWF.timeZone] shouldBe "America/Chicago"
        val listed = read[CWF.workflows].toJsonListOfMaps().associateBy { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] }
        listed.getValue("review")[CWF.windowsNotEditable].shouldBeNull()

        val windows = WfWindows(lifetime = WfWindow(now - 1.days, now + 30.days), engagement = WfWindow(end = now + 10.days))
        val saved = chief.postData(UADEP.clientWorkflowWindows, windowsRequest("review", windows))
        saved[CPY.mode] shouldBe EDM.live
        WfWindows.fromInfo(saved[CWF.windows].toJsonMapOrEmpty()) shouldBe windows
        WorkflowService.get(cxt).forClient(client).workflow("review").shouldNotBeNull().def.windows shouldBe windows
        storedDefinition(client, "main", "review").containsKey(WFD.lifetime) shouldBe true

        // A workflow made from a template keeps its windows in its overlay, beside its slots.
        val slots = WfTemplateSlots(null, WFT.collect, "Collect", listOf(WfTaskSlot("collect", "Info", listOf(WfTraitRef(GT.name)))))
        chief.postData(UADEP.clientWorkflowSave, mapOf(CWF.slots to slots.toInfo()))
        chief.postData(UADEP.clientWorkflowWindows, windowsRequest("collect", windows))
        val overlay = storedDefinition(client, "${CWF.configPrefix}collect", "collect")
        overlay[WFD.fromTemplate] shouldBe WFT.collect
        WfWindows.fromInfo(overlay) shouldBe windows
        WorkflowService.get(cxt).forClient(client).workflow("collect").shouldNotBeNull().def.windows shouldBe windows
    }

    "windows are refused on a workflow of another kind, when they do not nest, and when nothing changes" {
        val client = "wfwinno1121"
        defineClient(client)
        val chief = admin(client)
        val windows = WfWindows(lifetime = WfWindow(end = now + 5.days))
        chief.expectError(EXC.badInput, UADEP.clientWorkflowWindows, windowsRequest("details", windows)).toString() shouldContain
            "has no time windows"
        val inverted = WfWindows(lifetime = WfWindow(now + 5.days, now + 1.days))
        chief.expectError(EXC.badInput, UADEP.clientWorkflowWindows, windowsRequest("review", inverted)).toString() shouldContain
            "not before it ends"
        val outside = WfWindows(lifetime = WfWindow(end = now + 5.days), relevancy = WfWindow(end = now + 6.days))
        chief.expectError(EXC.badInput, UADEP.clientWorkflowWindows, windowsRequest("review", outside)).toString() shouldContain
            "after its lifetime window does"
        chief.expectError(EXC.badInput, UADEP.clientWorkflowWindows, windowsRequest("review", WfWindows.none)).toString() shouldContain
            "nothing to save"
        chief.expectError(EXC.notFound, UADEP.clientWorkflowWindows, windowsRequest("nosuch", windows))
    }

    "a change that would freeze or hide engaged forms now is refused with the impact report until acknowledged" {
        val client = "wfwinimp1121"
        defineClient(client)
        val chief = admin(client)
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val gid = user.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any?>()))[GDF.gedraId].toOptStr()!!
        user.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "review"))

        fun findingOf(windows: WfWindows): Map<String, Any?> {
            val refused = chief.expectError(EXC.badInput, UADEP.clientWorkflowWindows, windowsRequest("review", windows))
            refused[EP.errorCode] shouldBe IMP.refusedCode
            return refused[EP.extraData].toJsonMapOrEmpty()[IMP.report].toJsonMapOrEmpty()[IMP.findings].toJsonListOfMaps().single()
        }
        // Tracking ended a moment ago: the engaged form's state would be frozen.
        val frozen = findingOf(WfWindows(relevancy = WfWindow(end = now - 1.days)))
        frozen[IMP.kind] shouldBe ImpactKind.workflowFrozen.name
        frozen[IMP.sampleIds].toJsonListOfStrings() shouldContain gid
        // Tracking that starts later freezes it just the same.
        findingOf(WfWindows(relevancy = WfWindow(start = now + 1.days)))[IMP.kind] shouldBe ImpactKind.workflowFrozen.name
        // A lifetime that ended hides it.
        findingOf(WfWindows(lifetime = WfWindow(end = now - 1.days)))[IMP.kind] shouldBe ImpactKind.workflowHidden.name
        // A window that leaves the workflow as it is now is no harm, and saves.
        chief.postData(UADEP.clientWorkflowWindows, windowsRequest("review", WfWindows(lifetime = WfWindow(end = now + 90.days))))[CPY.mode] shouldBe
            EDM.live
        // And acknowledged, the harmful one goes ahead.
        chief.postData(UADEP.clientWorkflowWindows, windowsRequest("review", WfWindows(lifetime = WfWindow(end = now - 1.days)), acknowledge = true))
        WorkflowService.get(cxt).forClient(client).workflow("review").shouldNotBeNull().def.phaseAt(cxt.instanceNow()).exists shouldBe false
    }
})
