package com.dynamicruntime.kdn

import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.workflow.WDSP
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WSF
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.simulation.DesignDemo
import com.dynamicruntime.common.simulation.provisionDesignDemo
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The design demo's logistics workflow (issue #1071): a normal workflow whose form shows only the fields it arranges,
 * so it and the survey each save their own part of one request, ending in a reviewer's approval -- a step the designer,
 * an administrator without the `reviewer` label, sees as someone else's. Over a fresh copy of the demo, provisioned by
 * its simulation with its labeled reviewer.
 */
class DesignDemoLogisticsTest : StringSpec({
    val cxt = TestInstances.default("designDemoLogistics1071")
    val client = provisionDesignDemo(cxt, "logistics1071").clients.single()
    val designer = TestUser.create(cxt, "designer@$client.example", level = ROLE.admin, userClient = client)
    val requester = TestUser.create(cxt, "requester@$client.example", userClient = client)
    // An administrator, as the simulation makes them -- a reviewer reads forms other people own -- with the label.
    val reviewer = TestUser.create(cxt, "reviewer@$client.example", level = ROLE.admin, userClient = client)

    fun request(by: TestUser): String = by.postData(
        GEP.workflowSave,
        mapOf(
            WFD.workflowId to DesignDemo.requestWorkflow, GDF.taskId to DesignDemo.describeTask, GDF.saveId to DesignDemo.submitSave,
            GDF.entries to listOf(
                mapOf(GE.traitId to DesignDemo.eventRequest, GE.data to mapOf(DesignDemo.title to "Offsite", DesignDemo.attendees to 30, DesignDemo.venue to "office")),
            ),
        ),
    )[WSF.item].toJsonMapOrEmpty()[GDF.gedraId].toOptStr()!!
    fun answers(by: TestUser, gid: String): Map<String, Any?> = by.getItem(GEP.formDoc, mapOf(GDF.gedraId to gid))[GDF.entries]
        .toJsonListOfMaps().single { it[GE.traitId] == DesignDemo.eventRequest }[GE.data].toJsonMapOrEmpty()
    fun approvalStep(by: TestUser, gid: String): Map<String, Any?> =
        by.getData(GEP.workflowView, mapOf(WFD.workflowId to DesignDemo.logisticsWorkflow, GDF.gedraId to gid))[WFD.tasks]
            .toJsonListOfMaps().single { it[WFD.id] == DesignDemo.approveTask }

    "the logistics save writes the fields its form shows, and keeps the request's other answers" {
        val gid = request(requester)
        requester.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to DesignDemo.logisticsWorkflow))
        requester.postData(
            GEP.workflowSave,
            mapOf(
                WFD.workflowId to DesignDemo.logisticsWorkflow, GDF.taskId to DesignDemo.arrangeTask, GDF.saveId to DesignDemo.arrangeSave,
                GDF.gedraId to gid,
                GDF.entries to listOf(mapOf(GE.traitId to DesignDemo.eventRequest, GE.data to mapOf(DesignDemo.title to "Offsite", DesignDemo.venue to "hotel", DesignDemo.catering to true))),
            ),
        )
        // The headcount is the request's and the survey's, not the logistics form's: it stays.
        answers(requester, gid) shouldBe mapOf(DesignDemo.title to "Offsite", DesignDemo.attendees to 30L, DesignDemo.venue to "hotel", DesignDemo.catering to true)
    }

    "the approval is the labeled reviewer's: the designer sees it as someone else's, and the reviewer approves" {
        val gid = request(requester)
        requester.postData(GEP.workflowEngage, mapOf(GDF.gedraId to gid, WFD.workflowId to DesignDemo.logisticsWorkflow))
        requester.postData(
            GEP.workflowSave,
            mapOf(
                WFD.workflowId to DesignDemo.logisticsWorkflow, GDF.taskId to DesignDemo.arrangeTask, GDF.saveId to DesignDemo.arrangeSave,
                GDF.gedraId to gid,
                GDF.entries to listOf(mapOf(GE.traitId to DesignDemo.eventRequest, GE.data to mapOf(DesignDemo.title to "Offsite", DesignDemo.venue to "office"))),
            ),
        )
        // An administrator is not a reviewer: the step tells them to wait, and the approve endpoint refuses them.
        approvalStep(designer, gid)[WFD.display].toJsonMapOrEmpty()[WDSP.text] shouldBe "A reviewer approves the plan."
        designer.expectError(
            403, GEP.workflowApprove,
            mapOf(GDF.gedraId to gid, GDF.workflowId to DesignDemo.logisticsWorkflow, GDF.taskId to DesignDemo.approveTask),
        )
        approvalStep(reviewer, gid)[WFD.display].toJsonMapOrEmpty()[WDSP.mode] shouldBe WDSP.defaultMode
        reviewer.postData(
            GEP.workflowApprove,
            mapOf(GDF.gedraId to gid, GDF.workflowId to DesignDemo.logisticsWorkflow, GDF.taskId to DesignDemo.approveTask),
        )
        approvalStep(designer, gid)[WFD.display].toJsonMapOrEmpty()[WDSP.text] shouldBe $$"The plan has been approved by ${approvedByName}."
    }
})
