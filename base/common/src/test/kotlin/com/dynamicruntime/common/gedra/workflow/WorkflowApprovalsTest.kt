package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.LiteCxt
import com.dynamicruntime.common.gedra.GE
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
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

    "a new step is stamped after the newest stamp on its engagement, which a step in the same millisecond would equal (issue #1267)" {
        val t = Instant.parse("2026-10-22T12:00:00.123Z")
        val entries = listOf(
            mapOf(
                GE.traitId to WFS.workflowEngagement,
                GE.data to mapOf(
                    WFD.workflowId to "wf", WFS.engaged to true, WFS.lastEngagedAt to t,
                    WFS.events to listOf(mapOf(WFS.kind to WFS.engagedEvent, WFS.at to t)),
                ),
            ),
            mapOf(
                GE.traitId to WFS.workflowApproval,
                GE.data to mapOf(WFD.workflowId to "wf", WFS.taskId to "approve", WFS.approvedAt to t + 2.milliseconds),
            ),
        )
        // The newest is the approval's, two milliseconds on; a clock that has not passed it is moved one past it.
        WorkflowEngagement.newestStamp(entries, "wf") shouldBe t + 2.milliseconds
        WorkflowEngagement.stampAfter(entries, "wf", t) shouldBe t + 3.milliseconds
        // A clock already past it is taken as it is.
        WorkflowEngagement.stampAfter(entries, "wf", t + 10.milliseconds) shouldBe t + 10.milliseconds
        // Nothing on the engagement yet: the clock as it is.
        WorkflowEngagement.stampAfter(emptyList(), "wf", t) shouldBe t
    }

    // --- reversing an approval (issue #1787) -------------------------------------------------------------------------

    /** Record, approve A at once, record more, then approve B in two steps: the order a reversal walks. */
    fun reversalDef(): WfDef = parseWfDef(
        LiteCxt(),
        WfDefBuilder("wf", WfEntry.normal).apply {
            task("record", "Record") { trait("a"); save("s", "S", WfSaveKind.edit) }
            task("a", "Approve A") { approval("aApproved", "Approve A.", "Approve") }
            task("more", "Record more") { trait("b"); save("s", "S", WfSaveKind.edit) }
            task("b", "Approve B") {
                approvalSteps(
                    "bApproved",
                    listOf(
                        WfApprovalStep("look", "First look", "Look.", "Looked", null, null, null),
                        WfApprovalStep("sign", "Sign-off", "Sign.", "Sign off", null, null, null),
                    ),
                )
            }
        }.build(),
    )

    "a reversal takes the approval named and every later one that counts, and leaves earlier ones alone" {
        val def = reversalDef()
        WorkflowApprovals.keysInOrder(def) shouldBe listOf("a", "b.look", "b.sign")
        val all = mapOf("a" to emptyMap<String, Any?>(), "b.look" to emptyMap(), "b.sign" to emptyMap())
        WorkflowApprovals.reversedWith(def, all, "a") shouldBe listOf("a", "b.look", "b.sign")
        WorkflowApprovals.reversedWith(def, all, "b.look") shouldBe listOf("b.look", "b.sign")
        WorkflowApprovals.reversedWith(def, all, "b.sign") shouldBe listOf("b.sign")
        // A later step not given is not in the answer; nothing is when the named one is not given now.
        val partial = mapOf("a" to emptyMap<String, Any?>(), "b.look" to emptyMap())
        WorkflowApprovals.reversedWith(def, partial, "a") shouldBe listOf("a", "b.look")
        WorkflowApprovals.reversedWith(def, partial, "b.sign") shouldBe emptyList()
        // A data task is no approval, and a key the workflow has none of reverses nothing.
        WorkflowApprovals.reversedWith(def, all + ("record" to emptyMap()), "record") shouldBe emptyList()
    }

    "a reversal describes the step until it is given again, and only in the engagement it was made in" {
        val t = Instant.parse("2026-11-11T10:00:00Z")
        fun event(kind: String, note: String?, ms: Long, extra: Map<String, Any?> = emptyMap()) =
            linkedMapOf<String, Any?>(WFS.kind to kind, WFS.at to t + ms.milliseconds, WFS.by to 4L).apply {
                note?.let { put(WFS.note, it) }
                putAll(extra)
            }
        fun state(vararg events: Map<String, Any?>, approved: List<String> = emptyList()) = listOf(
            mapOf(
                GE.traitId to WFS.workflowEngagement,
                GE.data to mapOf(WFD.workflowId to "wf", WFS.engaged to true, WFS.lastEngagedAt to t, WFS.events to events.toList()),
            ),
        ) + approved.map {
            mapOf(
                GE.traitId to WFS.workflowApproval,
                GE.data to mapOf(WFD.workflowId to "wf", WFS.taskId to it, WFS.approvedAt to t, WFS.approvedBy to 9L),
            )
        }
        val reversed = event(WFS.approvalReversedEvent, "a", 3, mapOf(WFS.reason to "Wrong figures", WFS.reversedFrom to "a"))
        val engaged = event(WFS.engagedEvent, null, 0)
        currentReversals(state(engaged, event(WFS.approvedEvent, "a", 1), reversed), "wf") shouldBe mapOf("a" to reversed)
        // Given again: the reversal no longer describes it.
        val again = event(WFS.approvedEvent, "a", 5)
        currentReversals(state(engaged, reversed, again, approved = listOf("a")), "wf") shouldBe emptyMap()
        // Engaged again after it: a new engagement starts with nothing reversed.
        currentReversals(state(reversed, event(WFS.engagedEvent, null, 7)), "wf") shouldBe emptyMap()
    }
})
