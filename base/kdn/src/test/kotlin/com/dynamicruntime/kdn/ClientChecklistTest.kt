package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CHKL
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CWF
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFT
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfTaskSlot
import com.dynamicruntime.common.gedra.workflow.WfTemplateSlots
import com.dynamicruntime.common.gedra.workflow.WfTraitRef
import com.dynamicruntime.common.home.HCARD
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * A new client's checklist (issue #1639): each step checked off from the client's state as the client changes, from
 * inside its sandbox the parent's, and the card offered to its administrator outside the sandbox only. Over a person's
 * own client on the shared instance.
 */
class ClientChecklistTest : StringSpec({
    val cxt = TestInstances.default("checklist1639")

    /** [user]'s checklist, each step's id to whether it is done. */
    fun checklistOf(user: TestUser): Map<Any?, Any?> =
        user.getItem(UADEP.clientChecklist)[CHKL.steps].toJsonListOfMaps().associate { it[CHKL.id] to it[CHKL.done] }

    fun cardIds(user: TestUser): List<Any?> =
        user.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()[HCARD.cards].toJsonListOfMaps().map { it[HFLD.id] }

    "each step is checked off as the client changes" {
        val founder = TestUser.create(cxt, "founder@check1639.test")
        val client = founder.postData(AEP.createOwnClient, mapOf(CLD.name to "Checklist"))[CLD.client].toOptStr().shouldNotBeNull()
        founder.getItem(UADEP.clientChecklist)[CLD.client] shouldBe client
        // Nothing yet, in the order the card lists them: the starters it was made with are not its own work.
        checklistOf(founder) shouldBe linkedMapOf(
            CHKL.newForm to false, CHKL.workflow to false, CHKL.invite to false, CHKL.firstForm to false,
        )
        cardIds(founder) shouldContain HCARD.checklist

        // Someone besides its founder.
        TestUser.create(cxt, "colleague@check1639.test", userClient = client)
        checklistOf(founder)[CHKL.invite] shouldBe true

        // A first form, through the New form it starts with.
        val catalog = founder.getItem(UADEP.clientWorkflows)[CWF.workflows].toJsonListOfMaps()
        val creation = catalog.first { it[CWF.definition].toJsonMapOrEmpty()[WFD.entry] == WfEntry.creation.name }
        val creationId = creation[CWF.definition].toJsonMapOrEmpty()[WFD.workflowId].toOptStr().shouldNotBeNull()
        founder.postData(
            GEP.workflowSave,
            mapOf(
                WFD.workflowId to creationId, GDF.taskId to "identify", GDF.saveId to "create",
                GDF.entries to listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.nameField to "First"))),
            ),
        )
        checklistOf(founder)[CHKL.firstForm] shouldBe true

        // A workflow of its own, made from a template.
        val collect = WfTemplateSlots(null, WFT.collect, "Feedback", listOf(WfTaskSlot("collect", "Info", listOf(WfTraitRef(GT.name)))))
        founder.postData(UADEP.clientWorkflowSave, mapOf(CWF.slots to collect.toInfo()))
        checklistOf(founder)[CHKL.workflow] shouldBe true
        // Which is not its New form.
        checklistOf(founder)[CHKL.newForm] shouldBe false

        // Its New form changed since the starter: a later revision of the configuration holding it.
        val configName = creation[CWF.configName].toOptStr().shouldNotBeNull()
        val bundle = founder.getItem(CFEP.bundle, mapOf(CFEP.name to configName))
        val slots = bundle[CFEP.slots].toJsonMapOrEmpty().mapValues { (slot, entries) ->
            if (slot != CCT.workflowDef) entries
            else entries.toJsonListOfMaps().map { entry ->
                entry + (CCT.definition to entry[CCT.definition].toJsonMapOrEmpty() + (WFD.label to "Intake"))
            }
        }
        founder.postData(
            CFEP.bundleWrite,
            mapOf(CFEP.name to configName, CFEP.namespaceField to bundle[CFEP.namespaceField], CFEP.slots to slots),
        )
        checklistOf(founder) shouldBe linkedMapOf(
            CHKL.newForm to true, CHKL.workflow to true, CHKL.invite to true, CHKL.firstForm to true,
        )
    }

    "from inside the sandbox it is the client's, and Home leaves it to the client" {
        val founder = TestUser.create(cxt, "sandboxed@check1639.test")
        val client = founder.postData(AEP.createOwnClient, mapOf(CLD.name to "Sandboxed"))[CLD.client].toOptStr().shouldNotBeNull()
        founder.postData(AEP.openSandbox, emptyMap())
        founder.getItem(UADEP.clientChecklist)[CLD.client] shouldBe client
        cardIds(founder) shouldNotContain HCARD.checklist
    }

    "only a client's administrator may ask, and only a self-serve client's is offered the card" {
        TestUser.create(cxt, "member@check1639.test").expectError(EXC.notAuthorized, UADEP.clientChecklist)
        val hubAdmin = TestUser.create(cxt, "admin@check1639.test", level = ROLE.admin)
        cardIds(hubAdmin) shouldNotContain HCARD.checklist
    }
})
