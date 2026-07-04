package com.dynamicruntime.common.http.request

/**
 * Security rules for a top-level context root (the first path segment). Ported from
 * dn's `ContextRootRules`. Full enforcement awaits the auth subsystem; for now only
 * [requiredRole] being null (an anonymous root) is acted upon.
 */
class ContextRootRules(
    val contextRoot: String,
    /**
     * Whether a login is always required. When false, a request from a trusted IP that
     * does not go through the load balancer may be allowed even without login. (Not yet
     * enforced -- auth is stubbed.)
     */
    val needsLogin: Boolean,
    /** Role required to access this root, or null for an anonymous root. */
    val requiredRole: String?,
)

/** Role names used by the (stubbed) security check. */
@Suppress("ConstPropertyName")
object ROLE {
    const val user = "user"
    const val admin = "admin"
}
