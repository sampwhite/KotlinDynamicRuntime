package com.dynamicruntime.common.http.request

/**
 * Access rules for a section: the first path segment *after* the context root (e.g. `user` in
 * `/kda/user/profile`) that names a group of endpoints sharing an access policy. Ported from dn's
 * `ContextRootRules` (renamed, since "context root" now denotes the higher-level routing segment). Full
 * enforcement awaits the auth subsystem; for now only [requiredRole] being null (an anonymous section) is
 * acted upon.
 */
class SectionRules(
    val section: String,
    /**
     * Whether a login is always required. When false, a request from a trusted IP that
     * does not go through the load balancer may be allowed even without a login. (Not yet
     * enforced -- auth is stubbed.)
     */
    val needsLogin: Boolean,
    /** Role required to access this section, or null for an anonymous section. */
    val requiredRole: String?,
)

/** Role names used by the (stubbed) security check. */
@Suppress("ConstPropertyName")
object ROLE {
    const val user = "user"
    const val admin = "admin"
}
