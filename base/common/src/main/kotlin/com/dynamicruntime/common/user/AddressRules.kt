package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.http.request.ROLE

/** Constants for the address conventions (issue #352). */
@Suppress("ConstPropertyName")
object ADR {
    /**
     * The domain reserved for examples, which is controlled everywhere but production.
     *
     * Reserved by RFC 2606 precisely so that nobody can own it, which is what makes it safe to treat as ours.
     * Excluded in production for the same reason it is safe elsewhere: an address nobody can receive mail at
     * should not be able to earn a grant on a deployment that has real ones.
     */
    const val exampleDomain = "example.com"

    /**
     * The domains Google hosts *consumer* Gmail accounts under. Google is authoritative for an address at one
     * of these (issue #429) -- but "any Gmail address" is no organizational boundary, so a deployment that
     * names one as its admin domain admits nobody through the edge's `hd` check, by design.
     */
    val gmailDomains = setOf("gmail.com", "googlemail.com")
}

/**
 * What a **controlled** address says about the user it creates (issue #352): whether it is one of the
 * deployment's own, and so may earn the auto-admin grant.
 *
 * Two domains are controlled -- the deployment's configured admin domain, and [ADR.exampleDomain] outside
 * production. That is all an address says now. The `+client%persona` tags that once named a client and a
 * persona on these domains were **retired** in issue #750: an administrator provisions a user with a persona
 * and a personaSuffix through the console, and a person proves such a user by invitation (phase E), so an address
 * no longer needs to carry either. What survives of the convention is [AdminRules.isAutoAdminAddress]'s
 * no-tag rule -- a `+` tag still says "not the deployment's own person", and nothing more.
 *
 * What a controlled address *grants* is [AdminRules]' business, which is the division the design draws: an
 * address can say who somebody is, and only the rules say what that is worth.
 */
object AddressRules {
    /**
     * The client a new user lands in when nobody named one, given the [roles] it is being provisioned with.
     *
     * A user holding [ROLE.allClients] goes to [CL.hub], the deployment's own client (issue #799): such a user
     * is the house's -- most often the deployment's first admin, whom [AdminRules.initialRoles] grants the
     * capability on the auto-admin domain -- and parking them in the guests' placeholder client made the first
     * thing an operator saw a client that is nobody's. So does a user provisioned as an administrator
     * ([ROLE.admin]) with no client named (issue #805): `public` has no client administrators -- an
     * administrator there reaches only their own users -- so an administrator meant to administer a client
     * belongs in a real one. Everyone else lands in [CL.public], the placeholder for a person who has not yet
     * been invited anywhere; a `public` user is made an administrator of themselves only *after* landing there
     * (`AdminRules.rolesInClient`), so that grant never moves them.
     *
     * The address used to be able to name a client (retired, issue #750); the request's host will be able to,
     * later (the "default client" rule the Google sign-in and the registration share). Kept as a function so
     * those callers name the seam rather than the constant. An explicitly named client always wins over this.
     */
    @Suppress("UNUSED_PARAMETER")
    fun defaultClient(cxt: KdrCxt, roles: Collection<String>): String =
        if (ROLE.allClients in roles || ROLE.admin in roles) CL.hub else CL.public

    /**
     * Whether [address] sits on a domain this deployment treats as its own.
     *
     * Matched against the address's **domain part only**, and against a subdomain of it, exactly as
     * [AdminRules.isAutoAdminAddress] does -- a bare suffix test over the whole address would make
     * `notacme.com` match a configured `acme.com`, which is how somebody buys an admin account for the price
     * of a domain registration.
     */
    fun isControlledDomain(cxt: KdrCxt, address: String): Boolean {
        if (isAdminDomain(cxt, address)) return true
        val domain = domainOf(address) ?: return false
        return cxt.instanceConfig.env != ENV.prod && matches(domain, ADR.exampleDomain)
    }

    /**
     * Whether [address] sits on the deployment's configured admin domain (`KDR_ADMIN_EMAIL_DOMAIN`) or a
     * subdomain of it -- the deployment's own people, as distinct from the example domain that is also
     * controlled outside production. False when no admin domain is configured.
     */
    fun isAdminDomain(cxt: KdrCxt, address: String): Boolean {
        val domain = domainOf(address) ?: return false
        val configured = AdminRules.adminEmailDomain(cxt.instanceConfig) ?: return false
        return matches(domain, configured)
    }

    /**
     * Whether the signed `hd` claim [hostedDomain] establishes Google as authoritative for the deployment's
     * configured admin domain, to the standard of Google's own backend-auth guidance (issue #429).
     *
     * `email_verified` plus a matching address domain is deliberately **not** enough. A Google *consumer*
     * account can be registered against an arbitrary address -- ownership proven by receiving mail there, which
     * a stale alias, a forward, or a catch-all can also do -- and it presents `email_verified: true` with **no**
     * `hd`. Only a Workspace account carries `hd`, and Google says that claim, being inside the signed token,
     * is the one that may be trusted. So the perimeter requires it, matched **exactly** against the configured
     * domain: unlike [isControlledDomain]'s subdomain latitude for *addresses*, `eu.acme.com` does not satisfy
     * `acme.com` here -- the tighter rule a perimeter wants (issue #429's open question, decided for exactness).
     * The configured domain is normalized by [AdminRules.adminEmailDomain]; [hostedDomain] is normalized the
     * same way, so the comparison is that of two like-shaped domain parts, not a bare string test.
     *
     * **Fails closed** when no admin domain is configured, or it is itself a Gmail-hosted domain ([ADR.gmailDomains]):
     * neither can name a Workspace whose `hd` could match, so there is nobody this check could ever admit.
     */
    fun isGoogleAuthoritative(cxt: KdrCxt, hostedDomain: String?): Boolean {
        val configured = AdminRules.adminEmailDomain(cxt.instanceConfig) ?: return false
        if (configured in ADR.gmailDomains) {
            return false
        }
        val hd = hostedDomain?.trim()?.lowercase()?.ifEmpty { null } ?: return false
        return hd == configured
    }

    /** [address]'s domain part, lower-cased, or null when it has no usable one. */
    private fun domainOf(address: String): String? {
        val trimmed = address.trim().lowercase()
        val at = trimmed.lastIndexOf(ADMR.atChar)
        if (at <= 0 || at == trimmed.length - 1) {
            return null
        }
        return trimmed.substring(at + 1)
    }

    private fun matches(domain: String, controlled: String): Boolean =
        domain == controlled || domain.endsWith(ADMR.domainSep + controlled)
}
