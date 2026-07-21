package com.dynamicruntime.webapp

import com.dynamicruntime.common.app.APP

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
) {
    companion object {
        /** The assumed config before the first fetch (and if a fetch fails): do not suppress (matching dev),
         *  and the shared default interval. */
        val default = AppConfig(
            obfuscateSensitiveErrors = false,
            idleBumpIntervalMs = APP.defaultIdleBumpIntervalMs,
        )
    }
}

private var cached = AppConfig.default

/** The last-fetched app config (see [AppApi]). Deployment-global and stable, so a plain read is fine anywhere. */
fun appConfig(): AppConfig = cached

object AppApi {
    /** GETs `/app/ui/config` and refreshes the [appConfig] cache; a failure leaves the previous value in place. */
    suspend fun load() {
        val config = runCatching { fetchUiConfig(APP.uiConfig) }.getOrNull() ?: return
        cached = AppConfig(
            obfuscateSensitiveErrors = config.features[APP.obfuscateSensitiveErrors] == true,
            // A JSON number arrives as a Number; fall back to the default if the field is missing or malformed.
            idleBumpIntervalMs = (config.settings[APP.idleBumpIntervalMs] as? Number)?.toInt()
                ?: APP.defaultIdleBumpIntervalMs,
        )
    }
}
