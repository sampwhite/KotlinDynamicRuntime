package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraDataRow
import com.dynamicruntime.common.gedra.GedraDataService
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.user.ReadScopeRules
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr

/**
 * Approvals (issue #787): an **approval task** asks a reviewer to approve the form at a point in a normal
 * workflow, and approving records the fact in the form's state.
 *
 * ### Where an approval lives, and what it turns into
 *
 * The record is an **asserted** [WFS.workflowApproval] entry keyed by workflow, task, and step (issue #1702: an
 * approval given at once has one step, the task's own) -- who approved and when --
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
 * data it saw, and a core details workflow edits the same traits. That is a policy a later change could add, with a fingerprint.
 */
object WorkflowApprovals {
    /**
     * The approvals that **count** for [workflowId] in [entries] -- task id to the approval entry's data: none when
     * the form is not engaged with the workflow, and otherwise those given during the current engagement (see the
     * class doc). What the recompute, the view, and the approve endpoint all read, so they agree. The rule itself is
     * the kernel's `currentApprovals`, which a report's evaluator reads too (issue #978).
     */
    fun of(entries: List<Map<String, Any?>>, workflowId: String): Map<String, Map<String, Any?>> =
        currentApprovals(entries, workflowId)

    /** Every approval record [entries] hold for [workflowId], current or not: task id to the entry's data. */
    fun recorded(entries: List<Map<String, Any?>>, workflowId: String): Map<String, Map<String, Any?>> =
        recordedApprovals(entries, workflowId)

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

    /**
     * The cfacts [def]'s approval tasks emit, given its [approvals]: each step's own as it is given, and the approval's
     * once every step is (issue #1702) -- for an approval given at once, its cfact once approved.
     */
    fun cfacts(def: WfDef, approvals: Map<String, Map<String, Any?>>): Set<String> = def.tasks.flatMapTo(LinkedHashSet()) { task ->
        val approval = task.approval ?: return@flatMapTo emptyList()
        task.givenStepCfacts(approvals.keys) + listOfNotNull(approval.cfact.takeIf { task.isApproved(approvals.keys) })
    }

    /**
     * What the workflow view shows of a form's approvals: those that count ([current], by `approvalKey`), and those
     * reversed in this engagement and not given again ([reversals], each key to its latest reversal event, issue #1787).
     */
    class ForView(val current: Map<String, Map<String, Any?>>, val reversals: Map<String, Map<String, Any?>>) {
        companion object {
            val none = ForView(emptyMap(), emptyMap())
        }
    }

    /**
     * The approvals the workflow view shows for [gedraId]'s form, read in the caller's scope -- or none, without a
     * read, when there is no form yet or [def] has no approval task.
     */
    fun forView(cxt: KdrCxt, def: WfDef, gedraId: String?): ForView {
        if (gedraId == null || def.tasks.none { it.approval != null }) {
            return ForView.none
        }
        val states = GedraDataService.get(cxt).readState(cxt, GedraId.parse(gedraId), ReadScopeRules.forCaller(cxt))
        return ForView(of(states, def.workflowId), currentReversals(states, def.workflowId))
    }

    /**
     * Whether the caller may reverse approvals of [def] on a form of [client] (issue #1787): they administer that client
     * ([AdminRules.oversees] -- their own, or any for an administrator of every client), and the workflow is being
     * calculated, as an approval is only given while it is. Not editors, reviewers, or the form's owner: an approval is a
     * reviewer's act, and taking it back is a client's administrator's. What the reverse endpoint enforces and the view
     * offers the control by, so the two agree.
     */
    fun mayReverse(cxt: KdrCxt, client: String, def: WfDef): Boolean =
        AdminRules.oversees(cxt, client) && WorkflowPhases.calculatesNow(cxt, client, def)

    /**
     * Every approval of [def] in the workflow's order (issue #1787): each approval task in task order, and each of its
     * steps in turn, as `approvalKey`s -- the order a reversal takes the later ones away in.
     */
    fun keysInOrder(def: WfDef): List<String> = def.tasks.flatMap { task -> task.approvalSteps.map { approvalKey(task.id, it.id) } }

