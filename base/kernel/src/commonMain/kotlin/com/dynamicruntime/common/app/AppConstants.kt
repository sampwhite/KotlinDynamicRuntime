package com.dynamicruntime.common.app

/**
 * Shared wire vocabulary for the app-level UI config (issue #118): the endpoint path, and the feature keys the
 * frontend reads off the response. In the KMP kernel so the frontend uses exactly the strings the backend
 * writes. The app config is deployment-global -- the same for every caller -- and fetched once for the whole
 * frontend rather than per widget-group (part of the refresh foundation, issue #113).
 */
@Suppress("ConstPropertyName")
object APP {
    /** The app-level UI-config endpoint: deployment-wide config visible to the entire frontend. */
    const val uiConfig = "/app/ui/config"

    /**
     * Feature flag: whether this deployment obfuscates sensitive error messages (issue #108). The frontend
     * reads it to decide whether to show the content of a raw (non-fragment) error or suppress it (issue #111).
     */
    const val obfuscateSensitiveErrors = "obfuscateSensitiveErrors"

    /**
     * Setting (under the envelope's `settings`, not a flag): how often, in milliseconds, the frontend "bumps" its refresh
     * generation while a tab is visible, so a long-open tab notices a timed-out session or a newer deploy
     * (issue #146). Deployment-tunable through the custom-config object; the frontend re-arms its timer when the
     * value changes.
     */
    const val idleBumpIntervalMs = "idleBumpIntervalMs"

    /**
     * The default idle-bump interval (issue #146): one minute. Authoritative on the backend, which always
     * serves a value; on the frontend it is only the pre-first-fetch / fetch-failure fallback. Shared here so
     * the two cannot drift.
     */
    const val defaultIdleBumpIntervalMs = 60_000
}
