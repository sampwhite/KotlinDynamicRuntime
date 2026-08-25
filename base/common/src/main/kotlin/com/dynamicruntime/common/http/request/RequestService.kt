package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrRequest
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.node.NodeService
import com.dynamicruntime.common.user.AUTHC
import com.dynamicruntime.common.user.UserAuthCookie
import com.dynamicruntime.common.user.ENVA
import com.dynamicruntime.common.user.EnvAuthRules
import com.dynamicruntime.common.user.UserService
import com.dynamicruntime.common.user.refreshActingRoles
import com.dynamicruntime.common.util.crc32Hex
import com.dynamicruntime.common.util.mkUniqueId
import kotlin.time.Instant
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.EndpointKind
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.endpoint.ListPage
import com.dynamicruntime.common.endpoint.resolveEndpointInputType
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import org.eclipse.jetty.server.Handler
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.SchOpts
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.schema.failureSummary
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.toWireMap
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.sql.cache.SqlTableCacheService
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.toJsonMap
import com.dynamicruntime.common.util.toJsonStr
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The request dispatcher: given a decoded request, it applies (currently stubbed) security,
 * finds the matching endpoint, validates/coerces the input, runs the handler, wraps the result
 * in the protocol envelope, and sends the JSON response. Ported from dn's `DnRequestService`,
 * trimmed to what kd2 has today.
 *
 * Deferred (stubbed or TODO): the injected auth behaviors ([extractAuth]/[loadProfile]/
 * [checkAddAuthCookies] are no-ops until the auth subsystem lands), and content serving.
 */
class RequestService : ServiceInitializer {
    override val serviceName: String = RequestService.serviceName

    /** The context root under which API endpoints are served; from [ACFG.apiContextRoot]. Bound in [checkInit]. */
    var apiContextRoot: String = ContextRoot.kda

    /** The context root under which content is served; from [ACFG.contentContextRoot]. Bound in [checkInit]. */
    var contentContextRoot: String = ContextRoot.cp

    /** The context root under which the self-contained webapp is served; from [ACFG.appContextRoot]. Bound in [checkInit]. */
    var appContextRoot: String = ContextRoot.wa

    /** The context root under which immutable static content is served; from [ACFG.staticContextRoot]. Bound in [checkInit]. */
    var staticContextRoot: String = ContextRoot.st

    /**
     * Every context root this node recognizes, mapped to the [ContextFocus] it targets. A request whose
     * leading segment is not a key here is fast-failed with a short 404; otherwise its focus decides dispatch
     * (api → endpoints, anything else → content servers). Assembled in [checkInit] from the per-kind roots.
     */
    var contextRootFocus: Map<String, ContextFocus> = mapOf(
        ContextRoot.kda to ContextFocus.api,
        ContextRoot.cp to ContextFocus.content,
        ContextRoot.wa to ContextFocus.app,
        ContextRoot.st to ContextFocus.static,
    )

    /**
     * Section → access rules. A section with no entry is served permissively, which is why every section an
     * *endpoint* declares must appear in one of the lists below: [checkInit] refuses to start when one does
     * not (issue #211). That check covers endpoints only -- a content focus takes its section from a document
     * path, so its sections are open-ended and stay permissive by design.
     */
    val sectionRulesMap: MutableMap<String, SectionRules> = HashMap()

    // `home`/`logout` are listed here to record what they already were: each had no entry and was therefore
    // served anonymously, so naming them changes no behavior and only makes the decision visible.
    //
    // `fixture` and `demo` are the two non-application roots (issue #270), and the split is by *purpose*:
    //
    //  - `fixture` exists so a capability can be **exercised** -- by an automated test or by a developer by
    //    hand. It is never shown to a client, and every endpoint under it is gated (`forTestingOnly`, or a
    //    module that loads only in developer environments).
    //  - `demo` exists so a **person** can see a capability work, and may be deliberately enabled for a client
    //    as a showcase. That possibility is what earns it a grooming standard: honest verbs, real
    //    descriptions, coherent schema.
    //
    // Both are anonymous, which is what they already were under their old names (`test`, `file`,
    // `gedraSketch`). The old roots described the *gate* or the *feature*; these describe the purpose, which
    // is the thing a reader needs and the thing that stops a fixture squatting a name a real entity will want
    // -- `gedra` and `file` were both heading for exactly that collision.
    val anonSections: List<String> = listOf(
        "health", "schema", "content", "portal", "site", "auth", "app",
        "fixture", "demo", "home", "logout",
    )
    // `gedra` is login-gated and nothing more: how far a caller reaches into stored gedras is a *scope*
    // question, answered per request by `ReadScopeRules.forCaller`, so one surface serves an ordinary user
    // and an administrator rather than the two needing separate sections (issue #310).
    val userSections: List<String> = listOf("user", "profile", "gedra")

