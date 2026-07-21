package com.dynamicruntime.common.app

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.schema.SCT

/** Schema type name for the app UI-config output (backend-only; the frontend keys off the [APP] wire constants). */
private const val appUiConfigType = "AppUiConfig"

/**
 * The app-level UI-config endpoint (issue #118, part of the refresh foundation #113): deployment-global config
 * the whole frontend shares, fetched once at the app root rather than per widget-group. Anonymous -- the policy
 * is identical for every caller, so there is nothing user-specific to gate.
 *
 * Its first tenant is the error-display policy: whether this deployment obfuscates sensitive error messages
 * (issue #108), so the frontend can decide whether to show the content of a raw (non-fragment) error or
 * suppress it (issue #111). Resolved through the same [RequestHandler.obfuscateSensitiveErrors] the error edge
 * uses, so the frontend's view of the policy and the backend's actual behavior cannot drift apart.
 *
 * Follows the UI-config envelope (issue #70): `features` for the boolean error-display policy, and `settings`
 * for the idle-bump interval (issue #146) -- a tuning value the frontend re-arms its refresh timer from.
 * `fragments`/`state` arrive if the app config later grows "shell" copy or dynamic state.
 */
fun appSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "app") {
    type(appUiConfigType) {
        type = SCT.kObject
        property(UIC.features, "Deployment-wide policy flags visible to the whole frontend.", required = true) {
            type = SCT.kObject
            property(
                APP.obfuscateSensitiveErrors,
                "Whether sensitive error messages are obfuscated; when true the frontend suppresses raw error content.",
                required = true,
            ) { type = SCT.boolean }
        }
        property(UIC.settings, "Deployment-wide tuning values (non-flag) visible to the whole frontend.", required = true) {
            type = SCT.kObject
            property(
                APP.idleBumpIntervalMs,
                "How often (ms) the frontend refreshes itself while a tab is visible, so it notices a timed-out session or a newer deploy.",
                required = true,
            ) { type = SCT.integer }
        }
    }

    generalEndpoint(APP.uiConfig, "Returns deployment-wide app config visible to the entire frontend.",
        HttpMethod.GET, outputRef = appUiConfigType) { c, _ ->
        mapOf(
            UIC.features to mapOf(
                APP.obfuscateSensitiveErrors to RequestHandler.obfuscateSensitiveErrors(c.instanceConfig),
            ),
            UIC.settings to mapOf(
                // Always served, defaulting when the deployment did not tune it (a custom-config override, not
                // an env var -- this is UI tuning, not an *ops* concern).
                APP.idleBumpIntervalMs to
                    (c.instanceConfig.get(ACFG.idleBumpIntervalMs) as? Int ?: APP.defaultIdleBumpIntervalMs),
            ),
        )
    }
}
