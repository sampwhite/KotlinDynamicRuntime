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
 * back.** A sandbox user is a row of its own, and the roles on it are not all it takes to say what it may do: the
 * rest is asked of the person's users **outside** the sandbox, at every request, through the same user cache the
 * gate's role refresh reads -- their enabled, registered ones: a user nobody has claimed yet is not the person's to
 * act as (issue #1200). Nothing is kept in step, so nothing can fall out of step. There are two kinds of
 * sandbox user, told apart by [AuthUserRow.openedBy] -- what opening the sandbox records -- and never by the roles
 * on the row, which an administrator can edit.
 *
 * **A user somebody opened the sandbox as acts as the user they opened it from**: exactly that user's roles,
 * levels and capabilities both, whatever the sandbox row was made with. So a role taken away outside is gone inside
 * at the next request, and one given outside is held inside without a second grant. **Its organization too**
 * (issue #1196, [orgFrom]), so an administrator confined to one in the parent is confined to it in the sandbox --
 * but only when that user is the parent's own: an organization is a client's, and one of another client names
 * nothing here. The session reads it live; the sandbox row also stores it, written at each opening and kept in
 * step when an administrator changes the opener's (`UserService.followOpenerOrg`), since who may see and
 * administer the sandbox user goes by the stored one. That user must still stand
 * for the parent -- be enabled, and be the parent's or an `allClients` administrator -- or the sandbox user acts
 * with **nothing**, as a disabled account does: the person was disabled or removed in the parent, or what let
 * them in from elsewhere was a capability they no longer hold. Opening again records whoever opens it.
 *
 * **A sandbox's own user** -- somebody made there to try the client as a member -- acts with the roles on its
 * row, with these exceptions:
 * - **`admin` takes effect only while the person is an administrator outside**: their identity has an enabled
 *   administrator in the parent, or an enabled `allClients` administrator. That is what lets a sandbox
 *   administrator edit the parent's configuration (#930): whoever is one is, by construction, trusted in the
 *   parent. Granting the role inside the sandbox does not help, and the console refuses it.
 * - **`allClients` is never held**: reach across clients comes from outside, through an opening, or not at all.
 * - While `admin` takes effect through an administrator of the parent, it acts in **that administrator's
 *   organization**, as an opened user does -- so one opened before openings were recorded is confined too.
 * - **On one holding `admin`, any other level takes effect only while the person's user outside holds it** (issue
 *   #1199) -- their user in the parent, or the administrator `admin` takes effect through -- as `admin` itself
 *   does. Such a row is most often one opened before openings were recorded, copied with the levels its person
 *   held that day, which would otherwise outlive a demotion outside until the next opening recorded it. Not
 *   always: the console granted `admin` in a sandbox before #1195, and a test's fixture still makes such users.
 *   So the rule says what takes effect, not where the row came from, and the console grants no level beside
 *   `admin` in a sandbox. Off-ladder roles other than `allClients` are a deployment's own grants and are left as
 *   the row holds them.
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
        levelNotHeldOutside(
            "sandboxLevel: in a sandbox, a level beside admin on a user nobody recorded opening takes effect only while " +
                "the person's user outside holds it",
        ),
    }

    /**
     * How one sandbox user stands, as the object note says: computed once, so that what it acts with, what is
     * withheld from its row, and who it acts for cannot disagree.
     */
    class Standing(
        /** The roles it acts with. */
        val roles: Set<String>,
        /** The primary organization it acts in, which confines an administrator's reach (`ReadScopeRules`); null for none. */
        val org: String?,
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
        val parent = sandboxParentOf(row.client) ?: return Standing(row.roles.toSet(), row.org)
        // The identity's enabled users outside every sandbox, in the order they were made -- registered ones only
        // (issue #1200): a user an administrator provisioned that the person has not claimed yet, or recovered
        // and not reclaimed, is not theirs to act as, so it stands behind nothing here either, as the switcher
        // and the login landing pass it over.
        val outside = UserService.get(cxt).enabledUsersOf(cxt, row.identityId)
            .filter { !isSandboxClient(it.client) && it.isRegistered }.sortedBy { it.userId }
        val openedBy = row.openedBy
        if (openedBy != null) {
            val opener = outside.firstOrNull { it.userId == openedBy && (it.client == parent || isAllClientsAdmin(it)) }
                ?: return nothingStands(row)
            return Standing(
                opener.roles.toSet(), orgFrom(opener, row.client),
                actsAs = opener, parentActor = opener.takeIf { ROLE.admin in it.roles },
            )
        }
        val admins = outside.filter { ROLE.admin in it.roles }
        val actor = admins.firstOrNull { it.client == parent } ?: admins.firstOrNull { isAllClientsAdmin(it) }
        if (ROLE.admin in row.roles && actor == null && outside.none { it.client == parent }) {
            return nothingStands(row)
        }
        // Who outside stands behind a level on a row holding admin (issue #1199): the person's users in the parent,
        // and the administrator admin takes effect through.
        val holders = outside.filter { it.client == parent } + listOfNotNull(actor)
        val withheld = buildMap {
            if (ROLE.admin in row.roles && actor == null) put(ROLE.admin, Withheld.admin)
            if (ROLE.allClients in row.roles) put(ROLE.allClients, Withheld.allClients)
            if (ROLE.admin in row.roles) {
                row.roles.filter { it in besideAdmin && holders.none { h -> RoleLadder.satisfies(h.roles.toSet(), it) } }
                    .forEach { put(it, Withheld.levelNotHeldOutside) }
            }
        }
        val acting = row.roles.toSet() - withheld.keys
        val org = if (ROLE.admin in acting && actor?.client == parent) actor.org else row.org
        return Standing(acting, org, withheld, parentActor = actor)
    }

    /**
     * The organization a sandbox user of [sandboxClient] opened from [opener] acts in: [opener]'s, when it is a user
     * of the sandbox's parent; none otherwise, since an organization is a client's and one of another client names
     * nothing in this one.
     */
    fun orgFrom(opener: AuthUserRow, sandboxClient: String): String? =
        opener.org.takeIf { opener.client == sandboxParentOf(sandboxClient) }

    /** The user outside that makes sandbox user [row] an administrator there, or null: [standingOf]'s. */
    fun parentActor(cxt: KdrCxt, row: AuthUserRow): AuthUserRow? = standingOf(cxt, row).parentActor

    /** The ladder's levels other than `user` and `admin`: those a row holding `admin` keeps only while held outside. */
    private val besideAdmin: Set<String> = RoleLadder.ordered.toSet() - ROLE.user - ROLE.admin

    private fun isAllClientsAdmin(row: AuthUserRow): Boolean = ROLE.admin in row.roles && ROLE.allClients in row.roles

    private fun nothingStands(row: AuthUserRow): Standing =
        Standing(emptySet(), row.org, row.roles.associateWith { Withheld.noStanding })
}

/**
 * The profile [this] row acts as: [AuthUserRow.toUserProfile] with the roles and the organization [SandboxAccess]
 * says it acts with. What every place that builds a session's profile from a row uses, so the gate, a login's
 * cookie, and the user info agree on the roles -- the cookie carries no organization, which only a gated request,
 * having refreshed the profile, binds.
 */
fun AuthUserRow.toActingProfile(cxt: KdrCxt): UserProfile {
    val standing = SandboxAccess.standingOf(cxt, this)
    return toUserProfile().copy(roles = standing.roles, org = standing.org)
}
