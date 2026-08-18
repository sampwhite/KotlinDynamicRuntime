package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GD
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEdit
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraWorkflow
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.workflow.WF
import com.dynamicruntime.common.gedra.workflow.WfAssigneeKind
import com.dynamicruntime.common.gedra.workflow.WfAssignment
import com.dynamicruntime.common.gedra.workflow.WfDefinition
import com.dynamicruntime.common.gedra.workflow.WfHolder
import com.dynamicruntime.common.gedra.workflow.WfState
import com.dynamicruntime.common.gedra.workflow.WfTask
import com.dynamicruntime.common.gedra.workflow.WfTransition
import com.dynamicruntime.common.http.request.ROLE
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Phase 1 of the gedra workflow (issue #381): the transition operation end to end, in process, against a real
 * booted instance -- the guarded advance, the soft-validation refusal that still saves the edit, the
 * reopenable task set, and the **role / group / user assignment** that governs who may act. All workflow state
 * lives in the `data` map (no columns), so it is read back off `GedraDataRow.extra`.
 *
 * Everything is created in this spec's **own client**, so it cannot collide with another spec's rows in the
 * shared in-memory database. Definitions are constructed directly here; the authoring DSL is phase 2.
 */
class GedraWorkflowTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("gedraWf", "gedraWfTest")

    val client = "gwfclient"
    val applicantId = 91001L
    val advisorId = 91002L
    val advisor2Id = 91003L
    val strangerId = 91009L

    fun service(): GedraDataService = GedraDataService.get(cxt).shouldNotBeNull()

    val flow = WfDefinition(
        workflowId = "review",
        states = listOf(
            WfState("draft", WfHolder.user, initial = true),
            WfState("inReview", WfHolder.advisor),
            WfState("changesRequested", WfHolder.user),
            WfState("approved", WfHolder.none, terminal = true),
        ),
        transitions = listOf(
            WfTransition("submit", "draft", "inReview", ROLE.user, requiresTasks = listOf("details")),
            WfTransition("approve", "inReview", "approved", WF.advisor),
            WfTransition("return", "inReview", "changesRequested", WF.advisor, reopensTasks = true),
            WfTransition("resubmit", "changesRequested", "inReview", ROLE.user, requiresTasks = listOf("details")),
        ),
        tasks = listOf(WfTask("details", requiredTraitIds = listOf(GT.name))),
    )

    fun ctxFor(userId: Long, roles: Set<String>): KdrCxt = cxt.mkSubContext("gwf$userId", client).also {
        it.bindToUserProfile(UserProfile(authId = userId.toString(), userId = userId, client = client, roles = roles))
    }
    fun asApplicant() = ctxFor(applicantId, setOf(ROLE.user))
    fun asAdvisor() = ctxFor(advisorId, setOf(ROLE.user, WF.advisor))
    fun asAdvisor2() = ctxFor(advisor2Id, setOf(ROLE.user, WF.advisor))
    fun asStranger() = ctxFor(strangerId, setOf(ROLE.user))

    fun createWorkflow(name: String? = null): String {
        val entries = if (name == null) emptyList() else {
            listOf(mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to name)))
        }
        return service().createGedra(asApplicant(), GedraDataType.wfData, entries).gedraId.fullId
    }

    fun nameEdit(name: String) = GedraEdit(GedraEditAction.addOrMerge, GT.name, data = mapOf(GT.name to name))
    fun assignmentOf(id: String): WfAssignment? {
        val row = service().queryGedra(cxt, id, GedraDataType.wfData, ReadScope.ofClient(client)).shouldNotBeNull()
        return WfAssignment.fromMap(row.extra[GD.wfAssignment])
    }
    fun statusOf(id: String): String? =
        service().queryGedra(cxt, id, GedraDataType.wfData, ReadScope.ofClient(client))?.extra?.get(GD.wfStatus) as? String

    "a complete submit hands the workflow to the advisor role, not to one person" {
        val id = createWorkflow()
        val own = ReadScope.ofUser(applicantId)

        // Incomplete: refused by the step's completeness, still in draft.
        val refused = GedraWorkflow.transition(asApplicant(), id, flow, "submit", scope = own)
        refused.advanced shouldBe false
        refused.unmetTasks shouldContainExactly listOf("details")
        statusOf(id).shouldBeNull()

        // Complete: advances, and the default assignment for an advisor-held state is the advisor *role*.
        GedraWorkflow.transition(
            asApplicant(), id, flow, "submit", edits = listOf(nameEdit("Loan review")), scope = own,
        ).advanced shouldBe true
        statusOf(id) shouldBe "inReview"
        val assignment = assignmentOf(id).shouldNotBeNull()
        assignment.kind shouldBe WfAssigneeKind.role
        assignment.value shouldBe WF.advisor
    }

    "any advisor may act on a role-assigned workflow; a non-advisor and a non-owner may not" {
        val id = createWorkflow("Ready")
        GedraWorkflow.transition(asApplicant(), id, flow, "submit", scope = ReadScope.ofUser(applicantId))

        // A different user cannot submit someone else's draft: a user-held state is assigned to the owner.
        val fresh = createWorkflow("Not yours")
        shouldThrow<KdrException> {
            GedraWorkflow.transition(asStranger(), fresh, flow, "submit", scope = ReadScope.ofClient(client))
        }
        // A plain user cannot approve -- they hold neither the role nor the assignment.
        shouldThrow<KdrException> {
            GedraWorkflow.transition(asApplicant(), id, flow, "approve", scope = ReadScope.ofClient(client))
        }
        // Either advisor may, since the pool is the whole role -- advisor2 approves one it never submitted.
        GedraWorkflow.transition(asAdvisor2(), id, flow, "approve", scope = ReadScope.ofClient(client))
            .toState shouldBe "approved"
    }

    "an advisor can claim a workflow, narrowing it from the role to one person" {
        val id = createWorkflow("To claim")
        // The applicant submits, claiming it to a specific advisor rather than the pool.
        GedraWorkflow.transition(
            asApplicant(), id, flow, "submit", scope = ReadScope.ofUser(applicantId),
            assign = WfAssignment.ofUser(advisorId),
        ).advanced shouldBe true
        assignmentOf(id).shouldNotBeNull().let {
            it.kind shouldBe WfAssigneeKind.user
            it.value shouldBe advisorId.toString()
        }

        // The other advisor holds the role but not this claim, so cannot act...
        shouldThrow<KdrException> {
            GedraWorkflow.transition(asAdvisor2(), id, flow, "approve", scope = ReadScope.ofClient(client))
        }
        // ...while the claimed advisor can.
        GedraWorkflow.transition(asAdvisor(), id, flow, "approve", scope = ReadScope.ofClient(client))
            .toState shouldBe "approved"
    }

    "the advisor sends a step back, the user resubmits, and the reopened overlay clears" {
        val id = createWorkflow("First draft")
        GedraWorkflow.transition(asApplicant(), id, flow, "submit", scope = ReadScope.ofUser(applicantId))

        val returned = GedraWorkflow.transition(
            asAdvisor(), id, flow, "return", scope = ReadScope.ofClient(client),
            reopenTasks = listOf("details"), note = "Please correct the name.",
        )
        returned.toState shouldBe "changesRequested"

        // Back with the user (a user-held state returns to the owner), with the reopened step and its note.
        val back = service().queryGedra(cxt, id, GedraDataType.wfData, ReadScope.ofClient(client)).shouldNotBeNull()
        back.extra[GD.wfStatus] shouldBe "changesRequested"
        assignmentOf(id).shouldNotBeNull().let {
            it.kind shouldBe WfAssigneeKind.user
            it.value shouldBe applicantId.toString()
        }
        back.extra[GD.wfReopened] shouldBe mapOf("details" to "Please correct the name.")

        // Resubmitting moves forward, which clears the overlay so the advisor reviews afresh.
        GedraWorkflow.transition(
            asApplicant(), id, flow, "resubmit", edits = listOf(nameEdit("Corrected")),
            scope = ReadScope.ofUser(applicantId),
        ).toState shouldBe "inReview"
        val afresh = service().queryGedra(cxt, id, GedraDataType.wfData, ReadScope.ofClient(client)).shouldNotBeNull()
        afresh.extra[GD.wfReopened].shouldBeNull()
        assignmentOf(id)?.kind shouldBe WfAssigneeKind.role // back to the pool
    }

    "approving to a terminal state clears the assignment, and misuse is caught" {
        val id = createWorkflow("Ready")
        GedraWorkflow.transition(asApplicant(), id, flow, "submit", scope = ReadScope.ofUser(applicantId))

        // A "request changes" that names no step is a mistake -- the point is a *particular* step.
        shouldThrow<KdrException> {
            GedraWorkflow.transition(asAdvisor(), id, flow, "return", scope = ReadScope.ofClient(client))
        }
        // A transition that does not leave the current state (inReview) is a plain mistake.
        shouldThrow<KdrException> {
            GedraWorkflow.transition(asApplicant(), id, flow, "submit", scope = ReadScope.ofUser(applicantId))
        }

        GedraWorkflow.transition(asAdvisor(), id, flow, "approve", scope = ReadScope.ofClient(client))
            .toState shouldBe "approved"
        statusOf(id) shouldBe "approved"
        assignmentOf(id).shouldBeNull() // a terminal state holds nobody
    }

    "a refused submit still saves the edit it carried -- soft validation never fails the write" {
        val gated = WfDefinition(
            workflowId = "gated",
            states = listOf(WfState("draft", WfHolder.user, initial = true), WfState("inReview", WfHolder.advisor)),
            transitions = listOf(
                WfTransition("submit", "draft", "inReview", ROLE.user, requiresTasks = listOf("details", "signoff")),
            ),
            tasks = listOf(
                WfTask("details", requiredTraitIds = listOf(GT.name)),
                WfTask("signoff", requiredTraitIds = listOf("signoff")), // never fillable here
            ),
        )
        val id = createWorkflow()
        val own = ReadScope.ofUser(applicantId)

        val refused = GedraWorkflow.transition(
            asApplicant(), id, gated, "submit", edits = listOf(nameEdit("Saved anyway")), scope = own,
        )
        refused.advanced shouldBe false
        refused.unmetTasks shouldContainExactly listOf("signoff")

        // The edit committed even though the advance was refused.
        val row = service().queryGedra(cxt, id, GedraDataType.wfData, own).shouldNotBeNull()
        row.extra[GD.wfStatus].shouldBeNull()
        row.entries.single()[GE.data].toString() shouldBe mapOf(GT.name to "Saved anyway").toString()
    }
})
