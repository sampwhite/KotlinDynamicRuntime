package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder

/**
 * What a user acts with in a sandbox (issue #929, rule 2): **authority flows from the parent to its sandbox, never
 * back.** A sandbox user is a row of its own, and what its row says is not what it acts with -- that is asked of
 * the person's user **outside** the sandbox, at each request, through the same user cache the gate's role refresh
 * reads. Nothing is kept in step, so nothing can fall out of step.
 *
 * **A sandbox user whose row holds `admin` is somebody from outside** -- the role is only ever put there by opening
 * the sandbox, or granted in name -- **and acts with exactly what their user outside holds** ([standing]): its
 * levels and its capabilities, whatever the sandbox row was made with.
 *
 * - **`admin` takes effect only while the person is an administrator outside**: their identity has an enabled
 *   administrator in the parent, or an enabled `allClients` administrator ([parentActor]). That is what lets a
 *   sandbox administrator edit the parent's configuration (#930): whoever is one is, by construction, trusted in
 *   the parent. Granting the role inside the sandbox does not help.
 * - **Everything else follows the same user.** A capability or a level taken away outside is gone inside at the
 *   next request, and one given outside is held inside without a second grant. So an administrator of the parent
 *   who is demoted to a member is a member in its sandbox, and one who loses `allClients` keeps the one client.
 * - **With no standing left outside, they act with nothing.** When the identity has no enabled user in the parent
 *   and no enabled `allClients` administrator -- the user was disabled, or removed, or what made them an
 *   administrator here was a capability they no longer hold -- the sandbox user is theirs to act as no longer, as
 *   a disabled account drops to no roles at all.
 *
 * **A sandbox user without `admin` on its row is the sandbox's own** -- somebody made there to try the client as a
 * member -- and acts with the levels its row holds, whoever else its person is. Never with a capability: that is
 * held by an administrator from outside, or not at all.
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

    /** Why a role a sandbox user's row holds does not take effect: the rules of the object note, as [withheld] names them. */
    @Suppress("EnumEntryName")
    enum class Withheld {
        /** `admin`, from somebody who is no administrator outside. */
        admin,

        /** Any other role the person's user outside does not hold, and a capability on a row that is the sandbox's own. */
        notHeldOutside,

        /** Everything, from somebody who came from outside and has no standing left there. */
        noStanding,
    }

    /** The roles [row] acts with: its own outside a sandbox, and in one what the object note says. */
    fun actingRoles(cxt: KdrCxt, row: AuthUserRow): Set<String> {
        if (!isSandboxClient(row.client)) return row.roles.toSet()
        return if (ROLE.admin in row.roles) {
            standing(cxt, row)?.roles.orEmpty().toSet()
        } else {
            row.roles.filter { RoleLadder.rankOf(it) != null }.toSet()
        }
    }

    /**
     * The roles on [row] that do not take effect, each with the rule that withholds it; empty outside a sandbox.
     * What `explainAccess` reports, so that a role missing from the acting roles never reads as one the user was
     * not given.
     */
    fun withheld(cxt: KdrCxt, row: AuthUserRow): Map<String, Withheld> {
        if (!isSandboxClient(row.client)) return emptyMap()
        val fromOutside = ROLE.admin in row.roles
        if (fromOutside && standing(cxt, row) == null) return row.roles.associateWith { Withheld.noStanding }
        return (row.roles - actingRoles(cxt, row)).associateWith {
            if (it == ROLE.admin) Withheld.admin else Withheld.notHeldOutside
        }
    }

    /** Whether [row] holds `admin` in a sandbox and the rule withholds it -- alone, or with everything else. */
    fun adminWithheld(cxt: KdrCxt, row: AuthUserRow): Boolean = ROLE.admin in withheld(cxt, row)

    /**
     * The identity's enabled users outside every sandbox: where a sandbox user's authority is asked for. In the
     * order they were made, so that which one answers does not turn on how the cache happens to hold them.
     */
    private fun outsideUsers(cxt: KdrCxt, row: AuthUserRow): List<AuthUserRow> =
        UserService.get(cxt).enabledUsersOf(cxt, row.identityId).filter { !isSandboxClient(it.client) }.sortedBy { it.userId }

    /**
     * The user outside the sandbox that makes sandbox user [row] an administrator there: its identity's enabled
     * administrator in the parent, else its enabled `allClients` administrator -- or null when it has neither, or
     * [row] is no sandbox user. Who a configuration edit made from the sandbox is attributed to (issue #930), since
     * the configuration's history is the parent's.
     */
    fun parentActor(cxt: KdrCxt, row: AuthUserRow): AuthUserRow? {
        val parent = sandboxParentOf(row.client) ?: return null
        val outside = outsideUsers(cxt, row).filter { ROLE.admin in it.roles }
        return outside.firstOrNull { it.client == parent } ?: outside.firstOrNull { ROLE.allClients in it.roles }
    }

    /**
     * The user outside the sandbox that gives sandbox user [row] its **standing** there: the one that makes it an
     * administrator ([parentActor]), else the person's enabled user in the parent of whatever level -- or null when
     * the person has neither, or [row] is no sandbox user. The user somebody from outside acts as: an
     * administrator of the parent first, so one who is that and an `allClients` administrator elsewhere acts in
     * the sandbox as the one a change of theirs is attributed to.
     */
    fun standing(cxt: KdrCxt, row: AuthUserRow): AuthUserRow? {
        val parent = sandboxParentOf(row.client) ?: return null
        return parentActor(cxt, row) ?: outsideUsers(cxt, row).firstOrNull { it.client == parent }
    }
}

/**
 * The profile [this] row acts as: [AuthUserRow.toUserProfile] with the roles [SandboxAccess] lets take effect. What
 * every place that builds a session's profile from a row uses, so the cookie, the gate, and the user info agree.
 */
fun AuthUserRow.toActingProfile(cxt: KdrCxt): UserProfile =
    toUserProfile().copy(roles = SandboxAccess.actingRoles(cxt, this))
