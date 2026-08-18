package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WfActor
import com.dynamicruntime.common.gedra.workflow.WfAssignment
import com.dynamicruntime.common.gedra.workflow.WfDefinition
import com.dynamicruntime.common.gedra.workflow.WfEngine
import com.dynamicruntime.common.gedra.workflow.WfTransition
import com.dynamicruntime.common.http.request.RoleLadder
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlScopeUtil
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicTranProvider
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptInstant
import com.dynamicruntime.common.util.toOptLong
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
 * A transition is **edits, then a gate, then a state move**, and the order carries the soft-validation
 * contract: the edits are applied first and **commit whether or not the advance is allowed** (a refused
 * submit still saves what the user typed), then the transition's required tasks are checked for completeness
 * against the resulting entries, and only if they pass does the state move. The move -- the state, the
 * assignment, and the reopened-task overlay -- is one guarded update under the workflow instance's own topic
 * transaction, so the decision is atomic against committed state and stamps a monotonic `updatedAt` the cache
 * cannot miss.
 *
 * **No state lives in a column.** The status, the assignment and the reopened overlay are all keys in the
 * workflow's `data` map ([GD.wfStatus] / [GD.wfAssignment] / [GD.wfReopened]); the query that finds a
 * workflow -- the advisor queue -- is a computed index on the gedra cache, not a column. So this reads and
 * writes only `data`.
 *
 * **Who may act is a role, a group or a user.** A state's holder gives the default: a user-held state is the
 * owner's, an advisor-held state is the advisor *role's* (the pool). A caller passes an explicit [WfAssignment]
 * to narrow that to a group or one person. Taking a transition needs both the transition's [WfTransition.by]
 * role and a match against the workflow's *current* assignment -- the role says "may do this kind of thing",
 * the assignment says "this one is yours".
 */
object GedraWorkflow {

    /**
     * Takes [transitionName] on the `wfData` gedra [workflowFullId], applying [edits] to it first.
     *
     * @param assign who to hand the workflow to on a successful advance, overriding the target state's default
     *   (owner for user-held, advisor role for advisor-held). Use it to claim an advisor-held workflow to one
     *   person ([WfAssignment.ofUser]) or route it to a group ([WfAssignment.ofGroup]).
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
        val gedraService = GedraService.require(cxt)
        val dataService = GedraDataService.require(cxt)
        val gedraId = gedraService.readId(workflowFullId)
        if (gedraId.dataType != GedraDataType.wfData) {
            throw KdrException.mkInput("'$workflowFullId' is not a workflow (wfData) gedra.")
        }

        // Read the workflow first, through the scope, so a caller who may not see it is refused here rather
        // than by a write that reveals it exists. This is also where the current state and assignment come
        // from -- both `data` keys, which land on the row's `extra`.
        val workflow = dataService.queryGedra(cxt, gedraId.fullId, GedraDataType.wfData, scope)
            ?: throw KdrException("No workflow '$workflowFullId'.", code = EXC.notFound)
        val currentStateName = workflow.extra[GD.wfStatus].toOptStr() ?: definition.initialState.name
        val currentAssignment = WfAssignment.fromMap(workflow.extra[GD.wfAssignment])
            ?: definition.defaultAssignment(currentStateName, workflow.userId)

        val transition = definition.transition(transitionName)
            ?: throw KdrException.mkInput("Workflow '${definition.workflowId}' has no transition '$transitionName'.")
        if (transition.from != currentStateName) {
            throw KdrException.mkInput(
                "Transition '$transitionName' leaves '${transition.from}', but the workflow is in " +
                    "'$currentStateName'.",
            )
        }

        // Who may act: the transition's role, and a match against who currently holds the workflow. The role
        // is the same predicate the section gate uses; the assignment is what makes "assigned to the advisor
        // role" or "claimed by this advisor" mean something beyond merely holding the role.
        val actor = WfActor(cxt.userProfile.roles, cxt.userProfile.userId, groups = setOfNotNull(cxt.userProfile.org))
        if (!RoleLadder.satisfies(actor.roles, transition.by)) {
            throw KdrException("Taking '$transitionName' needs the '${transition.by}' role.", code = EXC.notAuthorized)
        }
        if (currentAssignment != null && !currentAssignment.matches(actor)) {
            throw KdrException(
                "The workflow is not assigned to you (${currentAssignment.kind}='${currentAssignment.value}').",
                code = EXC.notAuthorized,
            )
        }

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

        // Apply the edits. Their own transaction, and they commit regardless of what the gate decides below.
        if (edits.isNotEmpty()) {
            dataService.patchGedras(
                cxt,
                mapOf(GedraDataType.wfData to listOf(GedraPatchTarget(gedraId, edits))),
                scope,
            )
        }

        return advanceUnderLock(cxt, gedraId, definition, transition, scope, assign, reopenTasks, note, currentStateName)
    }

    /**
     * Reads the workflow's entries under its topic lock, checks the transition's required tasks against them,
     * and -- only if they pass -- writes the new state, assignment and reopened overlay into `data`. One
     * transaction, so the gate sees committed post-edit state and the move cannot race an edit.
     */
    @Suppress("LongParameterList")
    private fun advanceUnderLock(
        cxt: KdrCxt,
        gedraId: GedraId,
        definition: WfDefinition,
        transition: WfTransition,
        scope: ReadScope,
        assign: WfAssignment?,
        reopenTasks: List<String>,
        note: String?,
        fromState: String,
    ): WfTransitionOutcome {
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraDataTopic)
        val table = cxt.getSchema().tables[GDT.gedraData]
            ?: throw KdrException("${GDT.gedraData} table is not registered in the schema store.")
        val selectStmt = SqlTopicUtil.mkTableSelectStmt(sqlCxt, table)