    /**
     * Sections requiring [ROLE.operator] -- the middle rung of [RoleLadder], between an ordinary user and an
     * administrator. `operator` holds endpoints for running the deployment rather than using it (see
     * `OperatorEndpoints`); nothing that predates the role was moved onto this list, so adding the level
     * widened nobody's access.
     *
     * An admin reaches these without holding `operator`, because [RoleLadder] ranks admin above it.
     */
    val operatorSections: List<String> = listOf("operator")

    /**
     * Sections requiring [ROLE.admin] **and** the [ROLE.allClients] capability -- the **full-scope**
     * administration surface (issue #225). The level alone is not enough: an administrator confined to one
     * client does not get a narrowed view of these endpoints, they get a different surface
     * ([scopedAdminSections]).
     *
     * **Both, not either.** These sections originally named the capability as their required role, which made
     * holding it sufficient on its own -- `RoleLadder.satisfies` falls back to exact membership off the
     * ladder, so it granted the surface rather than widening it. A user demoted to `user` who kept the
     * capability therefore retained cross-client user administration while being refused the *lesser* scoped
     * surface, which is the wrong way round. A capability qualifies an authority; it never confers one.
     *
     * Since #211 the same comparison has driven the endpoint catalog, so these are also *invisible* to a caller
     * who cannot call them -- "see" and "use" are one answer.
     */
    val adminSections: List<String> = listOf("node", "admin")

    /**
     * Sections requiring [ROLE.admin] but **confined by the caller's scope** (issue #225) -- what a
     * client-scoped administrator has instead of a narrowed view of [adminSections]. A caller holding
     * [ROLE.allClients] satisfies this too and is simply unconfined, so one surface serves both.
     */
    val scopedAdminSections: List<String> = listOf("userAdmin")

    @KdrPrivate
    var isInit: Boolean = false

    // Caches of compiled endpoint input/output types (parsed against the compiled schema store), keyed by
    // KdrEndpoint.collationKey -- "path:method", which is what the endpoint store itself is keyed by and so
    // the one value that identifies an endpoint uniquely. Keyed by path alone, two endpoints sharing a URL
    // and differing only by verb (issue #335) would each be validated against whichever compiled first.
    private val inputTypeCache = ConcurrentHashMap<String, SchType>()
    private val outputTypeCache = ConcurrentHashMap<String, SchType>()

    /**
     * Content servers consulted (in registration order) before endpoint dispatch, so a
     * service like the portal can serve HTML/static content from within the request
     * pipeline. Registered by services during their init; see [ContentServer].
     */
    val contentServers = CopyOnWriteArrayList<ContentServer>()

    /** Registers a [ContentServer] (idempotent by identity). */
    fun addContentServer(server: ContentServer) {
        if (contentServers.none { it === server }) {
            contentServers.add(server)
        }
    }

    /**
     * Jetty handlers offered each request **before** this dispatcher, in registration order (issue #419).
     *
     * This is a different seam from [contentServers], and the difference is the point. A content server runs
     * *inside* the request pipeline: a `KdrCxt` exists, auth has been extracted, the context-root gate has
     * already accepted the request. A front handler runs *before* any of that, so it can take a request this
     * node will not otherwise recognize -- which is what a reverse proxy needs, since traffic it forwards is
     * addressed to somebody else and must never be measured against this node's own roots.
     *
     * A front handler that returns `false` declines, and the request falls through to the next one and finally
     * to the dispatcher, which answers exactly as it does when no front handler is installed. That is what
     * keeps this additive: a node with none behaves identically, and the dispatcher needs no notion that
     * proxying exists.
     */
    val frontHandlers = CopyOnWriteArrayList<Handler>()

    /** Registers a front handler (idempotent by identity). */
    fun addFrontHandler(handler: Handler) {
        if (frontHandlers.none { it === handler }) {
            frontHandlers.add(handler)
        }
    }

