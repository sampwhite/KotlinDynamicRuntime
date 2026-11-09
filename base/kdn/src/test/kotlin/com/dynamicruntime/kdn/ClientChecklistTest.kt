package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.CL
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
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * A new client's checklist (issue #1639): each step checked off from the client's state as the client changes, from
 * inside its sandbox the parent's, and the card offered to its administrator outside the sandbox only. Over a person's
 * own client on the shared instance.
 *
 * Also, since issue #1739, the tests that were `UnpublishedDraftsTest`, each kept whole below.
 */
class ClientChecklistTest : StringSpec({
    include(clientChecklistTests())
    include(unpublishedDraftsTests())
})

/** This file's own tests, as the class describes them. */
private fun clientChecklistTests() = stringSpec {
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
        // Drawn above the introduction (issue #1723), which until the founder writes their own is the site's.
        val card = founder.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()[HCARD.cards].toJsonListOfMaps()
            .single { it[HFLD.id] == HCARD.checklist }
        card[HCARD.aboveIntro] shouldBe true

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

        // Its New form changed from the starter.
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
            mapOf(
                CFEP.name to configName, CFEP.namespaceField to bundle[CFEP.namespaceField], CFEP.slots to slots,
                CFEP.reloadField to true,
            ),
        )
        checklistOf(founder) shouldBe linkedMapOf(
            CHKL.newForm to true, CHKL.workflow to true, CHKL.invite to true, CHKL.firstForm to true,
        )

        // Changed back, it is the starter again, however many revisions it took.
        founder.postData(
            CFEP.bundleWrite,
            mapOf(
                CFEP.name to configName, CFEP.namespaceField to bundle[CFEP.namespaceField], CFEP.slots to bundle[CFEP.slots],
                CFEP.reloadField to true,
            ),
        )
        checklistOf(founder)[CHKL.newForm] shouldBe false
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
}

/**
 * The client's unpublished drafts (issue #1638), as Home's "Unpublished drafts" card reads them: a draft written is
 * counted, publishing it clears it, and from inside the sandbox the answer is the parent's. Over a person's own client,
 * which has a sandbox, on the shared instance.
 */
private fun unpublishedDraftsTests() = stringSpec {
    val cxt = TestInstances.default("drafts1638")

    /** A configuration [name] of [client] declaring one cfact of its own: something to leave as a draft. */
    fun bundle(client: String, name: String): Map<String, Any?> = mapOf(
        CFEP.name to name,
        CFEP.namespaceField to clientNamespace(client),
        CFEP.slots to mapOf(
            CCT.cfactDef to listOf(
                mapOf(CCT.name to "${name}Ready", CCT.group to "grp", CCT.description to "Ready.", CCT.toFrontend to false),
            ),
        ),
    )

    "a draft is counted until it is published, and the sandbox answers for its parent" {
        val person = TestUser.create(cxt, "maker@drafts1638.test")
        val client = person.postData(AEP.createOwnClient, mapOf(CLD.name to "Drafts"))[CLD.client].toOptStr().shouldNotBeNull()
        val before = person.getItem(CFEP.drafts)
        before[CFEP.client] shouldBe client
        before[CFEP.inSandbox] shouldBe false
        val baseline = before[CFEP.names].toJsonListOfStrings()

        person.postData(CFEP.bundleWrite, bundle(client, "draft1638"))
        val drafted = person.getItem(CFEP.drafts)
        drafted[CFEP.names].toJsonListOfStrings() shouldContain "draft1638"
        (drafted[CFEP.count] as Number).toInt() shouldBe baseline.size + 1

        person.postData(CFEP.bundlePublish, mapOf(CFEP.names to listOf("draft1638")))
        val published = person.getItem(CFEP.drafts)
        published[CFEP.names].toJsonListOfStrings() shouldNotContain "draft1638"
        (published[CFEP.count] as Number).toInt() shouldBe baseline.size

        // From inside the sandbox: the parent's drafts, which the sandbox runs, said to be asked from there.
        person.postData(CFEP.bundleWrite, bundle(client, "sandboxed1638"))
        person.postData(AEP.openSandbox, emptyMap())
        val inside = person.getItem(CFEP.drafts)
        inside[CFEP.client] shouldBe client
        inside[CFEP.inSandbox] shouldBe true
        inside[CFEP.names].toJsonListOfStrings() shouldContain "sandboxed1638"
    }

    "only a client's administrator may ask" {
        TestUser.create(cxt, "member@drafts1638.test").expectError(EXC.notAuthorized, CFEP.drafts)
        // A public administrator administers only their own users, not the client everyone in public shares.
        val publicAdmin = TestUser.create(cxt, "public@drafts1638.test", level = ROLE.admin, userClient = CL.public)
        publicAdmin.expectError(EXC.notAuthorized, CFEP.drafts)
    }
}
