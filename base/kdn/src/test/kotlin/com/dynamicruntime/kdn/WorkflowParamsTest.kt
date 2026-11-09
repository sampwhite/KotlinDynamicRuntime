package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfig
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
import com.dynamicruntime.common.gedra.workflow.WfParamsBuilder
import com.dynamicruntime.common.gedra.workflow.WfParamsFrom
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
import com.dynamicruntime.common.sql.cache.SqlTableCacheService
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A workflow's parameters -- its time windows, whether it is on -- as a client and its sandbox run them (issue #1739
 * gathered these): set from the client page (issues #1121, #1356), defaulted by source code (issue #1386), and carried to
 * other nodes. Each part below is one of the specs that used to be a file of its own, kept whole. The clock walk through
 * a workflow's phases stays in `WorkflowWindowsTest`, on an instance of its own.
 */
class WorkflowParamsTest : StringSpec({
    include(clientWorkflowWindowsTests)
    include(workflowParamsSourceTests)
    include(workflowParamsSyncTests)
})

/**
 * A normal workflow's time windows set from the client page as its **parameters** (issues #1121, #1356): kept per
 * client in a table of their own, in force at once whoever declared the workflow, never part of its definition, with
 * the three earlier settings kept; the refusals; the impact finding for a change that would, now, freeze or hide forms
 * engaged with the workflow; and a sandbox reading its parent's until it has its own. A client per case, on the shared
 * default instance.
 */
private val clientWorkflowWindowsTests = stringSpec {
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
                // A normal workflow written whole, beside a core details workflow, which has no windows.
                workflow("review", WfEntry.normal) { task("record", "Record it") { trait(GT.name); save("go", "Save", WfSaveKind.edit) } }
                workflow("details", WfEntry.coreDetails) { task("fill", "Fill it in") { trait(GT.name); save("go", "Save",
                    WfSaveKind.edit) } }
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
        history.map { it.params.windowsInForce.lifetime.end } shouldBe listOf(now + 42.days, now + 41.days, now + 30.days)
        history.first().setByName.shouldNotBeNull()
        WfParamsSetting.fromInfo(listed[WFP.current].toJsonMapOrEmpty()).params.windowsInForce.lifetime.end shouldBe now + 43.days
        // A core details workflow has parameters too -- who sees it, issue #1384 -- but never windows.
        chief.getItem(UADEP.clientWorkflows)[CWF.workflows].toJsonListOfMaps()
            .single { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] == "details" }[CWF.params].toJsonMapOrEmpty()[WFP.inForce]
            .toJsonMapOrEmpty().containsKey(WFP.windows) shouldBe false
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
        chief.postData(UADEP.clientWorkflowParams, windowsRequest("review", windows))
        chief.expectError(EXC.badInput, UADEP.clientWorkflowParams, windowsRequest("review", windows)).toString() shouldContain
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
        shown[WFP.from].toJsonMapOrEmpty()[WFP.windows] shouldBe WfParamsFrom.parent.name
        // Its own, once saved, are its alone; the client's people keep theirs.
        val tried = WfWindows(lifetime = WfWindow(end = now + 5.days))
        full.postData(UADEP.clientWorkflowParams, windowsRequest("review", tried) + (CLD.client to sandbox))
        windowsIn(sandbox, "review") shouldBe tried
        windowsIn(client, "review") shouldBe live
        params.resolve(cxt, sandbox, "review").layers.from.isEmpty() shouldBe true
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
}

/**
 * A workflow's parameters from source code (issue #1386): a client's source configuration declares defaults, which
 * the client runs until it sets its own, and a sandbox until it or its client does -- **each parameter on its own**, so
 * a scope follows the fallback for what it does not set, and unsetting returns to it. Only source code declares them.
 */
