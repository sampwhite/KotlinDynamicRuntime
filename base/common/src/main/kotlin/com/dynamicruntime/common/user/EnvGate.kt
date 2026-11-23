package com.dynamicruntime.common.user

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.BOOT
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.context.KdrRequest
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.ETAG
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.endpoint.SchModuleBuilder
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.flavor.DeployFlavors
import com.dynamicruntime.common.http.client.OutboundHttpService
import com.dynamicruntime.common.http.request.ContextFocus
import com.dynamicruntime.common.http.request.ContextRoot
import com.dynamicruntime.common.http.request.HttpUtil
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.http.request.SECT
import com.dynamicruntime.common.http.request.sectionOf
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.schema.PRES
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.sql.SecretsUtil
import com.dynamicruntime.common.startup.hasClientCopy
import com.dynamicruntime.common.util.base64Encode
import com.dynamicruntime.common.util.getOptStr
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.normalizeEmail
import com.dynamicruntime.common.util.sameOriginPath
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import java.security.SecureRandom
import kotlin.time.Instant

/** What the env gate asks of a caller without env access (issue #1865): [EGATE.envGate]. */
@Suppress("EnumEntryName")
enum class EnvGateMode {
    /** No gate: the node is reached as its sections and roles say. The default. */
    none,

    /** Every page and endpoint needs env access, which a Google sign-in on the admin domain gives. */
    googleChallenge,
}

/** The env gate's settings and its fixed names (issue #1865). */
@Suppress("ConstPropertyName")
object EGATE {
    /** Instance-config key choosing the gate: an [EnvGateMode] name. Unset defaults from [envGateEnvVar]. */
    const val envGate = "envGate"

    /** Env var that defaults [envGate] when the config option is unset. */
    val envGateEnvVar = EnvVarDef(
        "KDR_ENV_GATE", group = ENVGRP.application, defaultDoc = EnvGateMode.none.name,
        description = "Puts the whole deployment behind a Google sign-in (issue #1865): `${EnvGateMode.googleChallenge}` " +
            "sends a visit to a page (the web app's or the portal's) without env access straight into Google's " +
            "sign-in, with no page of ours " +
            "first, and back to the page asked for; any other request without env access is refused. Only an " +
            "account on the admin domain (`KDR_ADMIN_EMAIL_DOMAIN`), where Google is authoritative for it, gets " +
            "through. Its env access is kept apart from the user signed in, and with nobody signed in the sign-in " +
            "signs the person in as their own user too. Google stands " +
            "in for a VPN and SSO. `${EnvGateMode.none}` (the default) leaves the node as its sections say. Needs " +
            "`KDR_GOOGLE_CLIENT_ID`, the client secret in the secrets file, and `KDR_PUBLIC_URL`. The `envGate` " +
            "config option wins over this.",
    )

    /** Instance-config key: how long a gate sign-in's env access lasts, in minutes. Default [defaultLifetimeMinutes]. */
    const val lifetimeMinutes = "envGateLifetimeMinutes"

    /**
     * How long env access from a gate sign-in lasts, in minutes: 12 hours, as the edge's own sign-in. Separate from
     * the session's lifetime, so access ends within a known time once Google no longer vouches for the person.
     */
    const val defaultLifetimeMinutes = 12 * 60

    /**
     * Instance-config key: whether this node may **simulate** the Google sign-in, for development and tests. Unset,
     * it may on a test instance in `local` that is not a public test deployment and runs no boot role.
     */
    const val simulate = "simulateEnvGate"

    /**
     * Instance-config key: the address a simulated sign-in lands as. Unset, the JVM's `user.name` at the admin
     * domain ([EnvGate.simulatedAddress]), so each developer lands as their own user.
     */
    const val simulatedAddress = "simulatedEnvGateAddress"

    /**
     * The host label that forces the gate, simulated, on a node that may simulate: `admin.kd4.localhost` and
     * `kd4-admin.localhost` are gated, `kd4.localhost` is not. Host names keep separate cookies, so the two views of
     * one local server sit side by side.
     */
    const val adminHostWord = "admin"

    /** The only host suffix a simulated sign-in answers on. */
    const val localhostSuffix = ".localhost"

    /**
     * Instance-config key: the name of the secrets-file property holding Google's client secret, which the code
     * exchange needs. Unset, [defaultClientSecretName]. An indirection, so the secret itself never lives in
     * configuration (as `MAIL.mailgunApiKeySecretKey` is).
     */
    const val clientSecretKey = "googleClientSecretKey"

    /** Default secrets-file property holding Google's client secret. */
    const val defaultClientSecretName = "googleClientSecret"

    /**
     * Instance-config key holding a [GoogleCodeExchange] outright, in place of the call to Google. For tests, which
     * sign their own ID tokens: it lets the flow after the exchange run for real with no network and no secret.
     */
    const val codeExchange = "googleCodeExchange"

    /** The gate's own path that starts a sign-in, under the API root; takes [EP.envAuthNextParam]. */
    const val startPath = "/auth/envGate/start"

    /** The gate's own path Google returns to, under the API root. */
    const val callbackPath = "/auth/envGate/callback"

    /** The section a gated node still answers without env access: health, for a load balancer or a monitor. */
    const val openSection = "health"

    /**
     * Instance-config key: the **allow list** (issue #1866), the endpoints a gated node also answers for a caller
     * without env access -- what a public website beside the node calls. A list of entries, or one text of entries
     * separated by commas, each an endpoint as `<METHOD> <path>` under the API root, such as
     * `POST /auth/newContact/sendVerify`. A client's own copy of an endpoint is named by its path
     * (`POST /gedra/<client>/formDoc`), so it is open for that client alone. The roles and the read scope still
     * apply to what it lets through: it only decides whether a caller without env access may try. A flavor builds it
     * with `add`, so each component adds the endpoints it serves to the site; the deployment's own setting replaces
     * the lot. The boot refuses one with the gate off, and an entry that names no endpoint.
     */
    const val allowList = "envGateAllowList"

