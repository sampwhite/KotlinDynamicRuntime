package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RoleLadder

/**
 * How far a caller's user administration reaches (issue #225) -- the *scope* half of an administrator's
 * authority, the level being the other half. An enum rather than string constants because this is a closed
 * operational set the code exhausts in a `when`, not a model value a deployment extends.
 */
@Suppress("EnumEntryName")
enum class AdminScope {
    /** Not an administrator: the admin surface is not theirs at all. */
    none,

    /** Administers only users in their own client -- the default for [ROLE.admin]. */
    ownClient,

    /** Administers every client, via the [ROLE.allClients] capability. */
    allClients,
}

/** Constants for the automatic-admin rule. */
@Suppress("ConstPropertyName")
object ADMR {
    /** Env var that defaults [ACFG.adminEmailDomain] when the config option is unset. */
    const val adminEmailDomainEnvVar = "KDR_ADMIN_EMAIL_DOMAIN"

    /** Marks a plus-addressed local part (`name+tag@domain`), which never auto-grants. */
    const val plusAddressChar = '+'

    /** Separates the local part from the domain in an email address. */
    const val atChar = '@'

    /** Separates a subdomain from its parent, for the subdomain match. */
    const val domainSep = "."
}

/**
 * Who is automatically an administrator, and how a deployment's *first* admin comes to exist.
 *
 * The admin endpoints are gated on [ROLE.admin] (they live under the `admin` section), which leaves the obvious
 * chicken-and-egg problem: with every user provisioned as a plain [ROLE.user], nobody could ever reach them. The
 * escape hatch is the **controlled domains** -- the configured admin domain and `example.com` outside
 * production, see [AddressRules.isControlledDomain] -- where an address whose local part carries no `+` tag is
 * granted [autoAdminRoles] as its user is provisioned.
 *
 * **At provisioning, and only there** (issue #352). It used to be re-applied on every login as well, so that
 * configuring the domain afterward reached an operator who had already registered. That went with the `+`
 * conventions, and the reason is worth keeping: a standing grant that re-asserts itself on every login is not
 * a statement about how an account was created, it is a permanent property of an address -- and one that only
 * ever grants, so a role an administrator deliberately removed would come back at the next login. An address
 * now says what a user is created *as*, and after that their roles are whatever an administrator has made
 * them. A deployment that configures its domain late reaches its operator through the `GrantRole` script or
 * through another administrator, which is where role changes belong.
 *
 * The `+` exclusion is no longer "this address is deliberately not an admin". A `+` tag now names a client and
 * optionally a persona within it (see [AddressRules]), so the exclusion here says something narrower: an
 * address that names a client is describing a user of that client, and the blanket grant is for the
 * deployment's own people rather than for anyone who tagged their way into one.
 *
 * The domain is matched against the address's domain part only, never as a suffix of the whole address -- see
 * [AddressRules.isControlledDomain] for why that distinction is the one that matters.
 *
 * The rule only ever **grants**, and only at provisioning. Revocation is an explicit administrative act -- see
 * the `admin/user/setRoles` endpoint and the `GrantRole` command-line script.
 */
object AdminRules {
    /**
     * The configured auto-admin domain, normalized (leading `@` dropped, lower-cased), or null when the
     * deployment configures none -- in which case nothing is ever auto-granted. [ACFG.adminEmailDomain] wins so
     * tests can set it directly; otherwise the [ADMR.adminEmailDomainEnvVar] env var supplies it.
     */
    fun adminEmailDomain(config: KdrInstanceConfig): String? {
        val configured = (config.get(ACFG.adminEmailDomain) as? String)
            ?: config.getEnvVar(ADMR.adminEmailDomainEnvVar)
        return configured?.trim()?.removePrefix(ADMR.atChar.toString())?.lowercase()?.ifEmpty { null }
    }

    /**
     * Whether [address] auto-qualifies for [autoAdminRoles]: it sits on a controlled domain and its local part
     * carries no `+` tag.
     *
     * The domain half is [AddressRules.isControlledDomain], shared rather than repeated, so the set of domains
     * this deployment treats as its own is written down once. That matters more than tidiness: the two rules
     * read the same addresses for different purposes, and a deployment where a domain named a client but did
     * not auto-admin (or the reverse) would be a difference nobody could see from either rule alone.
     */
    fun isAutoAdminAddress(cxt: KdrCxt, address: String): Boolean {
        if (!AddressRules.isControlledDomain(cxt, address)) {
            return false
        }
        // `isControlledDomain` established there is a local part, so the split below is safe.
        val trimmed = address.trim().lowercase()
        val local = trimmed.substring(0, trimmed.lastIndexOf(ADMR.atChar))
        return !local.contains(ADMR.plusAddressChar)
    }

