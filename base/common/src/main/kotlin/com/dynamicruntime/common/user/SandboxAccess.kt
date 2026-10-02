package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.http.request.ROLE

/**
 * Who is an administrator in a sandbox (issue #929, rule 2): **authority flows from the parent to its sandbox, never
 * back.** In a sandbox client the `admin` role takes effect only while the user's **identity** also has an enabled
 * administrator in the parent, or an enabled `allClients` administrator -- users outside any sandbox, either way.
 *
 * That is what lets a sandbox administrator edit the parent's configuration (#930): whoever is one is, by
 * construction, trusted in the parent. Granting the role inside the sandbox does not help -- a qualifying user lives
 * outside it -- and no role is copied, so demoting or removing the parent administrator takes effect in the sandbox
 * at the next request, through the same user cache the gate's role refresh reads.
 *
 * A user holding the role who does not qualify keeps every other role; only `admin` is withheld.
 */
object SandboxAccess {
    /**
     * Whether the caller may open [parent]'s sandbox (issue #929, rule 1): an `allClients` administrator any client's,
     * a client administrator their own -- and never from inside a sandbox, which has none of its own. What the open
     * endpoint enforces and the shell's offer (issue #931) asks, so the two agree.
     */
    fun mayOpen(cxt: KdrCxt, parent: String): Boolean {
        if (isSandboxClient(parent)) return false
        val scope = AdminRules.adminScope(cxt)
        return scope == AdminScope.allClients || (scope == AdminScope.ownClient && parent == cxt.userProfile.client)
    }

    /** Whether [parent] has a live sandbox to open: its definition asks for one, and the sandbox is present. */
    fun hasSandbox(cxt: KdrCxt, parent: String): Boolean {
        val clients = ClientService.get(cxt)
        return clients.present(parent)?.sandbox == true && clients.isPresent(sandboxOf(parent))
    }

    /** The roles [row] acts with: its own, less `admin` when it is a sandbox user that does not qualify. */
    fun actingRoles(cxt: KdrCxt, row: AuthUserRow): Set<String> {
        val roles = row.roles.toSet()
        return if (adminWithheld(cxt, row)) roles - ROLE.admin else roles
    }

    /** Whether [row] holds `admin` in a sandbox and the rule withholds it -- what `explainAccess` reports. */
    fun adminWithheld(cxt: KdrCxt, row: AuthUserRow): Boolean =
        ROLE.admin in row.roles && isSandboxClient(row.client) && parentActor(cxt, row) == null

    /**
     * The user outside the sandbox that makes sandbox user [row] an administrator there: its identity's enabled
     * administrator in the parent, else its enabled `allClients` administrator -- or null when it has neither, or
     * [row] is no sandbox user. Who a configuration edit made from the sandbox is attributed to (issue #930), since
     * the configuration's history is the parent's.
     */
    fun parentActor(cxt: KdrCxt, row: AuthUserRow): AuthUserRow? {
        val parent = sandboxParentOf(row.client) ?: return null
        val outside = UserService.get(cxt).enabledUsersOf(cxt, row.identityId)
            .filter { !isSandboxClient(it.client) && ROLE.admin in it.roles }
        return outside.firstOrNull { it.client == parent } ?: outside.firstOrNull { ROLE.allClients in it.roles }
    }
}

/**
 * The profile [this] row acts as: [AuthUserRow.toUserProfile] with the roles [SandboxAccess] lets take effect. What
 * every place that builds a session's profile from a row uses, so the cookie, the gate, and the user info agree.
 */
fun AuthUserRow.toActingProfile(cxt: KdrCxt): UserProfile =
    toUserProfile().copy(roles = SandboxAccess.actingRoles(cxt, this))