    /** The operator view of a gated node's anonymous surface (issue #1866). */
    const val surfacePath = "/${SECT.operator}/envGate/anonymous"

    // The operator view's types and fields.
    const val surfaceType = "EnvGateSurface"
    const val surfaceEntryType = "EnvGateSurfaceEntry"
    const val gate = "gate"
    const val entries = "entries"
    const val method = "method"
    const val path = "path"
    const val source = "source"
    const val component = "component"

    // Where an entry of the anonymous surface comes from.
    const val sourceGate = "gate"
    const val sourceHealth = "health"
    const val sourceAllowList = "allowList"

    /**
     * The cookie holding a gate sign-in's env access: the address Google vouched for and when the access ends,
     * encrypted. Apart from the session cookie (`kdrAuth`), as an edge's env sign-in is, so the person can sign in as,
     * or switch to, any user without losing it.
     */
    const val accessCookie = "kdrEnvGate"

    /**
     * The prefix of the short-lived cookie holding a sign-in's `state`, `nonce`, and where to return, encrypted. The
     * cookie is named by its state (`kdrEnvGateState-<state>`), so sign-ins started at once in one browser -- several
     * tabs whose env access lapsed together -- each keep their own and each return to their own page.
     */
    const val stateCookie = "kdrEnvGateState"

    /** How long a sign-in has to come back from Google, in milliseconds (10 minutes). */
    const val stateMillis = 10L * 60 * 1000

    /** Google's authorization endpoint. */
    const val authorizeUri = "https://accounts.google.com/o/oauth2/v2/auth"

    /** Google's token endpoint, which exchanges a code for an ID token. */
    const val tokenUri = "https://oauth2.googleapis.com/token"

    // OAuth and OpenID Connect parameter names (the wire spelling).
    const val clientIdParam = "client_id"
    const val clientSecretParam = "client_secret"
    const val redirectUriParam = "redirect_uri"
    const val responseTypeParam = "response_type"
    const val scopeParam = "scope"
    const val stateParam = "state"
    const val nonceParam = "nonce"
    const val hdParam = "hd"
    const val promptParam = "prompt"
    const val codeParam = "code"
    const val errorParam = "error"
    const val grantTypeParam = "grant_type"
    const val idTokenField = "id_token"
    const val codeResponse = "code"
    const val authorizationCodeGrant = "authorization_code"
    const val openIdScopes = "openid email profile"
    const val selectAccount = "select_account"
}

/**
 * Exchanges an authorization code for Google's ID token. An interface so a test can hand back a token it signed
 * itself ([EGATE.codeExchange]), exercising everything after the exchange with no network.
 */
interface GoogleCodeExchange {
    /** The raw ID token Google issues for [code], which was granted for [redirectUri]. */
    fun idTokenFor(cxt: KdrCxt, code: String, redirectUri: String): String
}

/** The real exchange: a form-encoded POST to Google's token endpoint through the `fast` outbound client. */
class GoogleTokenEndpoint(private val clientId: String, private val clientSecret: String) : GoogleCodeExchange {
    override fun idTokenFor(cxt: KdrCxt, code: String, redirectUri: String): String {
        val body = HttpUtil.encodeArgs(
            mapOf(
                EGATE.codeParam to code, EGATE.clientIdParam to clientId, EGATE.clientSecretParam to clientSecret,
                EGATE.redirectUriParam to redirectUri, EGATE.grantTypeParam to EGATE.authorizationCodeGrant,
            ),
        )
        val response = OutboundHttpService.get(cxt).fast(cxt).post(EGATE.tokenUri, RequestHandler.formUrlEncoded, body)
        if (response.status != EXC.ok) {
            // The body may say why (an expired or reused code); it is Google's, not the caller's, so it is logged.
            LogAuth.info(cxt) { "Google's token endpoint answered ${response.status}: ${response.body.take(500)}" }
            throw KdrException("Google did not accept the sign-in.", code = EXC.authNeeded)
        }
        return response.body.jsonMap()?.getOptStr(EGATE.idTokenField)
            ?: throw KdrException("Google's token endpoint answered without an ID token.", code = EXC.authNeeded)
    }
}

/**
 * The **env gate** (issue #1865): a deployment-level setting that puts the whole node behind a Google sign-in, with
 * Google standing in for a VPN and SSO.
 *
 * Visiting the web app without env access goes **straight into Google's sign-in**, with no page of ours first, and
 * comes back to the page asked for. Any other request without env access is refused with the 401 the web app
 * already follows for an edge (`EP.envAuthRequiredCode`), pointing at [EGATE.startPath], which carries the page the
 * browser is on.
 *
 * **Env access and the user signed in stay apart**, as they are behind an edge. The sign-in writes its env access to a
 * cookie of its own ([EGATE.accessCookie]): the address Google vouched for, until [lifetimeMs] has passed. On a gated
 * request that cookie is the only source of env access ([resolve]), whoever the session acts as, so the person may sign
 * in as another user, or switch to one, and keep it. As a convenience, a sign-in with nobody signed in also signs the
 * person in as their own user. Only the Google sign-in writes the cookie, so a code sign-in to the same address gives
 * no env access.
 *
 * **Simulated** for development and tests: on a node that may ([maySimulate], [EGATE.simulate]), a host label
 * containing [EGATE.adminHostWord] under `.localhost` forces the gate and skips only the trip to Google. Everything
 * after it runs for real -- the domain checks, the env access cookie, finding or making the user, the return.
 *
 * The gate's place in a request is `RequestService.dispatch`, after the session is restored: [resolve], then [check].
 *
 * Theme: #ENVAT.
 */
