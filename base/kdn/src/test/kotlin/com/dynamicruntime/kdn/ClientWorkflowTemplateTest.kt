package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WfTaskSlot
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
import com.dynamicruntime.common.gedra.workflow.WfTraitRef
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.workflowDefStamp
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Workflows made from templates on the client page (issue #1120): the catalog of core's templates and the client's
 * workflows, making one from a template -- stored as its overlay, in a configuration of its own -- changing its slots,
 * and the refusals. A client per case, on the shared default instance.
 */
class ClientWorkflowTemplateTest : StringSpec({
    val cxt = TestInstances.default("clientWorkflowTemplate1120")

    fun defineClient(client: String, ownCreation: Boolean = false) {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 11200L }
        val svc = GedraConfigService.get(cxt)
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = listOf(GT.name),
                    ),
                )
                // A workflow written whole, the way a client's was before templates.
                if (ownCreation) {
                    workflow("start", WfEntry.creation) { task("name", "Name it") { trait(GT.name); save("go", "Go", WfSaveKind.create) } }
                }
            },
        )
        svc.publish(setup, GedraId.of(GedraConfigType.configDoc, client, "main"))
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun admin(client: String): TestUser = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

    fun catalog(user: TestUser) = user.getItem(UADEP.clientWorkflows)

    fun save(user: TestUser, slots: WfTemplateSlots) = user.postData(UADEP.clientWorkflowSave, mapOf(CWF.slots to slots.toInfo()))

    fun surveySlots(workflowId: String? = null, label: String = "Event survey", taskLabel: String = "Your event") =
        WfTemplateSlots(workflowId, WFT.survey, label, listOf(WfTaskSlot("details", taskLabel, listOf(WfTraitRef(GT.name)))))

    "the catalog lists core's templates, the client's workflows, and the traits they may collect" {
        val client = "wfcat1120"
        defineClient(client, ownCreation = true)
        val read = catalog(admin(client))
        val templates = read[CWF.templates].toJsonListOfMaps()
        templates.map { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] } shouldContainExactly listOf(WFT.createForm, WFT.survey, WFT.collect)
        // The client has a creation workflow, so the creation template is greyed, saying why.
        templates.first()[CWF.refusal].toOptStr().shouldNotBeNull() shouldContain "has a creation workflow, 'start'"
        templates[1][CWF.refusal].shouldBeNull()
        // Its own workflow, written whole, is listed with why the page cannot change it.
        val own = read[CWF.workflows].toJsonListOfMaps().single()
        own[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] shouldBe "start"
        own[CWF.notEditable].toOptStr().shouldNotBeNull() shouldContain "written whole"
        read[CWF.traits].toJsonListOfMaps().map { it[CCT.traitId] } shouldContain GT.name
        // No template is a workflow of the client's.
        WorkflowService.get(cxt).forClient(client).workflows.keys shouldContainExactly setOf("start")
    }

    "a workflow made from a template is stored as its overlay, in a configuration of its own, and runs" {
        val client = "wfmake1120"
        defineClient(client)
        val chief = admin(client)
        val saved = save(chief, surveySlots())
        saved[CPY.mode] shouldBe EDM.live
        saved[CWF.configName] shouldBe "${CWF.configPrefix}eventSurvey"
        saved[CWF.slots].toJsonMapOrEmpty()[WFD.workflowId] shouldBe "eventSurvey"
        // It runs as the template with the client's choices.
        val made = WorkflowService.get(cxt).forClient(client).workflow("eventSurvey").shouldNotBeNull().def
        made.fromTemplate shouldBe WFT.survey
        made.entry shouldBe WfEntry.survey
        made.label shouldBe "Event survey"
        made.tasks.single().label shouldBe "Your event"
        made.tasks.single().saves.single().label shouldBe "Save"
        // What is stored is only what the client chose: the template's traits and saves are the template's.
        val row = GedraConfigService.get(cxt).readLatest(cxt.mkSubContext("read", client), GedraId.of(GedraConfigType.configDoc, client, "${CWF.configPrefix}eventSurvey"))
        val stored = row.shouldNotBeNull().entriesBySlot().getValue(CCT.workflowDef).single()[CCT.definition]
        stored shouldBe mapOf(
            WFD.workflowId to "eventSurvey", WFD.fromTemplate to WFT.survey, WFD.label to "Event survey",
            WFD.tasks to listOf(mapOf(WFD.id to "details", WFD.label to "Your event")),
        )
        // The catalog now lists it as changeable, and greys the survey template.
        val read = catalog(chief)
        read[CWF.workflows].toJsonListOfMaps().single()[CWF.notEditable].shouldBeNull()
        read[CWF.templates].toJsonListOfMaps().single { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] == WFT.survey }[CWF.refusal]
            .toOptStr().shouldNotBeNull() shouldContain "has a survey workflow, 'eventSurvey'"
    }

    "a workflow's slots are changed where it is stored, and a change of nothing is refused" {
        val client = "wfedit1120"
        defineClient(client)
        val chief = admin(client)
        save(chief, surveySlots())
        save(chief, surveySlots("eventSurvey", label = "Event details", taskLabel = "About the event"))[CWF.configName] shouldBe
            "${CWF.configPrefix}eventSurvey"
        val made = WorkflowService.get(cxt).forClient(client).workflow("eventSurvey").shouldNotBeNull().def
        made.label shouldBe "Event details"
        made.tasks.single().label shouldBe "About the event"
        chief.expectError(EXC.badInput, UADEP.clientWorkflowSave, mapOf(CWF.slots to surveySlots("eventSurvey", "Event details", "About the event").toInfo()))
            .toString() shouldContain "nothing to save"
    }

    // Design View edits the workflow as it runs, and what it saves is still the overlay: the diff from the template.
    "a Design View label edit of a workflow made from a template is kept as its overlay" {
        val client = "wfdesign1120"
        defineClient(client)
        val chief = admin(client)
        save(chief, surveySlots())
        val before = WorkflowService.get(cxt).forClient(client).workflow("eventSurvey").shouldNotBeNull().def
        chief.postData(
            DSV.labelEdit,
            mapOf(DSV.workflowId to "eventSurvey", DSV.taskId to "details", DSV.saveId to "saveDetails", DSV.label to "Keep it", DSV.basedOn to workflowDefStamp(before)),
        )
        WorkflowService.get(cxt).forClient(client).workflow("eventSurvey").shouldNotBeNull().def.tasks.single().saves.single().label shouldBe "Keep it"
        val row = GedraConfigService.get(cxt).readLatest(cxt.mkSubContext("read", client), GedraId.of(GedraConfigType.configDoc, client, "${CWF.configPrefix}eventSurvey"))
        row.shouldNotBeNull().entriesBySlot().getValue(CCT.workflowDef).single()[CCT.definition] shouldBe mapOf(
            WFD.workflowId to "eventSurvey", WFD.fromTemplate to WFT.survey, WFD.label to "Event survey",
            WFD.tasks to listOf(mapOf(WFD.id to "details", WFD.label to "Your event", WFD.saves to listOf(mapOf(WFD.id to "saveDetails", WFD.label to "Keep it")))),
        )
        // And the slots save keeps what Design View changed.
        save(chief, surveySlots("eventSurvey", taskLabel = "About the event"))
        WorkflowService.get(cxt).forClient(client).workflow("eventSurvey").shouldNotBeNull().def.tasks.single().saves.single().label shouldBe "Keep it"
    }

    "slots that are not a workflow's, a template that is not one, and a second survey are refused" {
        val client = "wfrefuse1120"
        defineClient(client)
        val chief = admin(client)
        chief.expectError(EXC.badInput, UADEP.clientWorkflowSave, mapOf(CWF.slots to surveySlots(label = " ").toInfo()))
            .toString() shouldContain "needs a name"
        val noTraits = WfTemplateSlots(null, WFT.survey, "Empty", listOf(WfTaskSlot("details", "Details", emptyList())))
        chief.expectError(EXC.badInput, UADEP.clientWorkflowSave, mapOf(CWF.slots to noTraits.toInfo()))
            .toString() shouldContain "at least one trait"
        chief.expectError(EXC.notFound, UADEP.clientWorkflowSave, mapOf(CWF.slots to WfTemplateSlots(null, "kdr:nothing", "X", emptyList()).toInfo()))
        // A client has one survey: the trial refuses a second, as the load would.
        save(chief, surveySlots())
        chief.expectError(EXC.badInput, UADEP.clientWorkflowSave, mapOf(CWF.slots to surveySlots(label = "Another survey").toInfo()))
            .toString() shouldContain "second survey workflow"
    }
})
