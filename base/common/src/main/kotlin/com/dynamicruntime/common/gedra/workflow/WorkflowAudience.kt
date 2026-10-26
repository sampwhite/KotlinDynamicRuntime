package com.dynamicruntime.common.gedra.workflow

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.user.AdminRules
import com.dynamicruntime.common.user.AuthUserRow

/**
 * Who sees a workflow, asked for the person a request acts as (issue #1384): a workflow's visibility parameter
 * ([WfVisibility]) as [WorkflowParamsService] resolves it in the workflow's client, judged against the person's labels
 * and whether they administer that client -- an administrator always sees a workflow, since they are the ones setting it
 * up.
 *
 * **Asked where a person meets a workflow**, and nowhere else: the workflow view, a save, an engagement, an approval, a
 * form's states, the forms listing's workflow summary, the chip's popover, and the workflow pages' aggregate
 * ([WorkflowPhases.liveFor], [WorkflowPhases.shownStates], [WorkflowPhases.statesWithRetired]), and the trait locks
 * a form holds for them ([TraitLocks]), which are released as a deleted workflow's are -- for a person: a writer with no
 * user row is held by every workflow's locks. Jobs, the state deriver, reports, and an impact report judge every
 * workflow as it is, whoever is looking.
 *
 * **Not the final word.** A person engaged with a workflow they no longer see is treated as if the workflow were deleted
 * or past its lifetime: the form's part in it is hidden from them, and kept, and its locks on the form's data are
 * released for them. That is one answer to a real tension, not the only one. A client may want engagement to keep a
 * workflow visible to the forms' owners instead, whatever its visibility says -- which costs knowing, per person,
 * whether any of their forms is engaged with it -- or want the choice made per workflow. A client asking is what would
 * settle it, and this class is where the rule would change.
 *
 * One per request ([of]): the person's labels are read once, and whether they administer a client once per client,
 * however many workflows are judged. The visibility itself is read each time, from memory, so a save made in the
 * request is what the answer to it judges by.
 */
class WorkflowAudience private constructor(private val cxt: KdrCxt) {
    private val labels: Set<String> by lazy { viewerLabels(cxt) }
    private val administers = HashMap<String, Boolean>()

    /** Whether the person sees [workflowId] of [client]; the global registry's (a null [client]), which no form runs, always. */
    fun sees(client: String?, workflowId: String): Boolean {
        if (client == null) return true
        val visibility = WorkflowParamsService.getOrNull(cxt)?.visibilityFor(cxt, client, workflowId) ?: return true
        if (!visibility.restricted) return true
        return visibility.admits(administers.getOrPut(client) { AdminRules.oversees(cxt, client) }, labels)
    }

    @Suppress("ConstPropertyName")
    companion object {
        private const val localsKey = "workflowAudience"

        /** The audience of [cxt]'s request, made on first use. */
        fun of(cxt: KdrCxt): WorkflowAudience = cxt.locals.getOrPut(localsKey) { WorkflowAudience(cxt) } as WorkflowAudience

        /**
         * Whether [user] -- a form's owner, not the caller -- sees a workflow of [client] under [visibility]: what an
         * impact report asks of each owner. Read off their row as `AdminRules` reads a caller's roles: an administrator
         * of every client, or of [client] itself outside `public`. A sandbox's user is taken at their row's roles, which
         * for one opened from outside can say less than they act with -- so the report errs toward naming a form. No
         * user (one since deleted) sees only an unrestricted workflow.
         */
        fun userSees(visibility: WfVisibility, user: AuthUserRow?, client: String): Boolean {
            if (!visibility.restricted) return true
            if (user == null || !user.enabled) return false
            val admin = ROLE.admin in user.roles &&
                (ROLE.allClients in user.roles || (user.client == client && client != CL.public))
            return visibility.admits(admin, user.labels)
        }
    }
}
