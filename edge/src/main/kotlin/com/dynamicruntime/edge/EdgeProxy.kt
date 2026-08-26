package com.dynamicruntime.edge

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.util.toJsonStr
import com.dynamicruntime.common.http.request.ContextRoot
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.user.ENVA
import com.dynamicruntime.common.user.EnvAuthRules
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.http.HttpURI
import org.eclipse.jetty.proxy.ProxyHandler
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.Callback

/**
 * Where the edge sends application traffic, and how patiently it waits (issue #419).
 *
 * The timeouts are set rather than inherited. Jetty's `ProxyHandler` configures only `followRedirects=false`
 * and an empty cookie store on its client, so everything here would otherwise be the client library's
 * general-purpose defaults arriving by accident.
 */
@Suppress("ConstPropertyName")
object EDGEUP {
    /** Where application traffic goes. One upstream for now; a route table replaces this. */
    val upstreamEnvVar = EnvVarDef(
        "KDR_EDGE_UPSTREAM", group = ENVGRP.edge, defaultDoc = "`http://localhost:7070`",
        description = "Where an edge forwards application traffic -- every request whose leading path segment " +
            "is an application context root (`kda`/`cp`/`wa`/`st`) rather than one of the edge's own. Read only " +
            "by a node in the `edge` role. The `edgeUpstream` config option wins over this.",
    )

    /**
     * The ordinary development application, which is the only upstream a local edge could mean.
     *
     * A default rather than a literal because verifying this means running an application and an edge side by
     * side, and in a workspace that is not the developer's own, 7070 is somebody else's server.
     */
    const val defaultUpstream = "http://localhost:7070"

    /** Matches Jetty's own default; stated so it is a decision. */
    const val connectTimeoutMs = 5_000L

    /** Inactivity on the connection. Matches Jetty's default; stated for the same reason. */
    const val idleTimeoutMs = 30_000L

    /**
     * The whole request-and-response, which Jetty does **not** bound by default.
     *
     * Only the idle timer applies otherwise, and it resets on every byte, so an upstream that trickles a
     * response is never cut off. This is the one to revisit per route rather than globally: a total timeout is
     * right for an application request and exactly wrong for a streamed or long-polled one, so when route
     * entries become real, this belongs on them.
     */
    const val requestTimeoutMs = 60_000L

    /** Jetty's default is 64. Explicit, so the number is a choice about this deployment, not an inheritance. */
    const val maxConnectionsPerUpstream = 64

    /** Carries the resolved address from the wrapper to the header copy, so the cookie is decrypted once. */
    const val emailAttribute = "com.dynamicruntime.edge.envAuthEmail"

    /** The application context roots this edge forwards. Not its own, which it serves. */
    val proxiedRoots: Set<String> = setOf(ContextRoot.kda, ContextRoot.cp, ContextRoot.wa, ContextRoot.st)
}

/**
 * The edge's front door: decides what to forward, challenges anyone not signed in, and hands the rest to
 * [UpstreamProxy] (issue #419).
 *
 * **Why a wrapper rather than `ProxyHandler.Reverse` on its own.** A front handler earns its place by being
 * able to *decline* -- returning false so the request falls through to this node's own dispatcher. Jetty's
 * `ProxyHandler.handle` ends in an unconditional `return true` and does not null-check the result of
 * `rewriteHttpURI`, so it cannot decline; a rewriter returning null throws rather than passing the request on.
 * The decision therefore has to happen before `ProxyHandler` is entered at all.
 *
 * That turns out to be where it belongs anyway. This class holds the edge's *policy* -- which roots are
 * forwarded, and who may -- while `ProxyHandler` stays the data plane that moves bytes. The three-way dispatch
 * of the design notes is exactly the first half of [handle]:
 *
 *  - one of the edge's own roots -> decline, and the dispatcher serves the edge's endpoints and UI;
 *  - a known application root -> authenticate, then forward;
 *  - anything else -> decline, and the dispatcher answers with the terse 404 it already gives an unrecognized
 *    context root. The probe defense of the design notes survives without being restated here.
 */