    /** The role an application path's section requires, or null when the section is anonymous (or unruled). */
    fun requiredRoleFor(appPath: String): String? = sectionRulesMap[sectionOf(appPath)]?.requiredRole

    /**
     * Whether [profile] may call [appPath] -- the single answer the dispatcher enforces and the endpoint
     * catalog filters on (issue #211), so what is advertised and what is served cannot drift apart.
     *
     * The comparison goes through [RoleLadder], exactly as the gate's does -- an admin is shown an operator
     * section without holding `operator`, because that is who the dispatcher would let in. Testing plain set
     * membership here would reinstate the very drift this function exists to prevent, one rung further down.
     *
     * It compares against the roles on the profile it is handed, and the caller is responsible for those being
     * live. The gate gets that from [refreshActingRoles] before enforcing; the catalog calls the same thing
     * once per request. Filtering on a session cookie's roles instead would hide endpoints from the very
     * people a role grant just admitted -- and keep hiding them for the cookie's whole life, since the grant
     * deliberately does not require a re-login.
     */
    fun canAccess(profile: UserProfile, appPath: String): Boolean {
        val rules = sectionRulesMap[sectionOf(appPath)] ?: return true
        return rules.admits(profile.roles)
    }

    /**
     * The browser bootstrap config: the live context roots keyed by focus (`{"contextRoots":{"api":"kda",
     * "content":"cp"}}`). A content server injects this into a served page (as `window.kdrCfg`) so its
     * JavaScript can build backend URLs from the configured roots rather than hardcoding them.
     */
    fun frontendConfig(): Map<String, Any?> =
        mapOf("contextRoots" to contextRootFocus.entries.associate { (root, focus) -> focus.name to root })

