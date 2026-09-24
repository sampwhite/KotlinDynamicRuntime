package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Who may save a normal workflow's task, and when (issue #856): a task's rule for who may save it is enforced by the
 * save endpoint and reported by the view, so the two agree; and a task is saved only on a form engaged with its
 * workflow.
 *
 * Acme's "Record the audit" is the reviewers' (`saveWhen(wfReviewer)` beside a `userHasLabel`); its site follow-up's
 * tasks say nothing, so anyone who can see the form may save them -- once the form is in the follow-up.
 */
class WorkflowTaskSaveTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "wfTaskSave856", "wfTaskSave856Test", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )
    val create = clientPath(GEP.formDocCreate, SC.acme)
    val engage = clientPath(GEP.workflowEngage, SC.acme)
    val save = clientPath(GEP.workflowSave, SC.acme)
    val view = clientPath(GEP.workflowView, SC.acme)

    val owner = TestUser.create(cxt, "save-owner@acme.test", userClient = SC.acme)
    val labeller = TestUser.create(cxt, "save-labeller@acme.test", userClient = SC.acme, level = ROLE.admin)
    val reviewer = TestUser.create(cxt, "save-reviewer@acme.test", userClient = SC.acme, level = ROLE.admin)
    labeller.postData(UADEP.userSetLabels, mapOf(ADF.userId to reviewer.userId, ADF.labels to listOf(SC.reviewerLabel)))

    fun newForm(): String = owner.postItem(
        create,
        mapOf(
            GDF.entries to listOf(
                mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2026)),
                mapOf(GE.traitId to SC.userInfo, GE.data to mapOf(SC.userName to "A Person")),
                mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "A Person", SC.findings to "seen")),
            ),
        ),
    )[GDF.gedraId].toOptStr()!!

    fun auditSave(gid: String, findings: String) = mapOf(
        WFD.workflowId to SW.auditReview, GDF.taskId to SW.recordAudit, GDF.saveId to SW.saveAudit, GDF.gedraId to gid,
        GDF.entries to listOf(mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "The Reviewer", SC.findings to findings))),
    )

    fun contactSave(gid: String) = mapOf(
        WFD.workflowId to SW.siteFollowUp, GDF.taskId to SW.confirmContact, GDF.saveId to SW.saveContact, GDF.gedraId to gid,
        GDF.entries to listOf(mapOf(GE.traitId to SC.userInfo, GE.data to mapOf(SC.userName to "Site Contact"))),
    )

    fun canSave(user: TestUser, gid: String, workflowId: String, taskId: String) =
        user.getData(view, mapOf(WFD.workflowId to workflowId, GDF.gedraId to gid))[WFD.tasks].toJsonListOfMaps()
            .first { it[WFD.id] == taskId }[WVF.canSave]

    "only a reviewer may record the audit, and the view says so to each" {
        val gid = newForm()
        owner.postData(engage, mapOf(GDF.gedraId to gid, WFD.workflowId to SW.auditReview))

        canSave(owner, gid, SW.auditReview, SW.recordAudit) shouldBe false
        canSave(reviewer, gid, SW.auditReview, SW.recordAudit) shouldBe true

        owner.expectError(EXC.notAuthorized, save, auditSave(gid, "open"))["errorMessage"].toOptStr().orEmpty() shouldContain
            "may not save task '${SW.recordAudit}'"
        reviewer.postData(save, auditSave(gid, "open"))[WSF.saved] shouldBe true
    }

    "a task that says nothing is anyone's who can see the form" {
        val gid = newForm()
        owner.postData(engage, mapOf(GDF.gedraId to gid, WFD.workflowId to SW.siteFollowUp))
        canSave(owner, gid, SW.siteFollowUp, SW.confirmContact) shouldBe true
        owner.postData(save, contactSave(gid))[WSF.saved] shouldBe true
    }

    "a task is saved only on a form engaged with its workflow" {
        val gid = newForm()
        owner.expectError(EXC.badInput, save, contactSave(gid))["errorMessage"].toOptStr().orEmpty() shouldContain
            "not in workflow '${SW.siteFollowUp}'"
        owner.postData(engage, mapOf(GDF.gedraId to gid, WFD.workflowId to SW.siteFollowUp))
        owner.postData(save, contactSave(gid))[WSF.saved] shouldBe true
        // Taken back out: saving stops again.
        owner.postData(engage, mapOf(GDF.gedraId to gid, WFD.workflowId to SW.siteFollowUp, "engaged" to false))
        owner.expectError(EXC.badInput, save, contactSave(gid))
    }
})
