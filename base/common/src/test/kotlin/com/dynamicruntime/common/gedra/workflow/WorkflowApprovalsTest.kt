package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.gedra.GE
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * The two approval rules a running test node cannot fully exercise (issue #787), as pure functions:
 *
 *  - **Who is the owner.** On a test instance, another of the owner's users (a second persona) may approve -- that
 *    is how one tester plays submitter and reviewer. A real node has no `becomeUser` to build that scenario over
 *    HTTP, so its half of the rule -- the owner's *person* is refused -- is pinned here.
 *  - **Which approvals count.** Only those given during the current engagement of an engaged form.
 */
class WorkflowApprovalsTest : StringSpec({
    "the same user is always the owner" {
        WorkflowApprovals.isOwnForm(7L, "id1", 7L, "id1", isTestInstance = true) shouldBe true
        WorkflowApprovals.isOwnForm(7L, "id1", 7L, "id1", isTestInstance = false) shouldBe true
    }

    "another user of the owner's identity is the owner in production, and a separate reviewer on a test node" {
        WorkflowApprovals.isOwnForm(7L, "id1", 8L, "id1", isTestInstance = false) shouldBe true
        WorkflowApprovals.isOwnForm(7L, "id1", 8L, "id1", isTestInstance = true) shouldBe false
    }

    "a different person is never the owner, and an unknown identity falls back to comparing users" {
        WorkflowApprovals.isOwnForm(7L, "id1", 8L, "id2", isTestInstance = false) shouldBe false
        WorkflowApprovals.isOwnForm(7L, null, 8L, "id1", isTestInstance = false) shouldBe false
    }

    "an approval counts only while engaged, and only if given during the current engagement" {
        val engagedAt = Instant.parse("2026-09-01T10:00:00Z")
        fun engagement(engaged: Boolean, since: Instant) = mapOf(
            GE.traitId to WFS.workflowEngagement,
            GE.data to mapOf(WFD.workflowId to "wf", WFS.engaged to engaged, WFS.lastEngagedAt to since),
        )
        fun approval(at: Instant) = mapOf(
            GE.traitId to WFS.workflowApproval,
            GE.data to mapOf(WFD.workflowId to "wf", WFS.taskId to "approve", WFS.approvedAt to at, WFS.approvedBy to 9L),
        )
        val during = approval(Instant.parse("2026-09-01T11:00:00Z"))

        WorkflowApprovals.of(listOf(engagement(true, engagedAt), during), "wf").keys shouldBe setOf("approve")
        // Taken out of the workflow: nothing counts, though the record stays as history.
        WorkflowApprovals.of(listOf(engagement(false, engagedAt), during), "wf").keys shouldBe emptySet()
        WorkflowApprovals.recorded(listOf(engagement(false, engagedAt), during), "wf").keys shouldBe setOf("approve")
        // Put back in later: the old approval belongs to the earlier engagement, so it no longer counts.
        val reEngaged = engagement(true, Instant.parse("2026-09-02T09:00:00Z"))
        WorkflowApprovals.of(listOf(reEngaged, during), "wf").keys shouldBe emptySet()
    }
})
