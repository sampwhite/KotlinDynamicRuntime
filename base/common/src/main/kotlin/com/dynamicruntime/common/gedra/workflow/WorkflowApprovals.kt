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
import com.dynamicruntime.common.util.toOptInstant
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
 *
 * ### An approval belongs to the engagement it was given in
 *
 * A record counts ([of]) only while the form is engaged with the workflow, and only when it was given during the
 * **current** engagement -- `approvedAt` not before the engagement's `lastEngagedAt`. So a form taken out of a
 * workflow and put back in starts over: the old approval stays in the state as history, but no longer finishes
 * the workflow, and a reviewer may approve again (the new record replaces the stale one). Without this, a form
 * could be disengaged, changed, and re-engaged into a Finished workflow no reviewer looked at.
 *
 * Deliberately **not** invalidated by edits made while engaged: nothing in the design ties an approval to the
 * data it saw, and a survey edits the same traits. That is a policy a later change could add, with a fingerprint.
 */
object WorkflowApprovals {
    /**
     * The approvals that **count** for [workflowId] in [entries] -- task id to the approval entry's data: none when
     * the form is not engaged with the workflow, and otherwise those given during the current engagement (see the
     * class doc). What the recompute, the view and the approve endpoint all read, so they agree.
     */
    fun of(entries: List<Map<String, Any?>>, workflowId: String): Map<String, Map<String, Any?>> {
        val engagement = entries
            .firstOrNull { WorkflowEngagement.isEngagementFor(it, workflowId) }
            ?.get(GE.data).toJsonMapOrEmpty()
        if (engagement[WFS.engaged] != true) {
            return emptyMap()
        }
        // An engagement written without a start (by hand, through the admin state endpoint) invalidates nothing.
        val since = engagement[WFS.lastEngagedAt].toOptInstant()
        return recorded(entries, workflowId).filterValues { data ->
            val at = data[WFS.approvedAt].toOptInstant()
            since == null || (at != null && at >= since)
        }
    }

    /** Every approval record [entries] hold for [workflowId], current or not: task id to the entry's data. */
    fun recorded(entries: List<Map<String, Any?>>, workflowId: String): Map<String, Map<String, Any?>> = entries
        .filter { it[GE.traitId].toOptStr() == WFS.workflowApproval }
        .map { it[GE.data].toJsonMapOrEmpty() }
        .filter { it[WFD.workflowId].toOptStr() == workflowId }
        .mapNotNull { data -> data[WFS.taskId].toOptStr()?.let { it to data } }
        .toMap()

    /**
     * Whether the caller would be approving **their own** form -- the second-person rule the approve endpoint
     * refuses (issue #787). Who counts as "the same person" depends on the node:
     *
     *  - **In production, the person** -- the owner's identity against the caller's. One identity can hold several
     *    users in a client (a member and an administrator persona, issue #747), and a second-person rule a person
     *    could satisfy by switching to their other user would be no rule at all.
     *  - **On a test instance, the user.** Personas exist so one tester can play several actors -- "Create a user
     *    for me" (#797) makes exactly such a user -- and submitting as Member and approving as Admin is the
     *    scenario an approval has to be tried in. The same test-instance fence `testFeatures` and the
     *    `forTestingOnly` endpoints use: an affordance on a test node, structurally off on a real one.
     *
     * The same user is its own form on any node. An identity unknown on either side (a caller or owner with no
     * identity on record) falls back to comparing users -- there is no person to compare.
     */
    fun isOwnForm(
        ownerUserId: Long,
        ownerIdentityId: String?,
        callerUserId: Long,
        callerIdentityId: String?,
        isTestInstance: Boolean,
    ): Boolean {
        if (ownerUserId == callerUserId) {
            return true
        }
        if (isTestInstance || ownerIdentityId == null || callerIdentityId == null) {
            return false
        }
        return ownerIdentityId == callerIdentityId
    }

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
     *  - it is **not approved already** in this engagement -- an approval is recorded once (409). A record from an
     *    earlier engagement does not count, and is replaced.
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
            // A stale record for this task (from an earlier engagement) shares the new one's key; it is replaced.
            val others = current.filterNot {
                it[GE.traitId].toOptStr() == WFS.workflowApproval &&
                    it[GE.data].toJsonMapOrEmpty().let { d ->
                        d[WFD.workflowId].toOptStr() == workflowId && d[WFS.taskId].toOptStr() == task.id
                    }
            }
            WorkflowEngagement.withEvent(others + approval, workflowId, event)
        }
    }
}