    /**
     * Binds everything this dispatcher needs from the instance config and refuses the boot if an endpoint
     * section has no access rules.
     *
     * In `onCreate` rather than `checkInit` because none of it touches another service -- it reads the
     * instance config and the schema store, both of which are in hand before any regular service starts. That
     * placement is what lets the content servers stop forcing this service to initialize: `onCreate` runs
     * across every service of the tier before any `checkInit` does, so a service registering itself in its own
     * `checkInit` finds the dispatcher already configured, by the lifecycle rather than by asking.
     */
    override fun onCreate(cxt: KdrCxt) {
        if (isInit) {
            return
        }
        for (s in anonSections) sectionRulesMap[s] = SectionRules(s, needsLogin = false, requiredRole = null)
        for (s in userSections) sectionRulesMap[s] = SectionRules(s, needsLogin = true, requiredRole = ROLE.user)
        for (s in operatorSections) sectionRulesMap[s] = SectionRules(s, needsLogin = true, requiredRole = ROLE.operator)
        for (s in scopedAdminSections) sectionRulesMap[s] = SectionRules(s, needsLogin = true, requiredRole = ROLE.admin)
        for (s in adminSections) {
            sectionRulesMap[s] = SectionRules(
                s, needsLogin = true, requiredRole = ROLE.admin, requiredCapability = ROLE.allClients,
            )
        }

        apiContextRoot = (cxt.instanceConfig.get(ACFG.apiContextRoot) as? String) ?: ContextRoot.kda
        contentContextRoot = (cxt.instanceConfig.get(ACFG.contentContextRoot) as? String) ?: ContextRoot.cp
        appContextRoot = (cxt.instanceConfig.get(ACFG.appContextRoot) as? String) ?: ContextRoot.wa
        staticContextRoot = (cxt.instanceConfig.get(ACFG.staticContextRoot) as? String) ?: ContextRoot.st
        // Each configured root maps to the focus it targets; the leading segment of a request is matched here.
        contextRootFocus = mapOf(
            apiContextRoot to ContextFocus.api,
            contentContextRoot to ContextFocus.content,
            appContextRoot to ContextFocus.app,
            staticContextRoot to ContextFocus.static,
        )

        // Say out loud, once, that this node believes an unverifiable header (issue #348). The guarantee behind
        // trusting it is a *deployment* property -- that only an edge can reach this node -- which nothing here
        // can check. An operator reading the boot log is the one person placed to notice it is not true, so the
        // assumption belongs in the log rather than only in the memory of whoever set the variable.
        if (EnvAuthRules.isTrusted(cxt.instanceConfig)) {
            LogRequest.info(cxt) {
                "Env auth is TRUSTED: this node accepts '${ENVA.header}' from any caller and treats it as an " +
                    "authenticated identity. It must be unreachable except through an edge server."
            }
        }

        // Every endpoint's section must have rules declared above (issue #211). An unruled section is served
        // permissively, so without this check a new section ships open to the world, and nothing says so --
        // the failure is invisible precisely because it is a failure to deny. Refusing to boot is the only
        // report that cannot be scrolled past, and the fix is one entry in the appropriate list.
        //
        // It lives here rather than beside the store it reads, because `SchemaService` is a *startup* service
        // and this is a regular one: during its init the dispatcher does not exist yet, so a check written
        // there could only find a null request service and skip -- failing open, silently, in the exact shape
        // it exists to prevent. The *tier* is what makes this the right place, not the pass within it: every
        // startup service has finished before any regular one is created, so the store is built here, and the
        // rules are populated immediately above -- the one point where both halves are in hand.
        //
        // Nothing contributes endpoints after that: schema is collected from components, before any service
        // runs, and compiled by the startup tier. So reading it in the first pass sees the whole set.
        val unruled = cxt.getSchema().endpoints.values
            .map { sectionOf(it.path) }.distinct().sorted()
            .filter { it !in sectionRulesMap }
        if (unruled.isNotEmpty()) {
            throw KdrException(
                "Refusing to start: the endpoint section(s) ${unruled.joinToString(", ") { "'$it'" }} have no " +
                    "access rules, so they would be served to anyone. Add each to anonSections, userSections, " +
                    "operatorSections or adminSections in RequestService.",
            )
        }
        // Publication is restricted to the user sections (issue #433).
        //
        // Note what this is *not* protecting. `publicApi` decides advertisement, not access, so a stray tag
        // cannot expose anything -- the section gate still refuses an anonymous caller at an admin endpoint.
        // What it protects is the **answerability of a promise**: "what did we commit to supporting?" has to
        // be checkable by reading one rule, and overrides scattered across sections turn it into a survey.
        // An endpoint that genuinely belongs in the published set gets a twin under a user section, which is
        // also the honest thing to do -- a path documented to outside developers as `/admin/...` tells them
        // something false about what it is for.
        //
        // **Production warns; everywhere else refuses** -- the house rule for a defect a running deployment
        // survives (`MarkdownFragmentService.fragmentCheckMode`, `GedraConfigCollector`). It applies here
        // because of the paragraph above: a node that advertises an endpoint it should not is serving every
        // request correctly and mis-describing one of them. Refusing to start over that would take a
        // deployment down to fix a documentation error, which is the larger outage.
        //
        // Deliberately NOT the same for the unruled-section check above, which stays fatal everywhere: that
        // one guards *access*, and a node that boots past it is serving a section to anyone. The distinction
        // is whether production is still viable, not whether the check is a boot check.
        //
        // A third hand-rolled instance of this rule, which is exactly what #303 exists to absorb into a
        // registry plus an operator endpoint -- so a warning nobody was watching for can still be asked about
        // on a running node. Kept minimal here (no mode override of its own) to leave that less to unpick.
        val published = cxt.getSchema().endpoints.values.filter { it.publicApi }
        val misplaced = published.filter { sectionOf(it.path) !in userSections }.map { it.path }.sorted()
        if (misplaced.isNotEmpty()) {
            val problem = "${misplaced.joinToString(", ") { "'$it'" }} " +
                "${if (misplaced.size == 1) "is" else "are"} marked publicApi but sit outside the user " +
                "sections ($userSections). Publish a twin under a user section instead, or drop the mark."
            if (cxt.instanceConfig.env == ENV.prod) {
                LogRequest.warn(cxt, "Published endpoints are misplaced, and the catalog will say so: $problem")
            } else {
                throw KdrException("Refusing to start: $problem")
            }
        }
        isInit = true
    }

