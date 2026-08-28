package com.dynamicruntime.edge

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.util.sameOriginPath

/**
 * Where a caller is sent after signing in (issue #386).
 *
 * The reason it exists: a challenge that drops you at the hostname rather than the page you asked for is a
 * poor experience, and is one of the things that made a bought solution not fit. So the originally requested
 * path travels through the login, and the page returns to it.
 *
 * The reason it is careful: a redirect target taken from a query string is an **open redirect** unless proven
 * otherwise, and a perimeter is the worst place in a system to have one -- a link to the genuine sign-in host
 * that lands somewhere else afterward is exactly the shape of a credible phishing link.
 */
@Suppress("ConstPropertyName")
object EnvAuthReturn {
    /**
     * Query parameter naming where to go after signing in.
     *
     * The name is `EP.envAuthNextParam`, not a second copy: the web app writes this parameter too, when it
     * follows an edge's 401 to the sign-in page, and the two spellings must not be able to drift apart.
     */
    const val param = EP.envAuthNextParam

    /** Where a caller goes when nothing valid was asked for. */
    const val default = "/"

    /**
     * [raw] reduced to a **same-site absolute path**, or [default] when it is anything else.
     *
     * The rule itself is `sameOriginPath` in the kernel, shared with the frontend's action registry so the two
     * cannot drift (issue #498) -- see it for what is refused and why. What stays here is the perimeter's own
     * answer to a refusal: the value is **replaced rather than rejected**, because somebody signing in should
     * land somewhere, and the home page is a truthful answer to "that could not be honored".
     */
    fun sanitize(raw: String?): String = sameOriginPath(raw) ?: default
}
