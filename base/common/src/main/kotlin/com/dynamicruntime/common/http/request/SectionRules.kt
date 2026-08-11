package com.dynamicruntime.common.http.request

/**
 * The section an application path belongs to: its first segment (`admin` for `/admin/users`). The application
 * path is already stripped of the context root, so the leading `/` is all there is to skip.
 *
 * The one definition of the rule, deliberately. A request learns its section here (`RequestHandler.section`)
 * and so does anything asking about an endpoint it is *not* currently serving -- the catalog, deciding what a
 * caller may see (issue #211). Two spellings of "which section is this" is precisely how a catalog comes to
 * advertise what the dispatcher then refuses.
 */
fun sectionOf(appPath: String): String = appPath.removePrefix("/").substringBefore('/')

/**
 * Access rules for a section: the first path segment *after* the context root (e.g. `user` in
 * `/kda/user/profile`) that names a group of endpoints sharing an access policy. Ported from dn's
 * `ContextRootRules` (renamed, since "context root" now denotes the higher-level routing segment).
 *
 * The section is the unit an endpoint's privilege is declared at -- there is no per-endpoint marking, so
 * endpoints that need different privileges belong in different sections. `RequestService` builds the map
 * from its section lists; [admits] is the one answer both the dispatcher's gate and the endpoint catalog
 * ask, so what is advertised and what is served cannot drift apart.
 *
 * **A section constrains two independent things**, matching the two axes the role model already draws:
 * [requiredRole] is a *level* on `RoleLadder`, where a higher rung satisfies a lower requirement, and
 * [requiredCapability] is an off-ladder *capability* that must be held outright. Both must be met.
 */
class SectionRules(
    val section: String,
    /**
     * Whether a login is always required. When false, a request from a trusted IP that
     * does not go through the load balancer may be allowed even without a login. (Not yet
     * enforced -- [admits] is what the dispatcher acts on today.)
     */
    val needsLogin: Boolean,
    /** Level required to access this section, or null when the section demands no level. */
    val requiredRole: String?,
    /**
     * A capability that must additionally be held, or null when the section demands none (issue #225).
     *
     * Separate from [requiredRole] rather than expressed as one, because a capability and a level are not
     * comparable: `RoleLadder.satisfies` falls back to exact membership for an off-ladder role, so naming a
     * capability as the *required role* makes holding it **sufficient on its own** -- it would confer the
     * section instead of qualifying it. That is exactly how a user demoted to `user` kept the full-scope
     * `admin` surface while losing the lesser scoped one, since nothing about their level was being asked.
     */
    val requiredCapability: String? = null,
) {
    /** Whether this section constrains anything at all; false for an anonymous section. */
    val isGated: Boolean get() = requiredRole != null || requiredCapability != null

    /**
     * Whether [heldRoles] may reach this section: the level satisfied through `RoleLadder` (so an admin
     * passes an operator section) **and** the capability held outright.
     */
    fun admits(heldRoles: Set<String>): Boolean = unmetRequirement(heldRoles) == null

    /**
     * The name of the first requirement [heldRoles] does not meet, or null when the section admits them. Used
     * to say which of the two is missing, since "requires the 'admin' role" is a misleading thing to tell
     * somebody who has it and is short the capability.
     */
    fun unmetRequirement(heldRoles: Set<String>): String? {
        val role = requiredRole
        if (role != null && !RoleLadder.satisfies(heldRoles, role)) return role
        val capability = requiredCapability
        if (capability != null && capability !in heldRoles) return capability
        return null
    }
}

// ROLE (the role-name constants) moved to the kernel (RoleConstants.kt), so the frontend shares them; it keeps
// this package name, so every reference to `com.dynamicruntime.common.http.request.ROLE` is unaffected.
