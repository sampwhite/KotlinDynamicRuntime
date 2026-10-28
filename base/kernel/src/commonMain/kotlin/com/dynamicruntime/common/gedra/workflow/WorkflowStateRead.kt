package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptStr

// Pure readers over a form's state entries, in the kernel so the backend's workflow code, a report's evaluator
// (issue #978) and the frontend all apply one rule. They read maps and nothing else.

/**
 * The workflow ids [entries] say this form is currently engaged with (issue #794; in the kernel since #1452, so the forms page reads it by the same rule) -- read from the asserted
 * [WFS.workflowEngagement] entries, in the order they appear. A disengaged entry ([WFS.engaged] false) keeps its
 * trail but is not engaged, so it is left out.
 */
fun engagedWorkflowIds(entries: List<Map<String, Any?>>): List<String> = entries
    .filter { it[GE.traitId].toOptStr() == WFS.workflowEngagement }
    .map { it[GE.data].toJsonMapOrEmpty() }
    .filter { it[WFS.engaged] == true }
    .mapNotNull { it[WFD.workflowId].toOptStr() }

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
 * none when the form is not engaged with the workflow, and otherwise those given during the **current** engagement.
 * So a form taken out of a workflow and put back in starts over: the old approval stays in the state as history and
 * no longer counts. The reasons are `WorkflowApprovals`'.
 *
 * **Order is the trail's** (issue #1267): an approval is of the current engagement when its `approved` event comes
 * after the trail's last `engaged` event. The trail is append-only, so it holds the true order even of steps taken
 * inside one millisecond, where the stored stamps (milliseconds) can be equal. Only where the trail lacks either
 * event -- an engagement or approval written by hand through the admin state endpoint -- is it decided by time:
 * `approvedAt` not before `lastEngagedAt`, and an engagement with no start invalidating nothing.
 *
 * What the recompute, the view, the approve endpoint and a report all read, so they agree on when an approval is one.
 */
fun currentApprovals(entries: List<Map<String, Any?>>, workflowId: String): Map<String, Map<String, Any?>> {
    val engagement = engagementDataOf(entries, workflowId).orEmpty()
    if (engagement[WFS.engaged] != true) {
        return emptyMap()
    }
    val events = engagement[WFS.events].toJsonListOfMaps()
    val lastEngaged = events.indexOfLast { it[WFS.kind].toOptStr() == WFS.engagedEvent }
    val since = engagement[WFS.lastEngagedAt].toOptInstant()
    return recordedApprovals(entries, workflowId).filter { (taskId, data) ->
        val approved = events.indexOfLast { it[WFS.kind].toOptStr() == WFS.approvedEvent && it[WFS.note].toOptStr() == taskId }
        if (lastEngaged >= 0 && approved >= 0) {
            approved > lastEngaged
        } else {
            val at = data[WFS.approvedAt].toOptInstant()
            since == null || (at != null && at >= since)
        }
    }
}
