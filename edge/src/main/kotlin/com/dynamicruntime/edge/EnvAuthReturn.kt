package com.dynamicruntime.edge

/**
 * Where a caller is sent after signing in (issue #386).
 *
 * The reason it exists: a challenge that drops you at the hostname rather than the page you asked for is a
 * poor experience, and is one of the things that made a bought solution not fit. So the originally requested
 * path travels through the login, and the page returns to it.
 *
 * The reason it is careful: a redirect target taken from a query string is an **open redirect** unless proven
 * otherwise, and a perimeter is the worst place in a system to have one -- a link to the genuine sign-in host
 * that lands somewhere else afterwards is exactly the shape of a credible phishing link.
 */
object EnvAuthReturn {
    /** Query parameter naming where to go after signing in. */
    const val param = "next"

    /** Where a caller goes when nothing valid was asked for. */
    const val default = "/"

    /**
     * [raw] reduced to a **same-site absolute path**, or [default] when it is anything else.
     *
     * Allowed: a single leading `/`, then path characters. Refused, each for its own reason:
     *
     *  - `//host` and `/\host` -- protocol-relative URLs, which a browser follows off-site while they look
     *    local. This is the case a naive "starts with `/`" check misses, and it is the one that gets used.
     *  - anything carrying a scheme (`https:`, `javascript:`, `data:`) -- off-site, or script execution.
     *  - a backslash anywhere -- browsers have historically normalized it to `/`, so it can smuggle the above
     *    past a check that only looks for `/`.
     *  - control characters, via `isISOControl` rather than a `< ' '` test, because that one misses DEL and
     *    the C1 range -- and these are the characters that split a header or truncate a value.
     *
     * A refused value is **replaced rather than rejected**: somebody signing in should land somewhere, and the
     * home page is a truthful answer to "that could not be honored".
     */
    fun sanitize(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || !value.startsWith("/")) {
            return default
        }
        if (value.startsWith("//") || value.startsWith("/\\")) {
            return default
        }
        if (value.any { it.isISOControl() || it == '\\' }) {
            return default
        }
        // A scheme cannot appear in something that starts with `/`, but checking costs nothing and the
        // failure it guards against is total.
        if (value.drop(1).substringBefore('/').contains(':')) {
            return default
        }
        return value
    }
}
