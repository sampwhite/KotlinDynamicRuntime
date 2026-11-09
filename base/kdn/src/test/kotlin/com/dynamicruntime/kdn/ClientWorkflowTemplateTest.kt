package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CIT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.CUST
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.TraitDesign
import com.dynamicruntime.common.gedra.TraitFieldDesign
import com.dynamicruntime.common.gedra.TraitFieldKind
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
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
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
 *
 * Also, since issue #1739, the tests that were `NewTraitInWorkflowCatalogTest`, each kept whole below.
 */
class ClientWorkflowTemplateTest : StringSpec({
    include(clientWorkflowTemplateTests)
    include(newTraitInWorkflowCatalogTests)
})

/** This file's own tests, as the class describes them. */
private val clientWorkflowTemplateTests = stringSpec {
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
        svc.publish(setup, listOf(GedraId.of(GedraConfigType.configDoc, client, "main")))
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun admin(client: String): TestUser = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

    fun catalog(user: TestUser) = user.getItem(UADEP.clientWorkflows)

    fun save(user: TestUser, slots: WfTemplateSlots) = user.postData(UADEP.clientWorkflowSave, mapOf(CWF.slots to slots.toInfo()))

    fun coreDetailsSlots(workflowId: String? = null, label: String = "Event details", taskLabel: String = "Your event") =
        WfTemplateSlots(workflowId, WFT.coreDetails, label, listOf(WfTaskSlot("details", taskLabel, listOf(WfTraitRef(GT.name)))))

    "the catalog lists core's templates, the client's workflows, and the traits they may collect" {
        val client = "wfcat1120"
        defineClient(client, ownCreation = true)
        val read = catalog(admin(client))
        val templates = read[CWF.templates].toJsonListOfMaps()
        templates.map { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] } shouldContainExactly listOf(WFT.createForm,
            WFT.coreDetails, WFT.collect)
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
        val saved = save(chief, coreDetailsSlots())
        saved[CPY.mode] shouldBe EDM.live
        saved[CWF.configName] shouldBe "${CWF.configPrefix}eventDetails"
        saved[CWF.slots].toJsonMapOrEmpty()[WFD.workflowId] shouldBe "eventDetails"
        // It runs as the template with the client's choices.
        val made = WorkflowService.get(cxt).forClient(client).workflow("eventDetails").shouldNotBeNull().def
        made.fromTemplate shouldBe WFT.coreDetails
        made.entry shouldBe WfEntry.coreDetails
        made.label shouldBe "Event details"
        made.tasks.single().label shouldBe "Your event"
        made.tasks.single().saves.single().label shouldBe "Save"
        // What is stored is only what the client chose: the template's traits and saves are the template's.
        val row = GedraConfigService.get(cxt).readLatest(cxt.mkSubContext("read", client), GedraId.of(GedraConfigType.configDoc,
            client, "${CWF.configPrefix}eventDetails"))
        val stored = row.shouldNotBeNull().entriesBySlot().getValue(CCT.workflowDef).single()[CCT.definition]
        stored shouldBe mapOf(
            WFD.workflowId to "eventDetails", WFD.fromTemplate to WFT.coreDetails, WFD.label to "Event details",
            WFD.tasks to listOf(mapOf(WFD.id to "details", WFD.label to "Your event")),
        )
        // The catalog now lists it as changeable, and greys the core details template.
        val read = catalog(chief)
        read[CWF.workflows].toJsonListOfMaps().single()[CWF.notEditable].shouldBeNull()
        read[CWF.templates].toJsonListOfMaps()
            .single { it[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId] == WFT.coreDetails }[CWF.refusal]
            .toOptStr().shouldNotBeNull() shouldContain "has a core details workflow, 'eventDetails'"
    }

    "a workflow's slots are changed where it is stored, and a change of nothing is refused" {
        val client = "wfedit1120"
        defineClient(client)
        val chief = admin(client)
        save(chief, coreDetailsSlots())
        save(chief, coreDetailsSlots("eventDetails", label = "Event details", taskLabel = "About the event"))[CWF.configName] shouldBe
            "${CWF.configPrefix}eventDetails"
        val made = WorkflowService.get(cxt).forClient(client).workflow("eventDetails").shouldNotBeNull().def
        made.label shouldBe "Event details"
        made.tasks.single().label shouldBe "About the event"
        chief.expectError(
            EXC.badInput, UADEP.clientWorkflowSave,
            mapOf(CWF.slots to coreDetailsSlots("eventDetails", "Event details", "About the event").toInfo()),
        ).toString() shouldContain "nothing to save"
    }

    // Design View edits the workflow as it runs, and what it saves is still the overlay: the diff from the template.
    "a Design View label edit of a workflow made from a template is kept as its overlay" {
        val client = "wfdesign1120"
        defineClient(client)
        val chief = admin(client)
        save(chief, coreDetailsSlots())
        val before = WorkflowService.get(cxt).forClient(client).workflow("eventDetails").shouldNotBeNull().def
        chief.postData(
            DSV.labelEdit,
            mapOf(DSV.workflowId to "eventDetails", DSV.taskId to "details", DSV.saveId to "saveDetails", DSV.label to "Keep it",
                DSV.basedOn to workflowDefStamp(before)),
        )
        WorkflowService.get(cxt).forClient(client).workflow("eventDetails").shouldNotBeNull()
            .def.tasks.single().saves.single().label shouldBe "Keep it"
        val row = GedraConfigService.get(cxt).readLatest(cxt.mkSubContext("read", client), GedraId.of(GedraConfigType.configDoc,
            client, "${CWF.configPrefix}eventDetails"))
        row.shouldNotBeNull().entriesBySlot().getValue(CCT.workflowDef).single()[CCT.definition] shouldBe mapOf(
            WFD.workflowId to "eventDetails", WFD.fromTemplate to WFT.coreDetails, WFD.label to "Event details",
            WFD.tasks to listOf(mapOf(WFD.id to "details", WFD.label to "Your event", WFD.saves to listOf(mapOf(WFD.id to "saveDetails", WFD.label to "Keep it")))),
        )
        // And the slots save keeps what Design View changed.
        save(chief, coreDetailsSlots("eventDetails", taskLabel = "About the event"))
        WorkflowService.get(cxt).forClient(client).workflow("eventDetails").shouldNotBeNull()
            .def.tasks.single().saves.single().label shouldBe "Keep it"
    }

    "a new workflow's id passes over the name of a configuration left from another" {
        val client = "wfleft1120"
        defineClient(client)
        val chief = admin(client)
        // A configuration of that name with no workflow in it, as a workflow taken out by hand would leave.
        GedraConfigService.get(cxt).writeConfig(
            cxt.mkSubContext("setup", client).also { it.userId = 11200L },
            gedraConfig(cxt, "${CWF.configPrefix}eventDetails", clientNamespace(client), client) { cfact("leftOver", "Test",
                "Left over.", false) },
        )
        GedraConfigReload.reloadClient(cxt, client)
        save(chief, coreDetailsSlots())[CWF.configName] shouldBe "${CWF.configPrefix}eventDetails2"
    }

    "a plan's limit on workflows greys every template, saying why" {
        val operator = TestUser.createFullAdmin(cxt, "operator@wfquota1120.test")
        val client = "${CUST.free}_wfquota1120"
        operator.postData(
            ADEP.clientCreate,
            mapOf(
                CLD.clientId to client, CLD.name to "Client $client", CLD.usageType to ClientUsageType.dev.name,
                CLD.audience to ClientAudience.customer.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
                // Without the starter workflows (issue #1346): the case writes the four it counts itself.
                CLD.starterWorkflows to false,
            ),
        )
        val flows = gedraConfig(cxt, "flows", clientNamespace(client), client) {
            trait("QEntry", "q", setOf(GedraDataType.formDoc)) { property("a", "A.") }
            for (w in 1..4) {
                workflow("w$w", WfEntry.normal) { task("t", "T") { trait("q"); save("s", "S", WfSaveKind.edit) } }
            }
        }
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 11200L }, flows, trial = true)
        GedraConfigReload.reloadClient(cxt, client)
        val templates = operator.getItem(UADEP.clientWorkflows, mapOf(CLD.client to client))[CWF.templates].toJsonListOfMaps()
        templates.map { it[CWF.refusal].toOptStr() }.forEach { it.shouldNotBeNull() shouldContain "The free plan allows 4 workflows, and this client has 4." }
    }

    // On an instance that forgives stored configuration, as production does.
    "a workflow whose template is gone costs only itself: kept unread, reported, written back, and refused if changed" {
        val warn = TestInstances.storedConfigWarn("clientWorkflowUnread1120")
        val client = "wfunread1120"
        val setup = warn.mkSubContext("setup", client).also { it.userId = 11201L }
        val lost = mapOf(WFD.workflowId to "lost", WFD.fromTemplate to "kdr:gone", WFD.label to "Lost")
        val config = gedraConfig(warn, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), includedTraits = listOf(GT.name),
                ),
            )
            workflow("start", WfEntry.creation) { task("name", "Name it") { trait(GT.name); save("go", "Go", WfSaveKind.create) } }
            // Stored beside it, a workflow whose template the deployment no longer declares, as a later release could leave.
            keepUnreadWorkflow = { true }
            workflowFromMap(lost)
        }
        val svc = GedraConfigService.get(warn)
        svc.writeConfig(setup, config)
        val reload = GedraConfigReload.reloadClient(warn, client)
        reload.issues.any { it.message.contains("holds the workflow 'lost'") } shouldBe true
        // The rest of the configuration loads.
        WorkflowService.get(warn).forClient(client).workflows.keys shouldContainExactly setOf("start")
        // An edit of something else in the configuration keeps it as stored...
        val id = GedraId.of(GedraConfigType.configDoc, client, "main")
        fun relabel(workflowId: String, label: String) = svc.patchConfigs(setup) { edit(id) { slots ->
            slots + (CCT.workflowDef to slots.getValue(CCT.workflowDef).map {
                if (it[CCT.workflowId] != workflowId) it else it + (CCT.definition to (it[CCT.definition].toJsonMapOrEmpty() + (WFD.label to label)))
            })
        } }.single()
        relabel("start", "Begin")
        svc.readLatest(setup, id).shouldNotBeNull().entriesBySlot().getValue(CCT.workflowDef)
            .single { it[CCT.workflowId] == "lost" }[CCT.definition] shouldBe lost
        // ...and a write changing it is refused: what a write adds or changes has to read.
        shouldThrow<KdrException> { relabel("lost", "Found") }.fullMessage() shouldContain "kdr:gone"
    }

    "slots that are not a workflow's, a template that is not one, and a second core details workflow are refused" {
        val client = "wfrefuse1120"
        defineClient(client)
        val chief = admin(client)
        chief.expectError(EXC.badInput, UADEP.clientWorkflowSave, mapOf(CWF.slots to coreDetailsSlots(label = " ").toInfo()))
            .toString() shouldContain "needs a name"
        val noTraits = WfTemplateSlots(null, WFT.coreDetails, "Empty", listOf(WfTaskSlot("details", "Details", emptyList())))
        chief.expectError(EXC.badInput, UADEP.clientWorkflowSave, mapOf(CWF.slots to noTraits.toInfo()))
            .toString() shouldContain "at least one trait"
        chief.expectError(EXC.notFound, UADEP.clientWorkflowSave, mapOf(CWF.slots to WfTemplateSlots(null, "kdr:nothing", "X", emptyList()).toInfo()))
        // A client has one core details workflow: the trial refuses a second, as the load would.
        save(chief, coreDetailsSlots())
        chief.expectError(
            EXC.badInput, UADEP.clientWorkflowSave, mapOf(CWF.slots to coreDetailsSlots(label = "Another core details workflow").toInfo()),
        )
            .toString() shouldContain "second core details workflow"
    }
}

