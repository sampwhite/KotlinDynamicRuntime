package com.dynamicruntime.common.app

import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.DebugTags
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
import com.dynamicruntime.common.user.PublicTestDeployment
import com.dynamicruntime.common.util.toOptEnum
import kotlin.time.Instant
import com.dynamicruntime.common.node.InstanceConfigService

/** Schema type name for the app UI-config output (backend-only; the frontend keys off the [APP] wire constants). */
private const val appUiConfigType = "AppUiConfig"

/** Schema type name for one entry of the app config's `_debug` words (issue #1334). */
private const val appDebugTagType = "AppDebugTag"

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
 * `state` carries the `_debug` words the debug box offers (issue #1334); `fragments` arrive if the app config later
 * grows "shell" copy.
 */
fun appSchema(cxt: KdrCxt): SchModule = schemaModule(cxt, "kdr.app") {
    type(appDebugTagType) {
        type = SCT.kObject
        description = "One _debug word the node declares (issue #1334), as the app bar's debug box offers it."
        property(DBGT.name, "The word, as a request sends it.", required = true)
        property(DBGT.description, "What it does, in a sentence.", required = true)
        property(DBGT.kind, "What kind of word: explain (adds to the response), log (adds log lines), or alter.", required = true)
    }

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
                APP.hasTestAccess,
                "Whether this caller may use the test-only fixtures: a test instance, with env auth effective.",
                required = true,
            ) { type = SCT.boolean }
            property(
                APP.publicTestDeployment,
                "Whether this is a public test deployment: reachable by the public, for testing and development only.",
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
                "How often (ms) the frontend refreshes itself while a tab is visible, so it notices a timed-out session or a " +
                    "newer deploy: the caller's client's interval, else the deployment's.",
                required = true,
            ) { type = SCT.integer }
            property(
                APP.dataId,
                "For a caller with test access, the id of the data this node serves: it changes when the data is reset, and holds across restarts on a persistent database.",
            )
        }
        property(UIC.state, "Per-caller values the frontend reads, beside the policy flags.", required = true) {
            type = SCT.kObject
            property(
                APP.debugTags,
                "The _debug words this node declares, for a caller they answer: test access or a session in ENV DEBUG. Empty for anyone else.",
                required = true,
            ) {
                type = SCT.array
                items { ref(appDebugTagType) }
            }
        }
    }

    generalEndpoint(APP.uiConfig, "Returns deployment-wide app config visible to the entire frontend.",
        // Polled (issue #1372): the webapp's idle poll re-reads it, so a call to it renews no session.
        HttpMethod.GET, outputRef = appUiConfigType, tags = setOf(ETAG.frontend), polled = true) { c, _ ->
        mapOf(
            UIC.features to mapOf(
                APP.obfuscateSensitiveErrors to RequestHandler.obfuscateSensitiveErrors(c.instanceConfig),
                // The test-instance fence (issue #223) -- for a caller with test access, so a caller without env
                // auth on a test instance sees what a real deployment shows (issue #1149) -- also opened by a
                // session in ENV DEBUG (issue #517): an env-authed operator may turn these on for their own session
                // on any deployment. The flag is still audited -- a node claiming to be a test instance outside
                // local/unit refuses to start, and both halves require effective env auth -- so a real deployment
                // cannot turn it on by accident.
                APP.showErrorDetail to (c.hasTestAccess || c.isEnvDebug),
                // Same fence, separate flag (issue #227): a route that manufactures a failure is a different
                // power from showing a stack, even where both happen to be permitted by the same instance.
                APP.allowDebugPages to (c.hasTestAccess || c.isEnvDebug),
                // Whether the fixtures exist for this caller (issues #517, #1149), which allowDebugPages does not
                // imply: a tool that demos a forTestingOnly fixture, and the Simulations page, are offered only
                // where the dispatcher would serve them, so nobody is handed a tool that can only fail.
                APP.hasTestAccess to c.hasTestAccess,
                // For everyone (issue #1150): the band saying the deployment is for testing is drawn for every caller.
                APP.publicTestDeployment to PublicTestDeployment.isOn(c.instanceConfig),
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
                // Always served: the caller's client's, else the deployment's, defaulting when the deployment did not
                // tune it (a custom-config override, not an env var -- this is UI tuning, not an *ops* concern).
                APP.idleBumpIntervalMs to IdleRefresh.millisFor(c, c.client),
            ) + dataIdSetting(c),
            // The `_debug` words (issue #1334), to whom the words answer: the diagnostic fence, as for error detail.
            UIC.state to mapOf(
                APP.debugTags to if (c.hasTestAccess || c.isEnvDebug) DebugTags.get(c.instanceConfig)?.toWire().orEmpty() else emptyList(),
            ),
        )
    }

    // Suppress this session's own env auth, or restore it (issue #360). Live behavior in a deployed
    // environment, not a test affordance: seeing the application as an ordinary user sees it is a real thing
    // to want. Anonymous because env auth is a property of the *channel* -- an env-authed caller need not be
    // logged in at all, so gating this on a role would refuse exactly the people it is for.
    //
    // Safe unfenced because it only ever subtracts. The opposite direction does not exist (issue #1149): env auth is
    // granted by an edge, or assumed for a direct local request -- never asserted by a caller.
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

/**
 * The [APP.dataId] setting (issue #1099), for a caller with test access on a node that reads one: the only caller
 * offered simulations, whose recent runs are kept against it (issue #1149). Nothing elsewhere.
 */
private fun dataIdSetting(c: KdrCxt): Map<String, Any?> {
    if (!c.hasTestAccess) return emptyMap()
    val id = InstanceConfigService.getOrNull(c)?.dataId?.ifEmpty { null } ?: return emptyMap()
    return mapOf(APP.dataId to id)
}
