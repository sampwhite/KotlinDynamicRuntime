package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.ImpactKind
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.TraitLocks
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfParamsSetting
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WfTaskSlot
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
import com.dynamicruntime.common.gedra.workflow.WfTraitRef
import com.dynamicruntime.common.gedra.workflow.WfVisibility
import com.dynamicruntime.common.gedra.workflow.WfVisibilityMode
import com.dynamicruntime.common.gedra.workflow.WfWindow
import com.dynamicruntime.common.gedra.workflow.WfWindows
import com.dynamicruntime.common.gedra.workflow.WorkflowParamsService
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Duration.Companion.days

/**
 * Who sees a workflow (issue #1384): a parameter beside its time windows, on a workflow of any kind. Restricted, it is
 * seen by the client's administrators and by people holding one of its labels, and by nobody else -- not in the view,
 * a save, an engagement, a form's states, or the workflow pages -- while its progress is kept. A rollout is a sequence
 * of ordinary edits, each in the history. Its locks on a form's data are released for whoever does not see it. Restricting a workflow engaged forms' owners would lose is refused with the
 * impact report. A workflow made from a template starts restricted, and on a client with a sandbox the client's own
 * stays administrators only whatever the sandbox chose. A client per case, on the shared default instance.
 */
class WorkflowVisibilityTest : StringSpec({
    val cxt = TestInstances.default("workflowVisibility1384")

    fun defineClient(client: String, sandbox: Boolean = false) {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 9000L }
        val svc = GedraConfigService.get(cxt)
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = listOf(GT.name),
                        userLabels = listOf("pilot", "east"), sandbox = sandbox,
                    ),
                )
                // One of each kind, written whole: visibility is a parameter of every kind, windows of a normal one only.
                workflow("make", WfEntry.creation) { task("name", "Name it") { trait(GT.name); save("go", "Go", WfSaveKind.create) } }
                workflow("details", WfEntry.survey) { task("fill", "Fill it in") { trait(GT.name); save("go", "Save", WfSaveKind.edit) } }
                workflow("review", WfEntry.normal) { task("record", "Record it") { trait(GT.name); save("go", "Save", WfSaveKind.edit) } }
            },
        )
        svc.publish(setup, listOf(GedraId.of(GedraConfigType.configDoc, client, "main")))
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun admin(client: String): TestUser = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

    fun labeled(chief: TestUser, user: TestUser, vararg labels: String) {
        chief.postData(UADEP.userSetLabels, mapOf(ADF.userId to user.userId, ADF.labels to labels.toList()))
    }

    fun visibilityRequest(workflowId: String, visibility: WfVisibility?, acknowledge: Boolean = false) = buildMap {
        put(WFD.workflowId, workflowId)
        if (visibility == null) put(WFP.unset, listOf(WFP.visibility)) else put(WFP.params, mapOf(WFP.visibility to visibility.toInfo()))
        if (acknowledge) put(IMP.acknowledgeImpact, true)
    }

    fun restrictedTo(vararg labels: String) = WfVisibility(WfVisibilityMode.restricted, labels.toList())

    /** Whether [user] can open [workflowId] -- or, with none, their client's creation workflow. */
    fun opens(user: TestUser, workflowId: String?): Boolean = if (workflowId == null) {
        user.getData(GEP.workflowView)[WVF.found] != false
    } else {
        runCatching { user.getData(GEP.workflowView, mapOf(GDF.workflowId to workflowId)) }.isSuccess
    }

    fun aggregated(user: TestUser): List<String?> = user.getItems(GEP.workflowAggregate).map { it[WFD.workflowId].toOptStr() }

    fun engagedShown(user: TestUser, gid: String): Boolean =
        user.getData(GEP.formDocStates, mapOf(GDF.gedraId to gid))[GDF.states].toJsonListOfMaps().any {
            it[GE.traitId] == WFS.workflowEngagement && it[GE.data].toJsonMapOrEmpty()[WFD.workflowId] == "review"
        }

    "nobody restricting a workflow, everybody sees it; restricted, its administrators and the people holding a label do" {
        val client = "wfvis1384"
        defineClient(client)
        val chief = admin(client)
        val plain = TestUser.create(cxt, "plain@$client.test", userClient = client)
        val pilot = TestUser.create(cxt, "pilot@$client.test", userClient = client)
        labeled(chief, pilot, "pilot")
        // Written in source, with no row: everybody sees every one, as before.
        opens(plain, "review") shouldBe true
        opens(plain, null) shouldBe true

        // Administrators only: the plain user and the pilot meet it nowhere, and the administrator everywhere.
        chief.postData(UADEP.clientWorkflowParams, visibilityRequest("review", WfVisibility.adminsOnly))
        opens(plain, "review") shouldBe false
        opens(pilot, "review") shouldBe false
        opens(chief, "review") shouldBe true
        aggregated(plain) shouldNotContain "review"
        aggregated(chief) shouldContain "review"
        val gid = plain.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any?>()))[GDF.gedraId].toOptStr()!!
        plain.expectError(EXC.notFound, GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "review"))

        // A label: the pilot sees it now, and the plain user still does not.
        chief.postData(UADEP.clientWorkflowParams, visibilityRequest("review", restrictedTo("pilot")))
        opens(pilot, "review") shouldBe true
        opens(plain, "review") shouldBe false

        // Any kind: a restricted creation workflow is no creation workflow to the plain user, who gets the trait picker.
        chief.postData(UADEP.clientWorkflowParams, visibilityRequest("make", restrictedTo("pilot")))
        opens(plain, null) shouldBe false
        opens(pilot, null) shouldBe true
        // A survey takes a visibility, but still no windows.
        chief.postData(UADEP.clientWorkflowParams, visibilityRequest("details", WfVisibility.adminsOnly))
        chief.expectError(
            EXC.badInput, UADEP.clientWorkflowParams,
            mapOf(WFD.workflowId to "details", WFP.params to mapOf(WFP.windows to WfWindows(WfWindow(end = cxt.instanceNow() + 5.days)).toInfo())),
        ).toString() shouldContain "has no time windows"

        // Everyone: the plain user sees it again.
        chief.postData(UADEP.clientWorkflowParams, visibilityRequest("review", WfVisibility.everyone))
        opens(plain, "review") shouldBe true
    }

    "a rollout is a sequence of ordinary edits, each in the history, and the labels outlast opening it to everyone" {
        val client = "wfvisroll1384"
        defineClient(client)
        val chief = admin(client)
        val steps = listOf(WfVisibility.adminsOnly, restrictedTo("pilot"), restrictedTo("pilot", "east"))
        steps.forEach { chief.postData(UADEP.clientWorkflowParams, visibilityRequest("review", it)) }
        // Opening it up keeps the group, so restricting it again brings the same people back.
        chief.postData(UADEP.clientWorkflowParams, visibilityRequest("review", restrictedTo("pilot", "east").withMode(WfVisibilityMode.everyone)))
        val params = WorkflowParamsService.get(cxt).resolve(cxt, client, "review")
        params.visibility.restricted shouldBe false
        params.visibility.labels shouldBe listOf("pilot", "east")
        params.history.map { it.params.visibility } shouldBe steps.reversed()
        // The page reads the same, with who made each choice.
        val listed = chief.getItem(UADEP.clientWorkflows)[CWF.workflows].toJsonListOfMaps()
            .single { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] == "review" }[CWF.params].toJsonMapOrEmpty()
        WfParamsSetting.listFrom(listed[WFP.history]) shouldHaveSize 3
        chief.getItem(UADEP.clientWorkflows)[CWF.userLabels].toJsonListOfStrings() shouldBe listOf("pilot", "east")
        // Setting the windows leaves the visibility as it is: each parameter is its own.
        chief.postData(UADEP.clientWorkflowParams, mapOf(WFD.workflowId to "review", WFP.params to mapOf(WFP.windows to WfWindows.none.toInfo())))
        WorkflowParamsService.get(cxt).resolve(cxt, client, "review").visibility.labels shouldBe listOf("pilot", "east")
    }

    "restricting a workflow out of engaged forms' owners' sight is refused with the impact report; acknowledged, their progress is kept" {
        val client = "wfvisimp1384"
        defineClient(client)
        val chief = admin(client)
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val gid = user.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any?>()))[GDF.gedraId].toOptStr()!!
        user.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "review"))

        val refused = chief.expectError(EXC.badInput, UADEP.clientWorkflowParams, visibilityRequest("review", restrictedTo("pilot")))
        refused[EP.errorCode] shouldBe IMP.refusedCode
        val finding = refused[EP.extraData].toJsonMapOrEmpty()[IMP.report].toJsonMapOrEmpty()[IMP.findings].toJsonListOfMaps().single()
        finding[IMP.kind] shouldBe ImpactKind.workflowUnseen.name
        finding[IMP.sampleIds].toJsonListOfStrings() shouldContain gid
        // The owner holding the label loses nothing, so the same change is no harm once they hold it.
        labeled(chief, user, "pilot")
        chief.postData(UADEP.clientWorkflowParams, visibilityRequest("review", restrictedTo("pilot")))
        // Without the label it would be harm again; acknowledged, it goes ahead.
        labeled(chief, user)
        chief.postData(UADEP.clientWorkflowParams, visibilityRequest("review", WfVisibility.adminsOnly, acknowledge = true))
        // The owner no longer sees their form's part in it, nor can act on it; the administrator still sees it.
        engagedShown(user, gid) shouldBe false
        engagedShown(chief, gid) shouldBe true
        // Nor does asking for retired workflows reveal it: it is not retired, only not theirs to see.
        val withRetired = user.getData(GEP.formDocStates, mapOf(GDF.gedraId to gid, GDF.includeRetired to true))
        withRetired[GDF.states].toJsonListOfMaps().none { it[GE.data].toJsonMapOrEmpty()[WFD.workflowId] == "review" } shouldBe true
        withRetired[GDF.retiredWorkflows].toJsonListOfStrings() shouldNotContain "review"
        user.expectError(EXC.notFound, GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "review", WFS.engaged to false))
        // Opened up again, it is all there.
        chief.postData(UADEP.clientWorkflowParams, visibilityRequest("review", WfVisibility.everyone))
        engagedShown(user, gid) shouldBe true
    }

    "a workflow's locks on a form's data are released for whoever does not see it, as a deleted workflow's are" {
        val client = "wfvislock1384"
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 9000L }
        GedraConfigService.get(cxt).writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), userLabels = listOf("pilot"),
                    ),
                )
                trait("NoteEntry", "note", setOf(GedraDataType.formDoc), "A note.") {
                    property("text", "What it says.") { type = SCT.string }
                }
                // Written once: the note is locked for anybody once the draft is complete.
                workflow("notes", WfEntry.normal) {
                    task("draft", "Draft it") {
                        trait("note")
                        save("saveDraft", "Save", WfSaveKind.edit)
                        saveWhen("~${WFC.taskComplete}")
                    }
                    lock("note", writableVia = "draft")
                }
            },
        )
        GedraConfigReload.reloadClient(cxt, client)
        val chief = admin(client)
        val user = TestUser.create(cxt, "u@$client.test", userClient = client)
        val gid = user.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any?>()))[GDF.gedraId].toOptStr()!!
        user.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "notes"))
        user.postData(
            GEP.workflowSave,
            mapOf(
                WFD.workflowId to "notes", GDF.taskId to "draft", GDF.saveId to "saveDraft", GDF.gedraId to gid,
                GDF.entries to listOf(mapOf(GE.traitId to "note", GE.data to mapOf("text" to "first"))),
            ),
        )
        fun lockedFor(who: TestUser): List<Any?> =
            who.getData(GEP.formDocLocks, mapOf(GDF.gedraId to gid))[WVF.lockedTraits].toJsonListOfMaps().map { it[WFD.traitId] }
        lockedFor(user) shouldBe listOf("note")

        // Restricted away from the owner: to them the workflow is as good as deleted, so its lock no longer holds.
        chief.postData(UADEP.clientWorkflowParams, visibilityRequest("notes", restrictedTo("pilot"), acknowledge = true))
        lockedFor(user) shouldBe emptyList()
        // Whoever still sees it -- the administrator, who may not write the completed draft either -- is held as before.
        lockedFor(chief) shouldBe listOf("note")
        // And a writer that is no person -- a system context, an import or a job -- answers to every workflow's locks.
        val system = cxt.mkSubContext("system", client)
        val data = GedraDataService.get(cxt)
        val scope = ReadScope.ofClient(client)
        val row = data.readGedras(system, GedraDataType.formDoc, listOf(gid), scope).getValue(gid)
        val states = data.readState(system, row.gedraId, scope)
        TraitLocks.heldFor(system, client, row.entries, states).map { it.lock.traitId } shouldBe listOf("note")
    }

    "a workflow made from a template is restricted from the start, as its maker chose" {
        val client = "wfvisnew1384"
        defineClient(client)
        val chief = admin(client)
        val plain = TestUser.create(cxt, "plain@$client.test", userClient = client)
        val params = WorkflowParamsService.get(cxt)
        fun slots(label: String) = WfTemplateSlots(null, WFT.collect, label, listOf(WfTaskSlot("collect", "Info", listOf(WfTraitRef(GT.name)))))
        // Choosing none is administrators only.
        chief.postData(UADEP.clientWorkflowSave, mapOf(CWF.slots to slots("First").toInfo()))
        params.resolve(cxt, client, "first").visibility shouldBe WfVisibility.adminsOnly
        opens(plain, "first") shouldBe false
        // A choice is what it starts as.
        chief.postData(UADEP.clientWorkflowSave, mapOf(CWF.slots to slots("Second").toInfo(), CWF.visibility to restrictedTo("pilot").toInfo()))
        params.resolve(cxt, client, "second").visibility shouldBe restrictedTo("pilot")
        // A change of slots takes none: who sees a workflow is its parameters' to change.
        val edit = WfTemplateSlots("second", WFT.collect, "Second, renamed", slots("x").tasks)
        chief.expectError(EXC.badInput, UADEP.clientWorkflowSave, mapOf(CWF.slots to edit.toInfo(), CWF.visibility to WfVisibility.everyone.toInfo()))
    }

    "on a client with a sandbox, the sandbox sees a new workflow as chosen, and the client's people only once it is opened there" {
        val client = "wfvissbx1384"
        defineClient(client, sandbox = true)
        val sandbox = sandboxOf(client)
        val full = TestUser.createFullAdmin(cxt, "full@$client.test")
        val params = WorkflowParamsService.get(cxt)
        val slots = WfTemplateSlots(null, WFT.collect, "Pilot", listOf(WfTaskSlot("collect", "Info", listOf(WfTraitRef(GT.name)))))
        val saved = full.postData(
            UADEP.clientWorkflowSave,
            mapOf(CLD.client to client, CWF.slots to slots.toInfo(), CWF.visibility to WfVisibility.everyone.toInfo(), CPY.mode to EDM.draft),
        )
        saved[CPY.mode] shouldBe EDM.draft
        // Two rows: the sandbox's as chosen, the client's administrators only.
        params.resolve(cxt, sandbox, "pilot").visibility shouldBe WfVisibility.everyone
        params.resolve(cxt, sandbox, "pilot").layers.from.containsKey(WFP.visibility) shouldBe false
        params.resolve(cxt, client, "pilot").visibility shouldBe WfVisibility.adminsOnly
        // Published, it arrives restricted: a publish never makes a workflow visible.
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 9000L }
        GedraConfigService.get(cxt).publish(setup, listOf(GedraId.of(GedraConfigType.configDoc, client, "${CWF.configPrefix}pilot")))
        GedraConfigReload.reloadClient(cxt, client)
        val plain = TestUser.create(cxt, "plain@$client.test", userClient = client)
        opens(plain, "pilot") shouldBe false
        // Opened on the client, its people see it.
        full.postData(UADEP.clientWorkflowParams, visibilityRequest("pilot", WfVisibility.everyone) + (CLD.client to client))
        opens(plain, "pilot") shouldBe true
    }
})
