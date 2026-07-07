package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr

/**
 * Performs endpoint requests in-process, bypassing the HTTP layer entirely, by driving a test-mode
 * [RequestHandler]. Used for tests and simulations. Ported from dn's `DnTestServletClient`.
 *
 * Lives in the main source (not test), so tests in any module can use it. Carries request headers and
 * cookies across calls, extracting `Set-Cookie` responses back into the cookie jar.
 *
 * Callers pass endpoint paths as the endpoint authors define them (e.g. `/health`); the client prepends the
 * instance's API context root ([ACFG.apiContextRoot], defaulting to [ContextRoot.kda]) so the request is
 * routed like a real one. Use the `*Raw` variants to send an unrouted path (e.g., to exercise the
 * context-root gate).
 */
class TestHttpClient(val instanceConfig: KdrInstanceConfig) {
    val curHeaders: MutableMap<String, MutableList<String>> = LinkedHashMap()
    val cookies: MutableMap<String, String> = LinkedHashMap()

    /** The context root prepended to endpoint paths: the configured API root, or [ContextRoot.kda]. */
    val contextRoot: String =
        (instanceConfig.get(ACFG.apiContextRoot) as? String) ?: ContextRoot.kda

    /** Prefixes an endpoint path with the context root, e.g. `/health` -> `/kda/health`. */
    private fun routed(endpoint: String): String = "/$contextRoot$endpoint"

    fun addHeader(header: String, value: String) {
        curHeaders.getOrPut(header.lowercase()) { mutableListOf() }.add(value)
    }

    fun setHeader(header: String, value: String) {
        curHeaders[header.lowercase()] = mutableListOf(value)
    }

    fun sendGetRequest(endpoint: String, args: Map<String, Any?>? = null): RequestHandler =
        sendGetRequestRaw(routed(endpoint), args)

    /** Sends a GET to a raw, unrouted path (no context root prepended) -- for exercising the context-root gate. */
    fun sendGetRequestRaw(path: String, args: Map<String, Any?>? = null): RequestHandler {
        val handler = RequestHandler(instanceConfig.instanceName, "GET", path, curHeaders, cookies)
        handler.queryStr = if (args != null) HttpUtil.encodeArgs(args) else ""
        execute(handler)
        return handler
    }

    fun sendJsonGetRequest(endpoint: String, args: Map<String, Any?>? = null): Map<String, Any?> =
        sendGetRequest(endpoint, args).rptResponseData?.jsonMap() ?: emptyMap()

    fun sendEditRequest(
        endpoint: String,
        args: Map<String, Any?>?,
        data: Map<String, Any?>?,
        isPut: Boolean,
    ): RequestHandler {
        val handler = RequestHandler(instanceConfig.instanceName, if (isPut) "PUT" else "POST", routed(endpoint), curHeaders, cookies)
        handler.queryStr = if (args != null) HttpUtil.encodeArgs(args) else ""
        handler.testPostData = data?.toJsonStr() ?: ""
        execute(handler)
        return handler
    }

    fun sendJsonPostRequest(endpoint: String, data: Map<String, Any?>): Map<String, Any?> =
        sendEditRequest(endpoint, null, data, isPut = false).rptResponseData?.jsonMap() ?: emptyMap()

    fun sendJsonPutRequest(endpoint: String, data: Map<String, Any?>): Map<String, Any?> =
        sendEditRequest(endpoint, null, data, isPut = true).rptResponseData?.jsonMap() ?: emptyMap()

    private fun execute(handler: RequestHandler) {
        handler.handleRequest()
        extractCookies(handler)
    }

    private fun extractCookies(handler: RequestHandler) {
        val setCookies = handler.rptResponseHeaders["set-cookie"] ?: return
        for (cookieStr in setCookies) {
            val nameValue = cookieStr.substringBefore(';').split("=", limit = 2)
            if (nameValue.size == 2) {
                cookies[nameValue[0].trim()] = nameValue[1].trim()
            }
        }
    }
}
