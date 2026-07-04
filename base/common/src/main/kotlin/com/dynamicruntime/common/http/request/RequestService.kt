package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.annotation.KdrPrivate
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrRequest
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.EndpointKind
import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.schema.parseSchemaTypes
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.toJsonMap
import java.util.concurrent.ConcurrentHashMap

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

    /** Context-root → security rules. Roots not present are treated permissively for now. */
    val contextRulesMap: MutableMap<String, ContextRootRules> = HashMap()

    val anonRoots: List<String> = listOf("health", "schema", "content", "portal", "site", "auth")
    val userRoots: List<String> = listOf("user")
    val adminRoots: List<String> = listOf("node", "admin")

    @KdrPrivate
    var isInit: Boolean = false

    // Cache of compiled endpoint input types (parsed against the compiled schema store).
    private val inputTypeCache = ConcurrentHashMap<String, SchType>()

    override fun checkInit(cxt: KdrCxt) {
        if (isInit) {
            return
        }
        for (root in anonRoots) contextRulesMap[root] = ContextRootRules(root, needsLogin = false, requiredRole = null)
        for (root in userRoots) contextRulesMap[root] = ContextRootRules(root, needsLogin = true, requiredRole = ROLE.user)
        for (root in adminRoots) contextRulesMap[root] = ContextRootRules(root, needsLogin = false, requiredRole = ROLE.admin)
        isInit = true
    }

    fun handleRequest(cxt: KdrCxt, handler: RequestHandler) {
        val target = handler.target
        val method = handler.method

        handler.contextRules = contextRulesMap[handler.contextRoot]
        handler.decodeRequestData()

        // Auth is stubbed until the auth subsystem is ported.
        extractAuth(cxt, handler)

        // Real role enforcement awaits auth. For now, a root that requires a role is rejected (there is no
        // way to be authenticated yet); anonymous and unregistered roots pass through.
        val requiredRole = handler.contextRules?.requiredRole
        if (requiredRole != null) {
            throw KdrException(
                "Request requires role '$requiredRole', but authentication is not yet implemented.",
                code = EXC.authNeeded,
            )
        }

        loadProfile(cxt, handler)

        // TODO: content serving (dn's DnContentService) is not ported yet.

        if (!handler.hasResponseBeenSent()) {
            val endpoint = cxt.getSchema().endpoints[target]
            if (endpoint != null && endpoint.method.name == method) {
                executeEndpoint(cxt, handler, endpoint)
            }
        }
        if (!handler.hasResponseBeenSent()) {
            throw KdrException("Path '$target' had no matching endpoint.", code = EXC.notFound)
        }

        checkAddAuthCookies(cxt, handler)
        handler.logSuccess(cxt, EXC.ok)
    }

    /** Validates the input, runs the handler, wraps the result, and sends the response. */
    fun executeEndpoint(cxt: KdrCxt, handler: RequestHandler, endpoint: KdrEndpoint) {
        val schema = cxt.getSchema()
        val inputType = inputTypeCache.getOrPut(endpoint.path) {
            val name = "${endpoint.path}#input"
            parseSchemaTypes(mapOf(name to endpoint.inputSchema), schema.types)[name]
                ?: throw KdrException("Could not compile input schema for '${endpoint.path}'.")
        }

        val data = LinkedHashMap<String, Any?>(handler.queryParams)
        handler.postData?.let { data.putAll(it) }
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
            handler.sendJsonResponse(buildEnvelope(cxt, handler, endpoint, requestData, inner), EXC.ok)
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