@Suppress("ConstPropertyName")
object EnvGate {
    private val random = SecureRandom()

    /** What a sign-in that cannot go on says: the way forward is a fresh start. */
    private const val startOver = "That sign-in could not be completed. Open the app again to start over."

    /** What a sign-in says when Google's answer does not hold up. */
    private const val uncheckable = "Google's answer could not be checked. Open the app again to start over."

    /** The configured [EnvGateMode]: the config option, else [EGATE.envGateEnvVar], else `none`. */
    fun mode(config: KdrInstanceConfig): EnvGateMode {
        val named = ((config.get(EGATE.envGate) as? String) ?: config.getEnvVar(EGATE.envGateEnvVar))?.trim()
        if (named.isNullOrEmpty()) return EnvGateMode.none
        return EnvGateMode.entries.firstOrNull { it.name == named }
            ?: throw KdrException(
                "Refusing to start: ${EGATE.envGateEnvVar} is '$named'; it takes one of ${EnvGateMode.entries.joinToString()}.",
            )
    }

    /** Whether the gate is configured on: [mode] is not `none`. */
    fun isOn(config: KdrInstanceConfig): Boolean = mode(config) != EnvGateMode.none

    /**
     * Whether this node may simulate the Google sign-in. Explicitly, by [EGATE.simulate]; unset, on a test instance in
     * `local` that is not a public test deployment and runs no boot role. [checkBoot] refuses an explicit yes
     * anywhere but a test instance in `local` or `unit` that is not a public test deployment.
     */
    fun maySimulate(config: KdrInstanceConfig): Boolean =
        (config.get(EGATE.simulate) as? Boolean)
            ?: (config.isTestInstance && config.env == ENV.local && !PublicTestDeployment.isOn(config) && config.bootRole == null)

    /**
     * Whether [handler]'s request is to be simulated: the node may simulate, the request came straight to it (no
     * `X-Forwarded-For`), and its host is under `.localhost` with a label containing [EGATE.adminHostWord]. The fence
     * is the node's ([maySimulate]); the host is only the trigger, since `Host` is whatever the caller sends.
     */
    fun isSimulated(config: KdrInstanceConfig, handler: RequestHandler): Boolean {
        if (!maySimulate(config) || handler.forwardedFor != null) return false
        val host = handler.getRequestHeader("Host")?.trim()?.lowercase()?.substringBefore(':') ?: return false
        if (!host.endsWith(EGATE.localhostSuffix)) return false
        return host.removeSuffix(EGATE.localhostSuffix).split('.').any { EGATE.adminHostWord in it }
    }

    /** Whether the gate applies to [handler]'s request: configured on, or forced by a simulated host. */
    fun applies(config: KdrInstanceConfig, handler: RequestHandler): Boolean = isOn(config) || isSimulated(config, handler)

    /** How long a gate sign-in's env access lasts, in milliseconds ([EGATE.lifetimeMinutes]). */
    fun lifetimeMs(config: KdrInstanceConfig): Long =
        ((config.get(EGATE.lifetimeMinutes) as? Number)?.toLong() ?: EGATE.defaultLifetimeMinutes.toLong()) * 60 * 1000

    /**
     * The address a simulated sign-in lands as: [EGATE.simulatedAddress], else the JVM's `user.name`, lower-cased with
     * any character an address cannot hold dropped, at the admin domain. Null when neither gives one.
     */
    fun simulatedAddress(config: KdrInstanceConfig): String? {
        (config.get(EGATE.simulatedAddress) as? String)?.trim()?.lowercase()?.ifEmpty { null }?.let { return it }
        val domain = AdminRules.adminEmailDomain(config) ?: return null
        val local = System.getProperty("user.name").orEmpty().lowercase().filter { it.isLetterOrDigit() || it in "._-" }
        return if (local.isEmpty()) null else "$local@$domain"
    }

