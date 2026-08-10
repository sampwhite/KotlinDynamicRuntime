package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.context.ReadScope
import com.dynamicruntime.common.http.request.ROLE

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
 * escape hatch is a configured email domain: an address **at that domain** (or a subdomain of it) whose local
 * part carries no `+` tag is granted [ROLE.admin] when its user is provisioned, and again on each login (see
 * [syncAdminRole]) so the rule also reaches accounts that predate the configuration.
 *
 * The `+` exclusion is the useful half of the rule. Plus-addressing means one operator mailbox can register any
 * number of accounts (`sam+test1@acme.com`, `sam+qa@acme.com`) that deliver to the same inbox but are *not*
 * admins -- so a deployment can test ordinary-user behavior without a second domain and without hand-editing
 * roles.
 *
 * The domain is matched against the address's domain part only. A bare suffix test over the whole address would
 * make `notacme.com` match a configured `acme.com`, which is precisely the mistake that hands an attacker an
 * admin account for the price of a domain registration.
 *
 * The rule only ever **grants**. Removing the configuration does not demote anyone (nor does an address that
 * stops matching): revocation is an explicit administrative act -- see the `admin/user/setRoles` endpoint and
 * the `GrantRole` command-line script.
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
     * Whether [address] auto-qualifies for [ROLE.admin] against [domain]: its domain part is [domain] or a
     * subdomain of it, and its local part is non-empty and carries no `+` tag. A null [domain] (unconfigured)
     * never qualifies.
     */
    fun isAutoAdminAddress(address: String, domain: String?): Boolean {
        val d = domain ?: return false
        val trimmed = address.trim().lowercase()
        val at = trimmed.lastIndexOf(ADMR.atChar)
        if (at <= 0 || at == trimmed.length - 1) {
            return false // no domain part, or no local part
        }
        val local = trimmed.substring(0, at)
        val addressDomain = trimmed.substring(at + 1)
        if (local.contains(ADMR.plusAddressChar) || local.contains(ADMR.atChar)) {
            return false
        }
        return addressDomain == d || addressDomain.endsWith(ADMR.domainSep + d)
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
     * once there, which is [adminReadScope]'s job, not this one's.
     *
     * This shapes the UI; it is not the enforcement point. The endpoints stay gated by their section, so a
     * frontend that ignores this answer still gets a 403.
     */
    fun canManageUsers(cxt: KdrCxt): Boolean = adminScope(cxt) != AdminScope.none

    /**
     * What an administrator's reads are confined to. Passed to the user queries, so "which rows may I see" is
     * decided once here rather than per endpoint -- a new admin endpoint gets the scope by construction
     * instead of by remembering.
     *
     * A caller who is not an administrator gets [ReadScope.unrestricted] rather than "nothing": the admin
     * surface is closed to them by its section gate, so there is no read for this to constrain. The day
     * ordinary endpoints scope their own reads, *their* scope is `ofUser`, resolved somewhere that knows it
     * is an ordinary read -- not here.
     */
    fun adminReadScope(cxt: KdrCxt): ReadScope = when (adminScope(cxt)) {
        AdminScope.ownClient -> ReadScope.ofClient(cxt.userProfile.client)
        AdminScope.allClients, AdminScope.none -> ReadScope.unrestricted
    }

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
     * should probably grant the level only, and full scope should become a deliberate act. Recorded on #225.
     */
    val autoAdminRoles: List<String> = listOf(ROLE.admin, ROLE.allClients)

    /** The roles a newly provisioned user gets: [ROLE.user], plus [autoAdminRoles] when [primaryId] qualifies. */
    fun initialRoles(cxt: KdrCxt, primaryId: String): List<String> =
        if (isAutoAdminAddress(primaryId, adminEmailDomain(cxt.instanceConfig))) {
            listOf(ROLE.user) + autoAdminRoles
        } else {
            listOf(ROLE.user)
        }

    /**
     * Grants [autoAdminRoles] to an existing [row] that auto-qualifies but does not yet hold them, returning
     * whether the row was changed (the caller persists it). Called on every login, so configuring the domain
     * reaches accounts that already existed -- the ordinary case, since the operator usually registers before
     * deciding to become an admin. Never revokes: see the class comment.
     *
     * It tops up **each** missing role rather than checking only for [ROLE.admin], which is what carries an
     * administrator from before #225 over the change: they already hold `admin`, and the next login is where
     * they gain the `allClients` they now need to reach the surface they had yesterday.
     */
    fun syncAdminRole(cxt: KdrCxt, row: AuthUserRow): Boolean {
        val missing = autoAdminRoles.filter { it !in row.roles }
        if (missing.isEmpty()) {
            return false
        }
        if (!isAutoAdminAddress(row.primaryId, adminEmailDomain(cxt.instanceConfig))) {
            return false
        }
        row.roles = row.roles + missing
        LogAuth.info(cxt) {
            "Granting ${missing.joinToString(", ")} to '${row.primaryId}' (matches the configured admin domain)."
        }
        return true
    }
}