    fun handleRequest(cxt: KdrCxt, handler: RequestHandler) {
        // Gate on the context root before touching the request body. An unrecognized leading segment is
        // almost always a hostile probe: reject it with a short 404 and do no decoding. (Future: route these
        // to a separate log sink rather than the normal request log.)
        val focus = contextRootFocus[handler.contextRoot]
        if (focus == null) {
            // A GET for the bare root ("/" or empty context root) is a browser landing on the site: send it to
            // the content root, whose content server (the portal) serves the landing page. Redirecting to the
            // content root -- rather than straight to the portal page -- keeps the dispatcher unaware of the
            // portal specifically. Any other unrecognized root stays a terse 404 (no decoding).
            if (handler.contextRoot.isEmpty() && handler.method.equals("GET", ignoreCase = true)) {
                handler.sendRedirect("/$contentContextRoot")
                return
            }
            LogRequest.debug(cxt, "Rejecting request outside known context roots: ${handler.logRequestUri}")
            handler.sendShortNotFound()
            return
        }
        handler.focus = focus

        // Table caches. `beginRequest` binds the change monitor that records which cached tables this request
        // writes; `endRequest` publishes those to the shared state row, which is how the *other* nodes find
        // out. It brackets the whole of dispatch, in a finally, because a request that changed a table and
        // then failed still changed it -- and `endRequest` never throws, so it cannot mask that failure. Both
        // calls are cheap and do nothing at all when no cache is registered; refreshing is lazy, driven by the
        // first read of a cache.
        //
        // Two calls in the one dispatcher, rather than a hook framework introduced to hold them: there is
        // exactly one dispatcher, and one subscriber.
        val tableCaches = SqlTableCacheService.get(cxt)
        tableCaches.beginRequest(cxt)
        try {
            dispatch(cxt, handler, focus)
        } finally {
            tableCaches.endRequest(cxt)
        }
    }

    /** The body of [handleRequest], once the context root has been recognized: auth, the section gate, dispatch. */
    @KdrPrivate
    fun dispatch(cxt: KdrCxt, handler: RequestHandler, focus: ContextFocus) {
        val appPath = handler.appPath
        val method = handler.method

        handler.sectionRules = sectionRulesMap[handler.section]
        handler.decodeRequestData()
        cxt.debug = handler.debug // the request's _debug tag, if any, rides on the context (and into logs)
        // The request's client identity (appId, traceId -- issue #105) is already on cxt: it was resolved from
        // the headers / query params and set when the context was created, before dispatch.

        // A request starts out **anonymous**, and now says so. The context's default profile is the *system*
        // user -- an internal acting identity for work nobody requested -- whose client is `hub`, and leaving
        // it in place made an unauthenticated caller look like an internal one to everything that asked. It
        // was already wrong and merely invisible: `/auth/self/info` substituted the anonymous profile at the
        // point of display, so that endpoint answered `public` while the catalog's client filter, sitting on
        // the same request, compared against `hub`. Binding it here means every reader agrees rather than each
        // deciding (issue #413).
        cxt.bindToUserProfile(UserProfile.anonymous())
        extractAuth(cxt, handler)

        // Enforce the section's required role against the acting profile (restored by extractAuth). The test
        // goes through RoleLadder rather than plain set membership, so a higher privilege satisfies a lower
        // requirement (an admin passes an operator section) while a deployment's own roles stay exact-match.
        //
        // Which failure it is turns on whether anyone is logged in, because the two say different things to a
        // caller: 401 means "authenticate and try again", which is actionable, while 403 means "you are known
        // and this is not yours", where retrying with the same identity never helps. Answering 401 to a
        // logged-in user invites exactly the retry that cannot work -- and tells a frontend to send them back
        // to a login screen they are already past.
        val rules = handler.sectionRules
        if (rules != null && rules.isGated) {
            refreshActingRoles(cxt)
            // The same predicate the catalog filters on, so a caller is never shown an endpoint that would
            // then refuse them. It reports *which* requirement is unmet, because a section can demand a level
            // and a capability, and telling an administrator they "require the 'admin' role" when what they
            // lack is `allClients` sends them looking for the wrong thing.
            val unmet = rules.unmetRequirement(cxt.userProfile.roles)
            if (unmet != null) {
                val loggedIn = cxt.userProfile.isLoggedIn
                throw KdrException(
                    if (loggedIn) {
                        "Request requires the '$unmet' role."
                    } else {
                        "Request requires the '$unmet' role and no user is logged in."
                    },
                    code = if (loggedIn) EXC.notAuthorized else EXC.authNeeded,
                )
            }
        }

        loadProfile(cxt, handler)

        // Dispatch by focus: the API root routes to JSON endpoints; every other root routes to content
        // servers. Each content server self-selects on the request's focus (see [ContentServer]).
        if (!handler.hasResponseBeenSent()) {
            if (focus == ContextFocus.api) {
                // Endpoints are keyed by "path:method" (KdrEndpoint.collationKey) on the context-root-stripped
                // application path, so endpoint definitions never carry the context root.
                val endpoint = cxt.getSchema().endpoints["$appPath:$method"]
                if (endpoint != null) {
                    executeEndpoint(cxt, handler, endpoint)
                }
            } else {
                for (server in contentServers) {
                    if (server.serve(cxt, handler)) {
                        break
                    }
                }
            }
        }
        if (!handler.hasResponseBeenSent()) {
            // Nothing served the request. Under the API focus that is a missing endpoint (a JSON 404 error
            // envelope); under a content focus it is a missing page (a friendly HTML 404).
            if (focus == ContextFocus.api) {
                throw KdrException("Path '${handler.target}' had no matching endpoint.", code = EXC.notFound)
            }
            handler.sendFriendlyNotFound()
            return
        }

        handler.logSuccess(cxt, EXC.ok)
    }

