package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.util.formatCookieDate
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.Callback
import java.nio.ByteBuffer
import kotlin.time.Instant

/**
 * Processes a single request and produces its response, implementing [WebRequest] so the
 * dispatcher/endpoints see a transport-neutral view. Dual-mode, ported from dn's
 * `DnRequestHandler`:
 *  - **Jetty mode** — backed by a live Jetty 12 [Request]/[Response]/[Callback].
 *  - **Test mode** — backed by in-memory `rpt*` capture fields, driven by [TestHttpClient].
 *
 * Every method routes on whether a Jetty response is present, so the same request logic
 * serves both real HTTP and in-process endpoint tests.
 */
class RequestHandler : WebRequest {
    val instanceName: String

    private val jettyRequest: Request?
    private val jettyResponse: Response?
    private val jettyCallback: Callback?

    override val target: String
    val contextRoot: String
    val subTarget: String
    val method: String
    val uri: String
    var queryStr: String?
    val contentType: String

    override var userAgent: String? = null
    override var forwardedFor: String? = null
    var isFromLoadBalancer: Boolean = false

    var queryParams: MutableMap<String, Any?> = LinkedHashMap()
    var postData: MutableMap<String, Any?>? = null
    var logRequestUri: String = ""

    /** Security rules for the request's context root; filled in by the dispatcher. */
    var contextRules: ContextRootRules? = null

    private var sentResponse: Boolean = false

    // Test-mode input body + captured response.
    var testPostData: String? = null
    private val testHeaders: Map<String, List<String>>
    private var cookies: MutableMap<String, String>?

    var rptStatusCode: Int = 0
    var rptResponseMimeType: String? = null
    val rptResponseHeaders: MutableMap<String, MutableList<String>> = LinkedHashMap()
    var rptResponseData: String? = null

    /** The context created to process this request; captured for tests. */
    var createdCxt: KdrCxt? = null

    /** Jetty-mode constructor. */
    constructor(instanceName: String, request: Request, response: Response, callback: Callback) {
        this.instanceName = instanceName
        this.jettyRequest = request
        this.jettyResponse = response
        this.jettyCallback = callback
        this.testHeaders = emptyMap()
        this.cookies = null

        this.target = Request.getPathInContext(request)
        val (root, sub) = parsePath(target)
        this.contextRoot = root
        this.subTarget = sub
        this.method = request.method
        this.uri = request.httpURI.path
        this.queryStr = request.httpURI.query
        val ct = request.headers.get("Content-Type")
        this.contentType = if (ct.isNullOrEmpty()) "application/none" else ct
        this.forwardedFor = getRequestHeader("X-Forwarded-For").takeUnless { it.isNullOrEmpty() }
        computeLogRequestUri()
    }

    /** Test-mode constructor (no HTTP). */
    constructor(
        instanceName: String,
        method: String,
        target: String,
        headers: Map<String, List<String>>,
        cookies: MutableMap<String, String>,
    ) {
        this.instanceName = instanceName
        this.jettyRequest = null
        this.jettyResponse = null
        this.jettyCallback = null

        this.target = target
        val (root, sub) = parsePath(target)
        this.contextRoot = root
        this.subTarget = sub
        this.method = method
        this.uri = target
        this.queryStr = ""
        this.testHeaders = headers.entries.associate { it.key.lowercase() to it.value }
        this.cookies = cookies
        val ct = getRequestHeader("content-type")
        this.contentType = ct ?: "application/json"
        this.forwardedFor = getRequestHeader("X-Forwarded-For").takeUnless { it.isNullOrEmpty() }
        computeLogRequestUri()
    }

    private fun computeLogRequestUri() {
        logRequestUri = "$method:$uri" + (queryStr?.let { if (it.isNotEmpty()) "?$it" else "" } ?: "")
    }

    /** Creates the request context, finds the dispatcher, and runs the request; errors become JSON responses. */
    fun handleRequest() {
        var cxt: KdrCxt? = null
        try {
            val config = InstanceRegistry.getOrCreateInstanceConfig(instanceName)
            cxt = InstanceRegistry.createCxt("request", config)
            cxt.forwardedFor = forwardedFor
            createdCxt = cxt
            val service = RequestService.get(cxt)
                ?: throw KdrException("This node cannot handle endpoint requests.", code = EXC.notSupported)
            service.handleRequest(cxt, this)
        } catch (t: Throwable) {
            handleException(cxt, t)
        }
    }

    /** Parses query params and (for JSON) the request body into [queryParams]/[postData]. */
    fun decodeRequestData() {
        userAgent = getRequestHeader("User-Agent")
        isFromLoadBalancer = userAgent?.contains("ELB-Health") == true

        val qs = queryStr
        if (qs != null && qs.length > 2048) {
            throw KdrException("Query string length ${qs.length} exceeds the maximum of 2048.")
        }
        queryParams = HttpUtil.parseQuery(qs).toMutableMap()

        if (contentType.startsWith("application/json") && postData == null) {
            val body = readBody()
            if (body.length > 16000) {
                throw KdrException("JSON body of size ${body.length} exceeds the maximum of 16000.")
            }
            if (body.isNotBlank()) {
                postData = body.jsonMap()
            }
        }
    }

