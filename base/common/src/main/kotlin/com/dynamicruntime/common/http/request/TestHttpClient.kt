package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr

/**
 * Performs endpoint requests in-process, bypassing the HTTP layer entirely, by driving a test-mode
 * [RequestHandler]. Used for tests and simulations. Ported from dn's `DnTestServletClient`.
 *
 * Lives in main source (not test) so tests in any module can use it. Carries request headers and
 * cookies across calls, extracting `Set-Cookie` responses back into the cookie jar.
 */
class TestHttpClient(val instanceConfig: KdrInstanceConfig) {
    val curHeaders: MutableMap<String, MutableList<String>> = LinkedHashMap()
    val cookies: MutableMap<String, String> = LinkedHashMap()

    fun addHeader(header: String, value: String) {
        curHeaders.getOrPut(header.lowercase()) { mutableListOf() }.add(value)
    }

    fun setHeader(header: String, value: String) {
        curHeaders[header.lowercase()] = mutableListOf(value)
    }

    fun sendGetRequest(endpoint: String, args: Map<String, Any?>? = null): RequestHandler {
        val handler = RequestHandler(instanceConfig.instanceName, "GET", endpoint, curHeaders, cookies)
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
        val handler = RequestHandler(instanceConfig.instanceName, if (isPut) "PUT" else "POST", endpoint, curHeaders, cookies)
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