        // The scoped update writes only `data` (and the audit stamps): all workflow state lives inside it.
        val bind = mutableMapOf<String, Any?>()
        val conditions = mutableListOf(
            "c:${GD.gedraId} = :${GD.gedraId}",
            "c:${PF.enabled} = true",
        )
        conditions.addAll(SqlScopeUtil.scopeConditions(scope, table, bind))
        val moveStmt = SqlStmtUtil.prepareSql(
            sqlCxt, "uGedraWfMove${scope.shapeKey}", table.columns,
            "update t:${GDT.gedraData} set c:${GD.data} = :${GD.data}, " +
                "c:${PF.updatedAt} = :${PF.updatedAt}, c:${PF.updatedBy} = :${PF.updatedBy} " +
                "where ${conditions.joinToString(" and ")}",
        )

        var unmet: List<String> = emptyList()
        var advanced = false
        SqlTopicTranProvider.executeTopicTran(
            sqlCxt, tranWfTransition, null, mapOf(GD.gedraId to gedraId.fullId),
        ) {
            val current = sqlCxt.sqlDb.queryOneEnabled(cxt, selectStmt, mapOf(GD.gedraId to gedraId.fullId))
                ?: throw KdrException("Workflow '${gedraId.fullId}' vanished mid-transition.", code = EXC.notFound)
            val data = current[GD.data].toJsonMapOrEmpty().toMutableMap()
            val entries = data[GD.entries].toJsonListOfMaps()

            val incomplete = WfEngine.incompleteTasks(transition.requiresTasks, definition, entries)
            if (incomplete.isNotEmpty()) {
                // Soft validation: the edits are already saved, the advance is refused, nothing moves.
                unmet = incomplete
                return@executeTopicTran
            }

            data[GD.wfStatus] = transition.to
            // The assignment: the caller's explicit choice, or the target state's default (owner / advisor
            // role / nobody). Nobody -> the key is cleared, so a terminal state holds no one.
            val assignment = assign ?: definition.defaultAssignment(transition.to, current[PF.userId].toOptLong())
            if (assignment != null) data[GD.wfAssignment] = assignment.toMap() else data.remove(GD.wfAssignment)
            // The reopened overlay: a request-changes transition sets it; any forward transition clears it, so
            // a resubmission is reviewed fresh.
            if (transition.reopensTasks) {
                data[GD.wfReopened] = reopenTasks.associateWith { note ?: "" }
            } else {
                data.remove(GD.wfReopened)
            }

            val move = bind.toMutableMap()
            move[GD.gedraId] = gedraId.fullId
            move[GD.data] = data
            move[PF.updatedAt] = SqlTopicUtil.nextUpdatedAt(cxt, current[PF.updatedAt].toOptInstant())
            move[PF.updatedBy] = cxt.userProfile.userId
            val changed = sqlCxt.sqlDb.executeStatement(cxt, moveStmt, move)
            if (changed == 0) {
                throw KdrException(
                    "Workflow '${gedraId.fullId}' could no longer be moved when the transition reached it.",
                    code = EXC.conflict,
                )
            }
            advanced = true
        }

        return WfTransitionOutcome(
            advanced = advanced,
            fromState = fromState,
            toState = if (advanced) transition.to else null,
            unmetTasks = unmet,
        )
    }

    /** Names the transition's topic transaction; prefixes its generated id, as the other gedra trans do. */
    const val tranWfTransition = "wfTransition"
}