    private fun readBody(): String {
        val req = jettyRequest
        return if (req != null) Content.Source.asString(req) else (testPostData ?: "")
    }

    fun logSuccess(cxt: KdrCxt, code: Int) {
        LogRequest.debug(cxt, "$code $logRequestUri (${cxt.durationMs()} ms)")
    }

    private fun handleException(cxt: KdrCxt?, t: Throwable) {
        val kdrE = t as? KdrException
        val code = kdrE?.code ?: EXC.internalError
        val message = kdrE?.fullMessage() ?: (t.message ?: "Internal error.")
        LogRequest.error(cxt, "Error for request $logRequestUri.", t)
        try {
            if (!sentResponse) {
                sendJsonResponse(
                    linkedMapOf("errorCode" to code, "errorMessage" to message, EP.requestUri to logRequestUri),
                    code,
                )
            } else {
                jettyCallback?.succeeded()
            }
        } catch (inner: Throwable) {
            jettyCallback?.failed(inner)
        }
    }

    // --- response writing (Jetty or rpt* capture) ---------------------------

    fun sendJsonResponse(data: Map<String, Any?>, code: Int) =
        sendStringResponse(data.toJsonStr(), code, "application/json")

    fun sendStringResponse(body: String, code: Int, mimeType: String) {
        setStatusCode(code)
        setResponseContentType(mimeType)
        sentResponse = true
        rptResponseData = body
        val resp = jettyResponse
        if (resp != null) {
            Content.Sink.write(resp, true, body, jettyCallback)
        }
    }

    fun setStatusCode(code: Int) {
        jettyResponse?.status = code
        rptStatusCode = code
    }

    fun setResponseContentType(mimeType: String) {
        jettyResponse?.headers?.put("Content-Type", mimeType)
        rptResponseMimeType = mimeType
    }

    fun setResponseHeader(header: String, value: String) {
        jettyResponse?.headers?.put(header, value)
        rptResponseHeaders[header.lowercase()] = mutableListOf(value)
    }

    override fun addResponseHeader(header: String, value: String) {
        jettyResponse?.headers?.add(header, value)
        rptResponseHeaders.getOrPut(header.lowercase()) { mutableListOf() }.add(value)
    }

    override fun addResponseCookie(name: String, value: String, expire: Instant?) {
        val parts = mutableListOf("$name=$value")
        if (expire != null) {
            parts.add("Expires=${expire.formatCookieDate()}")
        }
        parts.add("Path=/")
        if (forwardedFor != null && forwardedFor != "127.0.0.1") {
            parts.add("secure")
        }
        parts.add("HttpOnly")
        addResponseHeader("Set-Cookie", parts.joinToString("; "))
    }

    override fun sendRedirect(url: String) {
        setStatusCode(303)
        addResponseHeader("Location", url)
        sentResponse = true
        jettyResponse?.write(true, ByteBuffer.allocate(0), jettyCallback)
    }

    // --- request reads (Jetty or test maps) ---------------------------------

    override fun getHeaderNames(): List<String> {
        val req = jettyRequest
        return if (req != null) req.headers.fieldNamesCollection.toList() else testHeaders.keys.toList()
    }

    override fun getRequestHeader(header: String): String? {
        val req = jettyRequest
        return if (req != null) req.headers.get(header) else getRequestHeaders(header).firstOrNull()
    }

    override fun getRequestHeaders(header: String): List<String> {
        val req = jettyRequest
        return if (req != null) req.headers.getValuesList(header) else testHeaders[header.lowercase()] ?: emptyList()
    }

    override fun getRequestCookies(): Map<String, String> {
        var c = cookies
        if (c == null) {
            c = LinkedHashMap()
            val req = jettyRequest
            if (req != null) {
                for (cookie in Request.getCookies(req)) {
                    val v = cookie.value
                    if (!v.isNullOrEmpty()) {
                        c[cookie.name] = v
                    }
                }
            }
            cookies = c
        }
        return c
    }

    override fun hasResponseBeenSent(): Boolean = sentResponse

    override fun setResponseHasBeenSent(sent: Boolean) {
        sentResponse = sent
    }

    companion object {
        /** Splits a target path into its context root (first segment) and sub-target (remainder). */
        fun parsePath(target: String): Pair<String, String> {
            if (target.length <= 1) {
                return "" to ""
            }
            val idx = target.indexOf('/', 1)
            return if (idx < 0) target.substring(1) to "" else target.substring(1, idx) to target.substring(idx + 1)
        }
    }
}
