package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.gedra.GE
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which approvals count for the current engagement when its steps share a millisecond (issue #1267): the trail's order
 * decides, since the stored stamps cannot. Where the trail lacks the events, the stamps still do.
 */
class CurrentApprovalsTest {
    private val sameMs = "2026-10-22T12:00:00.123Z"

    private fun event(kind: String, note: String? = null) =
        listOfNotNull(WFS.kind to kind, WFS.at to sameMs, note?.let { WFS.note to it }).toMap()

    private fun engagement(events: List<Map<String, Any?>>?, lastEngagedAt: String? = sameMs) = mapOf(
        GE.traitId to WFS.workflowEngagement,
        GE.data to listOfNotNull(
            WFD.workflowId to "wf", WFS.engaged to true, lastEngagedAt?.let { WFS.lastEngagedAt to it },
            events?.let { WFS.events to it },
        ).toMap(),
    )

    private val approval = mapOf(
        GE.traitId to WFS.workflowApproval,
        GE.data to mapOf(WFD.workflowId to "wf", WFS.taskId to "approve", WFS.approvedAt to sameMs, WFS.approvedBy to 9L),
    )

    @Test
    fun anApprovalBeforeAReEngagementInTheSameMillisecondIsNotCurrent() {
        val trail = listOf(
            event(WFS.engagedEvent), event(WFS.approvedEvent, "approve"), event(WFS.disengagedEvent), event(WFS.engagedEvent),
        )
        // The stamps alone (all equal) would keep it, which is the bug.
        assertEquals(emptySet(), currentApprovals(listOf(engagement(trail), approval), "wf").keys)
    }

    @Test
    fun anApprovalAfterAReEngagementInTheSameMillisecondIsCurrent() {
        val trail = listOf(
            event(WFS.engagedEvent), event(WFS.disengagedEvent), event(WFS.engagedEvent), event(WFS.approvedEvent, "approve"),
        )
        assertEquals(setOf("approve"), currentApprovals(listOf(engagement(trail), approval), "wf").keys)
    }

    @Test
    fun withoutTheEventsInTheTrailTheStampsDecide() {
        // An engagement written by hand, with no trail: an approval not before lastEngagedAt counts.
        assertEquals(setOf("approve"), currentApprovals(listOf(engagement(null), approval), "wf").keys)
        // An approval recorded with no event of its own, against a later engagement, does not.
        val later = engagement(listOf(event(WFS.engagedEvent)), lastEngagedAt = "2026-10-22T12:00:01Z")
        assertEquals(emptySet(), currentApprovals(listOf(later, approval), "wf").keys)
    }
}
