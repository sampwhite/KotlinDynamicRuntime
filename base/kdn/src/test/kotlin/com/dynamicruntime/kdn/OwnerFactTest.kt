package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.RCF
import com.dynamicruntime.common.gedra.workflow.RowCfacts
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.ctaFilterByValue
import com.dynamicruntime.common.gedra.workflow.userHasLabel
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * `kdr:isOwner` (issue #1595), the fact about the caller's relation to a form: whether they own it, worked out from the
 * form's owner as it is read. A task's `saveWhen`, a CTA filter, and an approval's `approveWhen` may name it, so a step
 * can be the owner's turn; and the second-person rule is now the default approval rule, `kdr:reviewer,~kdr:isOwner`.
 *
 * Over a client of its own. Its `respond` is saved by the form's owner alone; `turns` waits on the owner when the site
 * says it is their turn; `confirm` is an approval the owner gives; and `review` an approval with the default rule.
 */
class OwnerFactTest : StringSpec({
    val cxt = TestInstances.default("ownerFact1595")
    val client = "isowner1595"
    val svc = GedraConfigService.get(cxt)

    fun GedraConfigBuilder.ownerClient(id: String) {
        defineClient(
            ClientDef(
                clientId = id, name = id, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local), userLabels = listOf("reviewer"),
            ),
        )
        cfact("confirmed", "Review", "The owner confirmed the details.")
        cfact("reviewed", "Review", "A reviewer approved the form.")
        trait("SiteEntry", "site", setOf(GedraDataType.formDoc), "The site.") { property("turn", "Whose turn it is.") }
        trait("ResponseEntry", "response", setOf(GedraDataType.formDoc), "The owner's response.") { property("text", "The text.") }
        trait("NotesEntry", "notes", setOf(GedraDataType.formDoc), "Notes.") { property("text", "The notes.") }
    }

    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        ownerClient(client)
        workflow("respond", WfEntry.normal) {
            task("respond", "Respond") {
                trait("response")
                save("saveResponse", "Save", WfSaveKind.edit)
                saveWhen(RCF.isOwner)
            }
            lock("response", writableVia = "respond")
        }
        workflow("turns", WfEntry.normal) {
            task("answer", "Answer") {
                trait("notes")
                save("saveNotes", "Save", WfSaveKind.edit)
                function(
                    ctaFilterByValue {
                        trait = "site"
                        valuePath = "turn"
                        case("owner", RCF.isOwner, "Waiting on the form's owner")
                        otherwise("#always")
                    },
                )
            }
        }
        workflow("confirm", WfEntry.normal) {
            task("confirm", "Confirm the details") { approval("confirmed", "Are these details right?", "Confirm", approveWhen = RCF.isOwner) }
        }
        workflow("review", WfEntry.normal) {
            task("review", "Review") {
                approval("reviewed", "Approve the form?", "Approve")
                function(userHasLabel { label = "reviewer" })
            }
        }
    }
    svc.writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 15951L }, config)
    GedraConfigReload.reloadClient(cxt, client)

    val owner = TestUser.create(cxt, "owner@$client.test", userClient = client)
    // Another user of the owner's own address: on a test instance a second person, so one tester can play both parts.
    val ownersEditor = TestUser.create(
        cxt, "owner@$client.test", capabilities = listOf(ROLE.editor), userClient = client, persona = "editor",
    )
    val editor = TestUser.create(cxt, "editor@$client.test", capabilities = listOf(ROLE.editor), userClient = client)
    val admin = TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client)
    fun label(user: TestUser) = admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to user.userId, ADF.labels to listOf("reviewer")))

    /** A form of the owner's whose site says it is [turn]'s turn, engaged with [workflowId]. */
    fun form(workflowId: String, turn: String = "owner"): String {
        val site = mapOf(GE.traitId to "site", GE.data to mapOf("turn" to turn))
        val gid = owner.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(site)))[GDF.gedraId].toOptStr()!!
        owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to workflowId))
        return gid
    }

    fun taskView(viewer: TestUser, gid: String, workflowId: String, taskId: String): Map<String, Any?> =
        viewer.getData(GEP.workflowView, mapOf(GDF.gedraId to gid, WFD.workflowId to workflowId))[WFD.tasks]
            .toJsonListOfMaps().single { it[WFD.id] == taskId }

    fun save(gid: String, workflowId: String, taskId: String, saveId: String, traitId: String) = mapOf(
        WFD.workflowId to workflowId, GDF.taskId to taskId, GDF.saveId to saveId, GDF.gedraId to gid,
        GDF.entries to listOf(mapOf(GE.traitId to traitId, GE.data to mapOf("text" to "Done."))),
    )

    fun approve(gid: String, workflowId: String, taskId: String) =
        mapOf(GDF.gedraId to gid, GDF.workflowId to workflowId, GDF.taskId to taskId)

    "a task saved only by kdr:isOwner is the owner's: no editor, administrator, or other user of the owner's may save it" {
        val gid = form("respond")
        val respond = save(gid, "respond", "respond", "saveResponse", "response")
        for (other in listOf(editor, admin, ownersEditor)) {
            taskView(other, gid, "respond", "respond")[WVF.canSave] shouldBe false
            other.expectError(EXC.notAuthorized, GEP.workflowSave, respond)
        }
        taskView(owner, gid, "respond", "respond")[WVF.canSave] shouldBe true
        owner.postData(GEP.workflowSave, respond)
    }

    "a CTA filter of kdr:isOwner keeps the step to the owner, and tells anyone else whom it waits on" {
        val gid = form("turns")
        val answer = save(gid, "turns", "answer", "saveNotes", "notes")
        taskView(editor, gid, "turns", "answer").let {
            it[WVF.canSave] shouldBe false
            it[WVF.waitingOn] shouldBe "Waiting on the form's owner"
        }
        editor.expectError(EXC.notAuthorized, GEP.workflowSave, answer).toString() shouldContain "Waiting on the form's owner"
        owner.postData(GEP.workflowSave, answer)
        // When it is not the owner's turn, the filter admits the editor as it would anyone.
        val open = form("turns", turn = "anyone")
        taskView(editor, open, "turns", "answer")[WVF.canSave] shouldBe true
        editor.postData(GEP.workflowSave, save(open, "turns", "answer", "saveNotes", "notes"))
    }

    "an approval whose rule is kdr:isOwner is the owner's to give, and needs no reviewer" {
        val gid = form("confirm")
        taskView(editor, gid, "confirm", "confirm")[WVF.canApprove] shouldBe false
        editor.expectError(EXC.notAuthorized, GEP.workflowApprove, approve(gid, "confirm", "confirm")).toString() shouldContain
            "You may not approve task 'confirm'"
        taskView(owner, gid, "confirm", "confirm")[WVF.canApprove] shouldBe true
        owner.postData(GEP.workflowApprove, approve(gid, "confirm", "confirm"))
    }

    "the default approval rule is the second-person rule: the owner may not approve, even as a reviewer" {
        label(owner)
        label(editor)
        val gid = form("review")
        taskView(owner, gid, "review", "review").let {
            it[WVF.facts].toJsonListOfStrings() shouldContain WFC.reviewer
            it[WVF.canApprove] shouldBe false
        }
        owner.expectError(EXC.notAuthorized, GEP.workflowApprove, approve(gid, "review", "review")).toString() shouldContain
            "You cannot approve your own form"
        taskView(editor, gid, "review", "review")[WVF.canApprove] shouldBe true
        editor.postData(GEP.workflowApprove, approve(gid, "review", "review"))
    }

    "kdr:isOwner is the owning user on a test instance, and in production any user of the owner's person" {
        fun caller(base: KdrCxt) = base.mkSubContext("caller", client).also {
            it.userProfile = UserProfile(authId = "a@$client.test", userId = 502L, client = client, identityId = "person1")
        }
        val test = caller(cxt)
        RowCfacts.isOwner(test, 502L) shouldBe true
        // Same person, another user: on a test instance a second person, so the identity is never even read.
        RowCfacts.isOwner(test, 501L) { error("A test instance compares users alone.") } shouldBe false
        val production = caller(TestInstances.notTestInstance("ownerFactProd1595"))
        RowCfacts.isOwner(production, 501L) { "person1" } shouldBe true
        RowCfacts.isOwner(production, 501L) { "person2" } shouldBe false
        RowCfacts.isOwner(production, 501L) { null } shouldBe false
    }

    "a test naming kdr:isOwner where the form is judged whoever looks is refused when the configuration is checked" {
        fun refused(id: String, workflow: GedraConfigBuilder.() -> Unit): String {
            val bad = gedraConfig(cxt, "${id}cfg", clientNamespace(id), id) {
                ownerClient(id)
                workflow()
            }
            svc.writeConfig(cxt.mkSubContext("setup", id).also { it.userId = 15951L }, bad)
            return shouldThrow<KdrException> { GedraConfigReload.reloadClient(cxt, id) }.message.orEmpty()
        }
        val why = "a fact about the caller's relation to the form"
        refused("isownerelig1595") {
            workflow("w", WfEntry.normal) {
                eligibility("mine", RCF.isOwner, "Only the owner's forms.")
                task("t", "T") { trait("notes"); save("s", "Save", WfSaveKind.edit) }
            }
        } shouldContain why
        refused("isownersing1595") {
            workflow("w", WfEntry.normal) {
                singleton(WSC.ownerOnly, RCF.isOwner)
                task("t", "T") { trait("notes"); save("s", "Save", WfSaveKind.edit) }
            }
        } shouldContain why
        refused("isownerlock1595") {
            workflow("w", WfEntry.normal) {
                task("t", "T") { trait("notes"); save("s", "Save", WfSaveKind.edit); saveWhen(RCF.isOwner) }
                lock("notes", writableVia = "t", whenCfacts = RCF.isOwner)
            }
        } shouldContain why
    }
})
