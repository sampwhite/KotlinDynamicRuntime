package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.TraitDesign
import com.dynamicruntime.common.gedra.TraitFieldDesign
import com.dynamicruntime.common.gedra.TraitFieldKind
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.report.RDEF
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.report.ReportService
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WfTaskSlot
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
import com.dynamicruntime.common.gedra.workflow.WfTraitRef
import com.dynamicruntime.common.gedra.workflow.WfVisibility
import com.dynamicruntime.common.gedra.workflow.WorkflowParamsService
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The dry runs of the Clients page's editors the workflow assistant uses (issue #1766): the trait designer, a workflow
 * made from a template and its removal, a workflow's parameters, and report making. Each dry run is judged as its save
 * would be and answers `dryRun: true`, and stores nothing -- no configuration, no trait, no workflow, no parameter, no
 * report. (Narrowing a global trait is `ClientTraitNarrowTest`'s, over its instance with a global trait to narrow.) A
 * client per case, without a sandbox, so each save would be live: a dry run of a live save is held to its publish checks
 * too (`PublishRefusalUndoTest`).
 */
class EditorDryRunTest : StringSpec({
    val cxt = TestInstances.default("editorDryRun1766")
    val svc = GedraConfigService.get(cxt)

    fun defineClient(client: String): TestUser {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 17660L }
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = listOf(GT.name),
                    ),
                )
            },
        )
        svc.publish(setup, listOf(GedraId.of(GedraConfigType.configDoc, client, "main")))
        GedraConfigReload.reloadClient(cxt, client)
        return TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
    }

    fun configs(client: String): List<String> = svc.listConfigs(cxt.mkSubContext("check", client)).map { it.configId.baseId }

    fun collect(label: String, workflowId: String? = null) =
        WfTemplateSlots(workflowId, WFT.collect, label, listOf(WfTaskSlot("collect", "Info", listOf(WfTraitRef(GT.name)))))

    "a new trait's dry run makes no configuration and no trait" {
        val client = "drytrait1766"
        val chief = defineClient(client)
        val design = TraitDesign(null, "Safety", "A safety check.", listOf(TraitFieldDesign(null, "Notes", TraitFieldKind.text)))
        chief.postData(UADEP.clientTraitDesign, mapOf(CIT.design to design.toInfo(), CFEP.dryRun to true))[CFEP.dryRun] shouldBe true
        configs(client) shouldBe listOf("main")
        SchemaService.get(cxt).gedraTraitsFor(client).map { it.traitId } shouldNotContain "safety"
    }

    "a template workflow's dry run makes no workflow and sets no one's view of it; its removal's dry run keeps it" {
        val client = "drywf1766"
        val chief = defineClient(client)
        val dry = chief.postData(
            UADEP.clientWorkflowSave,
            mapOf(CWF.slots to collect("Tried").toInfo(), CWF.visibility to WfVisibility.everyone.toInfo(), CFEP.dryRun to true),
        )
        dry[CFEP.dryRun] shouldBe true
        dry[CPY.mode] shouldBe EDM.live
        WorkflowService.get(cxt).forClient(client).workflow("tried").shouldBeNull()
        configs(client) shouldBe listOf("main")
        // Nor the visibility a real make writes before the workflow exists.
        WorkflowParamsService.get(cxt).resolve(cxt, client, "tried").own.shouldBeNull()

        chief.postData(UADEP.clientWorkflowSave, mapOf(CWF.slots to collect("Kept").toInfo()))
        WorkflowService.get(cxt).forClient(client).workflow("kept").shouldNotBeNull()
        val removal = mapOf(WFD.workflowId to "kept", CFEP.dryRun to true)
        chief.postData(UADEP.clientWorkflowRemove, removal)[CFEP.dryRun] shouldBe true
        WorkflowService.get(cxt).forClient(client).workflow("kept").shouldNotBeNull()
    }

    "a workflow's parameters dry-run to what they would be, and are not saved" {
        val client = "dryparams1766"
        val chief = defineClient(client)
        chief.postData(UADEP.clientWorkflowSave, mapOf(CWF.slots to collect("Pilot").toInfo()))
        val params = WorkflowParamsService.get(cxt)
        val resolved = params.resolve(cxt, client, "pilot")
        val before = resolved.visibility
        // The make set the client's own visibility, so the dry run shows that setting replaced, at the front of the history.
        resolved.own.shouldNotBeNull()
        val dry = chief.postData(
            UADEP.clientWorkflowParams,
            mapOf(
                WFD.workflowId to "pilot",
                WFP.params to mapOf(WFP.visibility to WfVisibility.everyone.toInfo()),
                CFEP.dryRun to true,
            ),
        )
        dry[CFEP.dryRun] shouldBe true
        val answered = dry[CWF.params].toJsonMapOrEmpty()
        answered.toString().contains("everyone") shouldBe true
        answered[WFP.history].toJsonListOfMaps().size shouldBe resolved.history.size + 1
        params.resolve(cxt, client, "pilot").visibility shouldBe before
    }

    "a report's dry run makes no report and no configuration; its removal's dry run keeps it" {
        val client = "dryreport1766"
        val chief = defineClient(client)
        fun report(label: String) =
            mapOf(RDEF.label to label, RDEF.columns to listOf(mapOf(RDEF.label to "Created", RDEF.path to "meta.createdAt")))
        chief.postData(UADEP.reportCreate, report("Tried") + (CFEP.dryRun to true))[CFEP.dryRun] shouldBe true
        ReportService.get(cxt).forClient(client).report("tried").shouldBeNull()
        configs(client) shouldNotContain "reports"

        chief.postData(UADEP.reportCreate, report("Kept"))
        chief.postData(UADEP.reportRemove, mapOf(RRUN.reportId to "kept", CFEP.dryRun to true))[CFEP.dryRun] shouldBe true
        ReportService.get(cxt).forClient(client).report("kept").shouldNotBeNull()
    }

    "a dry run asks the client named, as the save does" {
        // An administrator who sees every client names one, as a save would; a dry run is no wider.
        val client = "drynamed1766"
        defineClient(client)
        val other = defineClient("drynamedother1766")
        val design = TraitDesign(null, "Safety", null, listOf(TraitFieldDesign(null, "Notes", TraitFieldKind.text)))
        other.expectError(
            EXC.notAuthorized, UADEP.clientTraitDesign,
            mapOf(CLD.client to client, CIT.design to design.toInfo(), CFEP.dryRun to true),
        )
    }
})
