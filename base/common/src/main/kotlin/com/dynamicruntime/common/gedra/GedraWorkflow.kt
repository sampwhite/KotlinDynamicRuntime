package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WfActor
import com.dynamicruntime.common.gedra.workflow.WfAssigneeKind
import com.dynamicruntime.common.gedra.workflow.WfAssignment
import com.dynamicruntime.common.gedra.workflow.WfDefinition
import com.dynamicruntime.common.gedra.workflow.WfEngine
import com.dynamicruntime.common.gedra.workflow.WfHolder
import com.dynamicruntime.common.gedra.workflow.WfTransition
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptStr

/**
 * The outcome of attempting a transition.
 *
 * A **soft-validation refusal is a result, not an error** ([advanced] false, [unmetTasks] naming the
 * unfinished steps) -- `gedra-patch.md`'s rule that completeness stops an advance without failing a write,
 * surfaced as an ordinary answer the caller inspects. A caller who may not take the transition at all, or
 * names one that does not leave the current state, gets a [KdrException] instead: those are mistakes, not
 * expected outcomes.
 */
class WfTransitionOutcome(
    /** Whether the workflow moved. False means a completeness gate refused it and the state is unchanged. */
    val advanced: Boolean,
    /** The state the workflow was in before. */
    val fromState: String,
    /** The state it moved to, or null when the advance was refused. */
    val toState: String?,
    /** When refused, the required task ids that were not complete; empty when [advanced]. */
    val unmetTasks: List<String>,
)

/**
 * Applying a workflow transition: the guarded advance at the centre of `gedra-workflow.md`. Phase 1 -- the
 * engine, no endpoints (issue #381).
 *
 * A transition is **edits, then a gate, then a state move, in one topic transaction** on the workflow
 * instance. Everything the decision rests on -- the state the transition leaves, who holds the workflow, the
 * entries the completeness gate reads -- is re-read and **re-verified under the lock**, so the pre-lock read
 * (which may be served by the gedra cache) only fast-fails the obvious refusals; the authoritative decision is
 * made against committed state. The soft-validation contract holds exactly as before: a refused advance still
 * writes the edits it carried (the refusal path writes the edited entries without the state keys), it is only
 * the *advance* that is withheld.
 *
 * **Who may act fails closed.** Taking a transition needs the transition's role *and* a match against the
 * workflow's current assignment. No assignment -- an ownerless workflow, a process-held ([WfHolder.none])
 * state -- means nobody may act, not anybody; and a stored assignment that cannot be read is a fault, never a
 * fall-back to a wider audience.
 *
 * **The reopenable task set.** A "request changes" transition ([WfTransition.reopensTasks]) sends specific
 * *tasks* back: the caller names them, the engine records them under [GD.wfReopened] with the advisor's note.
 * The overlay is cleared when the **owner moves the workflow forward** (a transition leaving a user-held
 * state) -- a resubmission is reviewed fresh -- and preserved by other transitions, so an advisor-side move
 * (a claim, a triage step) does not erase the notes the user is still reading.
 */
object GedraWorkflow {

