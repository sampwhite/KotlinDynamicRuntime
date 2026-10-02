package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.user.SandboxAccess
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.user.toActingProfile

/**
 * Configuration edits made from inside a Shadow Sandbox act on its **parent** (issue #930): a sandbox holds no
 * configuration of its own -- it runs the parent's latest -- so the only configuration an administrator there can
 * mean is the parent's. Its history is the parent's too, so an edit is attributed to the identity's user there.
 *
 * Safe because of #929's rule: whoever acts as an administrator in a sandbox is, by construction, an administrator in
 * the parent (or an `allClients` one), so nothing reaches the parent that the parent's own administrators could not.
 */
object SandboxEdits {
    /**
     * The context a configuration edit of sandbox [sandbox] runs in -- bound to the parent, acting as the person's
     * user outside the sandbox -- or null when [sandbox] is no sandbox. A caller in the sandbox acts as the identity's
     * parent administrator ([SandboxAccess.parentActor]); a caller elsewhere naming it (an `allClients`
     * administrator) acts as itself.
     */
    fun parentCxt(cxt: KdrCxt, sandbox: String): KdrCxt? {
        val parent = sandboxParentOf(sandbox) ?: return null
        val profile = cxt.userProfile
        val actor = if (profile.client == sandbox) {
            val row = if (profile.isRowBacked) UserService.get(cxt).queryByUserId(cxt, profile.userId) else null
            val outside = row?.let { SandboxAccess.parentActor(cxt, it) }
                ?: throw KdrException(
                    "Only an administrator of '$parent' may change its configuration from its sandbox.",
                    code = EXC.notAuthorized,
                )
            outside.toActingProfile(cxt)
        } else {
            profile
        }
        return cxt.mkSubContext("sandboxParent", parent).also { sub ->
            sub.bindToUserProfile(actor)
            sub.client = parent
            if (actor.client != parent) sub.org = null
        }
    }

    /** Reloads [parent] -- and so its sandbox -- on this node and announces it, so the sandbox shows an edit at once. */
    fun reloadParent(cxt: KdrCxt, parent: String): ConfigReloadResult {
        val reload = GedraConfigReload.reloadClient(cxt, parent)
        ClientSyncService.get(cxt).announceReload(cxt, reload)
        return reload
    }
}
