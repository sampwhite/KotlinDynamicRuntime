package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
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
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfParams
import com.dynamicruntime.common.gedra.workflow.WfParamsSetting
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WfTaskSlot
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
import com.dynamicruntime.common.gedra.workflow.WfTraitRef
import com.dynamicruntime.common.gedra.workflow.WfWindow
import com.dynamicruntime.common.gedra.workflow.WfWindows
import com.dynamicruntime.common.gedra.workflow.WorkflowParamsService
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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * A normal workflow's time windows set from the client page as its **parameters** (issues #1121, #1356): kept per
 * client in a table of their own, in force at once whoever declared the workflow, never part of its definition, with
 * the three earlier settings kept; the refusals; the impact finding for a change that would, now, freeze or hide forms
 * engaged with the workflow; and a sandbox reading its parent's until it has its own. A client per case, on the shared
 * default instance.
 */
class ClientWorkflowWindowsTest : StringSpec({
    val cxt = TestInstances.default("clientWorkflowWindows1121")

    fun defineClient(client: String, sandbox: Boolean = false) {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 9000L }
        val svc = GedraConfigService.get(cxt)
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = listOf(GT.name), timeZone = "America/Chicago",
                        sandbox = sandbox,
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
        put(WFP.params, WfParams(windows).toInfo())
        if (acknowledge) put(IMP.acknowledgeImpact, true)
    }

    fun storedDefinition(client: String, config: String, workflowId: String) =
        GedraConfigService.get(cxt).readLatest(cxt.mkSubContext("read", client), GedraId.of(GedraConfigType.configDoc, client, config))
            .shouldNotBeNull().entriesBySlot().getValue(CCT.workflowDef).single { it[CCT.workflowId] == workflowId }[CCT.definition].toJsonMapOrEmpty()

    fun windowsIn(client: String, workflowId: String): WfWindows =
        WorkflowPhases.windowsOf(cxt, client, WorkflowService.get(cxt).forClient(client).workflow(workflowId).shouldNotBeNull().def)

    // To the second: a stored moment keeps milliseconds, so a finer one would not read back equal.
    val now = Instant.fromEpochSeconds(cxt.instanceNow().epochSeconds)

    "windows are a workflow's parameters: in force at once, never in its definition, the earlier ones kept" {
        val client = "wfwin1121"
        defineClient(client)
        val chief = admin(client)
        chief.getItem(UADEP.clientWorkflows)[CWF.timeZone] shouldBe "America/Chicago"

        val windows = WfWindows(lifetime = WfWindow(now - 1.days, now + 30.days), engagement = WfWindow(end = now + 10.days))
        val saved = chief.postData(UADEP.clientWorkflowParams, windowsRequest("review", windows))[CWF.params].toJsonMapOrEmpty()
        WfParamsSetting.fromInfo(saved[WFP.current].toJsonMapOrEmpty()).params.windows shouldBe windows
        windowsIn(client, "review") shouldBe windows
        // In force with no publish, and nothing written into the definition.
        storedDefinition(client, "main", "review").containsKey(WFD.lifetime) shouldBe false

        // A workflow made from a template takes them the same way: its overlay holds none.
        val slots = WfTemplateSlots(null, WFT.collect, "Collect", listOf(WfTaskSlot("collect", "Info", listOf(WfTraitRef(GT.name)))))
        chief.postData(UADEP.clientWorkflowSave, mapOf(CWF.slots to slots.toInfo()))
        chief.postData(UADEP.clientWorkflowParams, windowsRequest("collect", windows))
        storedDefinition(client, "${CWF.configPrefix}collect", "collect")[WFD.fromTemplate] shouldBe WFT.collect
        storedDefinition(client, "${CWF.configPrefix}collect", "collect").containsKey(WFD.lifetime) shouldBe false
        windowsIn(client, "collect") shouldBe windows

        // Each change keeps what it replaces, with who set it, newest first, three at most; the page lists them.
        for (n in 1..3) chief.postData(UADEP.clientWorkflowParams, windowsRequest("review", WfWindows(lifetime = WfWindow(end = now + (40 + n).days))))
        val listed = chief.getItem(UADEP.clientWorkflows)[CWF.workflows].toJsonListOfMaps()
            .single { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] == "review" }[CWF.params].toJsonMapOrEmpty()
        val history = WfParamsSetting.listFrom(listed[WFP.history])
        history shouldHaveSize 3
        history.map { it.params.windows.lifetime.end } shouldBe listOf(now + 42.days, now + 41.days, now + 30.days)
        history.first().setByName.shouldNotBeNull()
        WfParamsSetting.fromInfo(listed[WFP.current].toJsonMapOrEmpty()).params.windows.lifetime.end shouldBe now + 43.days
        // A survey has no parameters.
        chief.getItem(UADEP.clientWorkflows)[CWF.workflows].toJsonListOfMaps()
            .single { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] == "details" }.containsKey(CWF.params) shouldBe false
    }

    "windows are refused on a workflow of another kind, when they do not nest, and when nothing changes" {
        val client = "wfwinno1121"
        defineClient(client)
        val chief = admin(client)
        val windows = WfWindows(lifetime = WfWindow(end = now + 5.days))
        chief.expectError(EXC.badInput, UADEP.clientWorkflowParams, windowsRequest("details", windows)).toString() shouldContain
            "has no time windows"
        val inverted = WfWindows(lifetime = WfWindow(now + 5.days, now + 1.days))
        chief.expectError(EXC.badInput, UADEP.clientWorkflowParams, windowsRequest("review", inverted)).toString() shouldContain
            "not before it ends"
        val outside = WfWindows(lifetime = WfWindow(end = now + 5.days), relevancy = WfWindow(end = now + 6.days))
        chief.expectError(EXC.badInput, UADEP.clientWorkflowParams, windowsRequest("review", outside)).toString() shouldContain
            "after its lifetime window does"
        chief.expectError(EXC.badInput, UADEP.clientWorkflowParams, windowsRequest("review", WfWindows.none)).toString() shouldContain
            "nothing to save"
        chief.expectError(EXC.notFound, UADEP.clientWorkflowParams, windowsRequest("nosuch", windows))
        // Nor may a definition carry them: windows written into one are refused at the write.
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 9000L }
        val written = runCatching {
            val bad = gedraConfig(cxt, "windowed", clientNamespace(client), client) {
                workflowFromMap(
                    mapOf(
                        WFD.workflowId to "windowed", WFD.entry to WfEntry.normal.name, WFD.lifetime to mapOf(WFD.end to now.toString()),
                        WFD.tasks to listOf(mapOf(WFD.id to "t", WFD.label to "T", WFD.traits to listOf(mapOf(WFD.traitId to GT.name)))),
                    ),
                )
            }
            GedraConfigService.get(cxt).writeConfig(setup, bad, trial = true)
        }
        written.isFailure shouldBe true
    }

    "a change that would freeze or hide engaged forms now is refused with the impact report until acknowledged" {
        val client = "wfwinimp1121"
        defineClient(client)
        val chief = admin(client)
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val gid = user.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any?>()))[GDF.gedraId].toOptStr()!!
        user.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "review"))

        fun findingOf(windows: WfWindows): Map<String, Any?> {
            val refused = chief.expectError(EXC.badInput, UADEP.clientWorkflowParams, windowsRequest("review", windows))
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
        chief.postData(UADEP.clientWorkflowParams, windowsRequest("review", WfWindows(lifetime = WfWindow(end = now + 90.days))))
        // And acknowledged, the harmful one goes ahead, at once.
        chief.postData(UADEP.clientWorkflowParams, windowsRequest("review", WfWindows(lifetime = WfWindow(end = now - 1.days)), acknowledge = true))
        val def = WorkflowService.get(cxt).forClient(client).workflow("review").shouldNotBeNull().def
        WorkflowPhases.of(cxt, client, def).exists shouldBe false
    }

    "a sandbox reads its client's windows until it has its own, and neither reaches the other" {
        val client = "wfwinsbx1356"
        defineClient(client, sandbox = true)
        val sandbox = sandboxOf(client)
        val full = TestUser.createFullAdmin(cxt, "full@$client.test")
        val params = WorkflowParamsService.get(cxt)
        val live = WfWindows(lifetime = WfWindow(end = now + 20.days))
        full.postData(UADEP.clientWorkflowParams, windowsRequest("review", live) + (CLD.client to client))
        // The sandbox has none of its own, so it runs its client's, and its page says so.
        windowsIn(sandbox, "review") shouldBe live
        val shown = full.getItem(UADEP.clientWorkflows, mapOf(CLD.client to sandbox))[CWF.workflows].toJsonListOfMaps()
            .single { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] == "review" }[CWF.params].toJsonMapOrEmpty()
        shown[WFP.fromParent] shouldBe true
        // Its own, once saved, are its alone; the client's people keep theirs.
        val tried = WfWindows(lifetime = WfWindow(end = now + 5.days))
        full.postData(UADEP.clientWorkflowParams, windowsRequest("review", tried) + (CLD.client to sandbox))
        windowsIn(sandbox, "review") shouldBe tried
        windowsIn(client, "review") shouldBe live
        params.resolve(cxt, sandbox, "review").fromParent shouldBe false
        // And a later change to the client's does not reach the sandbox: nothing copies between them.
        full.postData(UADEP.clientWorkflowParams, windowsRequest("review", WfWindows(lifetime = WfWindow(end = now + 60.days))) + (CLD.client to client))
        windowsIn(sandbox, "review") shouldBe tried
    }

    "who set the windows is named only to a caller who may administer them" {
        val client = "wfwinwho1356"
        defineClient(client)
        val chief = admin(client)
        val full = TestUser.createFullAdmin(cxt, "full@$client.test")
        full.postData(UADEP.clientWorkflowParams, windowsRequest("review", WfWindows(lifetime = WfWindow(end = now + 20.days))) + (CLD.client to client))
        fun currentAs(user: TestUser): WfParamsSetting = WfParamsSetting.fromInfo(
            user.getItem(UADEP.clientWorkflows, mapOf(CLD.client to client))[CWF.workflows].toJsonListOfMaps()
                .single { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] == "review" }[CWF.params].toJsonMapOrEmpty()[WFP.current].toJsonMapOrEmpty(),
        )
        // The operator, in the hub, reaches every client's users, so they see themselves named.
        currentAs(full).setByName.shouldNotBeNull()
        // The client's own administrator does not reach the hub: the setting says when, and not who.
        val shown = currentAs(chief)
        shown.setAt.shouldNotBeNull()
        shown.setBy shouldBe null
        shown.setByName shouldBe null
        // Their own change is named to them.
        chief.postData(UADEP.clientWorkflowParams, windowsRequest("review", WfWindows(lifetime = WfWindow(end = now + 25.days))))
        currentAs(chief).setByName.shouldNotBeNull()
    }
})
