package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.DesignRefusal
import com.dynamicruntime.common.gedra.DesignSharedEdit
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.simulation.DesignDemo
import com.dynamicruntime.common.simulation.provisionDesignDemo
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
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

    "removing a choice or changing its value is refused, with the reason" {
        val basedOn = traitRead()[DSV.sharedBasedOn] as String
        val withoutOutdoors = venueOptions().filter { it[SCH.value] != "outdoors" }
        admin.expectError(400, DSV.sharedFieldEdit, sharedArgs(dataType, DesignDemo.venue, null, withoutOutdoors, basedOn))
            .toString() shouldContain "stored forms may hold it"
        val renamed = venueOptions().map { if (it[SCH.value] == "office") it + (SCH.value to "hq") else it }
        admin.expectError(400, DSV.sharedFieldEdit, sharedArgs(dataType, DesignDemo.venue, null, renamed, basedOn))
            .toString() shouldContain "'office'"
        // A free-text field gains no choices here: that would narrow what it accepts.
        admin.expectError(
            400, DSV.sharedFieldEdit,
            sharedArgs(dataType, DesignDemo.title, null, listOf(mapOf(SCH.value to "a", SCH.label to "A")), basedOn),
        ).toString() shouldContain "has no choices"
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

    "a client that runs its published configuration cannot edit here" {
        GedraConfigService.get(cxt).setPublishedOnly(cxt.mkSubContext("setup", client), client, true)
        try {
            traitRead()[DSV.sharedRefusalCode] shouldBe DesignRefusal.publishedOnly.name
        } finally {
            GedraConfigService.get(cxt).setPublishedOnly(cxt.mkSubContext("setup", client), client, false)
        }
        traitRead()[DSV.canEditShared] shouldBe true
    }
})