    /**
     * The approvals reversing [key] takes away, given the form's current [approvals] (issue #1787): [key] itself and every
     * later step and approval of [def] that counts now -- each was given on the strength of the one reversed. Earlier
     * ones, and later ones not given, are left alone. Empty when [key] is not given now or names no approval of [def].
     */
    fun reversedWith(def: WfDef, approvals: Map<String, Map<String, Any?>>, key: String): List<String> {
        if (key !in approvals) return emptyList()
        val order = keysInOrder(def)
        val at = order.indexOf(key)
        if (at < 0) return emptyList()
        return order.drop(at).filter { it in approvals }
    }

    /**
     * Gives step [stepId] of [task] of [declared] on the form [row], as the person [cxt] acts as, and returns the form's
     * state as it then stands. An approval given at once has one step, whose id is the task's (issue #1702). The caller
     * -- the approve endpoint -- has already established **who** may approve (a reviewer of the task, and not the
     * form's owner); this establishes **when**, under the lock and against the state as freshly recomputed
     * ([GedraDataService.changeState]):
     *
     *  - the form is **engaged** with the workflow -- an approval is a step in a workflow the form is in;
     *  - the task is the workflow's **CTA** -- the earlier tasks are done, so there is something to approve;
     *  - it is **not approved already** in this engagement -- every step given -- which is a conflict (409). A record from
     *    an earlier engagement does not count, and is replaced;
     *  - [stepId] is the **current** step, the first not given -- else a conflict (409): a stale page, or two people
     *    pressing at once, gives no step but the one the form is waiting on. Null names the task's one step, which only
     *    an approval given at once has; for one in steps it is refused (400), naming the step the form waits on.
     */
    fun approve(
        cxt: KdrCxt,
        row: GedraDataRow,
        declared: WfDeclared,
        task: WfTask,
        stepId: String?,
        /**
         * Whether the caller may (issue #1595): run under the lock once the checks above pass -- the task is the current
         * one -- with the form's state as it then stands, and throwing to refuse. The approve endpoint's judge: the
         * approval's rule and the CTA's filter rule, which only means something of the current task.
         */
        authorize: (current: List<Map<String, Any?>>) -> Unit = {},
    ): List<Map<String, Any?>> {
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
            val given = of(current, workflowId).keys
            val step = task.currentStep(given) ?: throw KdrException(
                "Task '${task.id}' of workflow '$workflowId' is already approved for this form.",
                code = EXC.conflict,
            )
            if (stepId == null && !task.approval?.steps.isNullOrEmpty()) {
                throw KdrException.mkInput(
                    "Task '${task.id}' of workflow '$workflowId' is approved in steps, so an approval names the step it " +
                        "gives (${GDF.stepId}). This form waits on step '${step.id}'.",
                )
            }
            if (step.id != (stepId ?: task.id)) {
                throw KdrException(
                    "Step '$stepId' of task '${task.id}' is not the one this form waits on: step '${step.id}' is. " +
                        "The steps are given in order.",
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
            authorize(current)
            // After every stamp already on the engagement (issue #1267), so the approval reads as later than the
            // engagement it belongs to even inside one millisecond.
            val stamp = WorkflowEngagement.stampAfter(current, workflowId, at)
            val approval = mapOf(
                GE.traitId to WFS.workflowApproval,
                GE.data to linkedMapOf(
                    WFD.workflowId to workflowId,
                    WFS.taskId to task.id,
                    WFS.stepId to step.id,
                    WFS.approvedAt to stamp,
                    WFS.approvedBy to by,
                ),
            )
            val key = approvalKey(task.id, step.id)
            val event = linkedMapOf<String, Any?>(WFS.kind to WFS.approvedEvent, WFS.at to stamp, WFS.by to by, WFS.note to key)
            // A stale record for this step (from an earlier engagement) shares the new one's key; it is replaced.
            val others = current.filterNot {
                it[GE.traitId].toOptStr() == WFS.workflowApproval &&
                    it[GE.data].toJsonMapOrEmpty().let { d ->
                        d[WFD.workflowId].toOptStr() == workflowId &&
                            approvalKey(d[WFS.taskId].toOptStr().orEmpty(), d[WFS.stepId].toOptStr()) == key
                    }
            }
            WorkflowEngagement.withEvent(others + approval, workflowId, event)
        }
    }

    /**
     * Reverses step [stepId] of [task] of [declared] on the form [row] (issue #1787), as the administrator [cxt] acts as,
     * for [reason], and returns the form's state as it then stands. Null [stepId] names the task's one step, which only an
     * approval given at once has. The caller -- the reverse endpoint -- has already established **who** may
     * ([mayReverse]); this establishes, under the lock and against the state as freshly recomputed
     * ([GedraDataService.changeState]), that the form is engaged with the workflow and the step's approval counts now --
     * else a conflict (409): a stale page, or two administrators at once, reverses nothing.
     *
     * It takes away the step's approval and every later one that counts ([reversedWith]): their records are removed, so
     * every reader of [of] -- the recompute, locks, reports, Needs Review -- sees them gone with no change of its own, and
     * an [WFS.approvalReversedEvent] is appended for each, which is the record of the reversal. The recompute after puts
     * the call to action back on the reversed step, takes away the cfacts the approvals gave, and re-arms their locks.
     * The form's data is untouched.
     */
    fun reverse(
        cxt: KdrCxt,
        row: GedraDataRow,
        declared: WfDeclared,
        task: WfTask,
        stepId: String?,
        reason: String,
    ): List<Map<String, Any?>> {
        val def = declared.def
        val workflowId = def.workflowId
        val step = if (stepId == null) {
            if (!task.approval?.steps.isNullOrEmpty()) {
                throw KdrException.mkInput(
                    "Task '${task.id}' of workflow '$workflowId' is approved in steps, so a reversal names the step it " +
                        "reverses (${GDF.stepId}).",
                )
            }
            task.approvalSteps.single()
        } else {
            task.approvalStep(stepId)
                ?: throw KdrException.mkInput("Task '${task.id}' of workflow '$workflowId' has no approval step '$stepId'.")
        }
        val key = approvalKey(task.id, step.id)
        // The actor and the moment, taken before the owner binding -- they are who reversed, not whose form it is.
        val at = cxt.instanceNow()
        val by = cxt.userProfile.userId
        return GedraDataService.get(cxt).changeState(cxt, row) { current ->
            if (workflowId !in engagedWorkflowIds(current)) {
                throw KdrException.mkInput(
                    "This form is not in workflow '$workflowId', so it has no approval there to reverse.",
                )
            }
            val reversed = reversedWith(def, of(current, workflowId), key)
            if (reversed.isEmpty()) {
                throw KdrException(
                    "Step '${step.id}' of task '${task.id}' of workflow '$workflowId' is not approved for this form, so " +
                        "there is nothing to reverse.",
                    code = EXC.conflict,
                )
            }
            val gone = reversed.toSet()
            val kept = current.filterNot {
                it[GE.traitId].toOptStr() == WFS.workflowApproval &&
                    it[GE.data].toJsonMapOrEmpty().let { d ->
                        d[WFD.workflowId].toOptStr() == workflowId &&
                            approvalKey(d[WFS.taskId].toOptStr().orEmpty(), d[WFS.stepId].toOptStr()) in gone
                    }
            }
            // One event per approval taken away, each after every stamp already on the engagement (issue #1267), so the
            // trail keeps their order even inside one millisecond.
            reversed.fold(kept) { entries, k ->
                val stamp = WorkflowEngagement.stampAfter(entries, workflowId, at)
                val event = linkedMapOf<String, Any?>(
                    WFS.kind to WFS.approvalReversedEvent,
                    WFS.at to stamp,
                    WFS.by to by,
                    WFS.note to k,
                    WFS.reversedFrom to key,
                    WFS.reason to reason,
                )
                WorkflowEngagement.withEvent(entries, workflowId, event)
            }
        }
    }
}