    /**
     * Refuses a start the gate cannot honor (issue #1865), beside the public test deployment's own checks.
     *
     * A gate on needs the admin domain (the only addresses it admits), Google's client id, and a way to exchange a
     * code: the client secret in the secrets file, or a test's [GoogleCodeExchange]. It refuses header trust, since
     * on a gated node env access is the Google sign-in's alone, and an administrator's code sign-in granting it
     * (`KDR_ENV_AUTH_FROM_ADMIN_LOGIN`). And it refuses simulation outside its fence: a test instance in `local` or
     * `unit` that is not a public test deployment.
     *
     * The allow list ([EGATE.allowList], issue #1866) is refused with the gate off, where it would mean nothing, and
     * when an entry does not parse or names no endpoint among [endpoints] -- the shared surface, before any client's
     * copies are made, so a client's own path is held to the shared endpoint it copies. A typo would otherwise quietly
     * keep a path closed, or open the wrong one.
     */
    fun checkBoot(config: KdrInstanceConfig, endpoints: Collection<KdrEndpoint>) {
        fun refuse(why: String): Nothing = throw KdrException("Refusing to start: $why")
        val mode = mode(config)
        val allowed = try {
            allowList(config)
        } catch (e: KdrException) {
            refuse(e.message.orEmpty())
        }
        if (allowed.isNotEmpty() && mode == EnvGateMode.none) {
            refuse("'${EGATE.allowList}' is set, but the env gate is off (${EGATE.envGateEnvVar}): it would open nothing.")
        }
        val unknown = allowed.filter { !namesEndpoint(it, endpoints) }
        if (unknown.isNotEmpty()) {
            refuse("'${EGATE.allowList}' names no endpoint at ${unknown.joinToString { "'$it'" }}.")
        }
        val explicitSimulate = config.get(EGATE.simulate) as? Boolean
        if (explicitSimulate == true) {
            val fenced = config.isTestInstance && (config.env == ENV.local || config.env == ENV.unit) &&
                !PublicTestDeployment.isOn(config) && config.bootRole != BOOT.edge
            if (!fenced) {
                refuse(
                    "'${EGATE.simulate}' is on, but a simulated Google sign-in is only for a test instance in 'local' or " +
                        "'unit' that is not a public test deployment.",
                )
            }
        }
        if (mode == EnvGateMode.none) return
        if (AdminRules.adminEmailDomain(config) == null) {
            refuse("${EGATE.envGateEnvVar} is '$mode', but no admin domain is set (${ADMR.adminEmailDomainEnvVar}): it admits nobody else.")
        }
        if (GoogleAuthConfig.clientId(config) == null) {
            refuse("${EGATE.envGateEnvVar} is '$mode', but Google sign-in is not configured (${GOOG.googleClientIdEnvVar}).")
        }
        if (config.get(EGATE.codeExchange) !is GoogleCodeExchange && clientSecret(config) == null) {
            refuse(
                "${EGATE.envGateEnvVar} is '$mode', but Google's client secret is not in ${SecretsUtil.secretsPath} " +
                    "(as '${clientSecretName(config)}').",
            )
        }
        // Google returns to an address it must have registered; built from a request's headers it is whatever the caller or
        // a proxy says. A test instance, reached directly, may build it so.
        if (config.getEnvVar(INVITE.publicUrl)?.trim()?.ifEmpty { null } == null && !config.isTestInstance) {
            refuse("${EGATE.envGateEnvVar} is '$mode', but ${INVITE.publicUrl} is not set: Google must be told where to return.")
        }
        // Only trust stated outright: behind the gate it defaults off, a test instance's included (EnvAuthRules.isTrusted).
        if (EnvAuthRules.explicitTrust(config) == true) {
            refuse(
                "${EGATE.envGateEnvVar} is '$mode', so env access is the Google sign-in's alone, but " +
                    "${ENVA.trustEnvAuthHeaderEnvVar} (or '${ACFG.trustEnvAuthHeader}') is set on. Unset it, or set it off.",
            )
        }
        if (config.getEnvBool(PTD.envAuthFromAdminLoginEnvVar) == true) {
            refuse(
                "${EGATE.envGateEnvVar} is '$mode', so ${PTD.envAuthFromAdminLoginEnvVar} must be off: a code sign-in must " +
                    "not grant env access.",
            )
        }
        if (config.bootRole == BOOT.edge) {
            refuse("${EGATE.envGateEnvVar} is for an application node; an edge has a sign-in of its own.")
        }
    }

    /**
     * On a request the gate applies to, sets its env access from [EGATE.accessCookie] alone: the address a gate sign-in
     * wrote there, while it lasts and is still on the admin domain. Any other source is dropped -- env access the node
     * **assumed** for a local request (`KDR_ASSUME_ENV_AUTH`), or a trusted header, which a simulated request on a test
     * instance may still have -- and the session's user has no bearing on it. A request the gate does not apply to is
     * left as `EnvAuthRules.resolve` made it. [applies] is [EnvGate.applies] for the request, worked out once by the
     * dispatcher for this and [check].
     */
    fun resolve(cxt: KdrCxt, handler: RequestHandler, applies: Boolean) {
        if (!applies) return
        val access = handler.getRequestCookies()[EGATE.accessCookie]?.let { EnvGateAccess.decode(NodeService.get(cxt), it) }
        cxt.envAuthEmail = access
            ?.takeIf { cxt.now().toEpochMilliseconds() <= it.expireEpochMs && AddressRules.isAdminDomain(cxt, it.email) }
            ?.let { EnvAuthRules.sanitizeAddress(it.email) }
    }

    /**
     * The gate itself: on a request it applies to, answers it -- and returns true -- unless the caller has env access
     * or the request is one the gate always lets through. Its own two paths are answered here whatever the caller
     * has: [EGATE.startPath] and [EGATE.callbackPath].
     *
     * A page visit (a GET or HEAD under the web app's or the portal's root) goes to Google; any other request is refused
     * with the 401 the web app follows. Static resources are among the second: a page asks for them once it is let in.
     *
     * Env access *available* is enough, not *effective*: a person who has turned their env access off to preview the
     * ordinary view is still the person Google vouched for, and challenging them would loop.
     *
     * A call made in this process for a user (the configuration assistant) passes: the person it acts for already
     * passed the gate to ask for it.
     */
    fun check(cxt: KdrCxt, handler: RequestHandler, focus: ContextFocus, applies: Boolean): Boolean {
        val config = cxt.instanceConfig
        if (!applies || handler.inProcessPrincipal != null) return false
        if (focus == ContextFocus.api) {
            when (handler.appPath) {
                EGATE.startPath -> {
                    val another = handler.queryParams[EGATE.promptParam] == EGATE.selectAccount
                    start(cxt, handler, handler.queryParams[EP.envAuthNextParam] as? String, selectAccount = another)
                    return true
                }
                EGATE.callbackPath -> {
                    callback(cxt, handler)
                    return true
                }
            }
        }
        if (cxt.envAuthEmail != null) return false
        if (focus == ContextFocus.api && handler.section == EGATE.openSection) return false
        if (focus == ContextFocus.api && "${handler.method} ${handler.appPath}" in allowList(config)) return false
        // A page -- the web app's or the portal's, the documents among them -- is a visit a browser makes, so it goes to
        // Google; anything else is answered with the 401 a page's own code follows.
        if ((focus == ContextFocus.app || focus == ContextFocus.content) && (handler.method == "GET" || handler.method == "HEAD")) {
            start(cxt, handler, requestedPath(handler))
            return true
        }
        throw KdrException(
            "This deployment needs a Google sign-in first.",
            code = EXC.authNeeded,
            extraData = mutableMapOf(
                KdrException.errorCodeKey to EP.envAuthRequiredCode,
                EP.envAuthLoginUrl to "/${ContextRoot.apiOf(config)}${EGATE.startPath}",
            ),
        )
    }

