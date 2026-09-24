package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A task's rule for who may save it over the **task's own** facts (issue #856), not only the viewer's: a draft that
 * may be saved only while incomplete. The rule is judged under the form's lock on its entries as they stand at the
 * write -- so the first save (the task incomplete) goes through, and the second (now complete) is refused -- and the
 * view reports the same verdict at each step.
 */
class WorkflowSaveRuleTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("wfSaveRule856", "wfSaveRule856")
    val client = "wfsave856"
    fun asClient(c: String): KdrCxt = cxt.mkSubContext("setup", c).also { it.userId = 9000L }

    val config = gedraConfig(cxt, "${client}cfg", "${client}config", client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev,
                audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        trait("NoteEntry", "note", setOf(GedraDataType.formDoc), "A note.") {
            property("text", "What it says.") { type = SCT.string }
        }
        workflow("notes", WfEntry.normal) {
            task("draft", "Draft it") {
                trait("note")
                save("saveDraft", "Save", WfSaveKind.edit)
                // Only while the draft is not yet written: a fact about the task, judged from the form's entries.
                saveWhen("~${WFC.taskComplete}")
            }
            // A save rule needs its lock (issue #857), or the raw editor would write the note the rule refuses.
            lock("note", writableVia = "draft")
        }
    }
    GedraConfigService.get(cxt).writeConfig(asClient(client), config)
    GedraConfigReload.reloadClient(cxt, client)

    val user = TestUser.create(cxt, "u@$client.test", userClient = client)

    "a save rule with no lock on its trait is refused before it goes live (issue #857)" {
        val unlocked = "wfsave857"
        val bad = gedraConfig(cxt, "${unlocked}cfg", "${unlocked}config", unlocked) {
            defineClient(
                ClientDef(
                    clientId = unlocked, name = unlocked, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            trait("DraftEntry", "draftNote", setOf(GedraDataType.formDoc), "A draft note.") {
                property("text", "What it says.") { type = SCT.string }
            }
            workflow("notes", WfEntry.normal) {
                task("draft", "Draft it") {
                    trait("draftNote")
                    save("saveDraft", "Save", WfSaveKind.edit)
                    saveWhen("~${WFC.taskComplete}")
                }
            }
        }
        GedraConfigService.get(cxt).writeConfig(asClient(unlocked), bad)
        // A unit instance checks stored config strictly, as a developer's does source config: the reload is refused,
        // naming the task and the unlocked trait, and nothing of the client goes live.
        shouldThrow<KdrException> { GedraConfigReload.reloadClient(cxt, unlocked) }.message.orEmpty().let {
            it shouldContain "restricts who may save task 'draft'"
            it shouldContain "no lock covers its trait(s) 'draftNote'"
        }
        WorkflowService.get(cxt).forClient(unlocked).workflows.containsKey("notes") shouldBe false
    }

    "a rule over the task's own facts is judged on the form as it stands at each save" {
        val gid = user.postItem(GEP.formDocCreate, mapOf(GDF.entries to emptyList<Any?>()))[GDF.gedraId].toOptStr()!!
        user.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to "notes"))
        fun canSave() = user.getData(GEP.workflowView, mapOf(WFD.workflowId to "notes", GDF.gedraId to gid))[WFD.tasks]
            .toJsonListOfMaps().single()[WVF.canSave]
        val body = mapOf(
            WFD.workflowId to "notes", GDF.taskId to "draft", GDF.saveId to "saveDraft", GDF.gedraId to gid,
            GDF.entries to listOf(mapOf(GE.traitId to "note", GE.data to mapOf("text" to "first"))),
        )

        canSave() shouldBe true
        user.postData(GEP.workflowSave, body)[WSF.saved] shouldBe true
        // Now complete: the same rule refuses, and the view says so first.
        canSave() shouldBe false
        user.expectError(EXC.notAuthorized, GEP.workflowSave, body)
    }
})
