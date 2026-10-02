package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.gedra.isSandboxClient
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
    /** The roles [row] acts with: its own, less `admin` when it is a sandbox user that does not qualify. */
    fun actingRoles(cxt: KdrCxt, row: AuthUserRow): Set<String> {
        val roles = row.roles.toSet()
        return if (adminWithheld(cxt, row)) roles - ROLE.admin else roles
    }

    /** Whether [row] holds `admin` in a sandbox and the rule withholds it -- what `explainAccess` reports. */
    fun adminWithheld(cxt: KdrCxt, row: AuthUserRow): Boolean {
        if (ROLE.admin !in row.roles || !isSandboxClient(row.client)) return false
        val parent = sandboxParentOf(row.client) ?: return true
        val qualifying = UserService.get(cxt).enabledUsersOf(cxt, row.identityId).any {
            !isSandboxClient(it.client) && ROLE.admin in it.roles && (it.client == parent || ROLE.allClients in it.roles)
        }
        return !qualifying
    }
}

/**
 * The profile [this] row acts as: [AuthUserRow.toUserProfile] with the roles [SandboxAccess] lets take effect. What
 * every place that builds a session's profile from a row uses, so the cookie, the gate, and the user info agree.
 */
fun AuthUserRow.toActingProfile(cxt: KdrCxt): UserProfile =
    toUserProfile().copy(roles = SandboxAccess.actingRoles(cxt, this))