    /** The path and query [handler]'s request asked for: where a sign-in started by it returns. */
    private fun requestedPath(handler: RequestHandler): String {
        val query = handler.queryStr?.ifEmpty { null }
        return if (query == null) handler.uri else "${handler.uri}?$query"
    }

    /** The web app's root, `/wa`: where a sign-in returns when nothing valid was asked for. */
    private fun appRoot(config: KdrInstanceConfig): String = "/" + ((config.get(ACFG.appContextRoot) as? String) ?: ContextRoot.wa)

    /** The portal's root, `/cp`. */
    private fun contentRoot(config: KdrInstanceConfig): String =
        "/" + ((config.get(ACFG.contentContextRoot) as? String) ?: ContextRoot.cp)

    /**
     * Where a sign-in returns: [raw] when it is a same-origin path under the web app's root or the portal's -- the
     * pages a visit is sent to Google from -- else the web app's root. Replaced rather than refused, as the edge's
     * `EnvAuthReturn` does, since somebody signing in should land somewhere; never another site, so the gate cannot be
     * used to send anyone elsewhere.
     */
    fun returnPath(config: KdrInstanceConfig, raw: String?): String {
        val fallback = appRoot(config)
        val path = sameOriginPath(raw) ?: return fallback
        val under = listOf(fallback, contentRoot(config)).any { root ->
            path == root || path.startsWith("$root/") || path.startsWith("$root?") || path.startsWith("$root#")
        }
        return if (under) path else fallback
    }

    /**
     * Starts a sign-in that returns to [next]. Already env-authed: straight there. Simulated: signed in now, with no
     * trip to Google. Otherwise: to Google's authorization endpoint, with a fresh `state` and `nonce` kept in
     * [EGATE.stateCookie] -- and, when [selectAccount], asking Google to show its chooser rather than reuse the
     * account it last signed in.
     */
    private fun start(cxt: KdrCxt, handler: RequestHandler, next: String?, selectAccount: Boolean = false) {
        val config = cxt.instanceConfig
        val returnTo = returnPath(config, next)
        if (cxt.envAuthEmail != null && !selectAccount) {
            handler.sendRedirect(returnTo)
            return
        }
        if (isSimulated(config, handler)) {
            val address = simulatedAddress(config)
            if (address == null) {
                refusePage(
                    handler, EXC.authNeeded,
                    "A simulated sign-in needs an address: set ${ADMR.adminEmailDomainEnvVar}, or '${EGATE.simulatedAddress}'.",
                    offerAnother = false,
                )
                return
            }
            val token = GoogleIdToken(
                subject = "simulated:$address", email = address, emailVerified = true, displayName = null,
                hostedDomain = AdminRules.adminEmailDomain(config), nonce = null,
            )
            signIn(cxt, handler, token, returnTo, simulated = true)
            return
        }
        val clientId = GoogleAuthConfig.clientId(config)
        val domain = AdminRules.adminEmailDomain(config)
        if (clientId == null || domain == null) {
            refusePage(handler, EXC.notFound, "Google sign-in is not configured on this node.", offerAnother = false)
            return
        }
        val state = GateState(randomToken(), randomToken(), returnTo, cxt.now().toEpochMilliseconds() + EGATE.stateMillis)
        handler.addResponseCookie(
            stateCookieName(state.state), state.encode(NodeService.get(cxt)), Instant.fromEpochMilliseconds(state.expireEpochMs),
        )
        handler.sendRedirect(authorizeUrl(clientId, redirectUri(cxt, handler), state, domain, selectAccount))
    }

    /** Google's authorization URL for [state]: a code, for `openid email profile`, hinting the admin [domain]. */
    private fun authorizeUrl(clientId: String, redirectUri: String, state: GateState, domain: String, selectAccount: Boolean): String =
        EGATE.authorizeUri + "?" + HttpUtil.encodeArgs(
            mapOf(
                EGATE.clientIdParam to clientId, EGATE.redirectUriParam to redirectUri,
                EGATE.responseTypeParam to EGATE.codeResponse, EGATE.scopeParam to EGATE.openIdScopes,
                EGATE.stateParam to state.state, EGATE.nonceParam to state.nonce, EGATE.hdParam to domain,
                EGATE.promptParam to if (selectAccount) EGATE.selectAccount else null,
            ),
        )

    /**
     * Where Google returns to: [EGATE.callbackPath] under the API root, at the deployment's public URL
     * (`KDR_PUBLIC_URL`), else -- on a node reached directly -- the request's own scheme and host. It must be one of
     * the redirect URIs registered for the client id.
     */
    private fun redirectUri(cxt: KdrCxt, handler: RequestHandler): String {
        val base = INVITE.configuredPublicUrl(cxt) ?: run {
            val host = handler.getRequestHeader("Host")?.trim()?.ifEmpty { null } ?: "localhost"
            val scheme = handler.getRequestHeader("X-Forwarded-Proto")?.trim()?.ifEmpty { null } ?: "http"
            "$scheme://$host"
        }
        return "$base/${ContextRoot.apiOf(cxt.instanceConfig)}${EGATE.callbackPath}"
    }

