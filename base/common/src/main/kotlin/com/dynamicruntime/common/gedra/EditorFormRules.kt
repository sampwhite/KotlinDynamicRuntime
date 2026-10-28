package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WorkflowPhases
import com.dynamicruntime.common.gedra.workflow.WorkflowService
import com.dynamicruntime.common.gedra.workflow.engagedWorkflowIds
import com.dynamicruntime.common.user.AdminRules

/**
 * What an **editor** (issue #1432) may not do to a form it reaches: delete another user's while it is in a workflow. An
 * editor reads every form of its client and edits them ([com.dynamicruntime.common.user.ReadScopeRules.forCaller]
 * reaches them), and since #1433 creates forms for others -- so it may also delete another user's form that is in no
 * workflow (issue #1452), such as one it started for the wrong person. Once a form is engaged with a workflow it is
 * someone's work under way, and deleting it is its owner's or an administrator's.
 *
 * **Engaged** means engaged with a workflow that is live ([WorkflowPhases.live]): one deleted from the configuration or
 * past its lifetime holds no work, and the forms page, which is shown no such workflow's entries, agrees. A live
 * workflow the editor does not see still counts -- it is work under way for whoever does -- so there the page may offer
 * a Delete this refuses.
 *
 * A write guard, so it runs under the form's lock on every deletion, whichever surface asks, and refuses before
 * anything is removed. It asks nothing of anyone but an editor: an ordinary user reaches only their own forms, an
 * administrator deletes any it reaches, and the system's own work is no editor's.
 */
object EditorDeleteGuard : GedraWriteGuard {
    override fun check(cxt: KdrCxt, write: GedraGuardedWrite): ((List<Map<String, Any?>>) -> List<Map<String, Any?>>)? {
        if (!write.deletesGedra || !AdminRules.editsClient(cxt)) return null
        if (write.row.userId == cxt.userProfile.userId) return null
        val registry = WorkflowService.get(cxt).forClient(write.row.client)
        if (engagedWorkflowIds(write.states).none { WorkflowPhases.live(cxt, registry, it) != null }) return null
        throw KdrException(
            "This form is in a workflow, so only its owner or an administrator may delete it.",
            code = EXC.notAuthorized,
        )
    }
}
