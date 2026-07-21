package com.dynamicruntime.webapp

import kotlinx.coroutines.awaitCancellation
import react.useEffect

/**
 * Keeps a long-open tab honest (issue #146). Periodically -- and whenever the app is returned to -- it "bumps"
 * the app-wide refresh generation ([bump] is the #115 counter's increment), which every mounted config
 * consumer already re-reads on. So a session that timed out while the tab sat idle quietly reverts to the
 * anonymous menu, and a version deployed in the meantime raises the reload banner (issue #136), all without the
 * user having touched anything.
 *
 * Three triggers, all funneling to the same bump. Re-fetching config that turns out unchanged costs a fetch,
 * not a visible change, so bumping on any of them is cheap by design (issue #113):
 *  - a periodic tick every [intervalMs] -- the backstop for a tab left open and untouched;
 *  - the tab becoming visible again (`visibilitychange`) -- returning after a tab switch;
 *  - the window regaining focus (`focus`) -- returning after switching to another application, where the tab
 *    stayed visible so `visibilitychange` never fired.
 *
 * **A hidden tab makes no traffic.** The periodic tick bumps only while the page is visible, so a backgrounded
 * tab sits silent (browsers also throttle its timers) and instead wakes on the visibility/focus triggers when
 * the user comes back. Visibility is re-checked on each tick rather than stopping and restarting the timer, so
 * the effect stays one self-contained subscription.
 *
 * The effect re-runs whenever [intervalMs] changes, tearing down the old timer and listeners first, so a later
 * reconfigured interval can never leave a stale timer running. [intervalMs] `<= 0` disables the periodic tick
 * (the visibility/focus bumps remain), which also makes the hook easy to neutralize in a test.
 *
 * The effect body is a suspending scope (the wrappers' effect model): it arms the subscriptions, then parks on
 * [awaitCancellation] so the `finally` teardown runs exactly when the effect is torn down -- on an
 * [intervalMs] change or on unmount -- which is what retires a superseded timer.
 */
fun useIdleBump(intervalMs: Int, bump: () -> Unit) {
    useEffect(intervalMs) {
        // Return to a hidden tab (a tab switch): bump only on the transition *to* visible, and ignore the
        // paired hidden event -- a tab going away is not a reason to re-fetch.
        val onVisibility = { if (isPageVisible()) bump() }
        // Return to the window (an application switch, with the tab already visible): the window's `focus`.
        val onFocus = { bump() }
        // The periodic backstop only counts while the tab is actually being looked at.
        val onTick = { if (isPageVisible()) bump() }

        addDocListener("visibilitychange", onVisibility)
        addWindowListener("focus", onFocus)
        val timerId = if (intervalMs > 0) startInterval(onTick, intervalMs) else -1

        try {
            awaitCancellation()
        } finally {
            removeDocListener("visibilitychange", onVisibility)
            removeWindowListener("focus", onFocus)
            if (timerId != -1) clearIntervalId(timerId)
        }
    }
}

/** Whether the tab is currently the visible/foreground one; a hidden tab must not generate idle traffic. */
private fun isPageVisible(): Boolean = js("document.visibilityState === 'visible'") as Boolean

// setInterval/clearInterval and add/removeEventListener are used raw (as elsewhere in the frontend, e.g.
// HashRoute) rather than through typed wrappers. The same Kotlin lambda value is handed to both the add and the
// remove call, so removal matches the exact listener that was registered.
private fun startInterval(callback: () -> Unit, ms: Int): Int = js("setInterval(callback, ms)") as Int
private fun clearIntervalId(id: Int) { js("clearInterval(id)") }
private fun addWindowListener(type: String, handler: () -> Unit) { js("window.addEventListener(type, handler)") }
private fun removeWindowListener(type: String, handler: () -> Unit) { js("window.removeEventListener(type, handler)") }
private fun addDocListener(type: String, handler: () -> Unit) { js("document.addEventListener(type, handler)") }
private fun removeDocListener(type: String, handler: () -> Unit) { js("document.removeEventListener(type, handler)") }