    /**
     * Google's return: checks `state` against [EGATE.stateCookie], exchanges the code, verifies the ID token and its
     * `nonce`, and grants env access ([signIn]). Any failure is a plain refusal from the node; an account Google vouched for
     * that is not the deployment's own gets a link back to Google's chooser, to pick another.
     */
    private fun callback(cxt: KdrCxt, handler: RequestHandler) {
        val config = cxt.instanceConfig
        val node = NodeService.get(cxt)
        val params = handler.queryParams
        // Only a state this node made has a cookie name to look up: one Google echoes back is a random token, and
        // anything else names no cookie.
        val sentState = (params[EGATE.stateParam] as? String)?.takeIf { it.isNotEmpty() && it.all(::isTokenChar) }
        val state = sentState?.let { sent ->
            val name = stateCookieName(sent)
            handler.getRequestCookies()[name]?.let { GateState.decode(node, it) }.also {
                // Spent whether it succeeds or not: a state is good for one return.
                handler.addResponseCookie(name, "", Instant.fromEpochMilliseconds(0))
            }
        }
        if (state == null || sentState != state.state || cxt.now().toEpochMilliseconds() > state.expireEpochMs) {
            LogAuth.info(cxt) { "Env gate sign-in refused: the state is missing, expired, or does not match." }
            refusePage(handler, EXC.badInput, startOver, offerAnother = false)
            return
        }
        (params[EGATE.errorParam] as? String)?.let { error ->
            LogAuth.info(cxt) { "Env gate sign-in: Google answered '$error'." }
            refusePage(handler, EXC.authNeeded, "Google did not sign you in.", offerAnother = true, returnTo = state.returnTo)
            return
        }
        val code = params[EGATE.codeParam] as? String
        val verifier = GoogleAuthConfig.mkVerifier(config)
        val exchange = codeExchangeFor(config)
        if (code == null || verifier == null || exchange == null) {
            refusePage(handler, EXC.badInput, startOver, offerAnother = false)
            return
        }
        // The exchange is a call out to Google and the check RSA work, both for a caller nobody has vouched for yet: held
        // to the per-IP ceiling the Google sign-in has, under the same key, so the two together cannot be used to send
        // Google a flood of requests from this node.
        if (!UserService.get(cxt).authFormHandler.allowGoogleAttempt(cxt, handler.forwardedFor ?: handler.peerAddress)) {
            LogAuth.info(cxt) { "Env gate sign-in refused: too many attempts from this address." }
            refusePage(
                handler, EXC.tooManyRequests, "Too many sign-in attempts. Wait a few minutes, then open the app again.",
                offerAnother = false,
            )
            return
        }
        val token = try {
            verifier.verify(cxt, exchange.idTokenFor(cxt, code, redirectUri(cxt, handler)))
        } catch (e: KdrException) {
            LogAuth.info(cxt) { "Env gate sign-in refused: ${e.message}" }
            refusePage(handler, EXC.authNeeded, uncheckable, offerAnother = false)
            return
        }
        if (token.nonce != state.nonce) {
            LogAuth.info(cxt) { "Env gate sign-in refused: the ID token's nonce does not match the one sent." }
            refusePage(handler, EXC.authNeeded, uncheckable, offerAnother = false)
            return
        }
        signIn(cxt, handler, token, state.returnTo)
    }

    /**
     * Grants env access to the person [token] names, when it is the deployment's own: Google verified the address, it is
     * on the admin domain, and Google is authoritative for that domain (the signed `hd` claim). Checked **before** any
     * user is found or made, so an outside account gets nothing on this node. Then the env access is written to
     * [EGATE.accessCookie]; when nobody is signed in, the person is signed in as their own user too; and the browser is
     * sent to [returnTo]. Somebody already signed in stays as they are.
     *
     * A [simulated] sign-in signs in by the address alone, linking no Google account: its subject is made up, and a link
     * would outlast it in a developer's database as a Google account the person never connected.
     */
    private fun signIn(cxt: KdrCxt, handler: RequestHandler, token: GoogleIdToken, returnTo: String, simulated: Boolean = false) {
        val email = token.email?.normalizeEmail()
        val admitted = token.emailVerified && email != null && AddressRules.isAdminDomain(cxt, email) &&
            AddressRules.isGoogleAuthoritative(cxt, token.hostedDomain)
        if (!admitted) {
            LogAuth.info(cxt) {
                "Env gate sign-in refused for '${token.email}' (verified ${token.emailVerified}, hd '${token.hostedDomain}'): " +
                    "not an account of the admin domain."
            }
            refusePage(
                handler, EXC.notAuthorized, "That Google account cannot sign in here.", offerAnother = true, returnTo = returnTo,
            )
            return
        }
        // The cookies go on before the redirect, which sends the response at once.
        val access = EnvGateAccess(email, cxt.now().toEpochMilliseconds() + lifetimeMs(cxt.instanceConfig))
        handler.addResponseCookie(
            EGATE.accessCookie, access.encode(NodeService.get(cxt)), Instant.fromEpochMilliseconds(access.expireEpochMs),
        )
        val signsIn = !cxt.userProfile.isLoggedIn
        if (signsIn) {
            // The sign-in completes as any other does (AuthFormHandler.completeLogin), which reads the request it is in.
            cxt.request = KdrRequest(cxt, emptyMap(), null, handler)
            UserService.get(cxt).authFormHandler.loginByGoogleToken(cxt, token, link = !simulated)
            RequestService.get(cxt).checkAddAuthCookies(cxt, handler)
        }
        LogAuth.info(cxt) {
            "Env gate sign-in for '$email' (subject '${token.subject}')" +
                if (signsIn) ", signed in as their user." else ", keeping the session's user ${cxt.userProfile.userId}."
        }
        handler.sendRedirect(returnTo)
    }

