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
 * from its section lists and enforces [requiredRole] on every request, comparing through
 * `RoleLadder` so a higher privilege satisfies a lower requirement.
 */
class SectionRules(
    val section: String,
    /**
     * Whether a login is always required. When false, a request from a trusted IP that
     * does not go through the load balancer may be allowed even without a login. (Not yet
     * enforced -- [requiredRole] is what the dispatcher acts on today.)
     */
    val needsLogin: Boolean,
    /** Role required to access this section, or null for an anonymous section. */
    val requiredRole: String?,
)

// ROLE (the role-name constants) moved to the kernel (RoleConstants.kt) so the frontend shares them; it keeps
// this package name, so every reference to `com.dynamicruntime.common.http.request.ROLE` is unaffected.
