package com.dynamicruntime.webapp

/**
 * Detects when a newer web app has been deployed than the one this browser tab is running (issue #136).
 *
 * The backend hashes the served bundle and both (a) injects the running tab's own hash into the page bootstrap
 * (`window.kdrCfg.webAppHash`, at serve time) and (b) returns the *deployed* hash on every response envelope
 * (`EP.webAppHash`, issue #134). When those diverge, a newer bundle is live than the one loaded here.
 *
 * The reaction is deliberately **non-destructive**: we never reload out from under the user (a half-filled
 * form, an unsent message). We flag the tab stale, [App] surfaces a "new version available" affordance, and the
 * reload happens on the user's next navigation or an explicit click.
 *
 * In dev the webpack server serves the bundle, so the backend does not know its hash: `kdrCfg.webAppHash` is
 * absent, which disables the check entirely (hot-reload covers dev).
 */

/** The hash of the bundle this tab is running, from the page bootstrap; empty in dev, which disables the check. */
private val ownWebAppHash: String = js("(window.kdrCfg && window.kdrCfg.webAppHash) || ''") as String

private var stale = false
private var onStale: (() -> Unit)? = null

/** Registers the one-shot callback fired when a newer web-app version is first detected. */
fun onWebAppStale(handler: () -> Unit) {
    onStale = handler
}

/** Whether a newer web-app version has been detected than the one this tab is running. */
fun isWebAppStale(): Boolean = stale

/**
 * Compares the [liveHash] a response reported against this tab's own hash. A difference means a newer app is
 * deployed: flag stale and notify (once). A no-op when either hash is unknown -- in dev, or for a response that
 * is not an endpoint envelope (a fragment or document has no `webAppHash`).
 */
fun observeWebAppHash(liveHash: String?) {
    if (stale || ownWebAppHash.isEmpty() || liveHash.isNullOrEmpty()) return
    if (liveHash != ownWebAppHash) {
        stale = true
        onStale?.invoke()
    }
}

/** Reloads the page to pick up the newly deployed web app. */
fun reloadWebApp() {
    js("window.location.reload()")
}
