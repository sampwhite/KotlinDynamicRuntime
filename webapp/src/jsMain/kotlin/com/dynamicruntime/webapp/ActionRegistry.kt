package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HACT
import com.dynamicruntime.common.uiblock.UiActions
import com.dynamicruntime.common.util.sameOriginPath

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
    private val setEnvDebug: (Boolean) -> Unit,
) {
    // Every URL an item supplies is guarded here, in the one place item data becomes a navigation. A menu item
    // is data a client may overlay, so an unguarded argument that reaches a full-window navigation is an open
    // redirect -- see `sameOriginPath` in the kernel for what is refused and why. A refused value makes the
    // action inert rather than throwing: a menu entry that cannot be trusted is better dead than followed.
    private val byName: Map<String, (List<String>) -> Unit> = mapOf(
        HACT.logout.name to { _ -> logout() },
        // Env logout takes its two URLs from the call (the edge is the authority on both), but authority is
        // not trust: both reach a sink -- an API call and a full-window navigation -- so both are guarded, and
        // the action runs only if both survive. See `HACT.envLogout`.
        HACT.envLogout.name to { args ->
            val logoutPath = sameOriginPath(args.getOrNull(0))
            val landingUrl = sameOriginPath(args.getOrNull(1))
            if (logoutPath != null && landingUrl != null) {
                envLogout(listOf(logoutPath, landingUrl))
            }
        },
        // openPath leaves the SPA for a same-origin server path. See `HACT.openPath`.
        HACT.openPath.name to { args -> sameOriginPath(args.firstOrNull())?.let { openPath(it) } },
        // Turn debug behaviors on or off (issue #517); the arg is the boolean as a string. No URL to guard --
        // it moves session state through the env-auth endpoint, which the backend still gates on env auth.
        HACT.setEnvDebug.name to { args -> setEnvDebug(args.firstOrNull() == "true") },
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
}
