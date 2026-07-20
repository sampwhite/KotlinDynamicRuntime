package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.http.request.ROLE

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

    /** The roles a newly provisioned user gets: [ROLE.user], plus [ROLE.admin] when [primaryId] auto-qualifies. */
    fun initialRoles(cxt: KdrCxt, primaryId: String): List<String> =
        if (isAutoAdminAddress(primaryId, adminEmailDomain(cxt.instanceConfig))) {
            listOf(ROLE.user, ROLE.admin)
        } else {
            listOf(ROLE.user)
        }

    /**
     * Grants [ROLE.admin] to an existing [row] that auto-qualifies but does not yet hold it, returning whether
     * the row was changed (the caller persists it). Called on every login, so configuring the domain reaches
     * accounts that already existed -- the ordinary case, since the operator usually registers before deciding
     * to become an admin. Never revokes: see the class comment.
     */
    fun syncAdminRole(cxt: KdrCxt, row: AuthUserRow): Boolean {
        if (row.roles.contains(ROLE.admin)) {
            return false
        }
        if (!isAutoAdminAddress(row.primaryId, adminEmailDomain(cxt.instanceConfig))) {
            return false
        }
        row.roles = row.roles + ROLE.admin
        LogAuth.info(cxt) { "Granting the admin role to '${row.primaryId}' (matches the configured admin domain)." }
        return true
    }
}
