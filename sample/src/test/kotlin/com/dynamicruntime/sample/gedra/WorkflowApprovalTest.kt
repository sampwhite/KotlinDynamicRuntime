package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The approval task and its endpoint (issue #787), over acme's `auditReview`: record the audit, then a reviewer --
 * an acme administrator carrying acme's `reviewer` label -- approves it.
 *
 * What is pinned: approving records who and when, turns into the workflow's `acmeAuditApproved` cfact, completes
 * the task (so the workflow is done) and -- through acme's ordinary singleton rule -- marks the form Finished; the
 * view shows the approval before and after; and the endpoint refuses everything that is not a reviewer approving
 * someone else's engaged form at the right moment, once.
 */
class WorkflowApprovalTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "wfApprove787", "wfApprove787Test", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )

    val create = clientPath(GEP.formDocCreate, SC.acme)
    val engage = clientPath(GEP.workflowEngage, SC.acme)
    val approve = clientPath(GEP.workflowApprove, SC.acme)
    val view = clientPath(GEP.workflowView, SC.acme)

    val owner = TestUser.create(cxt, "approve-owner@acme.test", userClient = SC.acme)
    // A reviewer has to be able to read the form, which an acme administrator can; the label is what makes one a
    // reviewer. Applied by a second administrator, as it would be in practice.
    val labeller = TestUser.create(cxt, "approve-labeller@acme.test", userClient = SC.acme, level = ROLE.admin)
    val reviewer = TestUser.create(cxt, "approve-reviewer@acme.test", userClient = SC.acme, level = ROLE.admin)
    labeller.postData(UADEP.userSetLabels, mapOf(ADF.userId to reviewer.userId, ADF.labels to listOf(SC.reviewerLabel)))
    val unlabelled = TestUser.create(cxt, "approve-unlabelled@acme.test", userClient = SC.acme, level = ROLE.admin)

    // Survey-complete (so auditReview is eligible) and, with [recorded], the audit recorded -- which completes the
    // first task and makes the approval the current one.
    fun engagedForm(user: TestUser = owner, recorded: Boolean = true): String {
        val entries = buildList {
            add(mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2026)))
            add(mapOf(GE.traitId to SC.userInfo, GE.data to mapOf(SC.userName to "A Person")))
            if (recorded) add(mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "A Person", SC.findings to "seen")))
        }
        val gid = user.postItem(create, mapOf(GDF.entries to entries))[GDF.gedraId].toOptStr()!!
        user.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview))
        return gid
    }

    fun approveBody(gid: String, taskId: String = SW.approveAudit) =
        mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview, GDF.taskId to taskId)

    fun entriesOf(states: List<Map<String, Any?>>, traitId: String) =
        states.filter { it[GE.traitId].toOptStr() == traitId }.map { it[GE.data].toJsonMapOrEmpty() }

    fun auditState(states: List<Map<String, Any?>>) =
        entriesOf(states, WFS.workflowState).single { it[WFD.workflowId].toOptStr() == SW.auditReview }

    fun approvalTaskView(user: TestUser, gid: String): Map<String, Any?> =
        user.getData(view, mapOf(GDF.workflowId to SW.auditReview, GDF.gedraId to gid))[WFD.tasks]
            .toJsonListOfMaps().single { it[WFD.id].toOptStr() == SW.approveAudit }

    "a reviewer approves: recorded, the workflow's cfact, the task done, the form Finished, and a trail event" {
        val gid = engagedForm()
        // Before: the approval is the current task, the reviewer is told so, and nothing is approved yet.
        val before = approvalTaskView(reviewer, gid)
        before[WVF.facts].toJsonListOrEmpty().map { it.toOptStr() }.let {
            it shouldContain WFC.isCta
            it shouldContain WFC.reviewer
        }
        before[WVF.approval].toJsonMapOrEmpty()[WVF.approved] shouldBe false

        val states = reviewer.postData(approve, approveBody(gid))[GDF.states].toJsonListOfMaps()
        val record = entriesOf(states, WFS.workflowApproval).single()
        record[WFS.taskId] shouldBe SW.approveAudit
        record[WFS.approvedBy].toString() shouldBe reviewer.userId.toString()
        // The approval turns into the workflow's configured cfact, completes the task -- no CTA left -- and acme's
        // singleton rule makes the form Finished.
        val audit = auditState(states)
        audit[WFS.cfacts].toJsonListOrEmpty().map { it.toOptStr() } shouldContain SC.auditApproved
        audit[WFS.tasksDone] shouldBe true
        audit[WFS.ctaTask].shouldBeNull()
        entriesOf(states, GT.cfacts).single()[GT.facts].toJsonListOrEmpty().map { it.toOptStr() } shouldContain WSC.finished
        // The engagement trail tells the story: engaged, then approved at this task.
        val lastEvent = entriesOf(states, WFS.workflowEngagement).single()[WFS.events].toJsonListOfMaps().last()
        lastEvent[WFS.kind] shouldBe WFS.approvedEvent
        lastEvent[WFS.note] shouldBe SW.approveAudit

        // After: the view says approved, and by whom -- what "approved by ..." shows.
        val after = approvalTaskView(owner, gid)[WVF.approval].toJsonMapOrEmpty()
        after[WVF.approved] shouldBe true
        after[WVF.approvedByName] shouldBe "approve-reviewer@acme.test"

        // Once: a second approval is a conflict, not a second record.
        reviewer.expectError(EXC.conflict, approve, data = approveBody(gid))
    }

    "an approval waits for the work before it" {
        // The audit is not recorded, so the first task is still the current one.
        val gid = engagedForm(recorded = false)
        reviewer.expectError(EXC.badInput, approve, data = approveBody(gid)).toString() shouldContain "comes first"
    }

    "an approval needs the form to be in the workflow" {
        val gid = owner.postItem(
            create,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "A", SC.findings to "seen")))),
        )[GDF.gedraId].toOptStr()!!
        reviewer.expectError(EXC.badInput, approve, data = approveBody(gid)).toString() shouldContain "not in workflow"
    }

    "only a reviewer approves, and never their own form" {
        val gid = engagedForm()
        // An administrator who can read the form but carries no reviewer label.
        unlabelled.expectError(EXC.notAuthorized, approve, data = approveBody(gid))
        // The reviewer's own form: the second-person rule, whatever labels they hold.
        val own = engagedForm(user = reviewer)
        reviewer.expectError(EXC.notAuthorized, approve, data = approveBody(own)).toString() shouldContain "your own form"
    }

    "only an approval task can be approved" {
        val gid = engagedForm()
        reviewer.expectError(EXC.badInput, approve, data = approveBody(gid, taskId = SW.recordAudit))
    }
})
