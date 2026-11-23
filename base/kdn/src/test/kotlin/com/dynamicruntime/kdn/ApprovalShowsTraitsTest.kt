package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfDefBuilder
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.userHasLabel
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SL
import com.dynamicruntime.common.schema.refName
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * An approval that names the traits it shows (issue #1875): a review's approval showing the form's request, which no task
 * of the review collects, read-only above its prompt, with a layout of its own -- through the workflow view, the load
 * checks, and Design View's edits of that layout.
 *
 * Over a client of its own, whose `review` workflow is one approval task naming `request`, a trait the form was created
 * with. A wrong answer here fails silently -- an approval showing nothing, or showing the workflow's copy where it set
 * its own -- so each part is checked.
 */
class ApprovalShowsTraitsTest : StringSpec({
    val cxt = TestInstances.default("approvalShows1875")
    val svc = GedraConfigService.get(cxt)
    val client = "shows1875"
    val workflowId = "review"
    val taskId = "approve"

    fun GedraConfigBuilder.showsClient(id: String) {
        defineClient(
            ClientDef(
                clientId = id, name = id, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local), userLabels = listOf("reviewer"),
            ),
        )
        cfact("approved", "Review", "A reviewer approved the request.")
        trait("RequestEntry", "request", setOf(GedraDataType.formDoc), "The request.") {
            property("amount", "The amount asked for.") { type = SCT.number }
            property("purpose", "What it is for.")
        }
        trait("NotesEntry", "notes", setOf(GedraDataType.formDoc), "Notes.") { property("text", "The notes.") }
    }

    /** The review: a notes task, then the approval, which names [shown] and lays out [types] its own way. */
    fun review(shown: List<String>, types: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val def = WfDefBuilder(workflowId, WfEntry.normal).apply {
            task("note", "Note it") { trait("notes", required = false); save("s", "Save", WfSaveKind.edit) }
            task(taskId, "Approve the request") {
                approval("approved", "Read the request, then approve it.", "Approve", showTraits = shown)
                function(userHasLabel { label = "reviewer" })
            }
        }.build()
        if (types.isEmpty()) return def
        val tasks = def[WFD.tasks].toJsonListOfMaps().map { t ->
            if (t[WFD.id] != taskId) t else t + (WFD.approval to t[WFD.approval].toJsonMapOrEmpty() + (WFD.types to types))
        }
        return def + (WFD.tasks to tasks)
    }

    fun write(id: String, workflow: Map<String, Any?>) {
        val config = gedraConfig(cxt, "${id}cfg", clientNamespace(id), id) {
            showsClient(id)
            workflowFromMap(workflow)
        }
        svc.writeConfig(cxt.mkSubContext("setup", id).also { it.userId = 18751L }, config)
        GedraConfigReload.reloadClient(cxt, id)
    }

    write(client, review(listOf("request")))
    // The request's data type, by the name the client's schema gives it: what an approval's own layout is keyed by.
    val requestType = SchemaService.get(cxt).gedraTraitsFor(client).single { it.traitId == "request" }
        .dataSchema[SCH.dRef].toOptStr()!!.let { refName(it)!! }
    val laidOut = mapOf(
        requestType to mapOf(
            SCH.layout to mapOf(
                SL.label to "The application",
                SL.schemaFields to listOf(mapOf(SL.field to "amount", SL.label to "Amount asked")),
            ),
        ),
    )

    val owner = TestUser.create(cxt, "owner@$client.test", userClient = client)
    val admin = TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client)

    val request = mapOf(GE.traitId to "request", GE.data to mapOf("amount" to 12500, "purpose" to "Clear the riverbank."))
    val gid = owner.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(request)))[GDF.gedraId].toOptStr()!!
    owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to workflowId))

    fun view(viewer: TestUser, extra: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        viewer.getData(GEP.workflowView, mapOf(GDF.gedraId to gid, WFD.workflowId to workflowId) + extra)

    fun approval(view: Map<String, Any?>): Map<String, Any?> =
        view[WFD.tasks].toJsonListOfMaps().single { it[WFD.id] == taskId }[WVF.approval].toJsonMapOrEmpty()

    fun fieldLabel(layouts: Any?, field: String): String? =
        layouts.toJsonMapOrEmpty()[requestType].toJsonMapOrEmpty()[SL.schemaFields].toJsonListOfMaps()
            .firstOrNull { it[SL.field] == field }?.get(SL.label).toOptStr()

    "an approval naming a trait shows it from the form's entry, though no task of its workflow collects it" {
        val v = view(owner)
        val a = approval(v)
        // In place of tasks: the earlier notes task, which it would show by default, is not shown.
        a[WFD.showTasks].toJsonListOfMaps().shouldBeEmpty()
        val shown = a[WVF.shownTraits].toJsonListOfMaps().single()
        shown[WFD.traitId] shouldBe "request"
        // Its ref resolves against the view's own $defs, as a task's do.
        val ref = shown[WVF.schemaRef].toOptStr()!!
        (refName(ref)!! in v[SCH.dDefs].toJsonMapOrEmpty()) shouldBe true
        // And the form's entry for it, whichever workflow collected it.
        a[WVF.entries].toJsonListOfMaps().single()[GE.data].toJsonMapOrEmpty()["purpose"] shouldBe "Clear the riverbank."
        // With no layout of its own, it sends none, and the page draws with the view's.
        (WVF.fieldLayouts in a) shouldBe false
    }

    "an approval's own layout reaches only the approval" {
        write(client, review(listOf("request"), laidOut))
        val v = view(owner)
        fieldLabel(approval(v)[WVF.fieldLayouts], "amount") shouldBe "Amount asked"
        approval(v)[WVF.fieldLayouts].toJsonMapOrEmpty()[requestType].toJsonMapOrEmpty()[SL.label] shouldBe "The application"
        // The workflow's own pages are untouched.
        fieldLabel(v[WVF.fieldLayouts], "amount") shouldBe null
        write(client, review(listOf("request")))
    }

    "an approval may show only a trait its client has, and lay out only what it shows" {
        val missing = "shows1875b"
        shouldThrow<KdrException> { write(missing, review(listOf("nope"))) }.message.orEmpty() shouldContain
            "showing the trait 'nope'"
        val elsewhere = "shows1875c"
        val notesType = "client.$elsewhere.NotesData"
        shouldThrow<KdrException> {
            write(elsewhere, review(listOf("request"), mapOf(notesType to mapOf(SCH.layout to mapOf(SL.label to "Notes")))))
        }.message.orEmpty() shouldContain "an approval may alter only a type the traits it shows reach"
    }

    "Design View edits the approval's own layout of what it shows, and the block says so" {
        val design = mapOf(EP.view to DSV.design)
        // A Design View save publishes what it changes, so it starts from a published bundle, as the Clients page would.
        admin.postData(CFEP.bundlePublish, mapOf(CFEP.names to listOf("${client}cfg"), CFEP.reloadField to true))
        fun basedOn() = view(admin, design)[DSV.designBlock].toJsonMapOrEmpty()[DSV.basedOn].toOptStr()!!
        admin.postData(
            DSV.layoutEntryEdit,
            mapOf(
                DSV.workflowId to workflowId, DSV.typeName to requestType, DSV.field to "amount",
                DSV.entry to mapOf(SL.label to "Amount asked"), DSV.basedOn to basedOn(), DSV.taskId to taskId,
            ),
        )
        val v = view(admin, design)
        fieldLabel(approval(v)[WVF.fieldLayouts], "amount") shouldBe "Amount asked"
        fieldLabel(v[WVF.fieldLayouts], "amount") shouldBe null
        // Stored on the approval, not the workflow.
        val def = WorkflowService.get(cxt).forClient(client).workflow(workflowId)!!.def
        def.typeAlterations.isEmpty() shouldBe true
        (def.task(taskId)!!.approval!!.typeAlterations[requestType] != null) shouldBe true
        // The block's account of it, under the approval.
        val edits = v[DSV.designBlock].toJsonMapOrEmpty()[DSV.approvals].toJsonMapOrEmpty()[taskId].toJsonMapOrEmpty()
        edits[DSV.layoutEdits].toJsonMapOrEmpty()[requestType].toJsonMapOrEmpty().keys shouldBe setOf("amount")
        (DSV.layoutEdits in v[DSV.designBlock].toJsonMapOrEmpty()) shouldBe false
        // A task that is no approval is refused.
        admin.expectError(
            400, DSV.layoutEntryEdit,
            mapOf(
                DSV.workflowId to workflowId, DSV.typeName to requestType, DSV.field to "amount",
                DSV.entry to mapOf(SL.label to "No"), DSV.basedOn to basedOn(), DSV.taskId to "note",
            ),
        )
    }
})