private val workflowParamsSourceTests = stringSpec {
    // Its own instance (issue #1075): the client is declared by a component, in source.
    val cxt = Startup.mkTestBootCxt("wfParamsSource", "wfParamsSource1386", additionalComponents = listOf(SourceParamsComponent()))
    val client = SourceParamsComponent.client
    val sandbox = sandboxOf(client)
    val params = WorkflowParamsService.get(cxt)
    val defaults = WfWindows(
        lifetime = WfWindow(SourceParamsComponent.defaultStart.parseDate(), SourceParamsComponent.defaultEnd.parseDate()),
    )
    val now = Instant.fromEpochSeconds(cxt.instanceNow().epochSeconds)
    val full = TestUser.createFullAdmin(cxt, "full@$client.test")

    fun request(scope: String, windows: WfWindows?) = buildMap {
        put(CLD.client, scope)
        put(WFD.workflowId, "review")
        if (windows == null) put(WFP.unset, listOf(WFP.windows)) else put(WFP.params, WfParams(windows).toInfo())
    }

    fun shown(scope: String): Map<String, Any?> = full.getItem(UADEP.clientWorkflows, mapOf(CLD.client to scope))[CWF.workflows]
        .toJsonListOfMaps().single { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] == "review" }[CWF.params].toJsonMapOrEmpty()

    fun fromOf(scope: String): String? = shown(scope)[WFP.from].toJsonMapOrEmpty()[WFP.windows] as String?

    "with nothing set, the client and its sandbox run the source code's defaults" {
        params.windowsFor(cxt, client, "review") shouldBe defaults
        params.windowsFor(cxt, sandbox, "review") shouldBe defaults
        fromOf(client) shouldBe WfParamsFrom.source.name
        fromOf(sandbox) shouldBe WfParamsFrom.source.name
        WfParams.fromInfo(shown(client)[WFP.source]).windows shouldBe defaults
    }

    "each scope's own windows win, unsetting them returns to the fallback, and the history keeps every step" {
        val clientOwn = WfWindows(lifetime = WfWindow(end = now + 40.days))
        val sandboxOwn = WfWindows(lifetime = WfWindow(end = now + 10.days))
        // The client sets its own: the sandbox now follows the client's, not the source code's.
        full.postData(UADEP.clientWorkflowParams, request(client, clientOwn))
        params.windowsFor(cxt, client, "review") shouldBe clientOwn
        params.windowsFor(cxt, sandbox, "review") shouldBe clientOwn
        fromOf(client) shouldBe null
        fromOf(sandbox) shouldBe WfParamsFrom.parent.name
        // The sandbox sets its own, then unsets them: back to its client's.
        full.postData(UADEP.clientWorkflowParams, request(sandbox, sandboxOwn))
        params.windowsFor(cxt, sandbox, "review") shouldBe sandboxOwn
        full.postData(UADEP.clientWorkflowParams, request(sandbox, null))
        params.windowsFor(cxt, sandbox, "review") shouldBe clientOwn
        // Its own row stays, with what it set kept in the history.
        shown(sandbox)[WFP.history].toJsonListOfMaps() shouldHaveSize 1
        // No limits, set deliberately, is the client's own and hides the source code's defaults.
        full.postData(UADEP.clientWorkflowParams, request(client, WfWindows.none))
        params.windowsFor(cxt, client, "review") shouldBe WfWindows.none
        // Unset, the client runs the source code's defaults again, and so does the sandbox.
        full.postData(UADEP.clientWorkflowParams, request(client, null))
        params.windowsFor(cxt, client, "review") shouldBe defaults
        params.windowsFor(cxt, sandbox, "review") shouldBe defaults
        shown(client)[WFP.history].toJsonListOfMaps() shouldHaveSize 2
        // Unsetting what is not set is nothing to save.
        full.expectError(EXC.badInput, UADEP.clientWorkflowParams, request(client, null)).toString() shouldContain "nothing to save"
    }

    // Issue #1635: the switch resolves as every parameter does, so code ships a workflow off, and a client turns off one
    // its code declares, with no override of the code.
    "code may ship a workflow turned off, and a client turns its code's workflows on and off" {
        fun exists(scope: String, workflowId: String): Boolean = WorkflowPhases.of(
            cxt, scope, WorkflowService.get(cxt).forClient(scope).workflow(workflowId).shouldNotBeNull().def,
        ).exists
        fun switch(scope: String, workflowId: String, on: Boolean?) = full.postData(
            UADEP.clientWorkflowParams,
            buildMap {
                put(CLD.client, scope)
                put(WFD.workflowId, workflowId)
                if (on == null) put(WFP.unset, listOf(WFP.enabled)) else put(WFP.params, mapOf(WFP.enabled to on))
            },
        )
        fun enabledFrom(scope: String, workflowId: String): Any? =
            full.getItem(UADEP.clientWorkflows, mapOf(CLD.client to scope))[CWF.workflows].toJsonListOfMaps()
                .single { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] == workflowId }[CWF.params].toJsonMapOrEmpty()[WFP.from]
                .toJsonMapOrEmpty()[WFP.enabled]
        // Off as shipped, in the client and its sandbox, and the page says it is the code's default.
        exists(client, "pilot") shouldBe false
        exists(sandbox, "pilot") shouldBe false
        enabledFrom(client, "pilot") shouldBe WfParamsFrom.source.name
        // The client turns it on; unset, it is off again, as the code ships it.
        switch(client, "pilot", true)
        exists(client, "pilot") shouldBe true
        exists(sandbox, "pilot") shouldBe true
        switch(client, "pilot", null)
        exists(client, "pilot") shouldBe false
        // A workflow its code declares on, the client turns off with no override of the code, and back.
        switch(client, "review", false)
        exists(client, "review") shouldBe false
        switch(client, "review", null)
        exists(client, "review") shouldBe true
        // The switch alone is a default, without windows.
        WfParamsBuilder("pilot").apply { enabled(false) }.build().windows shouldBe null
    }

    "only source code declares defaults" {
        // A stored configuration cannot hold them: what the database holds is a scope's own values.
        val written = shouldThrow<KdrException> {
            GedraConfigService.get(cxt).writeConfig(
                cxt.mkSubContext("setup", "storedparams1386").also { it.userId = 13860L },
                gedraConfig(cxt, "main", clientNamespace("storedparams1386"), "storedparams1386") {
                    workflow("review", WfEntry.normal) { task("t", "T") { trait(GT.name); save("go", "Save", WfSaveKind.edit) } }
                    workflowParams("review") { lifetime(end = "2099-01-01T00:00:00Z") }
                },
            )
        }
        written.message.orEmpty() shouldContain "only code declares"
        // Nor a global one, since a template has no windows, nor a sandbox's overlay.
        shouldThrow<KdrException> {
            gedraConfig(cxt, "templates", "kdr.templates") { workflowParams("review") { lifetime(end = "2099-01-01T00:00:00Z") } }
        }.message.orEmpty() shouldContain "global configuration"
        shouldThrow<KdrException> {
            gedraConfig(cxt, "overlay", clientNamespace(client), sandbox) { workflowParams("review") { lifetime(end = "2099-01-01T00:00:00Z") } }
        }.message.orEmpty() shouldContain "a sandbox follows its client's"
        // And a default naming no workflow of the client's source refuses the boot, as a mistake in source does.
        shouldThrow<KdrException> {
            // Its own instance (issue #1075): the boot itself is under test.
            Startup.mkTestBootCxt("badParams", "badSourceParams1386", additionalComponents = listOf(BadSourceParamsComponent()))
        }.message.orEmpty() shouldContain "name no workflow"
    }
}

