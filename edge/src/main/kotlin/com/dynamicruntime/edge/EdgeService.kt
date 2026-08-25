package com.dynamicruntime.edge

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ContentServer
import com.dynamicruntime.common.http.request.ContextFocus
import com.dynamicruntime.common.http.request.ContextRoot
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.user.GoogleAuthConfig
import com.dynamicruntime.common.logging.KdrLogger
import com.dynamicruntime.common.startup.ServiceInitializer

/** Topic logger for the edge, beside the code that owns the `"edge"` topic. */
object LogEdge : KdrLogger("edge")

/**
 * The KdrEdge service (issue #386): binds the edge's own context roots, and refuses to boot a node whose roots
 * would be mistaken for an application's.
 *
 * It will grow the route table and the upstream registry; today it is the boot-time guard and the place those
 * belong.
 */
class EdgeService : ServiceInitializer, ContentServer {
    override val serviceName: String = EdgeService.serviceName

    /**
     * Binds this node as a content server, after refusing the boot if its roots are an application's.
     *
     * Registered here rather than in `checkReady`, and the timing decides a race: content servers are offered
     * a request in registration order, and `PortalService` registers in ITS `checkInit` -- so anything
     * registering later loses the bare content root to the application's portal. Observed: `/ec` redirected
     * to `/ec/portal`. EdgeComponent's earlier load priority is what puts this first.
     *
     * Ordering is the fix available today. The real one is role profiling: an edge has no business loading
     * the portal at all, and then there is nothing to race.
     */
    override fun checkInit(cxt: KdrCxt) {
        // Before anything that can fail for an unrelated reason: reaching RequestService must not be able to
        // decide whether the boot refusal runs.
        val configured = checkContextRoots(cxt)
        val requestService = RequestService.get(cxt)
        requestService.addContentServer(this)
        // The forwarding front door (issue #419). Registered before the dispatcher sees anything, which is the
        // whole point: traffic bound for a backend is addressed to somebody else, and must never be measured
        // against this node's own context roots.
        val upstream = upstreamFor(cxt)
        requestService.addFrontHandler(
            EdgeProxyHandler(
                config = cxt.instanceConfig,
                contentRoot = cxt.instanceConfig.get(ACFG.contentContextRoot) as? String ?: EdgeRoot.ec,
                upstream = upstream,
            ),
        )
        LogEdge.info(cxt) { "KdrEdge forwarding ${EDGEUP.proxiedRoots.sorted()} to $upstream." }
        LogEdge.info(cxt) { "KdrEdge serving context roots ${configured.values.filterNotNull()}." }
    }

    /**
     * Refuses to start when an edge root equals one of the application's well-known roots.
     *
     * An edge and the backends it fronts are reachable on the same host, so a shared root makes a path
     * ambiguous -- `/kda/user/profile` would name two different servers' endpoints. That failure is a
     * *mis-route*, which shows up as the wrong server answering rather than as an error, so it is worth
     * refusing at the one moment somebody is watching. The same reasoning as the unruled-section check in
     * `RequestService.checkInit`: a report that cannot be scrolled past.
     *
     * It compares against the application **defaults** rather than a live list of what the backends use, which
     * is route configuration and does not exist yet. That catches the mistake anybody would actually make --
     * configuring an edge with `kda` -- and tightens when the route table arrives.
     *
     * Separate from [checkInit] because it is a pure function of the instance config while [checkInit] is not:
     * since issue #383 a missing service is a loud error, so a caller that wants only this guard -- a test,
     * or any later pre-boot validation -- cannot go through [checkInit] to reach it.
     */
    fun checkContextRoots(cxt: KdrCxt): Map<String, String?> {
        val config = cxt.instanceConfig
        val configured = linkedMapOf(
            ACFG.apiContextRoot to (config.get(ACFG.apiContextRoot) as? String),
            ACFG.contentContextRoot to (config.get(ACFG.contentContextRoot) as? String),
            ACFG.appContextRoot to (config.get(ACFG.appContextRoot) as? String),
            ACFG.staticContextRoot to (config.get(ACFG.staticContextRoot) as? String),
        )
        val applicationRoots = setOf(ContextRoot.kda, ContextRoot.cp, ContextRoot.wa, ContextRoot.st)
        val clashes = configured.filterValues { it != null && it in applicationRoots }
        if (clashes.isNotEmpty()) {
            throw KdrException(
                "Refusing to start the edge: ${clashes.entries.joinToString(", ") { "${it.key}='${it.value}'" }} " +
                    "matches an application context root. An edge and the backends it fronts share a host, so a " +
                    "shared root makes a path ambiguous and the wrong server answers rather than failing.",
            )
        }
        return configured
    }