    /**
     * The store an endpoint's types resolve against: its **client's** variant when it has one, and the global
     * store otherwise (issue #387).
     *
     * This is what makes a per-client endpoint mean the client's schema without touching the caches below.
     * They stay keyed by **path**, and stay sound, because the path names exactly one client -- which is
     * `client-definition.md`'s "path separation dissolves the type-cache problem rather than answering it",
     * now literal rather than anticipated. A shared path still resolves globally, so nothing about the shared
     * surface changes.
     *
     * Note it is the **endpoint's** client, never the caller's. Two callers of one path get one answer, which
     * is the property a path-keyed cache needs; who may call it is the access gate's question and is settled
     * before this runs.
     */
    private fun typesFor(cxt: KdrCxt, endpoint: KdrEndpoint): KdrSchemaStore {
        val client = endpoint.client ?: return cxt.getSchema()
        return SchemaService.get(cxt).storeFor(client)
    }

    /** Validates the input, runs the handler, wraps the result, and sends the response. */
    fun executeEndpoint(cxt: KdrCxt, handler: RequestHandler, endpoint: KdrEndpoint) {
        val schema = typesFor(cxt, endpoint)
        val inputType = inputTypeCache.getOrPut(endpoint.collationKey) {
            resolveEndpointInputType(endpoint, schema.types)
                ?: throw KdrException("Could not compile input schema for '${endpoint.collationKey}'.")
        }

        val data = LinkedHashMap<String, Any?>(handler.queryParams)
        handler.postData?.let { data.putAll(it) }
        // Endpoint input is a flat set of top-level fields (issue #40), so the flat HTTP input -- query params
        // and/or POST body -- validates directly, with no re-grouping. The input type is closed to undeclared
        // properties, though off-contract `_`/`$` keys remain exempt (see the validator).
        // forInput: this is a request, so a `g-derived` property is neither demanded of the caller nor taken
        // from them (issue #254). The same types validate a response elsewhere, where those fields are
        // ordinary values -- which is why the direction is a parameter rather than a property of the type.
        val result = coerceAndValidate(inputType, data, SchOpts(forInput = true))
        if (result.failures.isNotEmpty()) {
            // The failures travel STRUCTURED, under extraData, rather than interpolated into the sentence
            // (issue #198). They are already structured -- path, code, message, the schema's own wording, the
            // valid options -- and flattening that into English made a caller parse prose to find out which
            // field was wrong. The message keeps a readable summary for a log line; the detail is in the bag.
            throw KdrException.mkInput(failureSummary(result.failures)).also {
                it.extraData[EP.failures] = result.failures.map { f -> f.toWireMap() }
            }
        }

        val requestData = result.value?.toJsonMap() ?: emptyMap()

        val requestInfo = RequestInfo(
            handler.userAgent, handler.forwardedFor, handler.isFromLoadBalancer, handler.queryParams, handler.postData,
        )
        cxt.request = KdrRequest(cxt, requestData, endpoint, handler, requestInfo)

        val inner = endpoint.handler(cxt, requestData)

        if (!handler.hasResponseBeenSent()) {
            // Write any auth cookies BEFORE sending: sending commits the (Jetty) response, and headers added
            // after commit are silently dropped. (The in-memory test client captures headers regardless of
            // order, so this ordering matters only for a real browser -- which is how the bug hid.)
            if (inner is ContentData) {
                // A download: the response *is* the file, so there is no envelope to wrap it in and nothing
                // for output-schema validation to check -- the endpoint's output schema declares binary
                // content (OpenAPI's `type: string, format: binary`), not a JSON shape.
                checkAddAuthCookies(cxt, handler)
                handler.sendContentResponse(inner, EXC.ok)
            } else {
                val envelope = buildEnvelope(cxt, handler, endpoint, requestData, inner)
                validateResponse(cxt, schema, endpoint, envelope)
                checkAddAuthCookies(cxt, handler)
                handler.sendJsonResponse(envelope, EXC.ok)
            }
        }
    }

