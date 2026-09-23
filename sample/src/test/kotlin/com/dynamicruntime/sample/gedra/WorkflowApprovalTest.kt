package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.workflow.SVY
import com.dynamicruntime.common.gedra.workflow.SVYS
import com.dynamicruntime.common.gedra.workflow.SWF
import com.dynamicruntime.common.gedra.workflow.WDSP
import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WSC
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.PERSONA
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.uiblock.UIB
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOrEmpty
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
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
    val recompute = clientPath(GEP.formDocRecomputeState, SC.acme)

    val owner = TestUser.create(cxt, "approve-owner@acme.test", userClient = SC.acme)
    // A reviewer has to be able to read the form, which an acme administrator can; the label is what makes one a
    // reviewer. Applied by a second administrator, as it would be in practice.
    val labeller = TestUser.create(cxt, "approve-labeller@acme.test", userClient = SC.acme, level = ROLE.admin)
    val reviewer = TestUser.create(cxt, "approve-reviewer@acme.test", userClient = SC.acme, level = ROLE.admin)
    labeller.postData(UADEP.userSetLabels, mapOf(ADF.userId to reviewer.userId, ADF.labels to listOf(SC.reviewerLabel)))
    val unlabelled = TestUser.create(cxt, "approve-unlabelled@acme.test", userClient = SC.acme, level = ROLE.admin)

    // Survey-complete (so auditReview is eligible) and, with [recorded], the audit recorded -- which completes the
    // first task and makes the approval the current one.
    fun engagedForm(user: TestUser = owner, recorded: Boolean = true, findings: String = "seen"): String {
        val entries = buildList {
            add(mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2026)))
            add(mapOf(GE.traitId to SC.userInfo, GE.data to mapOf(SC.userName to "A Person")))
            if (recorded) add(mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "A Person", SC.findings to findings)))
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
            // Not complete until approved -- trait presence would say otherwise, since the task has no traits.
            it shouldNotContain WFC.taskComplete
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

        // After: the view says approved, and by whom -- the reviewer's public name, what "approved by ..." shows,
        // and not their user id: the owner could not read the reviewer's row. The task's facts say complete now.
        val afterTask = approvalTaskView(owner, gid)
        val after = afterTask[WVF.approval].toJsonMapOrEmpty()
        after[WVF.approved] shouldBe true
        after[WVF.approvedByName] shouldBe "approve-reviewer@acme.test"
        after.containsKey(WFS.approvedBy) shouldBe false
        afterTask[WVF.facts].toJsonListOrEmpty().map { it.toOptStr() } shouldContain WFC.taskComplete

        // A standalone recompute -- a batch job's, say -- leaves the approval standing: it is asserted.
        auditState(owner.postData(recompute, mapOf(GDF.gedraId to gid))[GDF.states].toJsonListOfMaps())[WFS.tasksDone] shouldBe true

        // Once: a second approval is a conflict, not a second record.
        reviewer.expectError(EXC.conflict, approve, data = approveBody(gid))
    }

    "an approval belongs to its engagement: re-engaging starts over, and the task may be approved again" {
        val gid = engagedForm()
        reviewer.postData(approve, approveBody(gid))
        // Out and back in -- the owner might have changed the audit in between. The old approval stays as history
        // but no longer finishes the workflow: the approval is the current task again.
        owner.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview, WFS.engaged to false))
        val reEngaged = owner.postData(engage, mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview))[GDF.states]
            .toJsonListOfMaps()
        auditState(reEngaged)[WFS.ctaTask] shouldBe SW.approveAudit
        auditState(reEngaged)[WFS.cfacts].toJsonListOrEmpty().map { it.toOptStr() } shouldNotContain SC.auditApproved
        // Approvable again rather than a 409, and the new record replaces the stale one.
        val states = reviewer.postData(approve, approveBody(gid))[GDF.states].toJsonListOfMaps()
        entriesOf(states, WFS.workflowApproval).size shouldBe 1
        auditState(states)[WFS.tasksDone] shouldBe true
    }

    "on a test instance, another persona of the owner may review -- one tester plays both parts" {
        // The owner's own address with an administrator persona: the same person, a different user. On a real node
        // the identity would be compared and this refused (WorkflowApprovalsTest pins that half).
        val ownerAsAdmin = TestUser.create(
            cxt, "approve-owner@acme.test", userClient = SC.acme, level = ROLE.admin, persona = PERSONA.admin,
        )
        labeller.postData(UADEP.userSetLabels, mapOf(ADF.userId to ownerAsAdmin.userId, ADF.labels to listOf(SC.reviewerLabel)))
        val gid = engagedForm()
        auditState(ownerAsAdmin.postData(approve, approveBody(gid))[GDF.states].toJsonListOfMaps())[WFS.tasksDone] shouldBe true
    }

    "an approval record for a task the workflow no longer has is kept, and counts for nothing" {
        // Config can rename an approval task away. Its record is a person's act, so it stays; with no task to
        // match, it adds no cfact and completes nothing -- and the recompute does not fail over it.
        val gid = engagedForm()
        val full = TestUser.createFullAdmin(cxt, "approve-full@example.com")
        val current = full.getItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid))[GDF.states].toJsonListOfMaps()
        val orphan = mapOf(
            GE.traitId to WFS.workflowApproval,
            GE.data to mapOf(
                WFD.workflowId to SW.auditReview, WFS.taskId to "renamedAway",
                WFS.approvedAt to "2099-01-01T00:00:00Z", WFS.approvedBy to reviewer.userId,
            ),
        )
        full.postItem(GEP.adminGedraState, mapOf(GDF.gedraId to gid, GDF.states to current + orphan))
        val states = owner.postData(recompute, mapOf(GDF.gedraId to gid))[GDF.states].toJsonListOfMaps()
        entriesOf(states, WFS.workflowApproval).map { it[WFS.taskId] } shouldBe listOf("renamedAway")
        auditState(states)[WFS.ctaTask] shouldBe SW.approveAudit
        auditState(states)[WFS.cfacts].toJsonListOrEmpty().map { it.toOptStr() } shouldNotContain SC.auditApproved
    }

    // --- how the approval step shows (issue #788) --------------------------------------------------------------

    "the approval step's display is chosen per caller and per moment, first applicable branch winning" {
        fun display(user: TestUser, gid: String) = approvalTaskView(user, gid)[WFD.display].toJsonMapOrEmpty()

        // Earlier work outstanding: not the step's turn yet -- shown disabled, to anyone.
        val early = engagedForm(recorded = false)
        display(reviewer, early) shouldBe mapOf(
            WDSP.mode to WDSP.textMode, WDSP.text to "Previous data entry must be completed before review.", WDSP.disabled to true,
        )

        // Its turn: a reviewer gets the approval's own rendering (the button); anyone else is told to wait.
        val ready = engagedForm()
        display(reviewer, ready) shouldBe mapOf(WDSP.mode to WDSP.defaultMode)
        display(owner, ready)[WDSP.text] shouldBe "You must wait for a reviewer to approve this form."

        // Approved: the first branch, for everyone. Its `${'$'}{approvedByName}` is left for the frontend to fill
        // from the approval block beside it -- the one frontend substitution a layout may carry.
        reviewer.postData(approve, approveBody(ready))
        val done = approvalTaskView(owner, ready)
        done[WFD.display].toJsonMapOrEmpty()[WDSP.text] shouldBe $$"The form has been approved by ${approvedByName}."
        done[WVF.approval].toJsonMapOrEmpty()[WVF.approvedByName] shouldBe "approve-reviewer@acme.test"
        // Only the chosen branch travels: no other branch, and no condition.
        done[WFD.display].toJsonMapOrEmpty().containsKey(UIB.select) shouldBe false
    }

    // --- the Needs Review / Finished chips (issue #789) --------------------------------------------------------

    val chipWorkflows = clientPath(GEP.formDocSingletonWorkflows, SC.acme)

    fun listedAs(status: String): List<Any?> =
        owner.getItems(GEP.formDocs, mapOf(SVY.surveyStatus to status)).map { it[GDF.gedraId] }

    fun chip(user: TestUser, gid: String, cfact: String) =
        user.getData(chipWorkflows, mapOf(GDF.gedraId to gid, WFD.cfact to cfact))[SWF.workflows].toJsonListOfMaps()

    "a form awaiting review reads as Needs Review, and its chip names the workflow and what it asks of each caller" {
        // Open findings: acme's audit review emits needsReview, which takes the place of a Valid chip.
        val gid = engagedForm(findings = SC.findingsOpen)
        listedAs(SVYS.needsReview) shouldContain gid
        listedAs(SVYS.valid) shouldNotContain gid

        // What stands behind the chip, from the form's state and the caller -- the same current task, told to each
        // person as their own display: the approve button for a reviewer, a wait for anyone else.
        val forReviewer = chip(reviewer, gid, WSC.needsReview).single()
        forReviewer[WFD.workflowId] shouldBe SW.auditReview
        forReviewer[WFD.label] shouldBe "Audit review"
        forReviewer[WFS.ctaTask] shouldBe SW.approveAudit
        forReviewer[SWF.actionText] shouldBe "Approve the audit"
        forReviewer[SWF.isReviewer] shouldBe true
        val forOwner = chip(owner, gid, WSC.needsReview).single()
        forOwner[SWF.actionText] shouldBe "You must wait for a reviewer to approve this form."
        forOwner[SWF.isReviewer] shouldBe false
        // Nothing has finished, so the other chip has nothing behind it.
        chip(owner, gid, WSC.finished).shouldBeEmpty()
    }

    "an approved review reads as Finished, and Needs Review still wins while something waits" {
        val closed = engagedForm()
        reviewer.postData(approve, approveBody(closed))
        listedAs(SVYS.finished) shouldContain closed
        listedAs(SVYS.valid) shouldNotContain closed
        // Finished: the workflow is listed with no current task -- there is nothing left to do.
        val done = chip(owner, closed, WSC.finished).single()
        done[WFD.workflowId] shouldBe SW.auditReview
        done.containsKey(WFS.ctaTask) shouldBe false

        // Findings still open after approval: both singletons at once, and Needs Review is the chip.
        val open = engagedForm(findings = SC.findingsOpen)
        reviewer.postData(approve, approveBody(open))
        listedAs(SVYS.needsReview) shouldContain open
        listedAs(SVYS.finished) shouldNotContain open
    }

    "Needs Info still trumps a pending review: the chip describes the owner's own work first" {
        // The survey is incomplete (no expense report), so the form Needs Info whatever a workflow emits.
        val gid = owner.postItem(
            create,
            mapOf(GDF.entries to listOf(mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "A", SC.findings to SC.findingsOpen)))),
        )[GDF.gedraId].toOptStr()!!
        listedAs(SVYS.needsInfo) shouldContain gid
        listedAs(SVYS.needsReview) shouldNotContain gid
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