class EdgeProxyHandler(
    private val config: KdrInstanceConfig,
    private val contentRoot: String,
    upstream: String,
) : Handler.Wrapper(UpstreamProxy(upstream)) {

    override fun handle(request: Request, response: Response, callback: Callback): Boolean {
        val (root, _) = RequestHandler.parsePath(Request.getPathInContext(request))
        if (root in EdgeRoot.all || root !in EDGEUP.proxiedRoots) {
            return false
        }
        // Created only AFTER the decline branch, and that ordering is what keeps it honest. A forwarded
        // request never reaches the dispatcher, so `RequestHandler` never builds one -- this is the only
        // KdrCxt the request will ever have on this node, rather than a second one shadowing the real thing.
        // A declined request gets no context from here at all, and the dispatcher builds its own as usual.
        //
        // It is a context for *this node's* work on the request -- reading config, timing, logging, and
        // later the node registry, which is database-backed and so cannot be reached without one. It carries
        // nothing inward: the only thing the upstream learns is the header, and the upstream builds its own
        // context from that. So nothing set here is expected to survive the hop, because nothing here is
        // meant to.
        val cxt = InstanceRegistry.createCxt("edgeProxy", config)
        val email = envAuthEmail(cxt, request)
        if (email == null) {
            // Challenged here rather than forwarded-and-refused: the application behind this edge has its own
            // idea of who is logged in, and would answer an anonymous request perfectly happily. The perimeter
            // is the only place that can insist.
            //
            // HOW it insists depends on who is asking, and getting that wrong breaks a running page. A
            // redirect answers a navigation; to a `fetch` it is invisible -- the browser follows it and hands
            // the caller the sign-in page with status 200, so `response.ok` is true and a JSON parse fails on
            // HTML. The frontend reported exactly that: "cannot parse the JSON in the response" from a
            // ui-config call whose session had expired. A 401 says the same thing in a form the caller can act
            // on.
            if (isNavigation(request)) {
                LogEdge.debug(cxt, "Challenging a navigation to /$root.")
                Response.sendRedirect(request, response, callback, loginUrl(request))
            } else {
                LogEdge.debug(cxt, "Refusing an unauthenticated background call to /$root.")
                sendAuthRequired(request, response, callback)
            }
            return true
        }
        request.setAttribute(EDGEUP.emailAttribute, email)
        // The one record this node keeps of a forwarded request. The dispatcher's request log never sees
        // proxied traffic, so without this an edge is silent about the majority of what passes through it.
        LogEdge.debug(cxt) { "Forwarding ${request.method} ${Request.getPathInContext(request)} for $email." }
        return super.handle(request, response, callback)
    }

    /**
     * The signed-in address, or null.
     *
     * Deliberately not `EdgeService.extractEnvAuth`, which is wired as `RequestService.authExtractor` and runs
     * inside dispatch -- a path proxied traffic never takes. Same cookie, same [EnvAuthCookie.decode], reached
     * from the one place that sees a request before the dispatcher does.
     *
     * The fallback goes through `EnvAuthRules.assumesEnvAuth` rather than re-deciding locally, and that is the
     * point of routing it through the rule at all. A developer's edge running with the "assume" switch on can
     * reach its own UI without Google, because the dispatcher resolves the same way; if this path judged
     * independently, forwarded traffic alone would still be challenged and the two halves of the same node
     * would disagree about who is signed in.
     */
    private fun envAuthEmail(cxt: KdrCxt, request: Request): String? {
        val raw = Request.getCookies(request).firstOrNull { it.name == ENVAUTH.cookie }?.value
        val decoded = raw?.let { EnvAuthCookie.decode(NodeService.get(cxt), it) }
        // cxt.now(), not the wall clock: it carries the test time offset, so an expiry can be driven forward
        // in a test exactly as it can on the dispatcher's path. The comparison is extractEnvAuth's.
        if (decoded != null && cxt.now().toEpochMilliseconds() <= decoded.expireEpochMs) {
            return decoded.email
        }
        return if (EnvAuthRules.assumesEnvAuth(config)) ENVA.assumedAddress else null
    }

    /**
     * Whether this request is a browser navigating, as opposed to a page fetching in the background.
     *
     * `Sec-Fetch-Mode` is the honest answer and every current browser sends it: `navigate` for a top-level
     * navigation, `cors` or `same-origin` for a `fetch`. It is a forbidden header name, so a page cannot
     * forge it.
     *
     * The `Accept` fallback covers a client that sends neither -- an old browser, a curl, a probe. Guessing
     * *navigation* there is the safer error: the worst case is a redirect where a 401 would have been
     * tidier, whereas guessing wrong the other way hands a browser a JSON body instead of a sign-in page and
     * strands somebody who could otherwise log in.
     */
    private fun isNavigation(request: Request): Boolean =
        ChallengeShape.isNavigation(request.headers.get("Sec-Fetch-Mode"), request.headers.get("Accept"))

    /**
     * Refuses a background call in the runtime's own error envelope, so the frontend's existing error path
     * reads it as an error rather than as data.
     *
     * `EP.envAuthRequiredCode` is what a caller matches on, and the login URL travels with it because only
     * this node knows where its sign-in page lives -- the frontend is served by the application and has no
     * idea it is behind an edge, let alone which one.
     */
    private fun sendAuthRequired(request: Request, response: Response, callback: Callback) {
        val body = mapOf(
            EP.status to EXC.authNeeded,
            EP.errorMessage to "Environment sign-in is required, or has expired.",
            EP.errorCode to EP.envAuthRequiredCode,
            // Bare: see EP.envAuthLoginUrl. The request being refused here is an API path, so a return
            // path built from it would send the caller back to JSON rather than to their page.
            EP.extraData to mapOf(EP.envAuthLoginUrl to "/$contentRoot${EDGEP.loginPage}"),
        ).toJsonStr(compact = true)
        response.status = EXC.authNeeded
        response.headers.put(HttpHeader.CONTENT_TYPE, "application/json")
        response.write(true, java.nio.ByteBuffer.wrap(body.toByteArray(Charsets.UTF_8)), callback)
    }

    /** The edge's own sign-in page, carrying where the caller was going. */
    private fun loginUrl(request: Request): String {
        val path = Request.getPathInContext(request)
        val query = request.httpURI.query
        val next = EnvAuthReturn.sanitize(if (query.isNullOrEmpty()) path else "$path?$query")
        return "/$contentRoot${EDGEP.loginPage}?${EnvAuthReturn.param}=${java.net.URLEncoder.encode(next, Charsets.UTF_8)}"
    }
}