    /**
     * Takes [transitionName] on the `wfData` gedra [workflowFullId], applying [edits] to it first.
     *
     * @param assign who to hand the workflow to on a successful advance, overriding the target state's default
     *   (owner for user-held, advisor role for advisor-held). Only an **advisor-held** target accepts one --
     *   a user-held state returns to the owner and a terminal state holds nobody, by rule -- and the
     *   assignment is validated before anything is written, because a workflow assigned to a principal nobody
     *   can match is permanently stuck (see [checkAssign]).
     * @param reopenTasks for a "request changes" transition, the task ids to send back -- at least one, each a
     *   real task of [definition]. Rejected for a transition that does not reopen.
     * @param note the reason recorded against each reopened task, shown to the user.
     */
    fun transition(
        cxt: KdrCxt,
        workflowFullId: String,
        definition: WfDefinition,
        transitionName: String,
        edits: List<GedraEdit> = emptyList(),
        scope: ReadScope,
        assign: WfAssignment? = null,
        reopenTasks: List<String> = emptyList(),
        note: String? = null,
    ): WfTransitionOutcome {
        val gedraService = GedraService.get(cxt)
        val dataService = GedraDataService.get(cxt)
        val gedraId = gedraService.readId(workflowFullId)
        if (gedraId.dataType != GedraDataType.wfData) {
            throw KdrException.mkInput("'$workflowFullId' is not a workflow (wfData) gedra.")
        }

        // Read the workflow first, through the scope, so a caller who may not see it is refused here rather
        // than by a write that reveals it exists. Everything checked against this read is re-verified under
        // the lock below -- this read (possibly cache-served) exists to fail fast and to keep 404 semantics,
        // never to authorize the write.
        val workflow = dataService.queryGedra(cxt, gedraId.fullId, GedraDataType.wfData, scope)
            ?: throw KdrException("No workflow '$workflowFullId'.", code = EXC.notFound)

        val transition = definition.transition(transitionName)
            ?: throw KdrException.mkInput("Workflow '${definition.workflowId}' has no transition '$transitionName'.")
        val actor = mkActor(cxt)

        // The transition's role: the same predicate the section gate uses, so who-may-transition cannot drift
        // from how roles are read elsewhere. Checked once here -- roles live on the caller, not the row, so
        // there is nothing to re-verify under the lock.
        if (!RoleLadder.satisfies(actor.roles, transition.by)) {
            throw KdrException("Taking '$transitionName' needs the '${transition.by}' role.", code = EXC.notAuthorized)
        }
        // Fast-fail the state and assignment checks on the pre-lock read; both are re-verified under the lock.
        checkFromState(definition, transition, wfStatusOf(workflow.extra, definition))
        checkAssignment(assignmentOf(workflow.extra, wfStatusOf(workflow.extra, definition), workflow.userId, definition), actor)

        // Reopen targets are validated against the definition before anything is written.
        if (transition.reopensTasks) {
            if (reopenTasks.isEmpty()) {
                throw KdrException.mkInput("'$transitionName' requests changes but names no task to send back.")
            }
            reopenTasks.firstOrNull { definition.task(it) == null }?.let {
                throw KdrException.mkInput("Workflow '${definition.workflowId}' has no task '$it' to reopen.")
            }
        } else if (reopenTasks.isNotEmpty()) {
            throw KdrException.mkInput("'$transitionName' does not reopen tasks, so it takes no reopen targets.")
        }
        // An explicit assignment is validated before anything is written -- an unmatched one wedges the run.
        if (assign != null) {
            checkAssign(cxt, definition, transition, assign, workflow)
        }

        return applyUnderLock(cxt, dataService, gedraId, definition, transition, edits, scope, actor, assign, reopenTasks, note)
    }