    /**
     * How far the caller's user administration reaches (issue #225) -- the scope this seam's KDoc always said
     * it would grow.
     *
     * The level and the scope are separate axes: [ROLE.admin] says *what* may be done, and this says *over
     * whose rows*. An administrator reaches their own client by default, and every client only with the
     * [ROLE.allClients] capability.
     *
     * **Narrow by default is deliberate.** The alternative -- global unless restricted -- fails in the
     * widening direction, and silently: a deployment that grows a second client would have every existing
     * administrator quietly able to see it. This way a mistake shows up as "I cannot see that user", which
     * someone reports.
     */
    fun adminScope(cxt: KdrCxt): AdminScope {
        val roles = cxt.userProfile.roles
        return when {
            !roles.contains(ROLE.admin) -> AdminScope.none
            roles.contains(ROLE.allClients) -> AdminScope.allClients
            else -> AdminScope.ownClient
        }
    }

    /**
     * Whether the caller may administer other users -- create them, edit their roles, enable or disable them.
     * The question the home menu asks before offering the Users page.
     *
     * Any administrator qualifies, scoped or not: the `userAdmin` section (issue #225) gives a client-scoped
     * administrator a surface of their own, so answering `true` for them no longer offers a menu item leading
     * to a 403 -- the drift issue #211 set out to remove. What differs between the two is *how much they see*
     * once there, which is [ReadScopeRules.forCaller]'s job, not this one's.
     *
     * This shapes the UI; it is not the enforcement point. The endpoints stay gated by their section, so a
     * frontend that ignores this answer still gets a 403.
     */
    fun canManageUsers(cxt: KdrCxt): Boolean = adminScope(cxt) != AdminScope.none

    // The read scope moved to `ReadScopeRules.forCaller` (issue #225). It answers the same question for an
    // administrator and now also for everybody else, and a scope resolver named for administrators is one an
    // ordinary endpoint's author reads as "not for me" -- so it stopped being an admin rule once the widths
    // covered every caller. `adminScope` above stays here: *that* is genuinely an administrative question.

    /**
     * What the auto-admin rule grants: [ROLE.admin] **and** [ROLE.allClients] (issue #225).
     *
     * The capability has to be part of it, because the rule's whole job is to solve a chicken-and-egg problem
     * and reserving the `admin` section for full-scope administrators created a second one. Nobody holds `allClients`
     * to begin with, and anti-escalation stops an administrator granting reach they do not have -- so without
     * this a fresh deployment would have no way to reach its own admin surface except the `GrantRole` script.
     *
     * The tension to revisit: a **multi-client** deployment almost certainly does not want every address at a
     * domain to become a *global* administrator. Once a client-scoped administration surface exists, this
     * should probably grant the level only, and full scope should become a deliberate act.
     * See deferred-work.md#when-a-deployment-has-a-second-client.
     */
    val autoAdminRoles: List<String> = listOf(ROLE.admin, ROLE.allClients)

    /**
     * The roles a newly provisioned user gets: [ROLE.user], plus whatever their address earns them.
     *
     * Two routes, and they cannot both apply: [autoAdminRoles] when the address carries no `+` tag and
     * matches the configured domain, and otherwise whatever persona the tag names (issue #352). That is the
     * whole of the inversion the design describes -- a `+` tag used to mean only *not an admin*, and now it
     * says which client and, optionally, what within it.
     */
    fun initialRoles(cxt: KdrCxt, primaryId: String): List<String> {
        if (isAutoAdminAddress(cxt, primaryId)) {
            return listOf(ROLE.user) + autoAdminRoles
        }
        return personaRoles(cxt, primaryId)
    }

    /**
     * What the persona in [primaryId] grants, or just [ROLE.user] when it names none (issue #352).
     *
     * **A persona can never grant [ROLE.allClients], and that is structural rather than a check.**
     * [RoleLadder.rolesAtLevel] composes a level out of the ladder plus the capabilities already held, and
     * here nothing is held -- so a persona naming a capability, `allClients` included, produces the floor.
     * The escalation ceiling of the whole email convention is therefore a property of how the roles are
     * built, not a rule somebody has to remember to apply. **A client with no persona is an ordinary user**,
     * which falls out of the same call.
     *
     * A persona that names nothing on the ladder is logged rather than refused. It can only ever
     * *under*-grant, so the failure is safe, and a typo that silently produced an ordinary user with no word
     * said is the thing worth avoiding.
     */
    fun personaRoles(cxt: KdrCxt, primaryId: String): List<String> {
        val persona = AddressRules.tagsFor(cxt, primaryId).persona ?: return listOf(ROLE.user)
        val roles = RoleLadder.rolesAtLevel(emptyList(), persona)
        if (persona != ROLE.user && roles.size == 1) {
            LogAuth.warn(cxt) {
                "Address '$primaryId' names the persona '$persona', which is not one of " +
                    "${RoleLadder.ordered.joinToString(", ")}; creating an ordinary user."
            }
        }
        return roles
    }
}