/**
 * A workflow's parameters reach every node (issue #1356): saved on one, they are in force on another as soon as it
 * next reads the shared cache state, with no reload and no publish. The single-node behavior is
 * `ClientWorkflowWindowsTest`'s.
 */
private val workflowParamsSyncTests = stringSpec {
    // Its own instances (issue #1075): two nodes over one database of their own (`KDR_DB_NAME`), and node B's clock
    // is moved past the state-read throttle.
    val db = mapOf("KDR_DB_NAME" to "wfParamsSyncDb")
    val nodeA = Startup.mkTestBootCxt("wfParamsSyncA", "wfParamsSyncNodeA", db)
    val nodeB = Startup.mkTestBootCxt("wfParamsSyncB", "wfParamsSyncNodeB", db)

    "windows saved on one node are in force on another at its next read" {
        val client = "wfparamsync1356"
        // A fresh context per read: a context refreshes the caches once, and remembers it did.
        fun windowsOn(node: KdrCxt): WfWindows =
            WorkflowParamsService.get(node).windowsFor(node.mkSubContext("read", client), client, "review")

        // Node B has read the table, so what follows is news to a loaded cache, not its first load.
        windowsOn(nodeB) shouldBe WfWindows.none
        val windows = WfWindows(lifetime = WfWindow(end = Instant.fromEpochSeconds(nodeA.instanceNow().epochSeconds) + 30.days))
        // Outside a request, the write is announced to the other nodes by monitoring it, as a request's end would.
        SqlTableCacheService.get(nodeA).withMonitoring(nodeA) {
            WorkflowParamsService.get(nodeA).save(nodeA.mkSubContext("set", client).also { it.userId = 13560L }, client, "review", WfParams(windows))
        }
        windowsOn(nodeA) shouldBe windows

        nodeB.instanceConfig.clock.advanceBy(1.seconds)
        windowsOn(nodeB) shouldBe windows
    }
}

// Declared with what was `WorkflowParamsSourceTest`.
/** A client declared in source code with default parameters for its normal workflow (issue #1386), and a sandbox. */
class SourceParamsComponent : ComponentDefinition {
    override val providerName: String = "sourceParamsFixture"

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = "Source params", usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = listOf(GT.name), sandbox = true,
                ),
            )
            workflow("review", WfEntry.normal) { task("record", "Record it") { trait(GT.name); save("go", "Save", WfSaveKind.edit) } }
            workflowParams("review") { lifetime(start = defaultStart, end = defaultEnd) }
            // Shipped turned off (issue #1635): the client turns it on when it wants it.
            workflow("pilot", WfEntry.normal) { task("note", "Note it") { trait(GT.name); save("go", "Save", WfSaveKind.edit) } }
            workflowParams("pilot") { enabled(false) }
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        const val client = "srcparams1386"
        const val defaultStart = "2026-01-01T00:00:00Z"
        const val defaultEnd = "2099-01-01T00:00:00Z"
    }
}

/** A source default naming no workflow of its client: a mistake in the code, which refuses the boot. */
class BadSourceParamsComponent : ComponentDefinition {
    override val providerName: String = "badSourceParamsFixture"

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "main", clientNamespace("badparams1386"), "badparams1386") {
            defineClient(
                ClientDef(
                    clientId = "badparams1386", name = "Bad params", usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            workflowParams("nosuch") { lifetime(end = "2099-01-01T00:00:00Z") }
        },
    )
}
