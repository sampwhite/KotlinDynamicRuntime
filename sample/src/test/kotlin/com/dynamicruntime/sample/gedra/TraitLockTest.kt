package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFS
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Trait locks (issue #857): while a form is engaged with acme's audit review, its audit (`acmeSiteAudit`) may be
 * changed -- by any path, the raw patch included -- only by whoever may record the audit (a reviewer), or by a client
 * administrator who overrides the lock with a reason, which the review's trail records. Nothing is stored about the
 * lock: disengaging lifts it.
 */
class TraitLockTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "traitLock857", "traitLock857Test", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )
    val create = clientPath(GEP.formDocCreate, SC.acme)
    val engage = clientPath(GEP.workflowEngage, SC.acme)
    val patch = clientPath(GEP.patch, SC.acme)
    val locks = clientPath(GEP.formDocLocks, SC.acme)
    val view = clientPath(GEP.workflowView, SC.acme)
    val recompute = clientPath(GEP.formDocRecomputeState, SC.acme)
    val formDoc = clientPath(GEP.formDoc, SC.acme)

    val owner = TestUser.create(cxt, "lock-owner@acme.test", userClient = SC.acme)
    val labeller = TestUser.create(cxt, "lock-labeller@acme.test", userClient = SC.acme, level = ROLE.admin)
    val reviewer = TestUser.create(cxt, "lock-reviewer@acme.test", userClient = SC.acme, level = ROLE.admin)
    labeller.postData(UADEP.userSetLabels, mapOf(ADF.userId to reviewer.userId, ADF.labels to listOf(SC.reviewerLabel)))
    // A client administrator without the reviewer label: locked out, but allowed to override.
    val admin = TestUser.create(cxt, "lock-admin@acme.test", userClient = SC.acme, level = ROLE.admin)

    fun engagedForm(): String {
        val gid = owner.postItem(
            create,
            mapOf(
                GDF.entries to listOf(
                    mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2026)),
                    mapOf(GE.traitId to SC.userInfo, GE.data to mapOf(SC.userName to "A Person")),
                    mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "A Person", SC.findings to "seen")),
                ),
            ),
        )[GDF.gedraId].toOptStr()!!
        owner.postData(engage, mapOf(GDF.gedraId to gid, WFD.workflowId to SW.auditReview))
        return gid
    }

    fun patchBody(gid: String, traitId: String, data: Map<String, Any?>?, reason: String? = null,
                  action: GedraEditAction = GedraEditAction.addOrReplace) = buildMap {
        val edit = buildMap { put(GED.action, action.name); put(GE.traitId, traitId); data?.let { put(GE.data, it) } }
        put(GPF.targets, mapOf(GedraDataType.formDoc.name to listOf(mapOf(GDF.gedraId to gid, GPF.edits to listOf(edit)))))
        reason?.let { put(GPF.overrideReason, it) }
    }
    fun audit(findings: String) = mapOf(SC.auditor to "Someone", SC.findings to findings)
    fun refused(user: TestUser, body: Map<String, Any?>) =
        user.expectError(EXC.conflict, patch, body)["errorMessage"].toOptStr().orEmpty()
    fun lockedFor(user: TestUser, gid: String) =
        user.getData(locks, mapOf(GDF.gedraId to gid))[WVF.lockedTraits].toJsonListOfMaps()

    "the owner cannot change the audit by any patch, but may change the rest of their form" {
        val gid = engagedForm()
        refused(owner, patchBody(gid, SC.siteAudit, audit("open"))).let {
            it shouldContain "Site audit is locked by Audit review and can't be changed now."
            it shouldNotContain "override"
        }
        // A delete is an edit too.
        refused(owner, patchBody(gid, SC.siteAudit, null, action = GedraEditAction.deleteOrNoOp))
        owner.postItems(patch, patchBody(gid, ST.expenseReport, mapOf(ST.year to 2025)))
    }

    "re-sending the audit as stored changes nothing, so no lock is tripped (the review's finding)" {
        val gid = engagedForm()
        val outcome = owner.postItems(patch, patchBody(gid, SC.siteAudit, mapOf(SC.auditor to "A Person", SC.findings to "seen")))
            .single()[GPF.outcomes].toJsonListOfMaps().single()
        outcome[GPF.applied] shouldBe false
    }

    "nobody the lock holds for may delete the form -- it would remove the audit -- until the lock lifts" {
        val gid = engagedForm()
        owner.expectError(EXC.conflict, formDoc, args = mapOf(GDF.gedraId to gid), method = HttpMethod.DELETE)[
            "errorMessage"].toOptStr().orEmpty() shouldContain "The form can't be deleted while it's in Audit review, which locks its Site audit."
        // Not even someone who may override an edit: a deletion leaves no form to read the override on.
        admin.expectError(EXC.conflict, formDoc, args = mapOf(GDF.gedraId to gid), method = HttpMethod.DELETE)
        owner.postData(engage, mapOf(GDF.gedraId to gid, WFD.workflowId to SW.auditReview, WFS.engaged to false))
        owner.deleteData(formDoc, mapOf(GDF.gedraId to gid))[GDF.gedraId] shouldBe gid
    }

    "a reviewer -- who may record the audit -- may change it" {
        val gid = engagedForm()
        reviewer.postItems(patch, patchBody(gid, SC.siteAudit, audit("open")))
        lockedFor(reviewer, gid) shouldBe emptyList()
    }

    "an administrator is refused unless they override, and the override is recorded" {
        val gid = engagedForm()
        refused(admin, patchBody(gid, SC.siteAudit, audit("open"))) shouldContain "You can override the lock by giving a reason."
        admin.postItems(patch, patchBody(gid, SC.siteAudit, audit("seen, corrected"), reason = "Fixing the auditor's typo"))
        val trail = owner.postData(recompute, mapOf(GDF.gedraId to gid))[GDF.states].toJsonListOfMaps()
            .first { it[GE.traitId] == WFS.workflowEngagement }[GE.data].toJsonMapOrEmpty()[WFS.events].toJsonListOfMaps()
        val override = trail.last()
        override[WFS.kind] shouldBe WFS.lockOverriddenEvent
        override[WFS.by].toOptLong() shouldBe admin.userId
        override[WFS.note].toOptStr().orEmpty() shouldContain "${SC.siteAudit}: Fixing the auditor's typo"
    }

    "someone the lock does not allow to override is refused even when they ask" {
        val gid = engagedForm()
        refused(owner, patchBody(gid, SC.siteAudit, audit("open"), reason = "Because")) shouldContain "You can't override the lock Audit review holds on Site audit."
    }

    "the locks endpoint and the workflow view tell each caller what is locked for them" {
        val gid = engagedForm()
        lockedFor(owner, gid).single().let {
            it[WFD.traitId] shouldBe SC.siteAudit
            it[WFD.workflowId] shouldBe SW.auditReview
            it[WFD.label] shouldBe "Audit review"
            it[WVF.traitName] shouldBe "Site audit"
            it[WVF.canOverride] shouldBe false
        }
        lockedFor(admin, gid).single()[WVF.canOverride] shouldBe true
        owner.getData(view, mapOf(WFD.workflowId to SW.auditReview, GDF.gedraId to gid))[WVF.lockedTraits]
            .toJsonListOfMaps().single()[WFD.traitId] shouldBe SC.siteAudit
    }

    "acme's follow-up is the site lead's by every path, as the audit is the reviewers'" {
        val siteLead = TestUser.create(cxt, "lock-sitelead@acme.test", userClient = SC.acme, level = ROLE.admin)
        labeller.postData(UADEP.userSetLabels, mapOf(ADF.userId to siteLead.userId, ADF.labels to listOf(SC.siteLeadLabel)))
        val gid = owner.postItem(
            create,
            mapOf(
                GDF.entries to listOf(
                    mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2026)),
                    mapOf(GE.traitId to SC.userInfo, GE.data to mapOf(SC.userName to "A Person")),
                ),
            ),
        )[GDF.gedraId].toOptStr()!!
        owner.postData(engage, mapOf(GDF.gedraId to gid, WFD.workflowId to SW.siteFollowUp))
        val followUp = mapOf(SC.followUpBy to "A Person", SC.followUpOutcome to "All fine")
        refused(owner, patchBody(gid, SC.siteFollowUpTrait, followUp)) shouldContain "is locked by Site follow-up"
        siteLead.postItems(patch, patchBody(gid, SC.siteFollowUpTrait, followUp))
    }

    "a form two workflows lock says both, by name, when a delete is refused" {
        // A form holding the audit and a follow-up; the review approved, so the follow-up -- held off while a review
        // is pending -- can engage too.
        val gid = owner.postItem(
            create,
            mapOf(
                GDF.entries to listOf(
                    mapOf(GE.traitId to ST.expenseReport, GE.data to mapOf(ST.year to 2026)),
                    mapOf(GE.traitId to SC.userInfo, GE.data to mapOf(SC.userName to "A Person")),
                    mapOf(GE.traitId to SC.siteAudit, GE.data to mapOf(SC.auditor to "A Person", SC.findings to "seen")),
                    mapOf(GE.traitId to SC.siteFollowUpTrait, GE.data to mapOf(SC.followUpBy to "A Person")),
                ),
            ),
        )[GDF.gedraId].toOptStr()!!
        owner.postData(engage, mapOf(GDF.gedraId to gid, WFD.workflowId to SW.auditReview))
        reviewer.postData(
            clientPath(GEP.workflowApprove, SC.acme),
            mapOf(GDF.gedraId to gid, GDF.workflowId to SW.auditReview, GDF.taskId to SW.approveAudit),
        )
        owner.postData(engage, mapOf(GDF.gedraId to gid, WFD.workflowId to SW.siteFollowUp))
        owner.expectError(EXC.conflict, formDoc, args = mapOf(GDF.gedraId to gid), method = HttpMethod.DELETE)[
            "errorMessage"].toOptStr().orEmpty() shouldContain
            "The form can't be deleted while it's in Audit review and Site follow-up, which lock its Site audit and " +
            "Site follow-up."
    }

    "disengaging lifts the lock -- nothing about it was stored" {
        val gid = engagedForm()
        owner.postData(engage, mapOf(GDF.gedraId to gid, WFD.workflowId to SW.auditReview, WFS.engaged to false))
        lockedFor(owner, gid) shouldBe emptyList()
        owner.postItems(patch, patchBody(gid, SC.siteAudit, audit("open")))
    }
})
