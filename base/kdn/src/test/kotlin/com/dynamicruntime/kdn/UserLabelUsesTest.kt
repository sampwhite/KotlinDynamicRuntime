package com.dynamicruntime.kdn

import com.dynamicruntime.common.gedra.workflow.WFC
import com.dynamicruntime.common.gedra.workflow.WFD
import com.dynamicruntime.common.gedra.workflow.WFP
import com.dynamicruntime.common.gedra.workflow.WfVisibility
import com.dynamicruntime.common.gedra.workflow.WfVisibilityMode
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.simulation.DesignDemo
import com.dynamicruntime.common.simulation.provisionDesignDemo
import com.dynamicruntime.common.user.ADF
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.UserLabelDetail
import com.dynamicruntime.common.user.UserLabelUseKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * The label-suggestions read says what each label does (issue #1658), worked out from the client's workflows: over a
 * fresh copy of the design demo, whose logistics workflow ends in an approval only a `reviewer` may give.
 */
class UserLabelUsesTest : StringSpec({
    val cxt = TestInstances.default("labelUses1658")
    val client = provisionDesignDemo(cxt, "labeluses1658").clients.single()
    val admin = TestUser.create(cxt, "admin@$client.example", level = ROLE.admin, userClient = client)

    fun reviewer(): UserLabelDetail =
        UserLabelDetail.listFrom(admin.getData(UADEP.userLabelSuggestions)).single { it.label == DesignDemo.reviewerLabel }

    "the reviewer label approves the logistics plan, in the words a person reads" {
        val use = reviewer().uses.single()
        use.kind shouldBe UserLabelUseKind.approves
        use.workflowId shouldBe DesignDemo.logisticsWorkflow
        use.taskId shouldBe DesignDemo.approveTask
        use.cfact shouldBe WFC.reviewer
        use.sentence shouldBe "Approves “Approve the plan” in “Plan the logistics”"
        use.withheld shouldBe "approve “Approve the plan” in “Plan the logistics”"
    }

    "the count is of the users who carry the label exactly as written" {
        val before = reviewer().carriers
        val labeled = TestUser.create(cxt, "newreviewer@$client.example", userClient = client)
        admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to labeled.userId, ADF.labels to listOf(DesignDemo.reviewerLabel)))
        // A second spelling is a different label, which no workflow's userHasLabel would match.
        val misspelled = TestUser.create(cxt, "capital@$client.example", userClient = client)
        admin.postData(UADEP.userSetLabels, mapOf(ADF.userId to misspelled.userId, ADF.labels to listOf("Reviewer")))
        reviewer().carriers shouldBe before + 1
    }

    "a workflow restricted to the label is one its holders see" {
        val restricted = WfVisibility(WfVisibilityMode.restricted, listOf(DesignDemo.reviewerLabel))
        admin.postData(
            UADEP.clientWorkflowParams,
            mapOf(WFD.workflowId to DesignDemo.logisticsWorkflow, WFP.params to mapOf(WFP.visibility to restricted.toInfo())),
        )
        val uses = reviewer().uses
        uses.map { it.kind } shouldContainExactly listOf(UserLabelUseKind.approves, UserLabelUseKind.sees)
        uses.last().sentence shouldBe "Sees “Plan the logistics”, which is restricted to the holders of its labels"
        uses.last().withheld shouldBe "see “Plan the logistics”"
    }
})
