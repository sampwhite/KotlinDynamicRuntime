package com.dynamicruntime.kdn

import com.dynamicruntime.common.cfact.CFACTS
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.clientPath
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
import com.dynamicruntime.common.gedra.workflow.CTAK
import com.dynamicruntime.common.gedra.workflow.CTKQ
import com.dynamicruntime.common.gedra.workflow.RCF
import com.dynamicruntime.common.gedra.workflow.SWF
import com.dynamicruntime.common.gedra.workflow.WDSP
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.gedra.workflow.WfApprovalStep
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.userHasLabel
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Ordered approvals in one task (issue #1702): an approval given in steps, each by a person in turn. The form waits on
 * the first step not given; only that step can be given, and only by whoever its rule admits; the call to action -- its
 * filter, its label, Waiting on you, and `review=mine` -- follows the step; each step's record and cfact are kept; and
 * the approval's own cfact holds once every step is given.
 *
 * Over a client of its own, whose `signoff` workflow's one task is approved by a reviewer (the `reviewer` label), then
 * by an administrator. Approval steps fail silently when they go wrong -- a form waiting on nobody, or finished with a
 * step missing -- so each surface is checked.
 */
class ApprovalStepsTest : StringSpec({
    val cxt = TestInstances.default("approvalSteps1702")
    val client = "steps1702"
    val svc = GedraConfigService.get(cxt)
    val workflowId = "signoff"
    val taskId = "approve"

    fun GedraConfigBuilder.stepsClient(id: String) {
        defineClient(
            ClientDef(
                clientId = id, name = id, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                enabledEnvironments = setOf(ENV.unit, ENV.local), userLabels = listOf("reviewer"),
            ),
        )
        cfact("signedOff", "Review", "Both people approved the form.")
        cfact("reviewed", "Review", "The reviewer approved the form.")
        trait("NotesEntry", "notes", setOf(GedraDataType.formDoc), "Notes.") { property("text", "The notes.") }
    }

    val reviewerStep = WfApprovalStep(
        "review", label = "Review", prompt = "Review the form.", button = "Approve the review",
        approveWhen = "${WFC.reviewer},~${RCF.isOwner}", waiting = "Waiting on a reviewer", cfact = "reviewed",
    )
    val adminStep = WfApprovalStep(
        "admin", label = "Sign-off", prompt = "Sign the form off.", button = "Sign off",
        approveWhen = "${CFACTS.hasAdminLevel},~${RCF.isOwner}", waiting = "Waiting on an administrator", cfact = null,
    )
    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        stepsClient(client)
        workflow(workflowId, WfEntry.normal) {
            task(taskId, "Approve") {
                approvalSteps("signedOff", listOf(reviewerStep, adminStep))
                function(userHasLabel { label = "reviewer" })
                // A branch on the first step's cfact: what a surface reading state alone has to choose as the page does.
                display {
                    whenCfacts("reviewed,~signedOff") { text("Reviewed; waiting on the sign-off.") }
                    otherwise { defaultRendering() }
                }
            }
            singleton(WSC.finished, "signedOff")
        }
    }
    svc.writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 17021L }, config)
    GedraConfigReload.reloadClient(cxt, client)

    val owner = TestUser.create(cxt, "owner@$client.test", userClient = client)
    val admin = TestUser.create(cxt, "admin@$client.test", level = ROLE.admin, userClient = client)
    val reviewer = TestUser.create(cxt, "reviewer@$client.test", capabilities = listOf(ROLE.editor), userClient = client)
    admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to reviewer.userId, ADF.labels to listOf("reviewer")))

    fun form(): String {
        val notes = mapOf(GE.traitId to "notes", GE.data to mapOf("text" to "Ready."))
        val gid = owner.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(notes)))[GDF.gedraId].toOptStr()!!
        owner.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to workflowId))
        return gid
    }

    fun approve(gid: String, stepId: String?) = buildMap {
        put(GDF.gedraId, gid)
        put(GDF.workflowId, workflowId)
        put(GDF.taskId, taskId)
        stepId?.let { put(GDF.stepId, it) }
    }

    /** The form's stored state entry for the workflow, as the owner reads it. */
    fun state(gid: String): Map<String, Any?> =
        owner.getItems(GEP.formDocs, mapOf(GDF.withStates to true)).single { it[GDF.gedraId] == gid }[GDF.states]
            .toJsonListOfMaps()
            .single { it[GE.traitId] == WFS.workflowState && it[GE.data].toJsonMapOrEmpty()[WFD.workflowId] == workflowId }[GE.data]
            .toJsonMapOrEmpty()

    fun task(viewer: TestUser, gid: String): Map<String, Any?> =
        viewer.getData(GEP.workflowView, mapOf(GDF.gedraId to gid, WFD.workflowId to workflowId))[WFD.tasks]
            .toJsonListOfMaps().single { it[WFD.id] == taskId }

    /** The steps [user]'s Waiting on you lists for [gid], each as its action text; empty when it is not listed. */
    fun waitingOn(user: TestUser, gid: String): List<Any?> =
        user.getItems(GEP.formDocs, mapOf(GDF.needsMe to true)).firstOrNull { it[GDF.gedraId] == gid }
            ?.get(GDF.waitingSteps).toJsonListOfMaps().map { it[SWF.actionText] }

    fun reviewMine(user: TestUser): List<String> =
        user.client.sendJsonGetRequest(clientPath(GEP.formDocs, client), mapOf(CTAK.review to CTKQ.mine, EP.limit to 500))[EP.items]
            .toJsonListOfMaps().map { it[GDF.gedraId].toOptStr()!! }

    "the form waits on the first step: its rule is the CTA filter, its waiting the label, and only its people are listed" {
        val gid = form()
        state(gid).let {
            it[WFS.ctaTask] shouldBe taskId
            it[WFS.ctaStep] shouldBe "review"
            it[WFS.ctaFilterRule] shouldBe reviewerStep.approveWhen
            it[WFS.ctaFilterLabel] shouldBe "Waiting on a reviewer"
            it[WFS.ctaKeyword] shouldBe CTAK.review
        }
        waitingOn(reviewer, gid) shouldBe listOf("Approve the review")
        waitingOn(admin, gid) shouldBe emptyList()
        reviewMine(reviewer) shouldContain gid
        reviewMine(admin) shouldNotContain gid
        // The view: the current step's prompt and button, and its waiting for whoever may not give it.
        task(reviewer, gid).let {
            it[WVF.canApprove] shouldBe true
            val approval = it[WVF.approval].toJsonMapOrEmpty()
            approval[WVF.currentStep] shouldBe "review"
            approval[WFD.button] shouldBe "Approve the review"
            approval[WVF.steps].toJsonListOfMaps().map { s -> s[WFD.id] to s[WVF.approved] } shouldBe
                listOf("review" to false, "admin" to false)
        }
        task(admin, gid).let {
            it[WVF.canApprove] shouldBe false
            it[WVF.waitingOn] shouldBe "Waiting on a reviewer"
        }
    }

    "only the current step can be given, and only by whom its rule admits" {
        val gid = form()
        // The second step first: a conflict, whoever asks.
        admin.expectError(EXC.conflict, GEP.workflowApprove, approve(gid, "admin")).toString() shouldContain
            "step 'review' is"
        // Naming no step is refused for an approval in steps, saying which step the form waits on.
        reviewer.expectError(EXC.badInput, GEP.workflowApprove, approve(gid, null)).toString() shouldContain
            "This form waits on step 'review'"
        // The current step, by somebody its rule refuses: the step's waiting says whom it waits on.
        admin.expectError(EXC.notAuthorized, GEP.workflowApprove, approve(gid, "review")).toString() shouldContain
            "Waiting on a reviewer"
        // Nothing was recorded by any of it.
        state(gid)[WFS.ctaStep] shouldBe "review"
    }

    "each step given moves the form to the next one's people, and the last finishes it" {
        val gid = form()
        reviewer.postData(GEP.workflowApprove, approve(gid, "review"))
        state(gid).let {
            it[WFS.ctaStep] shouldBe "admin"
            it[WFS.ctaFilterRule] shouldBe adminStep.approveWhen
            it[WFS.ctaFilterLabel] shouldBe "Waiting on an administrator"
            // The first step's cfact holds, and not the approval's: one step is not the whole.
            it[WFS.cfacts].toJsonListOfStrings().let { facts ->
                facts shouldContain "reviewed"
                facts shouldNotContain "signedOff"
            }
            it[WFS.singletonCfacts].toJsonListOfStrings() shouldNotContain WSC.finished
        }
        waitingOn(reviewer, gid) shouldBe emptyList()
        // The given step's cfact reaches the listing's task facts as the page's: the display's branch on it is chosen.
        waitingOn(admin, gid) shouldBe listOf("Reviewed; waiting on the sign-off.")
        task(admin, gid)[WFD.display].toJsonMapOrEmpty()[WDSP.text] shouldBe "Reviewed; waiting on the sign-off."
        reviewMine(reviewer) shouldNotContain gid
        reviewMine(admin) shouldContain gid
        // A stale page giving the first step again is refused: it is given.
        reviewer.expectError(EXC.conflict, GEP.workflowApprove, approve(gid, "review"))
        // The owner's view: the first step's record, and the second waited on.
        task(owner, gid)[WVF.approval].toJsonMapOrEmpty().let {
            it[WVF.approved] shouldBe false
            it[WVF.currentStep] shouldBe "admin"
            it[WFD.waiting] shouldBe "Waiting on an administrator"
            val given = it[WVF.steps].toJsonListOfMaps().first()
            given[WVF.approved] shouldBe true
            given[WFD.label] shouldBe "Review"
            given[WVF.approvedByName].toOptStr() shouldBe "reviewer@$client.test"
        }

        admin.postData(GEP.workflowApprove, approve(gid, "admin"))
        state(gid).let {
            it[WFS.tasksDone] shouldBe true
            it[WFS.ctaStep] shouldBe null
            it[WFS.cfacts].toJsonListOfStrings() shouldContain "signedOff"
            it[WFS.singletonCfacts].toJsonListOfStrings() shouldContain WSC.finished
        }
        task(owner, gid)[WVF.approval].toJsonMapOrEmpty().let {
            it[WVF.approved] shouldBe true
            it[WVF.currentStep] shouldBe null
            // The task's approver is the last step's.
            it[WVF.approvedByName].toOptStr() shouldBe "admin@$client.test"
            it[WVF.steps].toJsonListOfMaps().map { s -> s[WVF.approved] } shouldBe listOf(true, true)
        }
        admin.expectError(EXC.conflict, GEP.workflowApprove, approve(gid, "admin")).toString() shouldContain "already approved"
    }

    "a step's copy is checked when the configuration is checked, as an approval's own is" {
        val id = "stepscopy1702"
        val bad = gedraConfig(cxt, "${id}cfg", clientNamespace(id), id) {
            stepsClient(id)
            workflow(workflowId, WfEntry.normal) {
                task(taskId, "Approve") {
                    approvalSteps(
                        "signedOff",
                        listOf(reviewerStep, WfApprovalStep("admin", null, "P", "B", null, "%{@t(\"nope.ns.key\")}", null)),
                    )
                    function(userHasLabel { label = "reviewer" })
                }
            }
        }
        svc.writeConfig(cxt.mkSubContext("setup", id).also { it.userId = 17021L }, bad)
        shouldThrow<KdrException> { GedraConfigReload.reloadClient(cxt, id) }.message.orEmpty() shouldContain
            "approval step 'admin' waiting text of task 'approve'"
    }

    "a step's rule naming a fact that is not about the person is refused when the configuration is checked" {
        val id = "stepsbad1702"
        val bad = gedraConfig(cxt, "${id}cfg", clientNamespace(id), id) {
            stepsClient(id)
            workflow(workflowId, WfEntry.normal) {
                task(taskId, "Approve") {
                    approvalSteps("signedOff", listOf(reviewerStep, adminStep.copyWith(approveWhen = "reviewed")))
                    function(userHasLabel { label = "reviewer" })
                }
            }
        }
        svc.writeConfig(cxt.mkSubContext("setup", id).also { it.userId = 17021L }, bad)
        shouldThrow<KdrException> { GedraConfigReload.reloadClient(cxt, id) }.message.orEmpty() shouldContain
            "step 'admin''s rule for who may give it"
    }
})

private fun WfApprovalStep.copyWith(approveWhen: String?) = WfApprovalStep(id, label, prompt, button, approveWhen, waiting, cfact)
