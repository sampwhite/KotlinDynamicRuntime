package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientSandboxEdit
import com.dynamicruntime.common.gedra.ConfigImpact
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.DesignRefusal
import com.dynamicruntime.common.gedra.DesignSharedEdit
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.IMP
import com.dynamicruntime.common.gedra.ImpactKind
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.simulation.DesignDemo
import com.dynamicruntime.common.simulation.provisionDesignDemo
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Design View's shared editor (issue #1029): editing a field of a definition the client declares, for every workflow
 * on the client. Over a fresh copy of the Design View demo client -- provisioned by its simulation (#997) in-process --
 * whose two workflows collect the same trait.
 */
class SharedEditTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("sharedEdit1029", "sharedEdit1029")
    val client = provisionDesignDemo(cxt, "shared1029").clients.single()
    val admin = TestUser.create(cxt, "designer@$client.test", level = ROLE.admin, userClient = client)
    val design = mapOf(EP.view to DSV.design)
    val viewPath = clientPath(GEP.workflowView, client)
    // The trait's inline data type, by the name the page knows it under.
    val dataType = admin.getData(viewPath, design)[DSV.designBlock].toJsonMapOrEmpty()[DSV.types].toJsonMapOrEmpty()
        .entries.first { it.value.toJsonMapOrEmpty()[DSV.key] == DesignDemo.eventRequest }.key
    val contactType = "client.$client.${DesignDemo.contactType}"

    // A survey needs a form; create one through the request workflow.
    val formId = admin.postData(
        clientPath(GEP.workflowSave, client),
        mapOf(
            WFD.workflowId to DesignDemo.requestWorkflow, GDF.taskId to DesignDemo.describeTask, GDF.saveId to DesignDemo.submitSave,
            GDF.entries to listOf(mapOf("traitId" to DesignDemo.eventRequest, "data" to mapOf(DesignDemo.title to "Offsite"))),
        ),
    )["item"].toJsonMapOrEmpty()[GDF.gedraId].toOptStr()!!

    fun requestView() = admin.getData(viewPath, design)
    fun configRow() = GedraConfigService.get(cxt).readLatest(cxt.mkSubContext("setup", client), GedraId.of(GedraConfigType.configDoc, client, DesignDemo.configName))
    // A request form, created through the request workflow, holding [venue]; its gedra id.
    fun requestForm(title: String, venue: String, by: TestUser = admin, owner: String = client): String = by.postData(
        clientPath(GEP.workflowSave, owner),
        mapOf(
            WFD.workflowId to DesignDemo.requestWorkflow, GDF.taskId to DesignDemo.describeTask, GDF.saveId to DesignDemo.submitSave,
            GDF.entries to listOf(mapOf("traitId" to DesignDemo.eventRequest, "data" to mapOf(DesignDemo.title to title, DesignDemo.venue to venue))),
        ),
    )["item"].toJsonMapOrEmpty()[GDF.gedraId].toOptStr()!!
    fun reviewView() = admin.getData(viewPath, design + mapOf(GDF.gedraId to formId))
    fun label(view: Map<String, Any?>, type: String, field: String): String? =
        view["fieldLayouts"].toJsonMapOrEmpty()[type].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps()
            .firstOrNull { it[SL.field] == field }?.get(SL.label).toOptStr()
    fun definition(slot: String, key: String) = admin.getItem(DSV.definition, mapOf(DSV.slot to slot, DSV.key to key))
    fun traitRead() = definition(CCT.traitDef, DesignDemo.eventRequest)
    fun sharedArgs(type: String, field: String, entry: Map<String, Any?>?, options: List<Map<String, Any?>>?, basedOn: String) =
        buildMap {
            put(DSV.typeName, type)
            put(DSV.field, field)
            entry?.let { put(DSV.entry, it) }
            options?.let { put(DSV.options, it) }
            put(DSV.sharedBasedOn, basedOn)
        }
    fun shared(type: String, field: String, entry: Map<String, Any?>? = null, options: List<Map<String, Any?>>? = null, slot: String = CCT.traitDef, key: String = DesignDemo.eventRequest) =
        admin.postData(DSV.sharedFieldEdit, sharedArgs(type, field, entry, options, definition(slot, key)[DSV.sharedBasedOn] as String))
    fun venueOptions(): List<Map<String, Any?>> =
        traitRead()[DSV.entry].toJsonMapOrEmpty()[CCT.dataSchema].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()[DesignDemo.venue]
            .toJsonMapOrEmpty()[SCH.options].toJsonListOfMaps()

    "the definition read says where it is used and that it may be edited here" {
        val d = traitRead()
        d[DSV.usedBy].toJsonListOfMaps().map { it[DSV.workflowId] }.toSet() shouldBe setOf(DesignDemo.requestWorkflow, DesignDemo.reviewWorkflow)
        d[DSV.canEditShared] shouldBe true
        (d[DSV.sharedBasedOn] as String).isNotBlank() shouldBe true
    }

    "the read says which fields cannot take shared copy, and the save refuses them" {
        // The contact type's layout decides the order and leaves the phone out (issue #1039).
        val contact = definition(CCT.schemaDef, contactType)
        contact[DSV.sharedCopyRefusals].toJsonMapOrEmpty().keys shouldBe setOf(DesignDemo.contactPhone)
        traitRead()[DSV.sharedCopyRefusals].toJsonMapOrEmpty() shouldBe emptyMap()
        admin.expectError(
            400, DSV.sharedFieldEdit,
            sharedArgs(contactType, DesignDemo.contactPhone, mapOf(SL.label to "Phone"), null, contact[DSV.sharedBasedOn] as String),
        ).toString() shouldContain "not in its list"
    }

    "a shared copy edit shows on every workflow" {
        shared(dataType, DesignDemo.title, mapOf(SL.label to "Event name", SL.description to "What people will call it."))
        label(requestView(), dataType, DesignDemo.title) shouldBe "Event name"
        label(reviewView(), dataType, DesignDemo.title) shouldBe "Event name"
    }

    "a workflow's own variant still wins on its pages, and the read names it" {
        // The request workflow overrides the title; a later shared edit reaches only the survey.
        admin.postData(
            DSV.layoutEntryEdit,
            mapOf(
                DSV.workflowId to DesignDemo.requestWorkflow, DSV.typeName to dataType, DSV.field to DesignDemo.title,
                DSV.entry to mapOf(SL.label to "Name it"), DSV.basedOn to requestView()[DSV.designBlock].toJsonMapOrEmpty()[DSV.basedOn],
            ),
        )
        shared(dataType, DesignDemo.title, mapOf(SL.label to "What are we holding?"))
        label(requestView(), dataType, DesignDemo.title) shouldBe "Name it"
        label(reviewView(), dataType, DesignDemo.title) shouldBe "What are we holding?"
        traitRead()[DSV.variantFields].toJsonMapOrEmpty()[DesignDemo.title] shouldBe listOf(DesignDemo.requestWorkflow)
    }

    "choices are relabeled and added, and a new one is accepted on save" {
        val relabeled = venueOptions().map {
            if (it[SCH.value] == "hotel") it + (SCH.label to "A hotel or conference center") else it
        } + mapOf(SCH.value to "park", SCH.label to "A park")
        shared(dataType, DesignDemo.venue, options = relabeled)
        venueOptions().map { it[SCH.value] } shouldBe listOf("office", "hotel", "outdoors", "park")
        venueOptions().single { it[SCH.value] == "hotel" }[SCH.label] shouldBe "A hotel or conference center"
        // The widened list binds at once: a form may now hold the new choice.
        admin.postData(
            clientPath(GEP.workflowSave, client),
            mapOf(
                WFD.workflowId to DesignDemo.requestWorkflow, GDF.taskId to DesignDemo.describeTask, GDF.saveId to DesignDemo.submitSave,
                GDF.entries to listOf(
                    mapOf("traitId" to DesignDemo.eventRequest, "data" to mapOf(DesignDemo.title to "Picnic", DesignDemo.venue to "park")),
                ),
            ),
        )["item"].toJsonMapOrEmpty()[GDF.gedraId].toOptStr() shouldNotBe null
    }

    "a free-text field gains no choices, and a field with choices keeps at least one" {
        val basedOn = traitRead()[DSV.sharedBasedOn] as String
        // A free-text field gains no choices here: that would narrow what it accepts.
        admin.expectError(
            400, DSV.sharedFieldEdit,
            sharedArgs(dataType, DesignDemo.title, null, listOf(mapOf(SCH.value to "a", SCH.label to "A")), basedOn),
        ).toString() shouldContain "has no choices"
        admin.expectError(400, DSV.sharedFieldEdit, sharedArgs(dataType, DesignDemo.venue, null, emptyList(), basedOn))
            .toString() shouldContain "at least one"
    }

    // Removing a choice (issue #1040): checked against the forms the client stores, whatever its tier.

    "removing a choice no stored form holds saves at once" {
        requestForm("Planning day", "hotel")
        shared(dataType, DesignDemo.venue, options = venueOptions().filter { it[SCH.value] != "office" })
        venueOptions().map { it[SCH.value] } shouldNotContain "office"
    }

    "removing one a stored form holds is refused with the report and undone, and saves acknowledged" {
        val picnic = requestForm("Summer picnic", "park")
        val withoutPark = venueOptions().filter { it[SCH.value] != "park" }
        val refused = admin.expectError(
            EXC.badInput, DSV.sharedFieldEdit,
            sharedArgs(dataType, DesignDemo.venue, null, withoutPark, traitRead()[DSV.sharedBasedOn] as String),
        )
        refused[EP.errorCode] shouldBe IMP.refusedCode
        refused[EP.errorMessage].toOptStr().orEmpty() shouldContain "Save again acknowledging"
        val finding = refused[EP.extraData].toJsonMapOrEmpty()[IMP.report].toJsonMapOrEmpty()[IMP.findings].toJsonListOfMaps().single()
        finding[IMP.kind] shouldBe ImpactKind.dataInvalid.name
        finding[IMP.sampleIds].toJsonListOfStrings() shouldContain picnic
        // Undone: the choice is still offered, and the configuration is left published, not a draft.
        venueOptions().map { it[SCH.value] } shouldContain "park"
        configRow()?.isPublished shouldBe true

        admin.postData(
            DSV.sharedFieldEdit,
            sharedArgs(dataType, DesignDemo.venue, null, withoutPark, traitRead()[DSV.sharedBasedOn] as String) +
                (IMP.acknowledgeImpact to true),
        )
        venueOptions().map { it[SCH.value] } shouldNotContain "park"
    }

    "a changed value is a removal and an addition, and the report names the forms holding the old one" {
        val gala = requestForm("Winter gala", "hotel")
        val renamed = venueOptions().map { if (it[SCH.value] == "hotel") mapOf(SCH.value to "conference", SCH.label to "A conference center") else it }
        val refused = admin.expectError(
            EXC.badInput, DSV.sharedFieldEdit,
            sharedArgs(dataType, DesignDemo.venue, null, renamed, traitRead()[DSV.sharedBasedOn] as String),
        )
        refused[EP.extraData].toJsonMapOrEmpty()[IMP.report].toJsonMapOrEmpty()[IMP.findings].toJsonListOfMaps()
            .single()[IMP.sampleIds].toJsonListOfStrings() shouldContain gala
    }

    "a client running only its published configuration is checked the same way" {
        GedraConfigService.get(cxt).setPublishedOnly(cxt.mkSubContext("setup", client), client, true)
        try {
            val withoutHotel = venueOptions().filter { it[SCH.value] != "hotel" }
            admin.expectError(
                EXC.badInput, DSV.sharedFieldEdit,
                sharedArgs(dataType, DesignDemo.venue, null, withoutHotel, traitRead()[DSV.sharedBasedOn] as String),
            )[EP.errorCode] shouldBe IMP.refusedCode
        } finally {
            GedraConfigService.get(cxt).setPublishedOnly(cxt.mkSubContext("setup", client), client, false)
        }
    }

    "in a sandbox a removal is a draft, and its impact shows when that draft is published" {
        val parent = provisionDesignDemo(cxt, "sbx1040").clients.single()
        val parentAdmin = TestUser.create(cxt, "designer@$parent.test", level = ROLE.admin, userClient = parent)
        val held = requestForm("Retreat", "hotel", parentAdmin, parent)
        ClientSandboxEdit.set(cxt.mkSubContext("setup", parent), parent, true)
        parentAdmin.postData(AEP.openSandbox, emptyMap())

        val sandboxView = parentAdmin.getData(clientPath(GEP.workflowView, sandboxOf(parent)), design)
        val sandboxType = sandboxView[DSV.designBlock].toJsonMapOrEmpty()[DSV.types].toJsonMapOrEmpty()
            .entries.first { it.value.toJsonMapOrEmpty()[DSV.key] == DesignDemo.eventRequest }.key
        val read = parentAdmin.getItem(DSV.definition, mapOf(DSV.slot to CCT.traitDef, DSV.key to DesignDemo.eventRequest))
        val options = read[DSV.entry].toJsonMapOrEmpty()[CCT.dataSchema].toJsonMapOrEmpty()[SCH.properties].toJsonMapOrEmpty()[DesignDemo.venue]
            .toJsonMapOrEmpty()[SCH.options].toJsonListOfMaps().filter { it[SCH.value] != "hotel" }
        // Saved without a word: nothing reaches the parent's forms until the draft is published.
        parentAdmin.postData(DSV.sharedFieldEdit, sharedArgs(sandboxType, DesignDemo.venue, null, options, read[DSV.sharedBasedOn] as String))

        val report = ConfigImpact.report(cxt.mkSubContext("setup", parent), parent, DesignDemo.configName)
        report.findings.single().kind shouldBe ImpactKind.dataInvalid
        report.findings.single().rows shouldContain held
    }

    "an edit based on an entry that has since changed is refused" {
        val stale = traitRead()[DSV.sharedBasedOn] as String
        shared(dataType, DesignDemo.attendees, mapOf(SL.label to "Headcount"))
        admin.expectError(409, DSV.sharedFieldEdit, sharedArgs(dataType, DesignDemo.attendees, mapOf(SL.label to "People"), null, stale))
    }

    "a named type's field is edited in its own schema entry" {
        shared(contactType, DesignDemo.contactEmail, mapOf(SL.label to "Email the organizers"), slot = CCT.schemaDef, key = contactType)
        label(requestView(), contactType, DesignDemo.contactEmail) shouldBe "Email the organizers"
        label(reviewView(), contactType, DesignDemo.contactEmail) shouldBe "Email the organizers"
    }

    "a definition declared globally or in source is not this client's to edit, and says which" {
        val name = definition(CCT.traitDef, GT.name)
        name[DSV.canEditShared] shouldBe false
        name[DSV.sharedRefusalCode] shouldBe DesignRefusal.declaredGlobally.name
        val inSource = gedraConfig(LiteCxt(), "inCode1029", clientNamespace(client), client, GedraConfigOrigin.source) {}
        DesignSharedEdit.refusal(cxt.mkSubContext("setup", client), inSource)?.code shouldBe DesignRefusal.declaredInSource
    }

    "a configuration carrying somebody's unpublished changes is refused; published, editing returns" {
        // An unpublished change beside Design View: a save here publishes, and would take it live (issue #1026).
        val configId = GedraId.of(GedraConfigType.configDoc, client, DesignDemo.configName)
        GedraConfigService.get(cxt).patchConfig(cxt.mkSubContext("setup", client), configId) { it }
        try {
            traitRead()[DSV.sharedRefusalCode] shouldBe DesignRefusal.unpublishedChanges.name
        } finally {
            GedraConfigService.get(cxt).publish(cxt.mkSubContext("setup", client), configId)
        }
        traitRead()[DSV.canEditShared] shouldBe true
    }

    "a shared save publishes, so the client's configuration is left with no draft" {
        shared(dataType, DesignDemo.backupPlan, mapOf(SL.label to "If it rains"))
        configRow()?.isPublished shouldBe true
    }

    // The reconciliation with the Clients page's editors (issue #1026): Design View saves publish as theirs do, so after
    // any number of them the client's definition is still editable there -- no draft is left to refuse it.
    "after Design View saves, the Clients page can still edit the client's definition" {
        val result = admin.postData(UADEP.clientDefinitionSet, mapOf(CLD.name to "Design demo, renamed"))
        result[CLD.definition].toJsonMapOrEmpty()[CLD.name] shouldBe "Design demo, renamed"
    }
})
