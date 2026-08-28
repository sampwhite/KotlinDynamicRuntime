package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HACT
import com.dynamicruntime.common.uiblock.UiActions

/**
 * What the frontend can be asked to *do* by name (issue #483) -- the implementations behind the functions the
 * kernel declares in `UiActions`.
 *
 * **Hardwired, and that is the point.** Backend data may name a function; it may never supply one. The name is
 * the seam: everything on the data side is configurable, per-client and overlayable, and everything on this
 * side is typed, tested, and reviewed. When somebody wants a conditional argument or two calls in sequence, the
 * answer is a new entry here rather than a richer array on the wire.
 *
 * Parameters arrive as strings, and an implementation coerces what it needs -- which is why they are typed
 * here rather than on the wire.
 */
class FrontendActions(
    private val logout: () -> Unit,
    private val envLogout: (List<String>) -> Unit,
    private val openPath: (String) -> Unit,
) {
    private val byName: Map<String, (List<String>) -> Unit> = mapOf(
        HACT.logout.name to { _ -> logout() },
        // Env logout takes its two URLs from the call (the edge is the authority on both); this side is pure
        // mechanism and passes the arguments straight through -- see `HACT.envLogout`.
        HACT.envLogout.name to { args -> envLogout(args) },
        // openPath leaves the SPA for a same-origin server path. The path is guarded here -- a bad or off-site
        // value is dropped rather than navigated to -- because a menu item is data a client may overlay, and
        // this is the one function that turns such data into a navigation. See `HACT.openPath`.
        HACT.openPath.name to { args -> args.firstOrNull()?.let { sameOriginPath(it) }?.let { openPath(it) } },
    )

    /** Runs [name] with [args], or returns false when nothing implements it. */
    fun run(name: String, args: List<String>): Boolean {
        val fn = byName[name] ?: return false
        fn(args)
        return true
    }

    /**
     * Declared function names this registry does **not** implement.
     *
     * The mirror of the backend's boot check, and the only guard for the gap that check cannot see: the
     * backend refuses a block naming an undeclared function, but a function declared *and* named *and* never
     * implemented is a click that silently does nothing. Asserted at startup rather than discovered by a user.
     */
    fun missing(): List<String> = UiActions.declared.map { it.name }.filterNot { byName.containsKey(it) }

    companion object {
        /**
         * [raw] if it is a **same-origin absolute path**, or null (issue #493). The frontend twin of the
         * edge's `EnvAuthReturn.sanitize`, and for the same reason: a navigation target taken from data is an
         * open redirect unless proven otherwise, and a menu item's action is data a client may overlay.
         *
         * Refused, each for its own reason: a protocol-relative `//host` or `/\host` (a browser follows it
         * off-site while it looks local -- the case a naive "starts with /" check misses); any scheme
         * (`https:`, `javascript:`); a backslash anywhere (browsers normalize it to `/`, so it can smuggle the
         * above past a `/`-only check); and control characters. Covered under `jsNodeTest`.
         */
        fun sameOriginPath(raw: String): String? {
            val v = raw.trim()
            if (v.isEmpty() || !v.startsWith("/")) return null
            if (v.startsWith("//") || v.startsWith("/\\")) return null
            if (v.any { it.isISOControl() || it == '\\' }) return null
            if (v.drop(1).substringBefore('/').contains(':')) return null
            return v
        }
    }
}
