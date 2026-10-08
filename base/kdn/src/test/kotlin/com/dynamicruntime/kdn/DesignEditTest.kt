package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.DesignRefusal
import com.dynamicruntime.common.gedra.DesignView
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WfDeclared
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WorkflowService
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Editing a form's copy as a workflow variant from Design View (issue #984): the edit endpoint, the workflow's own
 * wording delivered on its pages only, the Design View block's account of it, reset, the stale-edit refusal, the
 * stale marker, and the load checks on a workflow's type alterations. Over the demo client, whose two workflows
 * collect the same trait -- which is what makes "this workflow only" visible.
 */
class DesignEditTest : StringSpec({
    val cxt = TestInstances.default("designEdit984")
    // Provisioned by its simulation (issue #997), so its configuration is published -- a Design View save publishes,
    // as the Clients page's editors do, and refuses a config carrying unpublished changes (issue #1026).
    val client = provisionDesignDemo(cxt).clients.single()
    val admin = TestUser.create(cxt, "designer@$client.test", level = ROLE.admin, userClient = client)
    val design = mapOf(EP.view to DSV.design)
    val viewPath = clientPath(GEP.workflowView, client)

    // A form, so the survey workflow -- the second over the same trait -- has a view to compare against.
    val formId = admin.postData(
        clientPath(GEP.workflowSave, client),
        mapOf(
            WFD.workflowId to DesignDemo.requestWorkflow, GDF.taskId to DesignDemo.describeTask, GDF.saveId to DesignDemo.submitSave,
            GDF.entries to listOf(
                mapOf("traitId" to DesignDemo.eventRequest, "data" to mapOf(DesignDemo.title to "Offsite")),
                mapOf("traitId" to "kdr:name", "data" to mapOf("name" to "Offsite 2027")),
            ),
        ),
    )[WSF.item].toJsonMapOrEmpty()[GDF.gedraId].toOptStr()!!

    fun requestView(): Map<String, Any?> = admin.getData(viewPath, design)
    fun reviewView(): Map<String, Any?> = admin.getData(viewPath, design + mapOf(GDF.gedraId to formId))
    fun block(view: Map<String, Any?>) = view[DSV.designBlock].toJsonMapOrEmpty()
    fun typeNamed(view: Map<String, Any?>, key: String, slot: String = CCT.traitDef): String =
        block(view)[DSV.types].toJsonMapOrEmpty().entries.first {
            val a = it.value.toJsonMapOrEmpty()
            a[DSV.key] == key && a[DSV.slot] == slot
        }.key
    fun label(view: Map<String, Any?>, type: String, field: String): String? =
        view["fieldLayouts"].toJsonMapOrEmpty()[type].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps()
            .firstOrNull { it[SL.field] == field }?.get(SL.label).toOptStr()
    fun editArgs(type: String, field: String, entry: Map<String, Any?>?, basedOn: String = block(requestView())[DSV.basedOn].toOptStr()!!) =
        buildMap {
            put(DSV.workflowId, DesignDemo.requestWorkflow)
            put(DSV.typeName, type)
            put(DSV.field, field)
            entry?.let { put(DSV.entry, it) }
            put(DSV.basedOn, basedOn)
        }
    fun edit(type: String, field: String, entry: Map<String, Any?>?) = admin.postData(DSV.layoutEntryEdit, editArgs(type, field, entry))

    val dataType = typeNamed(requestView(), DesignDemo.eventRequest)
    val contactType = "client.$client.${DesignDemo.contactType}"

    "the block says the workflow's copy can be edited here, with the stamp to edit against" {
        val b = block(requestView())
        b[DSV.canEdit] shouldBe true
        (b[DSV.basedOn] as String).isNotBlank() shouldBe true
    }

    "an edit shows on that workflow's pages only, and the block accounts for it" {
        edit(dataType, DesignDemo.title, mapOf(SL.label to "Name the event"))
        label(requestView(), dataType, DesignDemo.title) shouldBe "Name the event"
        // The survey collects the same trait and keeps the shared copy.
        label(reviewView(), dataType, DesignDemo.title) shouldBe DesignDemo.titleLabel
        val facts = block(requestView())[DSV.layoutEdits].toJsonMapOrEmpty()[dataType].toJsonMapOrEmpty()[DesignDemo.title]
            .toJsonMapOrEmpty()
        facts[DSV.entry].toJsonMapOrEmpty()[SL.label] shouldBe "Name the event"
        facts[DSV.inherited].toJsonMapOrEmpty()[SL.label] shouldBe DesignDemo.titleLabel
        facts[DSV.inheritedChanged] shouldBe false
    }

    "a field of a referenced type is altered the same way, by naming that type" {
        edit(contactType, DesignDemo.contactEmail, mapOf(SL.label to "Email for the organizers"))
        label(requestView(), contactType, DesignDemo.contactEmail) shouldBe "Email for the organizers"
        label(reviewView(), contactType, DesignDemo.contactEmail) shouldBe DesignDemo.contactEmailLabel
    }

    "an edit based on a definition that has since changed is refused" {
        val stale = block(requestView())[DSV.basedOn].toOptStr()!!
        edit(dataType, DesignDemo.venue, mapOf(SL.label to "Where"))
        admin.expectError(409, DSV.layoutEntryEdit, editArgs(dataType, DesignDemo.venue, mapOf(SL.label to "Somewhere"), stale))
        label(requestView(), dataType, DesignDemo.venue) shouldBe "Where"
    }

    "an entry the layout checks refuse is not stored" {
        admin.expectError(400, DSV.layoutEntryEdit, editArgs(dataType, "noSuchField", mapOf(SL.label to "Nothing")))
    }

    "a malformed template, or a fragment pull written for the frontend, is refused and not stored" {
        val refusals = mapOf(
            $$"Name ${unclosed" to "is a malformed template",
            $$"${@t(\"design.title\")}" to "uses a frontend fragment pull",
        )
        for ((bad, why) in refusals) {
            admin.expectError(400, DSV.layoutEntryEdit, editArgs(dataType, DesignDemo.title, mapOf(SL.label to bad)))
                .toString() shouldContain why
        }
        label(requestView(), dataType, DesignDemo.title) shouldBe "Name the event"
    }

    "the block says when the shared copy has changed since the workflow overrode it" {
        // The client's own wording for the title changes after the workflow made its own.
        val configId = GedraId.of(GedraConfigType.configDoc, client, DesignDemo.configName)
        GedraConfigService.get(cxt).patchConfig(cxt.mkSubContext("setup", client), configId) { slots ->
            slots + (
                CCT.traitDef to slots[CCT.traitDef].orEmpty().map { t ->
                    if (t[CCT.traitId] != DesignDemo.eventRequest) return@map t
                    val data = t[CCT.dataSchema].toJsonMapOrEmpty()
                    val layout = data[SCH.layout].toJsonMapOrEmpty()
                    val fields = layout[SL.schemaFields].toJsonListOfMaps().map {
                        if (it[SL.field] == DesignDemo.title) it + (SL.label to "What are we holding?") else it
                    }
                    t + (CCT.dataSchema to (data + (SCH.layout to (layout + (SL.schemaFields to fields)))))
                }
                )
        }
        // Published, as an edit on the Clients page would be: Design View saves refuse a config left with a draft.
        GedraConfigService.get(cxt).publish(cxt.mkSubContext("setup", client), configId)
        GedraConfigReload.reloadClient(cxt, client)
        val facts = block(requestView())[DSV.layoutEdits].toJsonMapOrEmpty()[dataType].toJsonMapOrEmpty()[DesignDemo.title]
            .toJsonMapOrEmpty()
        facts[DSV.inheritedChanged] shouldBe true
        // The workflow's own wording still stands; the survey picks up the client's new one.
        label(requestView(), dataType, DesignDemo.title) shouldBe "Name the event"
        label(reviewView(), dataType, DesignDemo.title) shouldBe "What are we holding?"
    }

    "reset goes back to the inherited copy and leaves no alteration behind" {
        for ((type, field) in listOf(dataType to DesignDemo.title, dataType to DesignDemo.venue, contactType to DesignDemo.contactEmail)) {
            edit(type, field, null)
        }
        label(requestView(), dataType, DesignDemo.title) shouldBe "What are we holding?"
        block(requestView()).containsKey(DSV.layoutEdits) shouldBe false
        val def = admin.getItem(DSV.definition, mapOf(DSV.slot to CCT.workflowDef, DSV.key to DesignDemo.requestWorkflow))[DSV.entry]
            .toJsonMapOrEmpty()[CCT.definition].toJsonMapOrEmpty()
        def.containsKey(WFD.types) shouldBe false
        def.containsKey(WFD.typeBasis) shouldBe false
    }

    "a workflow may alter only a type its pages draw -- refused at the reload, outside production" {
        val other = "alterBad984"
        val config = gedraConfig(cxt, "${other}cfg", clientNamespace(other), other) {
            defineClient(
                ClientDef(
                    clientId = other, name = other, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            type("Unshown") { property("x", "A value.") }
            trait("NoteEntry", "note", setOf(GedraDataType.formDoc), "A note.") { property("text", "The text.") }
            workflow("make", WfEntry.creation) {
                task("ask", "Ask") {
                    trait("note")
                    save("create", "Create")
                }
                alterType(
                    "client.$other.Unshown",
                    mapOf(SCH.layout to mapOf(SL.schemaFields to listOf(mapOf(SL.field to "x", SL.label to "Ex")))),
                )
            }
        }
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", other), config)
        shouldThrow<KdrException> { GedraConfigReload.reloadClient(cxt, other) }.fullMessage() shouldContain
            "which its pages do not show"
    }

    "a workflow alteration of anything but a layout is refused at the write" {
        val configId = GedraId.of(GedraConfigType.configDoc, client, DesignDemo.configName)
        shouldThrow<KdrException> {
            GedraConfigService.get(cxt).patchConfig(cxt.mkSubContext("setup", client), configId, trial = true) { slots ->
                slots + (
                    CCT.workflowDef to slots[CCT.workflowDef].orEmpty().map { w ->
                        if (w[CCT.workflowId] != DesignDemo.requestWorkflow) return@map w
                        val def = w[CCT.definition].toJsonMapOrEmpty()
                        w + (CCT.definition to (def + (WFD.types to mapOf(dataType to mapOf(SCH.properties to emptyMap<String, Any?>())))))
                    }
                    )
            }
        }.fullMessage() shouldContain "may alter only a type's 'g-layout'"
    }

    "a workflow declared in source, or a global one, cannot be edited here, and the reason says where its copy can change" {
        fun declaredIn(owner: String, namespace: String): WfDeclared {
            val bundle = gedraConfig(LiteCxt(), "inCode984", namespace, owner) {
                workflow("inCode", WfEntry.creation) {
                    task("ask", "Ask") {
                        trait(DesignDemo.eventRequest)
                        save("make", "Make")
                    }
                }
            }
            return WfDeclared(bundle, bundle.workflows.getValue("inCode"))
        }
        val clientCxt = cxt.mkSubContext("setup", client)
        for (declared in listOf(declaredIn(client, clientNamespace(client)), declaredIn("global", "kdr.inCode984"))) {
            val refusal = DesignView.editRefusal(clientCxt, declared)
            refusal?.code shouldBe DesignRefusal.declaredInSource
            refusal?.message.orEmpty() shouldContain "shared wording"
        }
    }

    "a client with a sandbox is sent to it to edit" {
        val parent = "sandboxed984"
        val config = gedraConfig(cxt, "main", clientNamespace(parent), parent) {
            defineClient(
                ClientDef(
                    clientId = parent, name = parent, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = true,
                ),
            )
            trait("NoteEntry", "note", setOf(GedraDataType.formDoc), "A note.") { property("text", "The text.") }
            workflow("make", WfEntry.creation) {
                task("ask", "Ask") {
                    trait("note")
                    save("create", "Create")
                }
            }
        }
        val parentCxt = cxt.mkSubContext("setup", parent)
        GedraConfigService.get(cxt).writeConfig(parentCxt, config)
        // The published definition is what decides that a client has a sandbox.
        GedraConfigService.get(cxt).publish(parentCxt, GedraId.of(GedraConfigType.configDoc, parent, "main"))
        GedraConfigReload.reloadClient(cxt, parent)
        val declared = WorkflowService.get(cxt).forClient(parent).workflow("make")!!
        val refusal = DesignView.editRefusal(parentCxt, declared)
        refusal?.code shouldBe DesignRefusal.publishedOnly
        refusal?.message.orEmpty() shouldContain "previews changes in its sandbox"
    }

    "a client set to run its published configuration, with no sandbox, still edits here: the save publishes" {
        GedraConfigService.get(cxt).setPublishedOnly(cxt.mkSubContext("setup", client), client, true)
        try {
            block(requestView())[DSV.canEdit] shouldBe true
            edit(dataType, DesignDemo.title, mapOf(SL.label to "Published at once"))
            // Live on the page, because the save published it -- as a Clients page edit of this client would.
            label(requestView(), dataType, DesignDemo.title) shouldBe "Published at once"
            edit(dataType, DesignDemo.title, null)
        } finally {
            GedraConfigService.get(cxt).setPublishedOnly(cxt.mkSubContext("setup", client), client, false)
        }
    }

    "a configuration carrying somebody's unpublished changes is refused, and the block says why" {
        // An unpublished change, made beside Design View: a save here would publish it too (issue #1026).
        val configId = GedraId.of(GedraConfigType.configDoc, client, DesignDemo.configName)
        GedraConfigService.get(cxt).patchConfig(cxt.mkSubContext("setup", client), configId) { it }
        try {
            block(requestView())[DSV.canEdit] shouldBe false
            block(requestView())[DSV.editRefusalCode] shouldBe DesignRefusal.unpublishedChanges.name
            admin.expectError(400, DSV.layoutEntryEdit, editArgs(dataType, DesignDemo.title, mapOf(SL.label to "Nope")))
                .toString() shouldContain "has unpublished changes"
        } finally {
            GedraConfigService.get(cxt).publish(cxt.mkSubContext("setup", client), configId)
        }
        block(requestView())[DSV.canEdit] shouldBe true
        block(requestView()).containsKey(DSV.editRefusalCode) shouldBe false
    }

    // The workflow copy editor's form requirements (issue #1048): saved into the workflow's own entry, beside its copy.
    "a form requirement saved from the editor holds on that workflow's save, and reset removes it" {
        val save = clientPath(GEP.workflowSave, client)
        fun request(vararg data: Pair<String, Any?>) = mapOf(
            WFD.workflowId to DesignDemo.requestWorkflow, GDF.taskId to DesignDemo.describeTask, GDF.saveId to DesignDemo.submitSave,
            GDF.entries to listOf(mapOf("traitId" to DesignDemo.eventRequest, "data" to mapOf(DesignDemo.title to "Picnic", *data))),
        )
        edit(dataType, DesignDemo.catering, mapOf(SL.required to true))
        edit(dataType, DesignDemo.venue, mapOf(SL.label to "Venue", SL.choices to listOf(mapOf(SL.value to "office"), mapOf(SL.value to "hotel", SL.label to "A conference hotel"))))
        val fields = requestView()["fieldLayouts"].toJsonMapOrEmpty()[dataType].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps()
        fields.single { it[SL.field] == DesignDemo.catering }[SL.required] shouldBe true
        fields.single { it[SL.field] == DesignDemo.venue }[SL.choices].toJsonListOfMaps().map { it[SL.value] } shouldBe listOf("office", "hotel")

        admin.expectError(400, save, request(DesignDemo.venue to "office")).toString() shouldContain "catering"
        admin.expectError(400, save, request(DesignDemo.venue to "outdoors", DesignDemo.catering to true, DesignDemo.backupPlan to "Tents"))
            .toString() shouldContain "not offered on this form"
        admin.postData(save, request(DesignDemo.venue to "hotel", DesignDemo.catering to false))[WSF.saved] shouldBe true

        edit(dataType, DesignDemo.catering, null)
        edit(dataType, DesignDemo.venue, null)
        admin.postData(save, request(DesignDemo.venue to "outdoors", DesignDemo.backupPlan to "Tents"))[WSF.saved] shouldBe true
    }

    // An administrator of every client opening another client's form designs it in that client (the client is named,
    // since their own -- `hub` -- holds none of its definitions); a client's own administrator may not name another.
    "an administrator of every client designs another client's form by naming it, and nobody else may" {
        val everyClient = TestUser.createFullAdmin(cxt, "every@hub1048.test")
        val read = everyClient.getItem(DSV.definition, mapOf(DSV.slot to CCT.traitDef, DSV.key to DesignDemo.eventRequest, DSV.client to client))
        read[DSV.entry].toJsonMapOrEmpty()[CCT.traitId] shouldBe DesignDemo.eventRequest
        everyClient.postData(DSV.layoutEntryEdit, editArgs(dataType, DesignDemo.title, mapOf(SL.label to "Named from the hub")) + (DSV.client to client))
        label(requestView(), dataType, DesignDemo.title) shouldBe "Named from the hub"
        edit(dataType, DesignDemo.title, null)

        admin.expectError(403, DSV.definition, args = mapOf(DSV.slot to CCT.traitDef, DSV.key to DesignDemo.eventRequest, DSV.client to "hub"))
    }
})
