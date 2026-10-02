package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptStr

// Pure readers over a form's state entries, in the kernel so the backend's workflow code, a report's evaluator
// (issue #978) and the frontend all apply one rule. They read maps and nothing else.

/** Whether the state [entry] is the engagement entry for [workflowId], engaged or not. */
fun isEngagementEntryFor(entry: Map<String, Any?>, workflowId: String): Boolean =
    entry[GE.traitId].toOptStr() == WFS.workflowEngagement &&
        entry[GE.data].toJsonMapOrEmpty()[WFD.workflowId].toOptStr() == workflowId

/** The data of [workflowId]'s engagement entry among [entries], or null when the form has none. */
fun engagementDataOf(entries: List<Map<String, Any?>>, workflowId: String): Map<String, Any?>? =
    entries.firstOrNull { isEngagementEntryFor(it, workflowId) }?.get(GE.data).toJsonMapOrEmpty().takeIf { it.isNotEmpty() }

/** The data of [workflowId]'s derived state entry among [entries], or null when nothing was computed for it. */
fun workflowStateDataOf(entries: List<Map<String, Any?>>, workflowId: String): Map<String, Any?>? = entries
    .firstOrNull {
        it[GE.traitId].toOptStr() == WFS.workflowState && it[GE.data].toJsonMapOrEmpty()[WFD.workflowId].toOptStr() == workflowId
    }
    ?.get(GE.data).toJsonMapOrEmpty().takeIf { it.isNotEmpty() }

/** Every approval record [entries] hold for [workflowId], current or not: task id to the entry's data (issue #787). */
fun recordedApprovals(entries: List<Map<String, Any?>>, workflowId: String): Map<String, Map<String, Any?>> = entries
    .filter { it[GE.traitId].toOptStr() == WFS.workflowApproval }
    .map { it[GE.data].toJsonMapOrEmpty() }
    .filter { it[WFD.workflowId].toOptStr() == workflowId }
    .mapNotNull { data -> data[WFS.taskId].toOptStr()?.let { it to data } }
    .toMap()

/**
 * The approvals that **count** for [workflowId] in [entries] -- task id to the approval entry's data (issue #787):
 * none when the form is not engaged with the workflow, and otherwise those given during the **current** engagement,
 * `approvedAt` not before the engagement's `lastEngagedAt`. So a form taken out of a workflow and put back in starts
 * over: the old approval stays in the state as history and no longer counts. The reasons are `WorkflowApprovals`'.
 *
 * What the recompute, the view, the approve endpoint and a report all read, so they agree on when an approval is one.
 */
fun currentApprovals(entries: List<Map<String, Any?>>, workflowId: String): Map<String, Map<String, Any?>> {
    val engagement = engagementDataOf(entries, workflowId).orEmpty()
    if (engagement[WFS.engaged] != true) {
        return emptyMap()
    }
    // An engagement written without a start (by hand, through the admin state endpoint) invalidates nothing.
    val since = engagement[WFS.lastEngagedAt].toOptInstant()
    return recordedApprovals(entries, workflowId).filterValues { data ->
        val at = data[WFS.approvedAt].toOptInstant()
        since == null || (at != null && at >= since)
    }
}
