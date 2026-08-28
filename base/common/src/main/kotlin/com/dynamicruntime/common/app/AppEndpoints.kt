package com.dynamicruntime.common.app

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.user.EnvAuthRules
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.SchModule
import com.dynamicruntime.common.endpoint.schemaModule
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.ENVA
import com.dynamicruntime.common.util.toOptEnum
import kotlin.time.Instant

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
            property(
                APP.showErrorDetail,
                "Whether the frontend may show a caught render failure's message and component stack on screen.",
                required = true,
            ) { type = SCT.boolean }
            property(
                APP.allowDebugPages,
                "Whether the frontend's debug pages exist, including the route that makes it fail on demand.",
                required = true,
            ) { type = SCT.boolean }
            property(
                APP.isEnvAuthed,
                "Whether this request is currently acting env-authed (false when suppressed by the session).",
                required = true,
            ) { type = SCT.boolean }
            property(
                APP.envAuthSuppressible,
                "Whether env auth is available on this channel at all, whatever the session is acting as.",
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
                // Reuses the test-instance fence rather than inventing a second notion of "a development
                // build" (issue #223): the flag is already audited, and a node claiming to be a test instance
                // outside local/unit refuses to start, so a real deployment cannot turn this on by accident.
                APP.showErrorDetail to c.instanceConfig.isTestInstance,
                // Same fence, separate flag (issue #227): a route that manufactures a failure is a different
                // power from showing a stack, even where both happen to be permitted by the same instance.
                APP.allowDebugPages to c.instanceConfig.isTestInstance,
                // Per-request, unlike its neighbors (issue #348): the answer depends on how this particular
                // request reached the node, not on how the deployment is configured. Read off the context
                // rather than the header, so the dispatcher's decision and the frontend's view are one answer.
                //
                // Effective and available are both served because one cannot be derived from the other, and
                // the indicator needs the second to survive its own suppression (issue #360).
                APP.isEnvAuthed to c.isEnvAuthEffective,
                // Both halves from one rule (issue #446): the control is offered exactly where the cookie behind
                // it is honored, so they cannot come to disagree.
                APP.envAuthSuppressible to
                    (c.envAuthEmail != null && EnvAuthRules.suppressionOffered(c.instanceConfig)),
            ),
            UIC.settings to mapOf(
                // Always served, defaulting when the deployment did not tune it (a custom-config override, not
                // an env var -- this is UI tuning, not an *ops* concern).
                APP.idleBumpIntervalMs to
                    (c.instanceConfig.get(ACFG.idleBumpIntervalMs) as? Int ?: APP.defaultIdleBumpIntervalMs),
            ),
        )
    }

    // Suppress this session's own env auth, or restore it (issue #360). Live behavior in a deployed
    // environment, not a test affordance: seeing the application as an ordinary user sees it is a real thing
    // to want. Anonymous because env auth is a property of the *channel* -- an env-authed caller need not be
    // logged in at all, so gating this on a role would refuse exactly the people it is for.
    //
    // Safe unfenced because it only ever subtracts. The opposite direction is a `forTestingOnly` fixture
    // (TENV.path) precisely because asserting an env auth nobody granted does not have that property.
    type(APP.envAuthStateType) {
        type = SCT.kObject
        property(APP.isEnvAuthed, "Whether the session is acting env-authed after the operation.",
            required = true) { type = SCT.boolean }
        property(APP.envAuthSuppressible, "Whether this caller may suppress their own env auth.",
            required = true) { type = SCT.boolean }
    }
    generalEndpoint(
        APP.envAuthPath,
        "Suppress this session's env auth, or restore it.",
        HttpMethod.POST, outputRef = APP.envAuthStateType,
        inputFields = {
            field(APP.envAuthOp, "Whether to suppress env auth for this session or restore it.",
                required = true) { options(EnvAuthOp.entries) }
        },
    ) { c, req ->
        // The op is choice-constrained above, so validation already rejected anything but an EnvAuthOp name.
        val op = req[APP.envAuthOp].toOptEnum<EnvAuthOp>()
            ?: throw KdrException.mkInput("A valid '${APP.envAuthOp}' is required.")
        // Written through the request's WebRequest -- the transport-neutral seam -- so this works identically
        // under a real browser and the in-process test client. Safe here because the endpoint handler runs
        // before the response is sent; a cookie set afterward would be silently dropped.
        val web = c.request?.webRequest
        when (op) {
            // A session cookie (no expiry): the downgrade lasts as long as the browser session and no longer,
            // so nobody is left in the reduced view weeks later wondering why.
            EnvAuthOp.suppress -> web?.addResponseCookie(ENVA.suppressCookie, "1", null)
            EnvAuthOp.restore -> web?.addResponseCookie(ENVA.suppressCookie, "", Instant.fromEpochMilliseconds(0))
        }
        // The state as it will be on the NEXT request: this request already resolved its own env auth before
        // the cookie changed, so reporting c.isEnvAuthEffective here would echo the state being left behind.
        val suppressed = op == EnvAuthOp.suppress
        // Same rule as the config above: the control is offered exactly where the cookie is honored.
        val suppressible = c.envAuthEmail != null && EnvAuthRules.suppressionOffered(c.instanceConfig)
        mapOf(APP.isEnvAuthed to (c.envAuthEmail != null && !suppressed), APP.envAuthSuppressible to suppressible)
    }
}
