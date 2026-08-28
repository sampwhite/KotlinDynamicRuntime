package com.dynamicruntime.common.util

/**
 * [raw] if it is a **same-origin absolute path**, or null if it is anything else.
 *
 * The one place this rule is written down. A navigation target taken from data is an open redirect unless
 * proven otherwise, and both sides of the deployment take one: the edge honors a `return` parameter after
 * sign-in, and the frontend honors a menu item's action arguments, which a client may overlay. Those two
 * had a copy each, identical check for identical check, until issue #498 -- and two copies of a security rule
 * drift, because hardening one leaves the other permissive and nothing fails when they disagree.
 *
 * It lives in `base:kernel` so the browser and the JVM run the same function rather than the same intent.
 * Callers keep their own answer to a refusal: the edge substitutes its home page (somebody signing in should
 * land somewhere), while the frontend drops the navigation entirely (a menu item that cannot be trusted is
 * better inert than redirected).
 *
 * Refused, each for its own reason:
 *  - anything not starting with `/` -- absolute URLs and relative paths alike; only a rooted path is
 *    unambiguously this origin.
 *  - `//host` and `/\host` -- protocol-relative URLs, which a browser follows off-site while they look local.
 *    This is the case a naive "starts with `/`" check misses, and it is the one that gets used.
 *  - a backslash anywhere -- browsers have historically normalized it to `/`, so it can smuggle the above past
 *    a check that only looks for `/`.
 *  - control characters, via `isISOControl` rather than a `< ' '` test, because that one misses DEL and the C1
 *    range -- and these are the characters that split a header or truncate a value.
 *  - anything carrying a scheme (`https:`, `javascript:`, `data:`) -- off-site, or script execution. A scheme
 *    cannot appear in something already known to start with `/`, but checking costs nothing and the failure it
 *    guards against is total.
 *
 * The value is trimmed before testing, so surrounding whitespace does not decide the answer.
 */
fun sameOriginPath(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty() || !value.startsWith("/")) {
        return null
    }
    if (value.startsWith("//") || value.startsWith("/\\")) {
        return null
    }
    if (value.any { it.isISOControl() || it == '\\' }) {
        return null
    }
    if (value.drop(1).substringBefore('/').contains(':')) {
        return null
    }
    return value
}