    /** Validates the outgoing [envelope] against the endpoint's output schema, when the config flag is set. */
    @KdrPrivate
    fun validateResponse(cxt: KdrCxt, schema: KdrSchemaStore, endpoint: KdrEndpoint, envelope: Map<String, Any?>) {
        if (cxt.instanceConfig.get(ACFG.validateResponseSchema) != true || endpoint.outputSchema.isEmpty()) {
            return
        }
        val outputType = outputTypeCache.getOrPut(endpoint.collationKey) {
            val name = "${endpoint.collationKey}#output"
            parseSchemaTypes(mapOf(name to endpoint.outputSchema), schema.types)[name]
                ?: throw KdrException("Could not compile output schema for '${endpoint.collationKey}'.")
        }
        val failures = validate(outputType, envelope)
        if (failures.isNotEmpty()) {
            throw KdrException("Response for '${endpoint.collationKey}' failed output-schema validation: $failures.")
        }
    }

    @KdrPrivate
    fun buildEnvelope(
        cxt: KdrCxt,
        handler: RequestHandler,
        endpoint: KdrEndpoint,
        requestData: Map<String, Any?>,
        inner: Any?,
    ): Map<String, Any?> {
        val env = LinkedHashMap<String, Any?>()
        // The result payload -- the value under results/item/items -- captured per kind, so the content hash
        // below covers exactly it, never the volatile envelope siblings (duration/requestUri) around it.
        val payload: Any? = when (endpoint.kind) {
            // A `file` endpoint reaches here only for an upload: a download's handler returns the ContentData
            // that executeEndpoint already sent, so there is no envelope to build. What is left is the upload's
            // metadata, which is a general result and travels as one.
            EndpointKind.general, EndpointKind.file -> {
                env[EP.requestUri] = handler.logRequestUri
                env[EP.duration] = cxt.durationMs()
                (inner ?: emptyMap<String, Any?>()).also { env[EP.results] = it }
            }
            EndpointKind.item -> {
                env[EP.requestUri] = handler.logRequestUri
                env[EP.duration] = cxt.durationMs()
                inner.also { env[EP.item] = it }
            }
            EndpointKind.list -> {
                // A handler that paged itself returns a ListPage: its items are the page as-is (already limited),
                // and it carries the hasMore / numAvailable its output type declared. A plain List is the
                // unpaged case, capped here at `limit` as a backstop for a handler that returned more.
                val page = inner as? ListPage
                val limited: List<*> = if (page != null) {
                    page.items
                } else {
                    val list = (inner as? List<*>) ?: emptyList<Any?>()
                    val limit = (requestData[EP.limit] as? Number)?.toInt()
                    if (limit != null && list.size > limit) list.subList(0, limit) else list
                }
                env[EP.numItems] = limited.size
                env[EP.requestUri] = handler.logRequestUri
                env[EP.duration] = cxt.durationMs()
                env[EP.items] = limited
                // Paging metadata only when the handler reported it; an endpoint that declared these fields is
                // held to supplying them by the response validator, and one that did not must not carry them.
                if (page != null) {
                    env[EP.hasMore] = page.hasMore
                    env[EP.numAvailable] = page.numAvailable
                }
                limited
            }
        }
        // The content hash of the payload alone (issue #114): a CRC32 of its compact JSON, so an unchanged
        // payload yields an unchanged hash no matter how the volatile siblings around it move, and a client can
        // re-fetch an inexpensive config freely and act only when the hash changes.
        env[EP.contentHash] = payload.toJsonStr(compact = true).crc32Hex()
        // The hash of the served web-app bundle (issue #134), deployment-global, published into the instance
        // config by whatever serves the bundle (AppUiService); empty when this backend serves no bundle. The
        // frontend compares it to the hash injected into its own bootstrap to notice a new deployment.
        env[EP.webAppHash] = cxt.instanceConfig.get(EP.webAppHash) as? String ?: ""
        // Any handler-injected response structure travels under the off-contract `_meta` key.
        val meta = cxt.request?.responseMeta
        if (!meta.isNullOrEmpty()) {
            env[EP.meta] = meta
        }
        return env
    }

