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
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.client.OutboundHttpService
import com.dynamicruntime.common.http.request.ContextFocus
import com.dynamicruntime.common.http.request.ContextRoot
import com.dynamicruntime.common.http.request.HttpUtil
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.sql.SecretsUtil
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
            "sends a visit to the web app without env access straight into Google's sign-in, with no page of ours " +
            "first, and back to the page asked for; any other request without env access is refused. Only an " +
            "account on the admin domain (`KDR_ADMIN_EMAIL_DOMAIN`), where Google is authoritative for it, gets " +
            "through, and that sign-in both grants env access and signs the person in as their user. Google stands " +
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

    /** The short-lived cookie holding a sign-in's `state`, `nonce`, and where to return, encrypted. */
    const val stateCookie = "kdrEnvGate"

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
 * browser is on. That sign-in both grants env access and signs the person in as their user: the session cookie it
 * writes is stamped with how long its env access lasts (`UserAuthCookie.envGateUntilEpochMs`), and only a gate
 * sign-in stamps one, so a code sign-in to the same address is no way in.
 *
 * **Simulated** for development and tests: on a node that may ([mayGrant]'s fence, [EGATE.simulate]), a host label
 * containing [EGATE.adminHostWord] under `.localhost` forces the gate and skips only the trip to Google. Everything
 * after it runs for real -- the domain checks, finding or making the user, the stamp, the return.
 *
 * The gate's place in a request is `RequestService.dispatch`, after the session is restored: [dropOtherEnvAuth], then
 * `EnvAuthRules.fromAdminLogin` (which grants env access from a stamped session), then [check].
 *
 * Theme: #ENVAT.
 */
object EnvGate {
    private val random = SecureRandom()

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
     * Whether a session's gate stamp may grant env access on this node: the gate is on, or the node may simulate it.
     * The stamp is in the session cookie, which only this deployment's key can have written.
     */
    fun mayGrant(config: KdrInstanceConfig): Boolean = isOn(config) || maySimulate(config)

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
     */
    fun checkBoot(config: KdrInstanceConfig) {
        fun refuse(why: String): Nothing = throw KdrException("Refusing to start: $why")
        val mode = mode(config)
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
        // Only trust stated outright: behind the gate it defaults off, a test instance's included (EnvAuthRules.isTrusted).
        if (EnvAuthRules.explicitTrust(config) == true) {
            refuse(
                "${EGATE.envGateEnvVar} is '$mode', so env access is the Google sign-in's alone, but " +
                    "${ENVA.trustEnvAuthHeaderEnvVar} (or '${ACFG.trustEnvAuthHeader}') is set on. Unset it, or set it off.",
            )
        }
        if (config.getEnvBool(PTD.envAuthFromAdminLoginEnvVar) == true) {
            refuse("${EGATE.envGateEnvVar} is '$mode', so ${PTD.envAuthFromAdminLoginEnvVar} must be off: a code sign-in must not grant env access.")
        }
        if (config.bootRole == BOOT.edge) {
            refuse("${EGATE.envGateEnvVar} is for an application node; an edge has a sign-in of its own.")
        }
    }

    /**
     * On a request the gate applies to, drops env access from any other source: one the node **assumed** for a local
     * request (`KDR_ASSUME_ENV_AUTH`), or a trusted header, which a simulated request on a test instance may still
     * have. On a gated request env access comes only with a Google sign-in, real or simulated. Run before
     * `EnvAuthRules.fromAdminLogin`, which grants it from the gate's sign-in and nothing to a request already env-authed.
     */
    fun dropOtherEnvAuth(cxt: KdrCxt, handler: RequestHandler) {
        if (cxt.envAuthEmail != null && applies(cxt.instanceConfig, handler)) {
            cxt.envAuthEmail = null
        }
    }

    /**
     * The gate itself: on a request it applies to, answers it -- and returns true -- unless the caller has env access
     * or the request is one the gate always lets through. Its own two paths are answered here whatever the caller
     * has: [EGATE.startPath] and [EGATE.callbackPath].
     *
     * Env access *available* is enough, not *effective*: a person who has turned their env access off to preview the
     * ordinary view is still the person Google vouched for, and challenging them would loop.
     *
     * A call made in this process for a user (the configuration assistant) passes: the person it acts for already
     * passed the gate to ask for it.
     */
    fun check(cxt: KdrCxt, handler: RequestHandler, focus: ContextFocus): Boolean {
        val config = cxt.instanceConfig
        if (!applies(config, handler) || handler.inProcessPrincipal != null) return false
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
        if (focus == ContextFocus.app && handler.method == "GET") {
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

    /**
     * Where a sign-in returns: [raw] when it is a same-origin path under the web app's root, else the root itself.
     * Replaced rather than refused, as the edge's `EnvAuthReturn` does, since somebody signing in should land
     * somewhere; never another site, so the gate cannot be used to send anyone elsewhere.
     */
    fun returnPath(config: KdrInstanceConfig, raw: String?): String {
        val root = appRoot(config)
        val path = sameOriginPath(raw) ?: return root
        return if (path == root || path.startsWith("$root/") || path.startsWith("$root?") || path.startsWith("$root#")) path else root
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
            signIn(cxt, handler, token, returnTo)
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
            EGATE.stateCookie, state.encode(NodeService.get(cxt)), Instant.fromEpochMilliseconds(state.expireEpochMs),
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
     * `nonce`, and signs the person in. Any failure is a plain refusal from the node; an account Google vouched for
     * that is not the deployment's own gets a link back to Google's chooser, to pick another.
     */
    private fun callback(cxt: KdrCxt, handler: RequestHandler) {
        val config = cxt.instanceConfig
        val node = NodeService.get(cxt)
        val state = handler.getRequestCookies()[EGATE.stateCookie]?.let { GateState.decode(node, it) }
        // Spent whether it succeeds or not: a state is good for one return.
        handler.addResponseCookie(EGATE.stateCookie, "", Instant.fromEpochMilliseconds(0))
        val params = handler.queryParams
        val sentState = params[EGATE.stateParam] as? String
        if (state == null || sentState == null || sentState != state.state || cxt.now().toEpochMilliseconds() > state.expireEpochMs) {
            LogAuth.info(cxt) { "Env gate sign-in refused: the state is missing, expired, or does not match." }
            refusePage(handler, EXC.badInput, "That sign-in could not be completed. Open the app again to start over.", offerAnother = false)
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
            refusePage(handler, EXC.badInput, "That sign-in could not be completed. Open the app again to start over.", offerAnother = false)
            return
        }
        val token = try {
            verifier.verify(cxt, exchange.idTokenFor(cxt, code, redirectUri(cxt, handler)))
        } catch (e: KdrException) {
            LogAuth.info(cxt) { "Env gate sign-in refused: ${e.message}" }
            refusePage(handler, EXC.authNeeded, "Google's answer could not be checked. Open the app again to start over.", offerAnother = false)
            return
        }
        if (token.nonce != state.nonce) {
            LogAuth.info(cxt) { "Env gate sign-in refused: the ID token's nonce does not match the one sent." }
            refusePage(handler, EXC.authNeeded, "Google's answer could not be checked. Open the app again to start over.", offerAnother = false)
            return
        }
        signIn(cxt, handler, token, state.returnTo)
    }

    /**
     * Signs in as the person [token] names, when it is the deployment's own: Google verified the address, it is on
     * the admin domain, and Google is authoritative for that domain (the signed `hd` claim). Checked **before** any
     * user is found or made, so an outside account gets nothing on this node. Then the session is written with its
     * env access stamp, and the browser sent to [returnTo].
     */
    private fun signIn(cxt: KdrCxt, handler: RequestHandler, token: GoogleIdToken, returnTo: String) {
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
        // The sign-in completes as any other does (AuthFormHandler.completeLogin), which reads the request it is in.
        val request = KdrRequest(cxt, emptyMap(), null, handler)
        cxt.request = request
        UserService.get(cxt).authFormHandler.loginByGoogleToken(cxt, token)
        request.envGateUntilMs = cxt.now().toEpochMilliseconds() + lifetimeMs(cxt.instanceConfig)
        LogAuth.info(cxt) { "Env gate sign-in for '$email' (subject '${token.subject}')." }
        // The cookie goes on before the redirect, which sends the response at once.
        RequestService.get(cxt).checkAddAuthCookies(cxt, handler)
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
            """<p><a href="/${ContextRoot.apiOf(handler.instanceConfig)}${EGATE.startPath}?$next&amp;${EGATE.promptParam}=${EGATE.selectAccount}">Use another Google account</a></p>"""
        } else {
            ""
        }
        val html = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Sign-in</title></head>""" +
            """<body style="font-family: system-ui, sans-serif; max-width: 32rem; margin: 4rem auto; padding: 0 1rem"><p>${escapeHtml(message)}</p>$another</body></html>"""
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

    /**
     * What a sign-in carries through Google, in [EGATE.stateCookie]: the `state` Google returns, the `nonce` its ID
     * token must hold, where to return, and when it lapses. Encrypted with the deployment's key, so the browser can
     * neither read nor change where it returns.
     */
    private class GateState(val state: String, val nonce: String, val returnTo: String, val expireEpochMs: Long) {
        fun encode(node: NodeService): String =
            node.encryptString(mapOf(K_STATE to state, K_NONCE to nonce, K_RETURN to returnTo, K_EXPIRE to expireEpochMs).toJsonStr(compact = true))

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
