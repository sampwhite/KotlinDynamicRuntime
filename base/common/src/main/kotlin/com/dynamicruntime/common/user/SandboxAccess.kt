package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.gedra.sandboxParentOf
import com.dynamicruntime.common.http.request.ROLE

/**
 * What a user acts with in a sandbox (issue #929, rule 2): **authority flows from the parent to its sandbox, never
 * back.** A sandbox user is a row of its own, and the roles on it are not all it takes to say what it may do: the
 * rest is asked of the person's users **outside** the sandbox, at every request, through the same user cache the
 * gate's role refresh reads. Nothing is kept in step, so nothing can fall out of step. There are two kinds of
 * sandbox user, told apart by [AuthUserRow.openedBy] -- what opening the sandbox records -- and never by the roles
 * on the row, which an administrator can edit.
 *
 * **A user somebody opened the sandbox as acts as the user they opened it from**: exactly that user's roles,
 * levels and capabilities both, whatever the sandbox row was made with. So a role taken away outside is gone inside
 * at the next request, and one given outside is held inside without a second grant. That user must still stand
 * for the parent -- be enabled, and be the parent's or an `allClients` administrator -- or the sandbox user acts
 * with **nothing**, as a disabled account does: the person was disabled or removed in the parent, or what let
 * them in from elsewhere was a capability they no longer hold. Opening again records whoever opens it.
 *
 * **A sandbox's own user** -- somebody made there to try the client as a member -- acts with the roles on its
 * row, with two exceptions:
 * - **`admin` takes effect only while the person is an administrator outside**: their identity has an enabled
 *   administrator in the parent, or an enabled `allClients` administrator. That is what lets a sandbox
 *   administrator edit the parent's configuration (#930): whoever is one is, by construction, trusted in the
 *   parent. Granting the role inside the sandbox does not help, and the console refuses it.
 * - **`allClients` is never held**: reach across clients comes from outside, through an opening, or not at all.
 *
 * And one holding `admin` whose identity has no enabled user in the parent and no enabled `allClients`
 * administrator acts with nothing: that is how a user opened before openings were recorded reads once its person
 * is gone from the parent.
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

    /** Why a role on a sandbox user's row does not take effect, with the rule as the access report words it. */
    @Suppress("EnumEntryName")
    enum class Withheld(val rule: String) {
        admin(
            "sandboxAdmin: in a sandbox, admin takes effect only for an identity that is an administrator in the " +
                "parent, or an allClients administrator",
        ),
        allClients("sandboxAllClients: in a sandbox, allClients is held only through the user the sandbox was opened from"),
        noStanding(
            "sandboxStanding: this sandbox user acts for somebody outside it who is no longer an enabled user of the " +
                "parent, nor an enabled allClients administrator",
        ),
    }

    /**
     * How one sandbox user stands, as the object note says: computed once, so that what it acts with, what is
     * withheld from its row, and who it acts for cannot disagree.
     */
    class Standing(
        /** The roles it acts with. */
        val roles: Set<String>,
        /** The roles on its row that do not take effect, each with the rule that withholds it. */
        val withheld: Map<String, Withheld> = emptyMap(),
        /** The user outside it acts as: the one it was opened from, while that one stands; null for a sandbox's own user. */
        val actsAs: AuthUserRow? = null,
        /**
         * The user outside that makes it an administrator here, or null when `admin` does not take effect. Who a
         * configuration edit made from the sandbox is attributed to (issue #930), since the configuration's
         * history is the parent's.
         */
        val parentActor: AuthUserRow? = null,
    ) {
        /** Whether nobody stands behind it any more: it acts with nothing. */
        val stranded: Boolean get() = Withheld.noStanding in withheld.values
    }

    /** How [row] stands; outside a sandbox, simply as its own roles say. */
    fun standingOf(cxt: KdrCxt, row: AuthUserRow): Standing {
        val parent = sandboxParentOf(row.client) ?: return Standing(row.roles.toSet())
        // The identity's enabled users outside every sandbox, in the order they were made.
        val outside = UserService.get(cxt).enabledUsersOf(cxt, row.identityId)
            .filter { !isSandboxClient(it.client) }.sortedBy { it.userId }
        val openedBy = row.openedBy
        if (openedBy != null) {
            val opener = outside.firstOrNull { it.userId == openedBy && (it.client == parent || isAllClientsAdmin(it)) }
                ?: return nothingStands(row)
            return Standing(opener.roles.toSet(), actsAs = opener, parentActor = opener.takeIf { ROLE.admin in it.roles })
        }
        val admins = outside.filter { ROLE.admin in it.roles }
        val actor = admins.firstOrNull { it.client == parent } ?: admins.firstOrNull { isAllClientsAdmin(it) }
        if (ROLE.admin in row.roles && actor == null && outside.none { it.client == parent }) {
            return nothingStands(row)
        }
        val withheld = buildMap {
            if (ROLE.admin in row.roles && actor == null) put(ROLE.admin, Withheld.admin)
            if (ROLE.allClients in row.roles) put(ROLE.allClients, Withheld.allClients)
        }
        return Standing(row.roles.toSet() - withheld.keys, withheld, parentActor = actor)
    }

    /** The roles [row] acts with: [standingOf]'s. */
    fun actingRoles(cxt: KdrCxt, row: AuthUserRow): Set<String> = standingOf(cxt, row).roles

    /** The user outside that makes sandbox user [row] an administrator there, or null: [standingOf]'s. */
    fun parentActor(cxt: KdrCxt, row: AuthUserRow): AuthUserRow? = standingOf(cxt, row).parentActor

    private fun isAllClientsAdmin(row: AuthUserRow): Boolean = ROLE.admin in row.roles && ROLE.allClients in row.roles

    private fun nothingStands(row: AuthUserRow): Standing = Standing(emptySet(), row.roles.associateWith { Withheld.noStanding })
}

/**
 * The profile [this] row acts as: [AuthUserRow.toUserProfile] with the roles [SandboxAccess] lets take effect. What
 * every place that builds a session's profile from a row uses, so the cookie, the gate, and the user info agree.
 */
fun AuthUserRow.toActingProfile(cxt: KdrCxt): UserProfile =
    toUserProfile().copy(roles = SandboxAccess.actingRoles(cxt, this))
