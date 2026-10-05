package com.dynamicruntime.webapp

import react.createContext
import react.use

/**
 * The app-wide **refresh generation** (issue #115): a counter [App] bumps whenever the user does something that
 * could change how the UI should render -- cross-page navigation, or a state mutation (sign-in, sign-out, a
 * password change). Config-fetching components read [useRefreshGeneration] as a `useEffect` dependency, so a
 * bump re-triggers their UI-config calls, and they pick up any change.
 *
 * This is the one trigger that replaces the ad-hoc ones the app grew: a widget re-fetching its own config on
 * mount, plus hand-rolled re-reads after a mutation (the [AppBar] used to re-fetch the auth config by hand
 * after logout, precisely because navigating home might be a no-op that fires no `hashchange`). A
 * state-mutating handler now just calls [useRefreshBump]'s function, and every mounted consumer re-reads --
 * including a mutation that does not navigate, or navigates to the page already shown.
 *
 * The calls are inexpensive by design, so bumping liberally is the point (issue #113): a bump that changed nothing
 * costs a re-fetch, not a visible change.
 */
class RefreshBus(val generation: Int, val bump: () -> Unit)

/** The context carrying the [RefreshBus]; [App] is the sole provider. The default is inert (generation 0, a
 *  no-op bump) and applies only to a consumer rendered outside the provider, which nothing is. */
val RefreshContext = createContext(RefreshBus(0) {})

/** The current refresh generation. Use it as a `useEffect` dependency on a config fetch so a bump re-runs it. */
fun useRefreshGeneration(): Int = use(RefreshContext).generation

/** Triggers an app-wide refresh: bumps the generation so every mounted config consumer re-fetches. */
fun useRefreshBump(): () -> Unit = use(RefreshContext).bump

/**
 * After a **same-person session change** (issue #1015) -- a switch to another of the person's users (#749), opening
 * or leaving a sandbox (#931), accepting an invitation, removing the user the session acts as: go to [to] (home when
 * empty) and [bump], the way a login completes. No page reload: every per-caller surface either re-reads on the
 * bump -- the shell, the menu, the users list, the sandbox chip -- or fetches when it mounts, and navigating away
 * unmounts the page that was showing. The bump covers the move that changes no hash (already home), which fires no
 * `hashchange`. A reload stays for picking up a newly deployed web app, which the next navigation does on its own.
 */
fun afterSessionChange(bump: () -> Unit, to: List<Pair<String, String>> = emptyList()) {
    navigateHash(to)
    bump()
}