/**
 * A trait a client has just made is offered to the workflow it makes next (issue #1425). A self-serve client has a
 * sandbox, so the trait is a draft only the sandbox runs; the workflow editor's catalog lists the sandbox's workflows,
 * and must offer the sandbox's traits with them -- and its user labels -- not only the published ones the client itself
 * runs. A client without a sandbox is offered its own, as it runs them. Over the shared instance, a client per case.
 */
private val newTraitInWorkflowCatalogTests = stringSpec {
    val cxt = TestInstances.default("newTraitCatalog1425")

    "a draft trait is offered to a new workflow at once, and a workflow collecting it saves as a draft" {
        val person = TestUser.create(cxt, "maker@newtrait1425.test")
        val client = person.postData(AEP.createOwnClient, mapOf(CLD.name to "Trait Shop"))[CLD.client].toOptStr()!!
        val design = TraitDesign(null, "Site safety", "How a site is kept safe.", listOf(TraitFieldDesign(null, "Notes", TraitFieldKind.text)))
        val made = person.postData(UADEP.clientTraitDesign, mapOf(CIT.design to design.toInfo()))
        made[CPY.mode] shouldBe EDM.draft
        val traitId = made[CIT.design].toJsonMapOrEmpty()[CIT.traitId].toOptStr()!!

        val offered = person.getItem(UADEP.clientWorkflows, mapOf(CLD.client to client))[CWF.traits].toJsonListOfMaps()
        offered.map { it[CCT.traitId] } shouldContain traitId
        // Named as the form names it, not by its id (issue #1438).
        offered.single { it[CCT.traitId] == traitId }[CWF.name] shouldBe "Site safety"

        val slots = WfTemplateSlots(null, WFT.collect, "Safety check", listOf(WfTaskSlot("collect", "Safety", listOf(WfTraitRef(traitId)))))
        person.postData(UADEP.clientWorkflowSave, mapOf(CWF.slots to slots.toInfo()))[CPY.mode] shouldBe EDM.draft
    }

    "a user label just added to the definition is offered to a new workflow's visibility at once" {
        val person = TestUser.create(cxt, "labels@newtrait1425.test")
        val client = person.postData(AEP.createOwnClient, mapOf(CLD.name to "Label Shop"))[CLD.client].toOptStr()!!
        person.postData(UADEP.clientDefinitionSet, mapOf(CLD.userLabels to listOf("siteLead")))[CPY.mode] shouldBe EDM.draft
        person.getItem(UADEP.clientWorkflows, mapOf(CLD.client to client))[CWF.userLabels].toJsonListOfStrings() shouldContain "siteLead"
    }

    "a client without a sandbox is offered its own traits, as it runs them" {
        val client = "plain1425"
        GedraConfigService.get(cxt).writeConfig(
            cxt.mkSubContext("setup", client).also { it.userId = 14250L },
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
                trait("InspectionEntry", "inspection", setOf(GedraDataType.formDoc), "An inspection.") { property("notes", "What was found.") }
            },
        )
        GedraConfigReload.reloadClient(cxt, client)
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        admin.getItem(UADEP.clientWorkflows)[CWF.traits].toJsonListOfMaps().map { it[CCT.traitId] } shouldContain "inspection"
    }
}
