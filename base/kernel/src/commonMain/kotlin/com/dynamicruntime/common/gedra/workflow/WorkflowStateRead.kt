package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptStr

// Pure readers over a form's state entries, in the kernel so the backend's workflow code, a report's evaluator
// (issue #978) and the frontend all apply one rule. They read maps and nothing else.

/**
 * The workflow ids [entries] say this form is currently engaged with (issue #794) -- read from the asserted
 * [WFS.workflowEngagement] entries, in the order they appear. A disengaged entry ([WFS.engaged] false) keeps its
 * trail but is not engaged, so it is left out. In the kernel since #1452, so the forms page reads it by the same rule.
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

/**
 * Every approval record [entries] hold for [workflowId], current or not (issue #787): each filed under its [approvalKey] --
 * the task's id for an approval given at once, `<task>.<step>` for a step of an ordered one (issue #1702) -- to the
 * entry's data.
 */
fun recordedApprovals(entries: List<Map<String, Any?>>, workflowId: String): Map<String, Map<String, Any?>> = entries
    .filter { it[GE.traitId].toOptStr() == WFS.workflowApproval }
    .map { it[GE.data].toJsonMapOrEmpty() }
    .filter { it[WFD.workflowId].toOptStr() == workflowId }
    .mapNotNull { data -> data[WFS.taskId].toOptStr()?.let { approvalKey(it, data[WFS.stepId].toOptStr()) to data } }
    .toMap()

/**
 * The approvals that **count** for [workflowId] in [entries] -- each [approvalKey] to the approval entry's data (issue #787):
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
    return recordedApprovals(entries, workflowId).filter { (key, data) ->
        // An approval's event is noted with its key (issue #1702), the task's id for an approval given at once.
        val approved = events.indexOfLast { it[WFS.kind].toOptStr() == WFS.approvedEvent && it[WFS.note].toOptStr() == key }
        if (lastEngaged >= 0 && approved >= 0) {
            approved > lastEngaged
        } else {
            val at = data[WFS.approvedAt].toOptInstant()
            since == null || (at != null && at >= since)
        }
    }
}

/**
 * The approvals of [workflowId] reversed in the **current** engagement and not given again since (issue #1787): each
 * `approvalKey` to its latest [WFS.approvalReversedEvent] -- who reversed it, when, why, and which approval they chose
 * ([WFS.reversedFrom]). None when the form is not engaged. What a task shows in place of the approval it had, until it
 * is approved again. Read from the trail alone, after its last `engaged` event, since a reversal removes the record.
 */
fun currentReversals(entries: List<Map<String, Any?>>, workflowId: String): Map<String, Map<String, Any?>> {
    val engagement = engagementDataOf(entries, workflowId).orEmpty()
    if (engagement[WFS.engaged] != true) {
        return emptyMap()
    }
    val events = engagement[WFS.events].toJsonListOfMaps()
    val since = events.indexOfLast { it[WFS.kind].toOptStr() == WFS.engagedEvent } + 1
    val given = currentApprovals(entries, workflowId).keys
    val out = LinkedHashMap<String, Map<String, Any?>>()
    for (event in events.drop(since)) {
        val key = event[WFS.note].toOptStr() ?: continue
        when (event[WFS.kind].toOptStr()) {
            WFS.approvalReversedEvent -> out[key] = event
            // Given again after it was reversed: the reversal no longer describes the step.
            WFS.approvedEvent -> out.remove(key)
        }
    }
    return out.filterKeys { it !in given }
}