    /**
     * One topic transaction: re-read the row under the lock, re-verify the state and the assignment against
     * what is actually committed, apply the edits, evaluate the completeness gate, and write once.
     *
     * The re-verification is the point (the pre-lock read may be stale -- served by the cache, or simply
     * raced): a transition taken against a workflow another actor has already moved fails with a conflict
     * here rather than overwriting their move, which is what makes "no transition leaves a terminal state"
     * hold at runtime and a claim mean something across nodes.
     */
    @Suppress("LongParameterList")
    private fun applyUnderLock(
        cxt: KdrCxt,
        dataService: GedraDataService,
        gedraId: GedraId,
        definition: WfDefinition,
        transition: WfTransition,
        edits: List<GedraEdit>,
        scope: ReadScope,
        actor: WfActor,
        assign: WfAssignment?,
        reopenTasks: List<String>,
        note: String?,
    ): WfTransitionOutcome {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        val table = dataService.gedraDataTable(cxt)
        val moveStmt = mutableMapOf<String, Any?>().let { bind ->
            dataService.mkScopedGedraUpdate(
                sqlCxt, table, scope, "uGedraWfMove",
                "c:${GD.data} = :${GD.data}, c:${PF.updatedAt} = :${PF.updatedAt}, " +
                    "c:${PF.updatedBy} = :${PF.updatedBy}",
                bind,
            ) to bind
        }

        var unmet: List<String> = emptyList()
        var advanced = false
        SqlTopicTranProvider.executeTopicTran(
            sqlCxt, tranWfTransition, null, mapOf(GD.gedraId to gedraId.fullId),
        ) {
            // Reset per attempt: doTran may re-run this lambda on a retryable failure.
            unmet = emptyList()
            advanced = false
            val row = dataService.readForPatch(cxt, sqlCxt, table, gedraId)

            // Re-verify against committed state: the pre-lock checks ran on a read that may be stale.
            val lockedState = wfStatusOf(row.extra, definition)
            checkFromState(definition, transition, lockedState)
            checkAssignment(assignmentOf(row.extra, lockedState, row.userId, definition), actor)

            // Apply the edits to the entries this transaction actually read -- the identical fold the patch
            // performs, validation included, so a workflow edit cannot store what a patch would refuse.
            val entries = if (edits.isEmpty()) row.entries else {
                dataService.applyEditsToEntries(cxt, GedraDataType.wfData, edits, row.entries)
            }

            val incomplete = WfEngine.incompleteTasks(transition.requiresTasks, definition, entries)
            val newExtra = row.extra.toMutableMap()
            if (incomplete.isNotEmpty()) {
                // Soft validation: the advance is refused, but the edits it carried are still saved -- a
                // refused submit must not punish the user for saving. With no edits there is nothing to
                // write at all.
                unmet = incomplete
                if (edits.isEmpty()) {
                    return@executeTopicTran
                }
            } else {
                newExtra[GD.wfStatus] = transition.to
                // The assignment: the caller's validated choice, or the target state's default (owner /
                // advisor role / nobody). Nobody -> the key is cleared, so a terminal state holds no one.
                val assignment = assign ?: definition.defaultAssignment(transition.to, row.userId)
                if (assignment != null) newExtra[GD.wfAssignment] = assignment.toMap() else newExtra.remove(GD.wfAssignment)
                // The reopened overlay: a request-changes transition sets it; the owner moving the workflow
                // forward (a transition leaving a user-held state) clears it, so a resubmission is reviewed
                // fresh; any other transition -- an advisor-side claim or triage move -- preserves it, so it
                // cannot silently erase the notes the user is still reading.
                if (transition.reopensTasks) {
                    newExtra[GD.wfReopened] = reopenTasks.associateWith { note ?: "" }
                } else if (definition.statesByName.getValue(transition.from).holder == WfHolder.user) {
                    newExtra.remove(GD.wfReopened)
                }
                advanced = true
            }

            val (stmt, bind) = moveStmt
            val move = bind.toMutableMap()
            move[GD.gedraId] = gedraId.fullId
            move[GD.data] = newExtra + linkedMapOf<String, Any?>(GD.entries to entries)
            move[PF.updatedAt] = SqlTopicUtil.nextUpdatedAt(cxt, row.updatedAt)
            move[PF.updatedBy] = cxt.userProfile.userId
            val changed = sqlCxt.sqlDb.executeStatement(cxt, stmt, move)
            if (changed == 0) {
                throw KdrException(
                    "Workflow '${gedraId.fullId}' could no longer be written when the transition reached it.",
                    code = EXC.conflict,
                )
            }
        }

        return WfTransitionOutcome(
            advanced = advanced,
            fromState = transition.from,
            toState = if (advanced) transition.to else null,
            unmetTasks = unmet,
        )
    }

    // --- the decision's inputs, read the same way pre-lock and under it -----

    /**
     * The workflow state a stored `data` map says the run is in: [GD.wfStatus], or the definition's initial
     * state when absent. A key that is *present but unreadable* is a fault, not a fresh workflow -- reading it
     * as the initial state would silently rewind the run to the start of the machine.
     */
    private fun wfStatusOf(extra: Map<String, Any?>, definition: WfDefinition): String {
        val stored = extra[GD.wfStatus] ?: return definition.initialState.name
        return stored.toOptStr()
            ?: throw KdrException("A workflow's ${GD.wfStatus} is unreadable: '$stored'.")
    }

    /**
     * Who holds the workflow: the stored [GD.wfAssignment], or the state's default. A stored value
     * [WfAssignment.fromMap] cannot read is a **fault**, never a fall-back -- falling back to the state
     * default would widen a one-person claim to the whole advisor pool exactly when the data is least
     * trustworthy.
     */
    private fun assignmentOf(
        extra: Map<String, Any?>,
        stateName: String,
        ownerUserId: Long?,
        definition: WfDefinition,
    ): WfAssignment? {
        val stored = extra[GD.wfAssignment] ?: return definition.defaultAssignment(stateName, ownerUserId)
        return WfAssignment.fromMap(stored)
            ?: throw KdrException("A workflow's ${GD.wfAssignment} is unreadable: '$stored'.")
    }

