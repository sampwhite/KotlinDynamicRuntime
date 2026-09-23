package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraDataRow
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.user.ReadScopeRules
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Approvals (issue #787): an **approval task** asks a reviewer to approve the form at a point in a normal
 * workflow, and approving records the fact in the form's state.
 *
 * ### Where an approval lives, and what it turns into
 *
 * The record is an **asserted** [WFS.workflowApproval] entry keyed by workflow and task -- who approved and when --
 * apart from the derived per-workflow state, because it is a person's act that no recompute may compute away. An
 * `approved` event is also appended to the form's engagement trail, which exists to tell the story of the form
 * and the workflow. What the record *does* happens in the recompute: an approved task's configured cfact joins
 * the workflow's own cfacts ([cfacts]), and the task counts as complete, so the CTA moves past it. A workflow that
 * wants approval to mean Finished maps that cfact to `WSC.finished` with an ordinary singleton rule.
 */
object WorkflowApprovals {
    /** The approvals [entries] record for [workflowId]: task id to the approval entry's data. */
    fun of(entries: List<Map<String, Any?>>, workflowId: String): Map<String, Map<String, Any?>> = entries
        .filter { it[GE.traitId].toOptStr() == WFS.workflowApproval }
        .map { it[GE.data].toJsonMapOrEmpty() }
        .filter { it[WFD.workflowId].toOptStr() == workflowId }
        .mapNotNull { data -> data[WFS.taskId].toOptStr()?.let { it to data } }
        .toMap()

    /** The cfacts [def]'s approved approval tasks emit, given its [approvals] -- one per approved task. */
    fun cfacts(def: WfDef, approvals: Map<String, Map<String, Any?>>): Set<String> =
        def.tasks.mapNotNullTo(LinkedHashSet()) { task -> task.approval?.cfact?.takeIf { task.id in approvals } }

    /**
     * The approvals the workflow view shows for [gedraId]'s form, read in the caller's scope -- or none, without a
     * read, when there is no form yet or [def] has no approval task.
     */
    fun forView(cxt: KdrCxt, def: WfDef, gedraId: String?): Map<String, Map<String, Any?>> {
        if (gedraId == null || def.tasks.none { it.approval != null }) {
            return emptyMap()
        }
        val states = GedraDataService.get(cxt).readState(cxt, GedraId.parse(gedraId), ReadScopeRules.forCaller(cxt))
        return of(states, def.workflowId)
    }

    /**
     * Approves [task] of [declared] on the form [row], as the person [cxt] acts as, and returns the form's state as
     * it then stands. The caller -- the approve endpoint -- has already established **who** may approve (a reviewer
     * of the task, and not the form's owner); this establishes **when**, under the lock and against the state as
     * freshly recomputed ([GedraDataService.changeState]):
     *
     *  - the form is **engaged** with the workflow -- an approval is a step in a workflow the form is in;
     *  - the task is the workflow's **CTA** -- the earlier tasks are done, so there is something to approve;
     *  - it is **not approved already** -- an approval is recorded once (409).
     */
    fun approve(cxt: KdrCxt, row: GedraDataRow, declared: WfDeclared, task: WfTask): List<Map<String, Any?>> {
        val workflowId = declared.def.workflowId
        // The actor and the moment, taken before the owner binding -- they are who approved, not whose form it is.
        val at = cxt.instanceNow()
        val by = cxt.userProfile.userId
        return GedraDataService.get(cxt).changeState(cxt, row) { current ->
            if (workflowId !in engagedWorkflowIds(current)) {
                throw KdrException.mkInput(
                    "This form is not in workflow '$workflowId', so it cannot be approved there. It has to be " +
                        "engaged with the workflow first.",
                )
            }
            if (task.id in of(current, workflowId)) {
                throw KdrException(
                    "Task '${task.id}' of workflow '$workflowId' is already approved for this form.",
                    code = EXC.conflict,
                )
            }
            val cta = current
                .firstOrNull {
                    it[GE.traitId].toOptStr() == WFS.workflowState &&
                        it[GE.data].toJsonMapOrEmpty()[WFD.workflowId].toOptStr() == workflowId
                }
                ?.get(GE.data).toJsonMapOrEmpty()[WFS.ctaTask].toOptStr()
            if (cta != task.id) {
                throw KdrException.mkInput(
                    "Task '${task.id}' is not the current task of workflow '$workflowId' " +
                        (cta?.let { "-- '$it' comes first. " } ?: "-- there is nothing left to do. ") +
                        "An approval is given once the work before it is complete.",
                )
            }
            val approval = mapOf(
                GE.traitId to WFS.workflowApproval,
                GE.data to linkedMapOf(
                    WFD.workflowId to workflowId,
                    WFS.taskId to task.id,
                    WFS.approvedAt to at,
                    WFS.approvedBy to by,
                ),
            )
            val event = linkedMapOf<String, Any?>(WFS.kind to WFS.approvedEvent, WFS.at to at, WFS.by to by, WFS.note to task.id)
            WorkflowEngagement.withEvent(current + approval, workflowId, event)
        }
    }
}