/**
 * The data plane: rewrites the target onto the upstream and moves bytes (issue #419).
 *
 * Reached only through [EdgeProxyHandler], which has already decided this request is forwarded and who is
 * sending it.
 */
class UpstreamProxy(private val upstream: String) : ProxyHandler.Reverse({ request ->
    val uri = request.httpURI
    HttpURI.build(upstream)
        .path(Request.getPathInContext(request).let { if (it.startsWith("/")) it else "/$it" })
        .query(uri.query)
        .asImmutable()
}) {

    /**
     * Copies the client's headers, then applies the two strips that make a trusted identity header safe.
     *
     * Both are unconditional, and both are authentication bypasses when they are not.
     *
     * 1. **Remove [ENVA.header] before setting it.** Otherwise a client sends the header itself and any
     *    backend that trusts it is impersonated. A conditional overwrite is not enough: it has to go on every
     *    forwarded request, including ones this edge chose not to authenticate.
     * 2. **Remove the env-auth cookie.** It is `Path=/` on a shared host, so a browser attaches it to every
     *    proxied path automatically. Forwarding it hands the upstream a replayable perimeter credential.
     *
     * Setting the header is worth exactly as much as the network guarantee that it came from an edge: the
     * upstream must be unreachable except through this node.
     */
    override fun copyRequestHeaders(clientToProxy: Request, proxyToServer: org.eclipse.jetty.client.Request) {
        super.copyRequestHeaders(clientToProxy, proxyToServer)
        val forwarded = EnvAuthForwarding.forwarded(
            Request.getCookies(clientToProxy).map { it.name to it.value },
            clientToProxy.getAttribute(EDGEUP.emailAttribute) as? String,
        )
        proxyToServer.headers { headers ->
            // Removed unconditionally, and before anything decides whether to set it. The order is the
            // safety: a remove that only runs on the authenticated branch leaves the client's own value
            // standing on every other one.
            headers.remove(ENVA.header)
            if (forwarded.cookie == null) headers.remove(HttpHeader.COOKIE)
            else headers.put(HttpHeader.COOKIE, forwarded.cookie)
            if (forwarded.envEmail != null) headers.put(ENVA.header, forwarded.envEmail)
        }
        proxyToServer.timeout(EDGEUP.requestTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Timeouts and pool size, none of which `ProxyHandler` sets for us. */
    override fun configureHttpClient(client: HttpClient) {
        super.configureHttpClient(client)
        client.connectTimeout = EDGEUP.connectTimeoutMs
        client.idleTimeout = EDGEUP.idleTimeoutMs
        client.maxConnectionsPerDestination = EDGEUP.maxConnectionsPerUpstream
    }
}

/** Reads the configured upstream, falling back to the ordinary development application. */
fun upstreamFor(cxt: KdrCxt): String =
    cxt.getEnvVar(EDGEUP.upstreamEnvVar).takeUnless { it.isNullOrEmpty() }
        ?: (cxt.instanceConfig.get(ACFG.edgeUpstream) as? String)
        ?: EDGEUP.defaultUpstream


/**
 * What an upstream is allowed to see of the caller's identity (issue #419).
 *
 * Split out from [UpstreamProxy.copyRequestHeaders] so it can be tested without a live proxy, and it is worth
 * the split precisely because these two rules are invisible when they are wrong: a forwarded perimeter cookie
 * and a surviving client-set identity header both produce a request that works perfectly, for the wrong
 * person. Nothing observes them failing except a test that looks.
 */
object EnvAuthForwarding {
    /** The `Cookie` header to forward (null to send none) and the identity header to set (null to send none). */
    class Forwarded(val cookie: String?, val envEmail: String?)

    /**
     * [cookies] as the client sent them, and the address this edge resolved -- null when it resolved nobody.
     *
     * The env-auth cookie never travels: it is `Path=/` on a host shared with the upstream, so a browser
     * attaches it to every proxied path by itself, and forwarding it hands the upstream a credential it could
     * replay against this edge. Every other cookie is the upstream's own business and is passed through.
     *
     * The identity is whatever this edge decided and nothing else. The caller's own value is not consulted
     * here at all -- that is [UpstreamProxy.copyRequestHeaders]'s unconditional remove -- so a null address
     * means the header is absent rather than merely unset.
     */
    fun forwarded(cookies: List<Pair<String, String>>, email: String?): Forwarded {
        val kept = cookies.filter { (name, _) -> name != ENVAUTH.cookie }
        return Forwarded(
            cookie = if (kept.isEmpty()) null else kept.joinToString("; ") { (n, v) -> "$n=$v" },
            envEmail = email,
        )
    }
}


/**
 * Whether a challenge should be a redirect or a 401 (issue #419).
 *
 * Split out to be tested directly, for the same reason [EnvAuthForwarding] is: both answers *work* -- the
 * request completes either way -- so getting it backwards is not visible as a failure. It shows up as a page
 * reporting that it cannot parse JSON, or as a person staring at an error envelope instead of a sign-in
 * button, and neither points back here.
 */
object ChallengeShape {
    /**
     * True when this looks like a browser navigating rather than a page fetching in the background.
     *
     * `Sec-Fetch-Mode` is the honest answer and every current browser sends it: `navigate` for a top-level
     * navigation, `cors` or `same-origin` for a `fetch`. It is a forbidden header name, so a page cannot
     * forge it.
     *
     * The `Accept` fallback covers a client sending neither -- an older browser, a curl, a probe. Defaulting
     * to *navigation* is the safer error: the worst case is a redirect where a 401 would have been tidier,
     * whereas the other way hands a browser a JSON body instead of a sign-in page and strands somebody who
     * could otherwise have logged in.
     */
    fun isNavigation(secFetchMode: String?, accept: String?): Boolean {
        if (!secFetchMode.isNullOrEmpty()) {
            return secFetchMode.equals("navigate", ignoreCase = true)
        }
        return accept?.contains("text/html", ignoreCase = true) ?: true
    }
}