    /** The transition must leave the state the workflow is in; anything else is a conflict, not a move. */
    private fun checkFromState(definition: WfDefinition, transition: WfTransition, currentState: String) {
        if (transition.from != currentState) {
            throw KdrException(
                "Transition '${transition.name}' leaves '${transition.from}', but the workflow is in " +
                    "'$currentState'.",
                code = EXC.conflict,
            )
        }
    }

    /**
     * The assignment gate, **failing closed**: no assignment means nobody may act -- an ownerless workflow or
     * a process-held state is stuck, never everybody's -- and a held assignment must match the caller.
     */
    private fun checkAssignment(assignment: WfAssignment?, actor: WfActor) {
        if (assignment == null) {
            throw KdrException("Nobody holds this workflow, so no caller may move it.", code = EXC.notAuthorized)
        }
        if (!assignment.matches(actor)) {
            throw KdrException(
                "The workflow is not assigned to you (${assignment.kind}='${assignment.value}').",
                code = EXC.notAuthorized,
            )
        }
    }

    /**
     * Validates a caller-supplied assignment before anything is written, because a workflow assigned to a
     * principal nobody can match is permanently stuck -- the only way to change an assignment is to take a
     * transition, which requires matching it.
     *
     *  - Only an **advisor-held** target takes one: a user-held state returns to the owner and a terminal
     *    state holds nobody, and an override of either would break the rule it overrides.
     *  - A **group** assignment is refused outright: there is no group-membership source yet, so no caller
     *    could ever match it (`gedra-workflow.md`, open questions).
     *  - A **role** must be the definition's advisor role or a ladder role -- a typoed capability matches
     *    nobody.
     *  - A **user** must exist, be enabled, belong to the workflow's client, and hold the advisor role, or
     *    the claim hands the workflow to someone who cannot act on it.
     */
    private fun checkAssign(
        cxt: KdrCxt,
        definition: WfDefinition,
        transition: WfTransition,
        assign: WfAssignment,
        workflow: GedraDataRow,
    ) {
        val target = definition.statesByName.getValue(transition.to)
        if (target.holder != WfHolder.advisor) {
            throw KdrException.mkInput(
                "'${transition.name}' lands in '${transition.to}', which is not advisor-held, so it takes no " +
                    "assignment: a user-held state returns to the owner and a terminal state holds nobody.",
            )
        }
        when (assign.kind) {
            WfAssigneeKind.group -> throw KdrException(
                "Group assignment is not supported yet: there is no group-membership source, so no caller " +
                    "could ever match it.",
                code = EXC.notSupported,
            )
            WfAssigneeKind.role -> {
                if (assign.value != definition.advisorRole && RoleLadder.rankOf(assign.value) == null) {
                    throw KdrException.mkInput(
                        "'${assign.value}' is not a role this workflow can be assigned to; use " +
                            "'${definition.advisorRole}' or a ladder role.",
                    )
                }
            }
            WfAssigneeKind.user -> {
                val userId = assign.value.toLongOrNull()
                    ?: throw KdrException.mkInput("'${assign.value}' is not a user id.")
                val user = UserService.get(cxt).queryByUserId(cxt, userId)
                if (user == null || !user.enabled) {
                    throw KdrException.mkInput("There is no active user ${assign.value} to assign to.")
                }
                if (user.client != workflow.client) {
                    throw KdrException.mkInput("User ${assign.value} is not in the workflow's client.")
                }
                if (!RoleLadder.satisfies(user.roles.toSet(), definition.advisorRole)) {
                    throw KdrException.mkInput(
                        "User ${assign.value} does not hold the '${definition.advisorRole}' role, so the " +
                            "claim would hand the workflow to someone who cannot act on it.",
                    )
                }
            }
        }
    }

    /**
     * The caller as an assignment sees them. The one place "what is a group" is decided -- and today the
     * answer is **no groups at all**: there is no group-membership source, and mapping the caller's primary
     * organization here would silently turn "a named group of users" into "everyone sharing an org name",
     * un-qualified by client. When a membership source exists, this is where it plugs in; until then
     * [checkAssign] refuses to write a group assignment, so the empty set refuses nothing real.
     */
    private fun mkActor(cxt: KdrCxt): WfActor =
        WfActor(cxt.userProfile.roles, cxt.userProfile.userId, groups = emptySet())

    /** Names the transition's topic transaction; prefixes its generated id, as the other gedra trans do. */
    const val tranWfTransition = "wfTransition"
}
