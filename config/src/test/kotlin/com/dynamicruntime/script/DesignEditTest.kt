package com.dynamicruntime.script

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.DSV
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
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Editing a form's copy as a workflow variant from Design View (issue #984): the edit endpoint, the workflow's own
 * wording delivered on its pages only, the Design View block's account of it, reset, the stale-edit refusal, the
 * stale marker, and the load checks on a workflow's type alterations. Over the demo client, whose two workflows
 * collect the same trait -- which is what makes "this workflow only" visible.
 */
class DesignEditTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("designEdit984", "designEdit984")
    val client = DesignDemo.client
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client), designDemoConfig(cxt))
    GedraConfigReload.reloadClient(cxt, client)
    val admin = TestUser.create(cxt, "designer@$client.test", level = ROLE.admin, userClient = client)
    val design = mapOf(EP.view to DSV.design)
    val viewPath = clientPath(GEP.workflowView, client)

    // A form, so the survey workflow -- the second over the same trait -- has a view to compare against.
    val formId = admin.postData(
        clientPath(GEP.workflowSave, client),
        mapOf(
            WFD.workflowId to DesignDemo.requestWorkflow, GDF.taskId to DesignDemo.describeTask, GDF.saveId to "submit",
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
    fun edit(type: String, field: String, entry: Map<String, Any?>?, basedOn: String = block(requestView())[DSV.basedOn].toOptStr()!!) =
        admin.postData(
            DSV.layoutEntryEdit,
            buildMap {
                put(DSV.workflowId, DesignDemo.requestWorkflow)
                put(DSV.typeName, type)
                put(DSV.field, field)
                entry?.let { put(DSV.entry, it) }
                put(DSV.basedOn, basedOn)
            },
        )

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
        label(reviewView(), dataType, DesignDemo.title) shouldBe "What is the event?"
        val facts = block(requestView())[DSV.layoutEdits].toJsonMapOrEmpty()[dataType].toJsonMapOrEmpty()[DesignDemo.title]
            .toJsonMapOrEmpty()
        facts[DSV.entry].toJsonMapOrEmpty()[SL.label] shouldBe "Name the event"
        facts[DSV.inherited].toJsonMapOrEmpty()[SL.label] shouldBe "What is the event?"
        facts[DSV.inheritedChanged] shouldBe false
    }

    "a field of a referenced type is altered the same way, by naming that type" {
        edit(contactType, DesignDemo.contactEmail, mapOf(SL.label to "Email for the organizers"))
        label(requestView(), contactType, DesignDemo.contactEmail) shouldBe "Email for the organizers"
        label(reviewView(), contactType, DesignDemo.contactEmail) shouldBe "Contact email"
    }

    "an edit based on a definition that has since changed is refused" {
        val stale = block(requestView())[DSV.basedOn].toOptStr()!!
        edit(dataType, DesignDemo.venue, mapOf(SL.label to "Where"))
        admin.expectError(
            409, DSV.layoutEntryEdit,
            mapOf(
                DSV.workflowId to DesignDemo.requestWorkflow, DSV.typeName to dataType, DSV.field to DesignDemo.venue,
                DSV.entry to mapOf(SL.label to "Somewhere"), DSV.basedOn to stale,
            ),
        )
        label(requestView(), dataType, DesignDemo.venue) shouldBe "Where"
    }

    "an entry the layout checks refuse is not stored" {
        admin.expectError(
            400, DSV.layoutEntryEdit,
            mapOf(
                DSV.workflowId to DesignDemo.requestWorkflow, DSV.typeName to dataType, DSV.field to "noSuchField",
                DSV.entry to mapOf(SL.label to "Nothing"), DSV.basedOn to block(requestView())[DSV.basedOn],
            ),
        )
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

    "a client that runs its published configuration cannot edit here, and says why" {
        GedraConfigService.get(cxt).setPublishedOnly(cxt.mkSubContext("setup", client), client, true)
        try {
            block(requestView())[DSV.canEdit] shouldBe false
            val refused = admin.expectError(
                400, DSV.layoutEntryEdit,
                mapOf(
                    DSV.workflowId to DesignDemo.requestWorkflow, DSV.typeName to dataType, DSV.field to DesignDemo.title,
                    DSV.entry to mapOf(SL.label to "Nope"), DSV.basedOn to block(requestView())[DSV.basedOn],
                ),
            )
            refused.toString() shouldContain "runs its published configuration"
        } finally {
            GedraConfigService.get(cxt).setPublishedOnly(cxt.mkSubContext("setup", client), client, false)
        }
        block(requestView())[DSV.canEdit] shouldNotBe false
    }
})
