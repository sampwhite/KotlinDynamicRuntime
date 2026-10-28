package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.TraitLockCopy
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.gedra.workflow.WfParams
import com.dynamicruntime.common.gedra.workflow.WfVisibility
import com.dynamicruntime.common.gedra.workflow.WfWindow
import com.dynamicruntime.common.gedra.workflow.WfWindows
import com.dynamicruntime.common.gedra.workflow.WorkflowParamsService
import com.dynamicruntime.common.gedra.workflow.computeCFactsFromData
import com.dynamicruntime.common.gedra.workflow.userHasLabel
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours

/**
 * Forms kept to their owner (issue #1433): a client's workflow maps one of its own cfacts onto `kdr:ownerOnly`, and
 * while a form carries it an editor (#1432) may change it only as its owner or as a reviewer of the workflow's current
 * task. Over the shared instance, with a client of its own.
 */
class OwnerOnlyTest : StringSpec({
    val cxt = TestInstances.default("ownerOnly1433")
    val client = "owneronly1433"
    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.customer,
                enabledEnvironments = setOf(ENV.unit, ENV.local), userLabels = listOf("reviewer", "judge"),
            ),
        )
        cfact("keptClosed", "Privacy", "The note asks to be kept to its owner.")
        cfact("noteApproved", "Privacy", "A reviewer approved the note.")
        trait("NoteEntry", "note", setOf(GedraDataType.formDoc), "A private note.") {
            property("text", "What the note says.") { type = SCT.string }
            property("kept", "Whether it is kept to its owner: yes or no.") { type = SCT.string }
        }
        trait("VerdictEntry", "verdict", setOf(GedraDataType.formDoc), "A judge's verdict on the note.") {
            property("ruling", "The ruling.") { type = SCT.string }
        }
        // The survey may put the fact on a form with no workflow at all (issue #1452), from a note that says "always".
        workflow("noteSurvey", WfEntry.survey) {
            function(computeCFactsFromData {
                trait = "note"
                valuePath = "kept"
                map("always", WSC.ownerOnly)
            })
            task("fill", "Fill in the note") { trait("note"); save("saveFill", "Save", WfSaveKind.edit) }
        }
        workflow("privateReview", WfEntry.normal) {
            label = "Private review"
            // Kept to its owner while the note says so -- data-driven, so a form with the fact off is one word away.
            function(computeCFactsFromData {
                trait = "note"
                valuePath = "kept"
                map("yes", "keptClosed")
            })
            singleton(WSC.ownerOnly, "keptClosed")
            task("write", "Write the note") { trait("note"); save("saveNote", "Save", WfSaveKind.edit) }
            task("approve", "Approve") {
                approval("noteApproved", "Read it.", "Approve")
                function(userHasLabel { label = "reviewer" })
            }
            // A judge's step whose trait is locked to it, so a kept-out editor meets both a trait lock and the owner-only
            // rule -- and is told the broader one. Optional, so it never holds the current task away from the approval.
            task("rule", "Rule on it") {
                trait("verdict", required = false)
                save("saveVerdict", "Save", WfSaveKind.edit)
                function(userHasLabel { label = "judge" })
                saveWhen(WFC.reviewer)
            }
            lock("verdict", writableVia = "rule")
        }
    }
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 14330L }, config)
    GedraConfigReload.reloadClient(cxt, client)

    val owner = TestUser.create(cxt, "owner@$client.test", userClient = client)
    val editor = TestUser.create(cxt, "editor@$client.test", capabilities = listOf(ROLE.editor), userClient = client)
    val reviewer = TestUser.create(cxt, "reviewer@$client.test", capabilities = listOf(ROLE.editor), userClient = client)
    val admin = TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client)
    admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to reviewer.userId, ADF.labels to listOf("reviewer")))

    fun noteEntry(text: String, kept: String) = mapOf(GE.traitId to "note", GE.data to mapOf("text" to text, "kept" to kept))

    /** A form of the owner's, engaged with the review, kept to its owner unless [kept] says otherwise. */
    fun engagedForm(kept: String = "yes"): String {
        val gid = owner.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(noteEntry("draft", kept))))[GDF.gedraId].toOptStr()!!
        owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, GDF.workflowId to "privateReview"))
        return gid
    }

    fun patchBody(gid: String, text: String, kept: String = "yes") = mapOf(
        GPF.targets to mapOf(
            GedraDataType.formDoc.name to listOf(
                mapOf(
                    GDF.gedraId to gid,
                    GPF.edits to listOf(
                        mapOf(GED.action to GedraEditAction.addOrReplace.name) + noteEntry(text, kept),
                    ),
                ),
            ),
        ),
    )

    fun saveBody(gid: String, text: String) = mapOf(
        GDF.workflowId to "privateReview", GDF.taskId to "write", GDF.saveId to "saveNote", GDF.gedraId to gid,
        GDF.entries to listOf(noteEntry(text, "yes")),
    )

    fun textOf(gid: String): Any? = owner.getItem(GEP.formDoc, mapOf(GDF.gedraId to gid))[GDF.entries]
        .toJsonListOfMaps().single { it[GE.traitId] == "note" }[GE.data].toJsonMapOrEmpty()["text"]

    fun refusal(user: TestUser, endpoint: String, body: Map<String, Any?>) =
        user.expectError(EXC.notAuthorized, endpoint, body)["errorMessage"].toOptStr()

    fun keptFor(user: TestUser, gid: String): Boolean =
        user.getData(GEP.formDocLocks, mapOf(GDF.gedraId to gid))[WVF.keptToOwner] == true

    "an editor who is neither owner nor reviewer changes a kept form by no path" {
        val gid = engagedForm()
        refusal(editor, GEP.patch, patchBody(gid, "Changed by the editor")) shouldBe TraitLockCopy.keptToOwner
        refusal(editor, GEP.workflowSave, saveBody(gid, "Saved by the editor")) shouldBe TraitLockCopy.keptToOwner
        // Nor is the form taken out of the workflow that keeps it so, which would be a way around the rule.
        refusal(
            editor, GEP.workflowEngage, mapOf(GDF.gedraId to gid, GDF.workflowId to "privateReview", WFS.engaged to false),
        ) shouldBe TraitLockCopy.keptToOwner
        textOf(gid) shouldBe "draft"
        // It still reads it.
        editor.getItem(GEP.formDoc, mapOf(GDF.gedraId to gid))[GDF.gedraId] shouldBe gid
    }

    "the owner, a reviewer of the current task, and an administrator may change it" {
        val gid = engagedForm()
        owner.postItems(GEP.patch, patchBody(gid, "By the owner"))
        textOf(gid) shouldBe "By the owner"
        // The form waits on its approval, so whoever holds the reviewer label is a reviewer of its current work.
        reviewer.postItems(GEP.patch, patchBody(gid, "By the reviewer"))
        reviewer.postData(GEP.workflowSave, saveBody(gid, "Saved by the reviewer"))
        textOf(gid) shouldBe "Saved by the reviewer"
        admin.postItems(GEP.patch, patchBody(gid, "By the administrator"))
        textOf(gid) shouldBe "By the administrator"
    }

    "a kept-out editor is told the whole form is closed to them, not that one trait is locked" {
        val gid = engagedForm()
        val verdict = mapOf(
            GPF.targets to mapOf(
                GedraDataType.formDoc.name to listOf(
                    mapOf(
                        GDF.gedraId to gid,
                        GPF.edits to listOf(
                            mapOf(GED.action to GedraEditAction.addOrReplace.name, GE.traitId to "verdict", GE.data to mapOf("ruling" to "No")),
                        ),
                    ),
                ),
            ),
        )
        refusal(editor, GEP.patch, verdict) shouldBe TraitLockCopy.keptToOwner
        // The owner, whom the rule admits, still meets the trait's own lock.
        owner.expectError(EXC.conflict, GEP.patch, verdict)
    }

    "once the workflow moves past the reviewer's task, the reviewer is kept out again" {
        val gid = engagedForm()
        reviewer.postData(GEP.workflowApprove, mapOf(GDF.gedraId to gid, GDF.workflowId to "privateReview", GDF.taskId to "approve"))
        refusal(reviewer, GEP.patch, patchBody(gid, "After approval")) shouldBe TraitLockCopy.keptToOwner
        keptFor(reviewer, gid) shouldBe true
    }

    "with the fact off, an editor edits the form as any other" {
        val gid = engagedForm(kept = "no")
        editor.postItems(GEP.patch, patchBody(gid, "Open to the editor", kept = "no"))
        textOf(gid) shouldBe "Open to the editor"
        keptFor(editor, gid) shouldBe false
    }

    "the locks answer and the workflow view draw the form read-only for exactly those the guard refuses" {
        val gid = engagedForm()
        keptFor(editor, gid) shouldBe true
        keptFor(owner, gid) shouldBe false
        keptFor(reviewer, gid) shouldBe false
        keptFor(admin, gid) shouldBe false
        val view = editor.getData(GEP.workflowView, mapOf(GDF.workflowId to "privateReview", GDF.gedraId to gid))
        view[WVF.keptToOwner] shouldBe true
        view[WFD.tasks].toJsonListOfMaps().map { it[WVF.canSave] } shouldBe listOf(false, false, false)
        owner.getData(GEP.workflowView, mapOf(GDF.workflowId to "privateReview", GDF.gedraId to gid))[WVF.keptToOwner] shouldBe null
    }

    "an editor deletes another user's form only while it is in no workflow (issue #1452)" {
        val open = engagedForm(kept = "no")
        editor.expectError(EXC.notAuthorized, GEP.formDoc, args = mapOf(GDF.gedraId to open), method = HttpMethod.DELETE)[
            "errorMessage"].toOptStr() shouldBe "This form is in a workflow, so only its owner or an administrator may delete it."
        // Withdrawn by its owner, it is in no workflow, and the editor deletes it.
        owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to open, GDF.workflowId to "privateReview", WFS.engaged to false))
        editor.deleteData(GEP.formDoc, mapOf(GDF.gedraId to open))[GDF.gedraId] shouldBe open
    }

    // A client of its own for the workflows whose parameters these cases change, so the review above is untouched.
    val other = "editordel1452"
    val setup = cxt.mkSubContext("setup", other).also { it.userId = 14520L }
    GedraConfigService.get(cxt).writeConfig(
        setup,
        gedraConfig(cxt, "${other}cfg", clientNamespace(other), other) {
            defineClient(
                ClientDef(
                    clientId = other, name = other, usageType = ClientUsageType.dev, audience = ClientAudience.customer,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            trait("BriefEntry", "brief", setOf(GedraDataType.formDoc), "A brief.") {
                property("text", "What the brief says.") { type = SCT.string }
            }
            workflow("hidden", WfEntry.normal) { task("read", "Read it") { trait("brief"); save("go", "Save", WfSaveKind.edit) } }
            workflow("ended", WfEntry.normal) { task("read", "Read it") { trait("brief"); save("go", "Save", WfSaveKind.edit) } }
        },
    )
    GedraConfigReload.reloadClient(cxt, other)
    val otherOwner = TestUser.create(cxt, "owner@$other.test", userClient = other)
    val otherEditor = TestUser.create(cxt, "editor@$other.test", capabilities = listOf(ROLE.editor), userClient = other)
    val otherAdmin = TestUser.create(cxt, "admin@$other.test", level = ROLE.admin, userClient = other)
    fun brief(): String = otherOwner.postItem(
        GEP.formDocCreate, mapOf(GDF.entries to listOf(mapOf(GE.traitId to "brief", GE.data to mapOf("text" to "b")))),
    )[GDF.gedraId].toOptStr()!!

    "a workflow the editor does not see still holds the form, and the refusal does not name it (issue #1452 review)" {
        WorkflowParamsService.get(cxt).save(setup, other, "hidden", WfParams(visibility = WfVisibility.adminsOnly))
        val gid = brief()
        otherAdmin.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, GDF.workflowId to "hidden"))
        otherEditor.expectError(EXC.notAuthorized, GEP.formDoc, args = mapOf(GDF.gedraId to gid), method = HttpMethod.DELETE)[
            "errorMessage"].toOptStr() shouldBe "Only this form's owner or an administrator may delete it."
    }

    "an engagement with a workflow past its lifetime holds no work, so the editor deletes the form (issue #1452 review)" {
        val gid = brief()
        otherOwner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, GDF.workflowId to "ended"))
        otherEditor.expectError(EXC.notAuthorized, GEP.formDoc, args = mapOf(GDF.gedraId to gid), method = HttpMethod.DELETE)
        // Its lifetime closed an hour ago: the engagement stays stored, but nothing is under way.
        val now = cxt.instanceNow()
        WorkflowParamsService.get(cxt).save(
            setup, other, "ended", WfParams(WfWindows(lifetime = WfWindow(now - 2.hours, now - 1.hours))),
        )
        otherEditor.deleteData(GEP.formDoc, mapOf(GDF.gedraId to gid))[GDF.gedraId] shouldBe gid
    }

    "a form kept to its owner with no workflow is still not an editor's to delete" {
        val gid = owner.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(noteEntry("private", "always"))))[GDF.gedraId].toOptStr()!!
        // The survey put the fact on it, with no workflow engaged: the delete rule lets the editor through, and the owner-
        // only rule, which does not ask about workflows, still keeps it out.
        editor.getData(GEP.formDocLocks, mapOf(GDF.gedraId to gid))[WVF.keptToOwner] shouldBe true
        editor.expectError(EXC.notAuthorized, GEP.formDoc, args = mapOf(GDF.gedraId to gid), method = HttpMethod.DELETE)[
            "errorMessage"].toOptStr() shouldBe TraitLockCopy.keptToOwner
        owner.deleteData(GEP.formDoc, mapOf(GDF.gedraId to gid))[GDF.gedraId] shouldBe gid
    }

    "an editor's own kept form is its own to change and delete" {
        val gid = editor.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(noteEntry("mine", "yes"))))[GDF.gedraId].toOptStr()!!
        editor.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, GDF.workflowId to "privateReview"))
        editor.postItems(GEP.patch, patchBody(gid, "Still mine"))
        editor.deleteData(GEP.formDoc, mapOf(GDF.gedraId to gid))[GDF.gedraId] shouldBe gid
        editor.expectError(EXC.notFound, GEP.formDoc, args = mapOf(GDF.gedraId to gid), method = HttpMethod.GET)
    }
})
