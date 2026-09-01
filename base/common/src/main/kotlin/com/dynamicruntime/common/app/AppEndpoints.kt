package com.dynamicruntime.common.app

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.user.EnvAuthRules
import com.dynamicruntime.common.endpoint.ETAG
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
                APP.isTestInstance,
                "Whether this is a genuine test instance (test-only fixtures present), separate from allowDebugPages.",
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
            property(
                APP.envAuthDebug,
                "Whether this session has turned on debug behaviors (issue #517); implies isEnvAuthed.",
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
        HttpMethod.GET, outputRef = appUiConfigType, tags = setOf(ETAG.frontend)) { c, _ ->
        mapOf(
            UIC.features to mapOf(
                APP.obfuscateSensitiveErrors to RequestHandler.obfuscateSensitiveErrors(c.instanceConfig),
                // The test-instance fence (issue #223), now also opened by a session in ENV DEBUG (issue #517):
                // an env-authed operator may turn these on for their own session on any deployment. The flag is
                // still audited -- a node claiming to be a test instance outside local/unit refuses to start,
                // and ENV DEBUG requires effective env auth -- so a real deployment cannot turn it on by accident.
                APP.showErrorDetail to (c.instanceConfig.isTestInstance || c.isEnvDebug),
                // Same fence, separate flag (issue #227): a route that manufactures a failure is a different
                // power from showing a stack, even where both happen to be permitted by the same instance.
                APP.allowDebugPages to (c.instanceConfig.isTestInstance || c.isEnvDebug),
                // The raw test-instance truth (issue #517), which allowDebugPages no longer implies: a debug
                // tool that demos a forTestingOnly fixture is offered only where the fixture is registered, so
                // an env-debug operator on a real deployment is never handed a tool that can only fail.
                APP.isTestInstance to c.instanceConfig.isTestInstance,
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
                // The third state (issue #517): whether debug behaviors are on for this session. Implies
                // isEnvAuthed, since debug requires env auth to be effective.
                APP.envAuthDebug to c.isEnvDebug,
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
        property(APP.envAuthDebug, "Whether debug behaviors are on after the operation (issue #517).",
            required = true) { type = SCT.boolean }
    }
    generalEndpoint(
        APP.envAuthPath,
        "Set this session's env-auth state: off (suppress), on (restore), or debug (issue #517).",
        HttpMethod.POST, outputRef = APP.envAuthStateType,
        inputFields = {
            field(APP.envAuthOp, "Which state to move to: '${EnvAuthOp.suppress}' (off), '${EnvAuthOp.restore}' " +
                "(on), or '${EnvAuthOp.debug}'.", required = true) { options(EnvAuthOp.entries) }
        },
    ) { c, req ->
        // The op is choice-constrained above, so validation already rejected anything but an EnvAuthOp name.
        val op = req[APP.envAuthOp].toOptEnum<EnvAuthOp>()
            ?: throw KdrException.mkInput("A valid '${APP.envAuthOp}' is required.")
        // Written through the request's WebRequest -- the transport-neutral seam -- so this works identically
        // under a real browser and the in-process test client. Safe here because the endpoint handler runs
        // before the response is sent; a cookie set afterward would be silently dropped.
        val web = c.request?.webRequest
        val set = { name: String -> web?.addResponseCookie(name, "1", null) }
        val clear = { name: String -> web?.addResponseCookie(name, "", Instant.fromEpochMilliseconds(0)) }
        when (op) {
            // A session cookie (no expiry): the state lasts as long as the browser session and no longer, so
            // nobody is left in a reduced or debug view weeks later wondering why. The two cookies are the tri-
            // state: off sets suppress and clears debug; on clears both; debug clears suppress and sets debug.
            EnvAuthOp.suppress -> { set(ENVA.suppressCookie); clear(ENVA.debugCookie) }
            EnvAuthOp.restore -> { clear(ENVA.suppressCookie); clear(ENVA.debugCookie) }
            EnvAuthOp.debug -> { clear(ENVA.suppressCookie); set(ENVA.debugCookie) }
        }
        // The state as it will be on the NEXT request: this request already resolved its own env auth before
        // the cookies changed, so reporting c.isEnvAuthEffective/isEnvDebug here would echo the state being left.
        val suppressed = op == EnvAuthOp.suppress
        val available = c.envAuthEmail != null
        // Same rule as the config above: the control is offered exactly where the cookie is honored.
        val offered = EnvAuthRules.suppressionOffered(c.instanceConfig)
        mapOf(
            APP.isEnvAuthed to (available && !suppressed),
            APP.envAuthSuppressible to (available && offered),
            // Gated by `offered` too, since the debug cookie is only honored where the env controls are (an edge
            // ignores both): otherwise this would report a debug state the next request's resolve would deny.
            APP.envAuthDebug to (available && !suppressed && op == EnvAuthOp.debug && offered),
        )
    }
}