    /**
     * A plain page from the node refusing a sign-in, with [message], and -- when [offerAnother] -- a link back to
     * Google's chooser to pick another account, returning to [returnTo]. The one response of the gate's that anyone
     * sees.
     */
    private fun refusePage(
        handler: RequestHandler, status: Int, message: String, offerAnother: Boolean, returnTo: String? = null,
    ) {
        val another = if (offerAnother) {
            val next = HttpUtil.encodeArgs(mapOf(EP.envAuthNextParam to (returnTo ?: "")))
            val start = "/${ContextRoot.apiOf(handler.instanceConfig)}${EGATE.startPath}"
            val href = "$start?$next&amp;${EGATE.promptParam}=${EGATE.selectAccount}"
            """<p><a href="$href">Use another Google account</a></p>"""
        } else {
            ""
        }
        val html = """<!doctype html><html lang="en"><head><meta charset="utf-8">""" +
            """<meta name="viewport" content="width=device-width, initial-scale=1"><title>Sign-in</title></head>""" +
            """<body style="font-family: system-ui, sans-serif; max-width: 32rem; margin: 4rem auto; padding: 0 1rem">""" +
            """<p>${escapeHtml(message)}</p>$another</body></html>"""
        handler.sendStringResponse(html, status, "text/html; charset=utf-8")
    }

    /** [text] with the characters HTML gives meaning to escaped. */
    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** The exchange this node makes: a test's ([EGATE.codeExchange]), else Google's token endpoint, or null when unconfigured. */
    private fun codeExchangeFor(config: KdrInstanceConfig): GoogleCodeExchange? {
        (config.get(EGATE.codeExchange) as? GoogleCodeExchange)?.let { return it }
        val clientId = GoogleAuthConfig.clientId(config) ?: return null
        val secret = clientSecret(config) ?: return null
        return GoogleTokenEndpoint(clientId, secret)
    }

    /** The secrets-file property holding Google's client secret ([EGATE.clientSecretKey]). */
    private fun clientSecretName(config: KdrInstanceConfig): String =
        (config.get(EGATE.clientSecretKey) as? String)?.trim()?.ifEmpty { null } ?: EGATE.defaultClientSecretName

    /** Google's client secret from the secrets file, or null when it is not there. */
    private fun clientSecret(config: KdrInstanceConfig): String? =
        SecretsUtil.getSecret(clientSecretName(config))?.trim()?.ifEmpty { null }

    /** 128 random bits, URL-safe. */
    private fun randomToken(): String = ByteArray(16).also { random.nextBytes(it) }.base64Encode()

    /** Whether [c] can be in a [randomToken]: URL-safe base64, which a cookie name may also hold. */
    private fun isTokenChar(c: Char): Boolean = c.isLetterOrDigit() || c == '-' || c == '_' || c == '='

    /** The state cookie for the sign-in whose state is [state]. */
    private fun stateCookieName(state: String): String = "${EGATE.stateCookie}-$state"

    /**
     * The allow list ([EGATE.allowList]): its entries as `<METHOD> <path>`, the method upper-cased and one space
     * between. Throws when an entry is not of that shape.
     */
    fun allowList(config: KdrInstanceConfig): Set<String> {
        val raw = when (val value = config.get(EGATE.allowList)) {
            null -> return emptySet()
            is Collection<*> -> value.map { it?.toString().orEmpty() }
            else -> value.toString().split(',')
        }
        return raw.map { it.trim() }.filter { it.isNotEmpty() }.map(::normalizeEntry).toSet()
    }

    /** An allow-list [entry] as `<METHOD> <path>`, the method upper-cased and one space between; throws when it is not. */
    private fun normalizeEntry(entry: String): String {
        val parts = entry.trim().split(Regex("\\s+"))
        val method = parts.first().uppercase()
        if (parts.size != 2 || HttpMethod.entries.none { it.name == method } || !parts[1].startsWith("/")) {
            throw KdrException(
                "'${EGATE.allowList}' entry '$entry' is not '<METHOD> <path>', such as 'POST /auth/newContact/sendVerify'.",
            )
        }
        return "$method ${parts[1]}"
    }

    /**
     * Whether the allow-list [entry] names one of [endpoints], or a client's own copy of one: a path whose second
     * segment is a client, over a shared endpoint that has a copy per client.
     */
    private fun namesEndpoint(entry: String, endpoints: Collection<KdrEndpoint>): Boolean {
        val method = entry.substringBefore(' ')
        val path = entry.substringAfter(' ')
        fun named(p: String, copied: Boolean) =
            endpoints.any { it.method.name == method && it.path == p && it.client == null && (!copied || hasClientCopy(it)) }
        if (named(path, copied = false)) return true
        val segments = path.removePrefix("/").split('/')
        if (segments.size < 2) return false
        val shared = "/" + (listOf(segments[0]) + segments.drop(2)).joinToString("/")
        return named(shared, copied = true)
    }

    /**
     * One path a gated node answers without env access (issue #1866): an endpoint's [method] and full [path], under
     * the API root, and where it comes from ([EGATE.sourceGate], [EGATE.sourceHealth], [EGATE.sourceAllowList]) --
     * for an allow-list entry a flavor added, also the [component] that added it. Reads as `GET /kda/health`.
     */
    data class AnonymousPath(val method: String, val path: String, val source: String, val component: String? = null) {
        override fun toString(): String = "$method $path"
    }