    // --- auth touchpoints (issue #67) ---

    /**
     * How this node decides **who a caller is** -- replaceable by a component that authenticates differently
     * (issue #386). Defaults to [extractSessionAuth], the session-cookie path every ordinary node uses.
     *
     * **One, deliberately, and replacing rather than adding.** A registry of contributors would be the obvious
     * generalization and is the wrong shape here: identity must have a single source. Once an edge proxies, a
     * browser holds *both* cookies on one host -- `kdrAuth` for a proxied backend, which must pass through
     * untouched, and the edge's own -- and an additive scheme would let a backend's application session confer
     * identity on the **edge itself**. That is a privilege path nobody intended, and it would look like it
     * worked. The same reason `canAccess` is the single answer to "may they": one question, one answer.
     *
     * Swapped during service init, the way `PortalService` registers itself as a content server.
     */
    var authExtractor: (KdrCxt, RequestHandler) -> Unit = ::extractSessionAuth

    /** Applies the node's [authExtractor]. Called by the dispatcher before the section gate. */
    fun extractAuth(cxt: KdrCxt, handler: RequestHandler) = authExtractor(cxt, handler)

    /**
     * Restores the acting [KdrCxt.userProfile] from the session auth cookie: decrypt it (via the instance key),
     * and if it is valid and unexpired, bind the user. Silently leaves the (system) profile in place otherwise
     * -- an absent/expired/forged cookie simply means "not logged in". Header-token auth is a follow-up.
     */
    fun extractSessionAuth(cxt: KdrCxt, handler: RequestHandler) {
        val cookie = handler.getRequestCookies()[AUTHC.authCookie] ?: return
        val node = NodeService.get(cxt)
        val decoded = UserAuthCookie.decode(node, cookie) ?: return
        if (cxt.now().toEpochMilliseconds() > decoded.expireEpochMs) return
        cxt.bindToUserProfile(
            UserProfile(
                authId = decoded.userId.toString(), userId = decoded.userId,
                client = decoded.client, roles = decoded.roles.toSet(),
            ),
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun loadProfile(cxt: KdrCxt, handler: RequestHandler) {
        // Extended profile data (the separate user-profile store) is stubbed: a different approach is coming.
    }

    /**
     * After dispatch, writes the session cookie for a fresh login and clears it on logout. On login, it also
     * records the device, issuing a long-lived device cookie if the browser has none. A verification-code
     * login (flagged via [KdrRequest.trustDevice]) additionally marks that device *familiar*, which is what
     * later permits a password login from it (issue #69).
     */
    fun checkAddAuthCookies(cxt: KdrCxt, handler: RequestHandler) {
        val req = cxt.request ?: return
        val node = NodeService.get(cxt)
        if (req.clearAuth) {
            handler.addResponseCookie(AUTHC.authCookie, "", Instant.fromEpochMilliseconds(0))
            return
        }
        if (!req.setAuthCookie) return
        val profile = cxt.userProfile
        val expireMs = cxt.now().toEpochMilliseconds() + AUTHC.sessionMillis
        val cookie = UserAuthCookie(profile.userId, profile.client, profile.roles.toList(), expireMs)
        handler.addResponseCookie(AUTHC.authCookie, cookie.encode(node), Instant.fromEpochMilliseconds(expireMs))

        // Device recording + a long-lived device cookie when the browser has none yet. An existing device
        // cookie is reused, never re-minted -- a code login just flips that same device to trusted.
        var deviceGuid = handler.getRequestCookies()[AUTHC.deviceCookie]
        if (deviceGuid == null) {
            deviceGuid = cxt.mkUniqueId()
            handler.addResponseCookie(AUTHC.deviceCookie, deviceGuid, Instant.fromEpochMilliseconds(expireMs + AUTHC.sessionMillis))
        }
        UserService.get(cxt).recordDevice(
            cxt, profile.userId, deviceGuid, handler.forwardedFor, handler.userAgent, markTrusted = req.trustDevice,
        )
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "RequestService"

        fun get(cxt: KdrCxt): RequestService = cxt.instanceConfig.get(serviceName) as? RequestService
            ?: throw KdrException("The $serviceName is not available on this node.")
    }
}
