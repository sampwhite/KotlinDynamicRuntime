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
 *
 * Theme: #SBXAU.
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

    /**
     * Refuses a change to [client]'s configuration made from outside its sandbox, while it has one (issue #1824): a
     * client with a sandbox is changed in the sandbox, where the change is previewed and published, never from the
     * client itself, where a draft would be a change nobody there sees. A sandbox named is the way in, and passes, as a
     * client without a sandbox does. So does one whose sandbox this node does not carry -- its load failed -- whose
     * configuration then has to be mendable from the client itself; an editor's save refuses that case on its own
     * terms ([ClientStoredEdit.target]).
     *
     * A 409 carrying [EDM.notAsExpected], as the edit target's other refusals of a save the page did not expect: a page
     * that read the client before its sandbox was added re-reads on it, and then shows the configuration read-only, with
     * the way to the sandbox.
     */
    fun requireChangedInSandbox(cxt: KdrCxt, client: String) {
        if (isSandboxClient(client)) return
        val sandbox = sandboxOf(client)
        if (!ClientService.get(cxt).isPresent(sandbox)) return
        throw KdrException(
            "Client '$client' has a sandbox, so its configuration is changed in the sandbox, '$sandbox', where a " +
                "change is previewed and published. Nothing was changed.",
            code = EXC.conflict,
        ).also { it.extraData[KdrException.errorCodeKey] = EDM.notAsExpected }
    }

    /** Reloads [parent] -- and so its sandbox -- on this node and announces it, so the sandbox shows an edit at once. */
    fun reloadParent(cxt: KdrCxt, parent: String): ConfigReloadResult {
        val reload = GedraConfigReload.reloadClient(cxt, parent)
        ClientSyncService.get(cxt).announceReload(cxt, reload)
        return reload
    }
}