    /**
     * Every path this node answers without env access, when its env gate is on (issue #1866): the gate's own two
     * paths, the `health` section's endpoints, and the allow list. Empty with the gate off, where the sections decide
     * instead. Made from what the node actually loaded, so an endpoint a component outside this repository adds shows
     * here, where the generated endpoint reference never sees it. Sorted by path, then method.
     *
     * Theme: #ENVAT.
     */
    fun anonymousSurface(cxt: KdrCxt): List<AnonymousPath> {
        val config = cxt.instanceConfig
        if (!isOn(config)) return emptyList()
        val api = "/" + ContextRoot.apiOf(config)
        val gatePaths = listOf(EGATE.startPath, EGATE.callbackPath).map { AnonymousPath(HttpMethod.GET.name, api + it, EGATE.sourceGate) }
        val health = cxt.getGlobalSchema().endpoints.values
            .filter { it.client == null && sectionOf(it.path) == EGATE.openSection }
            .map { AnonymousPath(it.method.name, api + it.path, EGATE.sourceHealth) }
        // Who added each entry, where a flavor did (`add`): the components that open what, for the operator to see.
        val addedBy = DeployFlavors.get(cxt).values
            .filter { it.name == EGATE.allowList && it.added && it.applied }
            .associate { normalizeEntry(it.value.toString()) to it.component }
        val allowed = allowList(config).map {
            AnonymousPath(it.substringBefore(' '), api + it.substringAfter(' '), EGATE.sourceAllowList, addedBy[it])
        }
        return (gatePaths + health + allowed).distinctBy { it.toString() }.sortedWith(compareBy({ it.path }, { it.method }))
    }

    /**
     * For a component's own tests (issue #1866): throws, saying what differs, unless [cxt]'s anonymous surface is
     * exactly [expected], each written as `<METHOD> <path>` with the API root (`GET /kda/health`). Plain Kotlin, so a
     * test in any framework can call it; a new public path then fails the test of whichever component added it.
     */
    fun requireAnonymousSurface(cxt: KdrCxt, expected: Collection<String>) {
        val actual = anonymousSurface(cxt).map { it.toString() }.toSet()
        val wanted = expected.toSet()
        if (actual == wanted) return
        val added = (actual - wanted).sorted()
        val missing = (wanted - actual).sorted()
        throw IllegalStateException(
            "The anonymous surface differs from what was expected." +
                (if (added.isEmpty()) "" else " Answers without env access, unexpectedly: ${added.joinToString()}.") +
                (if (missing.isEmpty()) "" else " Expected but not answered: ${missing.joinToString()}."),
        )
    }

    /** The operator view of [anonymousSurface] (issue #1866), in the operator section's module. */
    fun defineOperatorEndpoint(builder: SchModuleBuilder) = with(builder) {
        type(EGATE.surfaceEntryType) {
            type = SCT.kObject
            presentation = PRES.table
            property(EGATE.method, "The HTTP method.", required = true)
            property(EGATE.path, "The path, under the API root.", required = true) { presentation = PRES.identifier }
            property(EGATE.source, "Why it answers: the gate's own path, the health section, or the allow list.", required = true)
            property(EGATE.component, "The component whose flavor contribution added the entry to the allow list, if one did.")
        }
        type(EGATE.surfaceType) {
            type = SCT.kObject
            property(EGATE.gate, "The env gate's mode on this node.", required = true)
            property(EGATE.entries, "Every path this node answers without env access; empty with the gate off.", required = true) {
                type = SCT.array
                items { ref(EGATE.surfaceEntryType) }
            }
        }
        generalEndpoint(
            EGATE.surfacePath,
            "What this node answers without env access behind its env gate: the gate's own paths, health, and the allow list.",
            HttpMethod.GET,
            outputRef = EGATE.surfaceType,
            tags = setOf(ETAG.internal),
        ) { c, _ ->
            mapOf(
                EGATE.gate to mode(c.instanceConfig).name,
                EGATE.entries to anonymousSurface(c).map {
                    mapOf(EGATE.method to it.method, EGATE.path to it.path, EGATE.source to it.source, EGATE.component to it.component)
                },
            )
        }
    }

    /**
     * A gate sign-in's env access, in [EGATE.accessCookie]: the address Google vouched for, and when the access ends.
     * Encrypted with the deployment's key, so the browser can neither read nor forge it.
     */
    class EnvGateAccess(val email: String, val expireEpochMs: Long) {
        fun encode(node: NodeService): String =
            node.encryptString(mapOf(K_EMAIL to email, K_EXPIRE to expireEpochMs).toJsonStr(compact = true))

        companion object {
            private const val K_EMAIL = "m"
            private const val K_EXPIRE = "e"

            /** Decrypts and parses a wire cookie, or null if it is absent, malformed, or undecryptable. */
            fun decode(node: NodeService, cookie: String): EnvGateAccess? = try {
                val m = node.decryptString(cookie).jsonMap() ?: return null
                EnvGateAccess(m[K_EMAIL].toOptStr() ?: return null, m[K_EXPIRE].toOptLong() ?: return null)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * What a sign-in carries through Google, in [EGATE.stateCookie]: the `state` Google returns, the `nonce` its ID
     * token must hold, where to return, and when it lapses. Encrypted with the deployment's key, so the browser can
     * neither read nor change where it returns.
     */
    private class GateState(val state: String, val nonce: String, val returnTo: String, val expireEpochMs: Long) {
        fun encode(node: NodeService): String =
            node.encryptString(
                mapOf(K_STATE to state, K_NONCE to nonce, K_RETURN to returnTo, K_EXPIRE to expireEpochMs).toJsonStr(compact = true),
            )

        companion object {
            private const val K_STATE = "s"
            private const val K_NONCE = "n"
            private const val K_RETURN = "r"
            private const val K_EXPIRE = "e"

            fun decode(node: NodeService, cookie: String): GateState? = try {
                val m = node.decryptString(cookie).jsonMap() ?: return null
                GateState(
                    m[K_STATE].toOptStr() ?: return null, m[K_NONCE].toOptStr() ?: return null,
                    m[K_RETURN].toOptStr() ?: return null, m[K_EXPIRE].toOptLong() ?: return null,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
