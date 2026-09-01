package com.dynamicruntime.webapp

import com.dynamicruntime.common.app.APP
import com.dynamicruntime.common.app.EnvAuthOp

/**
 * The app-level config the whole frontend shares (issues #118/#120): deployment-global policy fetched once at
 * the app root -- not per widget-group -- and re-fetched on every refresh generation, so it follows a change
 * like any other config. Held in a module-level cache readable from anywhere, including a non-React error
 * handler, because it is a stable deployment value rather than per-render state.
 *
 * It carries the error-display policy [obfuscateSensitiveErrors] -- the error-rendering frontend (issue #111)
 * reads it to decide whether to show the content of a raw (non-fragment) error or suppress it -- and the
 * idle-bump interval [idleBumpIntervalMs] the app root re-arms its refresh timer from (issue #146).
 */
class AppConfig(
    /** When true, this deployment obfuscates sensitive errors, so the frontend suppresses raw error content. */
    val obfuscateSensitiveErrors: Boolean,
    /** How often (ms) a visible tab refreshes itself; the deployment tunes it, the app root re-arms on change. */
    val idleBumpIntervalMs: Int,
    /**
     * When true, a caught render failure may show its message and component stack on screen rather than a
     * generic panel (issue #223). The backend sets it from `isTestInstance`, so it is on where the app is
     * being developed or tested and off on a real deployment -- the same fence `explainAccess` uses, and for
     * the same reason: the detailed form must not be reachable by an ordinary user.
     */
    val showErrorDetail: Boolean,
    /**
     * When true, the frontend's debug pages resolve -- including the fault route that makes it throw on
     * demand (issue #227). False elsewhere, and then those routes do not exist at all rather than being
     * refused: nothing should acknowledge that a way to break the app is there.
     */
    val allowDebugPages: Boolean,
    /**
     * Whether this is a genuine **test instance** (issue #517) -- distinct from [allowDebugPages], which an
     * env-debug operator now turns on too. A debug tool that demos a `forTestingOnly` fixture is offered only
     * where this is true, so an env-debug operator on a real deployment is never handed a tool that can only fail.
     */
    val isTestInstance: Boolean,
    /**
     * Whether this session is **currently acting** env-authed (issue #360) -- reached the deployment through
     * an authenticating edge, and has not suppressed it. Anything that varies with env auth reads this.
     */
    val isEnvAuthed: Boolean,
    /**
     * Whether env auth is **available** at all, whatever the session is acting as (issue #360). Only the
     * indicator's visibility reads this: while suppressed, [isEnvAuthed] is false but the control that
     * restores it must stay on screen, or there is no way back.
     */
    val envAuthSuppressible: Boolean,
    /**
     * Whether this session has turned on **debug behaviors** (issue #517) -- the third state of the env control.
     * Implies [isEnvAuthed]. Turns on the debug pages, on-screen error detail, and the debug menu.
     */
    val envAuthDebug: Boolean,
) {
    companion object {
        /** The assumed config before the first fetch (and if a fetch fails): the safe, closed defaults, and the
         *  shared interval. */
        val default = AppConfig(
            // Obfuscate until the backend says otherwise (issue #519). If the config never loads -- most likely
            // because the backend is the thing failing -- a raw internal (5xx) message must not be why internals
            // appear on a real deployment's screen. Same fail-closed reasoning as showErrorDetail below.
            obfuscateSensitiveErrors = true,
            idleBumpIntervalMs = APP.defaultIdleBumpIntervalMs,
            // Withhold detail until the backend says otherwise. A fetch that has not happened (or failed) must
            // not be the reason internals appear on a real deployment's screen.
            showErrorDetail = false,
            allowDebugPages = false,
            // Not a test instance until the backend says so; a test-only debug tool must not be offered before
            // the config that confirms the fixtures exist has loaded (issue #517).
            isTestInstance = false,
            // Assume neither until the backend says so, for the same reason as showErrorDetail: a fetch that
            // has not happened must not be why an internal affordance appears.
            isEnvAuthed = false,
            envAuthSuppressible = false,
            // Off until the backend says otherwise -- a debug affordance must not appear before the config that
            // authorizes it has loaded (issue #517).
            envAuthDebug = false,
        )
    }
}

private var cached = AppConfig.default

/** The last-fetched app config (see [AppApi]). Deployment-global and stable, so a plain read is fine anywhere. */
fun appConfig(): AppConfig = cached

/**
 * The pure [UiConfig] -> [AppConfig] mapping, separated from the fetch so it is unit-testable (issue #161).
 * A JSON number arrives as a [Number]; [AppConfig.idleBumpIntervalMs] falls back to the shared default when the
 * field is missing or malformed.
 */
fun appConfigFrom(config: UiConfig): AppConfig = AppConfig(
    obfuscateSensitiveErrors = config.features[APP.obfuscateSensitiveErrors] == true,
    idleBumpIntervalMs = (config.settings[APP.idleBumpIntervalMs] as? Number)?.toInt()
        ?: APP.defaultIdleBumpIntervalMs,
    showErrorDetail = config.features[APP.showErrorDetail] == true,
    allowDebugPages = config.features[APP.allowDebugPages] == true,
    isTestInstance = config.features[APP.isTestInstance] == true,
    isEnvAuthed = config.features[APP.isEnvAuthed] == true,
    envAuthSuppressible = config.features[APP.envAuthSuppressible] == true,
    envAuthDebug = config.features[APP.envAuthDebug] == true,
)

object AppApi {
    /** GETs `/app/ui/config` and refreshes the [appConfig] cache; a failure leaves the previous value in place. */
    suspend fun load() {
        val config = runCatching { fetchUiConfig(APP.uiConfig) }.getOrNull() ?: return
        cached = appConfigFrom(config)
    }
}

/**
 * Moves this session to an env-auth state -- off ([EnvAuthOp.suppress]), on ([EnvAuthOp.restore]), or debug
 * ([EnvAuthOp.debug], issue #517) -- then re-reads the app config so the indicator and the debug affordances
 * reflect the new state.
 *
 * The switch is a **backend** call rather than local state on purpose: remembering the state in the browser
 * would leave the two sides disagreeing -- the backend still reporting env-authed while the screen pretends
 * otherwise -- and the first thing that ever varies with env auth would quietly follow the wrong one.
 */
suspend fun setEnvAuthOp(op: EnvAuthOp) {
    runCatching { Http.sendApi("POST", APP.envAuthPath, mapOf(APP.envAuthOp to op.name)) }
    AppApi.load()
}