    /**
     * Takes over how this node decides who a caller is (issue #386).
     *
     * In `checkInit` because `RequestService` must exist to be reached -- the same point and the same way
     * `PortalService` registers itself as a content server.
     *
     * **A replacement, not an addition.** The session-cookie path it displaces must not also run here: once
     * the edge proxies, a browser holds `kdrAuth` for some backend reached *through* this host, and that
     * cookie has nothing to say about who may operate the edge. Letting it bind a profile here would hand an
     * application session authority over the perimeter.
     */
    override fun checkReady(cxt: KdrCxt) {
        RequestService.get(cxt).authExtractor = ::extractEnvAuth
    }

    /**
     * Serves the sign-in page and sends the bare content root to it.
     *
     * The page is the whole anonymous surface of an edge, so this is deliberately two paths and no more.
     */
    override fun serve(cxt: KdrCxt, handler: RequestHandler): Boolean {
        if (handler.focus != ContextFocus.content) {
            return false
        }
        val apiRoot = "/" + (cxt.instanceConfig.get(ACFG.apiContextRoot) as? String ?: EdgeRoot.ea)
        // This edge's own front end, and the application's reached through it -- the latter being a path that
        // only resolves because this node forwards it (issue #419).
        val edgeAppRoot = "/" + (cxt.instanceConfig.get(ACFG.appContextRoot) as? String ?: EdgeRoot.ew)
        val backendAppRoot = "/" + ContextRoot.wa
        return when (handler.appPath) {
            "/" -> {
                // A signed-in caller gets a page rather than a redirect. Sending them to the sign-in page
                // instead is a LOOP -- root to login, login back to root -- which is what an edge with no
                // home page of its own does by default. Observed the first time somebody signed in from the
                // bare root rather than from a deep link.
                val email = cxt.envAuthEmail
                if (email != null) {
                    handler.sendStringResponse(
                        EnvAuthPage.renderSignedIn(email, edgeAppRoot, backendAppRoot),
                        EXC.ok, "text/html; charset=utf-8",
                    )
                } else {
                    handler.sendRedirect("/" + handler.contextRoot + EDGEP.loginPage)
                }
                true
            }
            EDGEP.loginPage -> {
                // Already signed in: go where they were headed rather than asking again. Without this, a
                // signed-in caller who lands back on this page is offered a login they do not need, and the
                // redirect after it starts the loop over.
                if (cxt.envAuthEmail != null) {
                    handler.sendRedirect(
                        EnvAuthReturn.sanitize(handler.queryParams[EnvAuthReturn.param] as? String),
                    )
                    return true
                }
                val clientId = GoogleAuthConfig.clientId(cxt.instanceConfig)
                if (clientId == null) {
                    // Saying so plainly beats a button that cannot work: without a client id there is no way
                    // in at all, and that is an operator's problem rather than a visitor's mistake.
                    handler.sendStringResponse(
                        "Google sign-in is not configured on this node.", EXC.notFound, "text/plain",
                    )
                    return true
                }
                val returnTo = EnvAuthReturn.sanitize(handler.queryParams[EnvAuthReturn.param] as? String)
                handler.sendStringResponse(
                    EnvAuthPage.render(clientId, returnTo, apiRoot + EAEP.login),
                    EXC.ok, "text/html; charset=utf-8",
                )
                true
            }
            else -> false
        }
    }

    /**
     * Restores the acting profile from the Env Auth cookie: decrypt with the instance key, check it has not
     * expired, and bind the caller as an env-authed operator.
     *
     * Silent about every failure -- absent, forged, expired -- because all three mean the same thing to a
     * request: nobody is logged in. The address also goes onto [KdrCxt.envAuthEmail], so the identity reaches
     * the log line here exactly as it does on a backend that was told by header.
     */
    fun extractEnvAuth(cxt: KdrCxt, handler: RequestHandler) {
        val raw = handler.getRequestCookies()[ENVAUTH.cookie] ?: return
        val node = NodeService.get(cxt)
        val decoded = EnvAuthCookie.decode(node, raw) ?: return
        if (cxt.now().toEpochMilliseconds() > decoded.expireEpochMs) {
            return
        }
        cxt.envAuthEmail = decoded.email
        cxt.bindToUserProfile(UserProfile.envAuthed(decoded.email))
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "EdgeService"

        fun get(cxt: KdrCxt): EdgeService = cxt.instanceConfig.get(serviceName) as? EdgeService
            ?: throw KdrException("The $serviceName is not available on this node.")
    }
}
