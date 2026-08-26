package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrInstanceConfig

/** Cookie attribute constants (issue #431). */
@Suppress("ConstPropertyName")
object CKI {
    /** Env var that defaults [ACFG.cookieSecure] when the config option is unset. */
    const val cookieSecureEnvVar = "KDR_COOKIE_SECURE"

    // `SameSite` attribute values (the wire spelling). `Lax` is the default a session cookie wants -- it still
    // rides a top-level GET navigation, which is what a redirect back from Google is. `Strict` is for a
    // perimeter cookie (`kdrEnvAuth`): nothing should reach the perimeter cross-site, so the cookie need not.
    const val lax = "Lax"
    const val strict = "Strict"
}

/**
 * Whether a `Set-Cookie` this node issues carries `Secure` (issue #431).
 *
 * **Derived from the deployment's own configuration, not from a request header.** The old rule read the
 * `Secure` flag off `X-Forwarded-For`. In a real deployment the edge (or load balancer) in front of a node
 * sets that header, so it is not directly attacker-supplied -- but a request reaching a node with it absent
 * was still issued its session cookie (`kdrAuth`, or the edge's `kdrEnvAuth`) without `Secure`. A cookie's
 * `Secure` flag should be a property of how the node is *served*, not read off a request header at all:
 * hardening, and the kind of line flagged on sight independent of reachability.
 */
object CookieRules {
    /**
     * The [ACFG.cookieSecure] config option wins (so a test can set it directly), then the
     * [CKI.cookieSecureEnvVar] env var, then the environment: secure everywhere except [ENV.local] and
     * [ENV.unit], which is what keeps a plain-HTTP `localhost` -- the developer's box and the real-socket
     * tests -- working.
     */
    fun isSecure(config: KdrInstanceConfig): Boolean =
        (config.get(ACFG.cookieSecure) as? Boolean)
            ?: config.getEnvBool(CKI.cookieSecureEnvVar)
            ?: (config.env != ENV.local && config.env != ENV.unit)
}
