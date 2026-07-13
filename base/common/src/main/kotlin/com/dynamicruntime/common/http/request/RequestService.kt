package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrRequest
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.EndpointKind
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.endpoint.resolveEndpointInputType
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.toJsonMap
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

    /** Section → access rules. Sections not present are treated permissively for now. */
    val sectionRulesMap: MutableMap<String, SectionRules> = HashMap()

    val anonSections: List<String> = listOf("health", "schema", "content", "portal", "site", "auth", "db")
    val userSections: List<String> = listOf("user")
    val adminSections: List<String> = listOf("node", "admin")

    @KdrPrivate
    var isInit: Boolean = false

    // Caches of compiled endpoint input/output types (parsed against the compiled schema store).
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
     * The browser bootstrap config: the live context roots keyed by focus (`{"contextRoots":{"api":"kda",
     * "content":"cp"}}`). A content server injects this into a served page (as `window.kdrCfg`) so its
     * JavaScript can build backend URLs from the configured roots rather than hardcoding them.
     */
    fun frontendConfig(): Map<String, Any?> =
        mapOf("contextRoots" to contextRootFocus.entries.associate { (root, focus) -> focus.name to root })

    override fun checkInit(cxt: KdrCxt) {
        if (isInit) {
            return
        }
        for (s in anonSections) sectionRulesMap[s] = SectionRules(s, needsLogin = false, requiredRole = null)
        for (s in userSections) sectionRulesMap[s] = SectionRules(s, needsLogin = true, requiredRole = ROLE.user)
        for (s in adminSections) sectionRulesMap[s] = SectionRules(s, needsLogin = false, requiredRole = ROLE.admin)

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

        val appPath = handler.appPath
        val method = handler.method

        handler.sectionRules = sectionRulesMap[handler.section]
        handler.decodeRequestData()
        cxt.debug = handler.debug // the request's _debug tag, if any, rides on the context (and into logs)

        // Auth is stubbed until the auth subsystem is ported.
        extractAuth(cxt, handler)

        // Real role enforcement awaits auth. For now, a section that requires a role is rejected (there is no
        // way to be authenticated yet); anonymous and unregistered sections pass through.
        val requiredRole = handler.sectionRules?.requiredRole
        if (requiredRole != null) {
            throw KdrException(
                "Request requires role '$requiredRole', but authentication is not yet implemented.",
                code = EXC.authNeeded,
            )
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

        checkAddAuthCookies(cxt, handler)
        handler.logSuccess(cxt, EXC.ok)
    }

    /** Validates the input, runs the handler, wraps the result, and sends the response. */
    fun executeEndpoint(cxt: KdrCxt, handler: RequestHandler, endpoint: KdrEndpoint) {
        val schema = cxt.getSchema()
        val inputType = inputTypeCache.getOrPut(endpoint.path) {
            resolveEndpointInputType(endpoint, schema.types)
                ?: throw KdrException("Could not compile input schema for '${endpoint.path}'.")
        }

        val data = LinkedHashMap<String, Any?>(handler.queryParams)
        handler.postData?.let { data.putAll(it) }
        // Endpoint input is a flat set of top-level fields (issue #40), so the flat HTTP input -- query params
        // and/or POST body -- validates directly, with no re-grouping. The input type is closed to undeclared
        // properties, though off-contract `_`/`$` keys remain exempt (see the validator).
        val result = coerceAndValidate(inputType, data)
        if (result.failures.isNotEmpty()) {
            throw KdrException.mkInput("Validation failure in request data: ${result.failures}.")
        }

        val requestData = result.value?.toJsonMap() ?: emptyMap()

        val requestInfo = RequestInfo(
            handler.userAgent, handler.forwardedFor, handler.isFromLoadBalancer, handler.queryParams, handler.postData,
        )
        cxt.request = KdrRequest(cxt, requestData, endpoint, handler, requestInfo)

        val inner = endpoint.handler(cxt, requestData)

        if (!handler.hasResponseBeenSent()) {
            val envelope = buildEnvelope(cxt, handler, endpoint, requestData, inner)
            validateResponse(cxt, schema, endpoint, envelope)
            handler.sendJsonResponse(envelope, EXC.ok)
        }
    }

    /** Validates the outgoing [envelope] against the endpoint's output schema, when the config flag is set. */
    @KdrPrivate
    fun validateResponse(cxt: KdrCxt, schema: KdrSchemaStore, endpoint: KdrEndpoint, envelope: Map<String, Any?>) {
        if (cxt.instanceConfig.get(ACFG.validateResponseSchema) != true || endpoint.outputSchema.isEmpty()) {
            return
        }
        val outputType = outputTypeCache.getOrPut(endpoint.path) {
            val name = "${endpoint.path}#output"
            parseSchemaTypes(mapOf(name to endpoint.outputSchema), schema.types)[name]
                ?: throw KdrException("Could not compile output schema for '${endpoint.path}'.")
        }
        val failures = validate(outputType, envelope)
        if (failures.isNotEmpty()) {
            throw KdrException("Response for '${endpoint.path}' failed output-schema validation: $failures.")
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
        when (endpoint.kind) {
            EndpointKind.general -> {
                env[EP.requestUri] = handler.logRequestUri
                env[EP.duration] = cxt.durationMs()
                env[EP.results] = inner ?: emptyMap<String, Any?>()
            }
            EndpointKind.item -> {
                env[EP.requestUri] = handler.logRequestUri
                env[EP.duration] = cxt.durationMs()
                env[EP.item] = inner
            }
            EndpointKind.list -> {
                val list = (inner as? List<*>) ?: emptyList<Any?>()
                val limit = (requestData[EP.limit] as? Number)?.toInt()
                val limited = if (limit != null && list.size > limit) list.subList(0, limit) else list
                env[EP.numItems] = limited.size
                env[EP.requestUri] = handler.logRequestUri
                env[EP.duration] = cxt.durationMs()
                env[EP.items] = limited
                // TODO: hasMore / numAvailable paging metadata once list handlers can report them.
            }
        }
        // Any handler-injected response structure travels under the off-contract `_meta` key.
        val meta = cxt.request?.responseMeta
        if (!meta.isNullOrEmpty()) {
            env[EP.meta] = meta
        }
        return env
    }

    // --- stubbed auth touchpoints (no-ops until the auth subsystem is ported) ---

    @Suppress("UNUSED_PARAMETER")
    fun extractAuth(cxt: KdrCxt, handler: RequestHandler) {
        // TODO(auth): parse the auth cookie (decrypt via NodeService) and call the auth-extraction hook.
    }

    @Suppress("UNUSED_PARAMETER")
    fun loadProfile(cxt: KdrCxt, handler: RequestHandler) {
        // TODO(auth): load the acting user's profile via the load-profile hook.
    }

    @Suppress("UNUSED_PARAMETER")
    fun checkAddAuthCookies(cxt: KdrCxt, handler: RequestHandler) {
        // TODO(auth): prepare and set refreshed auth cookies via the prep-auth-cookies hook.
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "RequestService"

        fun get(cxt: KdrCxt): RequestService? = cxt.instanceConfig.get(serviceName) as? RequestService
    }
}
