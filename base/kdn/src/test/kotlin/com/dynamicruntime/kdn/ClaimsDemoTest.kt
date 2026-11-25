package com.dynamicruntime.kdn

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.clientPath
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GPF
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.ListingColumn
import com.dynamicruntime.common.gedra.report.RRUN
import com.dynamicruntime.common.gedra.workflow.FSV
import com.dynamicruntime.common.gedra.workflow.WDSP
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.gedra.workflow.WVF
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.kdn.claimsdemo.ClaimsDemo
import com.dynamicruntime.kdn.claimsdemo.provisionClaimsDemo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch

/**
 * The claims demo's process (issue #1897), as its documentation says it goes: an incident numbered as its report is filed,
 * an approval that waits on whoever the amount calls for, and an assessment nobody changes once it is saved, but an
 * administrator who says why. On a copy of the demo of its own, with the users its simulation makes.
 */
class ClaimsDemoTest : StringSpec({
    val cxt = TestInstances.default("claimsDemo1897")
    val client = provisionClaimsDemo(cxt, "t1897").clients.single()
    val policyholder = TestUser.create(cxt, "policyholder@$client.example", userClient = client)
    // An editor with the label, as the simulation makes them.
    val adjuster = TestUser.create(cxt, "adjuster@$client.example", userClient = client)
    val manager = TestUser.create(cxt, "manager@$client.example", level = ROLE.admin, userClient = client)

    fun entry(traitId: String, vararg data: Pair<String, Any?>) = mapOf(GE.traitId to traitId, GE.data to mapOf(*data))
    val incident = entry(
        ClaimsDemo.incident, ClaimsDemo.kind to ClaimsDemo.kindProperty, ClaimsDemo.occurredAt to "2026-11-20T18:30:00Z",
        ClaimsDemo.place to "12 Harbor Road", ClaimsDemo.whatHappened to "A branch came down on the porch roof in the storm.",
    )
    val reporter = entry(
        ClaimsDemo.reporter, ClaimsDemo.reporterName to "Parker", ClaimsDemo.involvement to ClaimsDemo.involvementWitness,
    )

    /** A report filed by the policyholder, the number left to the server; its form's id. */
    fun file(): String = policyholder.postData(
        GEP.workflowSave,
        mapOf(
            WFD.workflowId to ClaimsDemo.reportWorkflow, GDF.taskId to ClaimsDemo.describeTask, GDF.saveId to ClaimsDemo.fileSave,
            GDF.entries to listOf(incident, reporter, entry(ClaimsDemo.filing)),
        ),
    )[WSF.item].toJsonMapOrEmpty()[GDF.gedraId].toOptStr()!!

    fun dataOf(gid: String, traitId: String): Map<String, Any?> = policyholder.getItem(GEP.formDoc, mapOf(GDF.gedraId to gid))[GDF.entries]
        .toJsonListOfMaps().single { it[GE.traitId] == traitId }[GE.data].toJsonMapOrEmpty()
    fun numberOf(gid: String) = dataOf(gid, ClaimsDemo.filing)[ClaimsDemo.incidentNumber].toOptStr().orEmpty()

    fun assessBody(gid: String, vararg data: Pair<String, Any?>) = mapOf(
        WFD.workflowId to ClaimsDemo.settlementWorkflow, GDF.taskId to ClaimsDemo.assessTask, GDF.saveId to ClaimsDemo.saveAssessment,
        GDF.gedraId to gid, GDF.entries to listOf(entry(ClaimsDemo.assessment, *data)),
    )
    fun pays(amount: Int) = arrayOf(
        ClaimsDemo.outcome to ClaimsDemo.outcomePay, ClaimsDemo.amount to amount, ClaimsDemo.reasons to "The storm is confirmed.",
    )

    /** A claim an adjuster has assessed; its form's id. */
    fun assessed(vararg data: Pair<String, Any?>): String = file().also { gid ->
        adjuster.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to ClaimsDemo.settlementWorkflow))
        adjuster.postData(GEP.workflowSave, assessBody(gid, *data))
    }
    fun approvalOf(gid: String) =
        mapOf(GDF.gedraId to gid, GDF.workflowId to ClaimsDemo.settlementWorkflow, GDF.taskId to ClaimsDemo.approveTask)
    fun waitsOn(user: TestUser, gid: String): Boolean =
        user.getItems(GEP.formDocs, mapOf(GDF.needsMe to true)).any { it[GDF.gedraId] == gid }
    fun approvalTask(user: TestUser, gid: String): Map<String, Any?> =
        user.getData(GEP.workflowView, mapOf(WFD.workflowId to ClaimsDemo.settlementWorkflow, GDF.gedraId to gid))[WFD.tasks]
            .toJsonListOfMaps().single { it[WFD.id] == ClaimsDemo.approveTask }
    fun hasStatus(gid: String, status: String): Boolean =
        policyholder.getItems(GEP.formDocs, mapOf(GDF.formStatus to status)).any { it[GDF.gedraId] == gid }
    fun patchBody(gid: String, data: Map<String, Any?>, reason: String? = null) = buildMap {
        val edit = mapOf(GED.action to GedraEditAction.addOrReplace.name, GE.traitId to ClaimsDemo.assessment, GE.data to data)
        put(GPF.targets, mapOf(GedraDataType.formDoc.name to listOf(mapOf(GDF.gedraId to gid, GPF.edits to listOf(edit)))))
        reason?.let { put(GPF.overrideReason, it) }
    }

    "a report is numbered as it is filed, the day in its number, and the forms list finds it by that" {
        val first = numberOf(file())
        first shouldMatch Regex("""INC-\d{8}-\d{3}""")
        // The next one filed that day: an unfinished report between them would have used no number up.
        val gid = file()
        val second = numberOf(gid)
        // The day of both, unless the spec runs across midnight.
        if (second.substringBeforeLast('-') == first.substringBeforeLast('-')) {
            second.substringAfterLast('-').toInt() shouldBe first.substringAfterLast('-').toInt() + 1
        }
        val listing = policyholder.client.sendJsonGetRequest(clientPath(GEP.formDocs, client), mapOf(ClaimsDemo.incidentNumber to second))
        listing[EP.items].toJsonListOfMaps().map { it[GDF.gedraId] } shouldBe listOf(gid)
        val columns = ListingColumn.listFrom(listing[EP.summary].toJsonMapOrEmpty()[GDF.columns])
        columns.map { it.label } shouldBe listOf("Incident", "Kind", "Where")
    }

    "the reporter's name starts as the one on their profile" {
        val view = policyholder.getData(GEP.workflowView, mapOf(WFD.workflowId to ClaimsDemo.reportWorkflow))
        view.toString() shouldContain "Parker Policyholder"
    }

    "an assessment is an adjuster's to save, and the claim is under their Waiting on you before it has started" {
        val gid = file()
        waitsOn(adjuster, gid) shouldBe true
        waitsOn(manager, gid) shouldBe false
        policyholder.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to ClaimsDemo.settlementWorkflow))
        for (user in listOf(policyholder, manager)) user.expectError(EXC.notAuthorized, GEP.workflowSave, assessBody(gid, *pays(100)))
        adjuster.postData(GEP.workflowSave, assessBody(gid, *pays(100)))
        // What the adjuster did not type: worked out from the amount as it was saved.
        dataOf(gid, ClaimsDemo.assessment)[ClaimsDemo.overLimit] shouldBe false
    }

    "a payment up to the adjusters' limit waits on an adjuster, who approves it, and the claim is finished" {
        val gid = assessed(*pays(ClaimsDemo.adjusterLimit))
        waitsOn(adjuster, gid) shouldBe true
        waitsOn(manager, gid) shouldBe false
        policyholder.expectError(EXC.notAuthorized, GEP.workflowApprove, approvalOf(gid))
        manager.expectError(EXC.notAuthorized, GEP.workflowApprove, approvalOf(gid))[EP.errorMessage]
            .toString() shouldContain "An adjuster approves the outcome"
        adjuster.postData(GEP.workflowApprove, approvalOf(gid))
        hasStatus(gid, FSV.finished) shouldBe true
    }

    "a payment over the limit waits on a claims manager, whom an adjuster is told of" {
        val gid = assessed(*pays(ClaimsDemo.adjusterLimit + 1))
        dataOf(gid, ClaimsDemo.assessment)[ClaimsDemo.overLimit] shouldBe true
        waitsOn(adjuster, gid) shouldBe false
        waitsOn(manager, gid) shouldBe true
        hasStatus(gid, FSV.needsReview) shouldBe true
        val told = approvalTask(adjuster, gid)
        told[WVF.canApprove] shouldBe false
        told[WVF.waitingOn] shouldBe "A claims manager approves the outcome, since it pays more than $5,000."
        adjuster.expectError(EXC.notAuthorized, GEP.workflowApprove, approvalOf(gid))
        manager.postData(GEP.workflowApprove, approvalOf(gid))
        hasStatus(gid, FSV.finished) shouldBe true
    }

    "a declined claim has no amount, and waits on an adjuster" {
        val gid = assessed(ClaimsDemo.outcome to ClaimsDemo.outcomeDecline, ClaimsDemo.reasons to "The policy had lapsed.")
        dataOf(gid, ClaimsDemo.assessment).let {
            (ClaimsDemo.amount in it) shouldBe false
            it[ClaimsDemo.overLimit] shouldBe false
        }
        waitsOn(adjuster, gid) shouldBe true
        approvalTask(policyholder, gid)[WFD.display].toJsonMapOrEmpty()[WDSP.text] shouldBe
            "The claim has been assessed, and its outcome waits for approval."
    }

    "an assessment does not change once saved, before its approval or after, but by a manager who says why" {
        val gid = assessed(*pays(400))
        adjuster.expectError(EXC.notAuthorized, GEP.workflowSave, assessBody(gid, *pays(90_000)))
        adjuster.postData(GEP.workflowApprove, approvalOf(gid))
        adjuster.expectError(EXC.notAuthorized, GEP.workflowSave, assessBody(gid, *pays(90_000)))
        adjuster.expectError(EXC.conflict, GEP.patch, patchBody(gid, mapOf(*pays(90_000))))
        manager.expectError(EXC.conflict, GEP.patch, patchBody(gid, mapOf(*pays(450))))
        manager.postData(GEP.patch, patchBody(gid, mapOf(*pays(450)), reason = "The estimate left out the tax."))
        dataOf(gid, ClaimsDemo.assessment)[ClaimsDemo.amount] shouldBe 450L
    }

    "the claims register says what was decided on a claim, and who approved it" {
        val gid = assessed(*pays(700))
        adjuster.postData(GEP.workflowApprove, approvalOf(gid))
        val number = numberOf(gid)
        val row = manager.getItems(UADEP.reportRun, mapOf(RRUN.reportId to ClaimsDemo.claimsRegister))
            .map { it[RRUN.values].toJsonMapOrEmpty() }.single { it["number"] == number }
        row["outcome"] shouldBe ClaimsDemo.outcomePay
        row["amount"] shouldBe 700L
        row["policyholder"] shouldBe "Parker Policyholder"
        row["approvedBy"] shouldBe adjuster.userId
        row["status"] shouldBe FSV.finished
    }

    "provisioning it again, as each start of a reference deployment does, adds no revision" {
        val bound = cxt.mkSubContext("claimsDemoAgain", client)
        val configs = GedraConfigService.get(cxt)
        fun revisions() = configs.listConfigs(bound).map { it.gedraId to it.version }
        val before = revisions()
        provisionClaimsDemo(cxt, "t1897", skipUnchanged = true)
        revisions() shouldBe before
    }
})
