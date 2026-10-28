package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.user.AdminRules

/**
 * What an **editor** (issue #1432) may not do to a form it reaches: delete one it does not own. An editor reads every
 * form of its client and edits them ([com.dynamicruntime.common.user.ReadScopeRules.forCaller] reaches them), but
 * deleting is not editing -- only a form's owner, or an administrator, deletes it.
 *
 * A write guard, so it runs under the form's lock on every deletion, whichever surface asks, and refuses before
 * anything is removed. It asks nothing of anyone but an editor: an ordinary user reaches only their own forms, an
 * administrator deletes any it reaches, and the system's own work is no editor's.
 */
object EditorDeleteGuard : GedraWriteGuard {
    override fun check(cxt: KdrCxt, write: GedraGuardedWrite): ((List<Map<String, Any?>>) -> List<Map<String, Any?>>)? {
        if (!write.deletesGedra || !AdminRules.editsClient(cxt)) return null
        if (write.row.userId == cxt.userProfile.userId) return null
        throw KdrException("Only a form's owner or an administrator may delete it.", code = EXC.notAuthorized)
    }
}
