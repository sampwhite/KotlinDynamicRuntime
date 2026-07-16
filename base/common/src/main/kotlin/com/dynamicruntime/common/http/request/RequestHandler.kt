package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.util.formatCookieDate
import com.dynamicruntime.common.util.isVariableName
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.splitComma
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

    /** The context root: the leading path segment (e.g. `kda` in `/kda/user/profile`). The dispatcher gates
     *  on this before decoding anything -- an unknown root is a short 404. */
    val contextRoot: String

    /** Everything after the context root, without a leading slash (e.g. `user/profile`). */
    val subTarget: String

    /** The application path used to look up an endpoint: the request path with the context root stripped
     *  (e.g. `/user/profile`, or `/` when nothing followed the context root). */
    val appPath: String get() = if (subTarget.isEmpty()) "/" else "/$subTarget"

    /** The section: the first path segment after the context root (e.g. `user`), used to select the
     *  [SectionRules] access rules. */
    val section: String get() = subTarget.substringBefore('/')
    val method: String
    val uri: String
    var queryStr: String?
    val contentType: String

    override var userAgent: String? = null
    override var forwardedFor: String? = null
    var isFromLoadBalancer: Boolean = false

    var queryParams: MutableMap<String, Any?> = LinkedHashMap()
    var postData: MutableMap<String, Any?>? = null

    /** Validated `_debug` value (a comma-separated list of variable names), or null; assigned to [KdrCxt.debug]. */
    var debug: String? = null
    var logRequestUri: String = ""

    /** Access rules for the request's section; filled in by the dispatcher. */
    var sectionRules: SectionRules? = null

    /** The focus of the request's context root (api / content / …); set by the dispatcher after the gate. */
    var focus: ContextFocus? = null

    private var sentResponse: Boolean = false

    // Test-mode input body + captured response.
    var testPostData: String? = null
    private val testHeaders: Map<String, List<String>>
    private var cookies: MutableMap<String, String>?

    var rptStatusCode: Int = 0
    var rptResponseMimeType: String? = null
    val rptResponseHeaders: MutableMap<String, MutableList<String>> = LinkedHashMap()

    /** The captured body of a *text* response ([sendStringResponse]); null when the response was binary. */
    var rptResponseData: String? = null

    /** The captured body of a *binary* response ([sendBytesResponse]); null when the response was text. A
     *  response sets exactly one of this and [rptResponseData] -- there is no honest String for arbitrary
     *  bytes, so a binary body is never also offered as one. */
    var rptResponseBytes: ByteArray? = null

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

        // Extract the off-contract `_debug` tag (query or body): <= 40 chars, comma-separated variable names.
        val debugRaw = (queryParams[EP.debug] ?: postData?.get(EP.debug)) as? String
        if (debugRaw != null) {
            if (debugRaw.length > 40) {
                throw KdrException.mkInput("_debug value exceeds the maximum of 40 characters.")
            }
            for (name in debugRaw.splitComma()) {
                if (!name.isVariableName()) {
                    throw KdrException.mkInput("_debug entry '$name' is not a valid variable name.")
                }
            }
            debug = debugRaw
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
                    linkedMapOf(EP.errorCode to code, EP.errorMessage to message, EP.requestUri to logRequestUri),
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

    /**
     * Rejects a change to a response that has already gone out. Once the body is written the exchange is
     * committed: a status, a header, a `Set-Cookie` set afterwards reaches nobody. Jetty drops it silently and
     * the in-process test client never notices at all -- it captures headers into a map, order-independently,
     * so a test can pass on a response a browser would never receive. That is not hypothetical: it is exactly
     * how the auth session cookie was lost (issue #81), found only by driving a real browser.
     *
     * So this is deliberately loud. [what] names the attempted change, since by the time it is caught the
     * stack says only "somebody touched the response too late".
     *
     * Reading [hasResponseBeenSent] first, and doing nothing when it is true, remains the correct way to hand
     * off to whoever already responded -- this guards the *change*, not the question.
     */
    private fun requireNotSent(what: String) {
        if (sentResponse) {
            // The default code (an internal error) is the honest one: this is a bug in the request's ordering,
            // not a condition a caller can do anything about.
            throw KdrException(
                "Cannot $what for $logRequestUri: the response has already been sent, so anything set now " +
                    "would be dropped.",
            )
        }
    }

    fun sendJsonResponse(data: Map<String, Any?>, code: Int) =
        sendStringResponse(data.toJsonStr(), code, "application/json")

    /**
     * Sends an abbreviated 404 for a request that falls outside every known context root -- almost always a
     * hostile probe. Deliberately terse (a bare status and a short body, no protocol envelope) and reached
     * before any request decoding, so probing costs the server as little as possible.
     */
    fun sendShortNotFound() = sendStringResponse("Not Found", EXC.notFound, "text/plain")

    /**
     * Sends a friendly HTML 404 for a request under a known content context root that no [ContentServer]
     * claimed -- a legitimate browser at a wrong content address, not a probe (so a readable page, not the
     * terse [sendShortNotFound]).
     */
    fun sendFriendlyNotFound() = sendStringResponse(
        "<!doctype html><meta charset=\"utf-8\"><title>Not Found</title>" +
            "<body style=\"font:15px system-ui,sans-serif;margin:40px;color:#1c2126\">" +
            "<h1>404 &mdash; Not Found</h1><p>No content is served at this address.</p></body>",
        EXC.notFound,
        "text/html; charset=utf-8",
    )

    /**
     * Sends [content] -- the content itself and everything about how to handle it. The one response path for a
     * body; the [sendStringResponse]/[sendBytesResponse] overloads below are shorthands onto it for callers
     * who have nothing to say beyond a MIME type.
     *
     * Text and bytes are written differently, and that is not a detail: [ContentData.text] goes out as UTF-8,
     * while [ContentData.bytes] goes straight to a [ByteBuffer] -- as [sendRedirect] already does for its
     * empty body -- because routing arbitrary bytes through a String silently corrupts them (every byte that
     * is not valid UTF-8 decodes to U+FFFD and re-encodes as three different bytes). Which of the two applies
     * is [ContentData.isBinary]'s answer, settled by whoever built the content and knew.
     *
     * In test mode the body is captured in [rptResponseBytes] or [rptResponseData] -- whichever matches the
     * content; the other stays null.
     */
    fun sendContentResponse(content: ContentData, code: Int) {
        requireNotSent("send a second response")
        setStatusCode(code)
        setResponseContentType(content.mimeType)
        // Null means the header adds nothing (plain inline content), so it is left off entirely.
        content.contentDispositionHeader()?.let { setResponseHeader(ContentData.contentDispositionKey, it) }
        sentResponse = true
        if (content.isBinary) {
            val bytes = content.bytes ?: ByteArray(0)
            rptResponseBytes = bytes
            jettyResponse?.write(true, ByteBuffer.wrap(bytes), jettyCallback)
        } else {
            val body = content.text ?: ""
            rptResponseData = body
            val resp = jettyResponse
            if (resp != null) {
                Content.Sink.write(resp, true, body, jettyCallback)
            }
        }
    }

    /** Sends a text response body, written as UTF-8. For bytes that are not text, use [sendBytesResponse]. */
    fun sendStringResponse(body: String, code: Int, mimeType: String) =
        sendContentResponse(ContentData(body, mimeType), code)

    /**
     * Sends a **binary** response body -- an image, a font, an archive: any content whose bytes are not text.
     * The counterpart of [sendStringResponse], and not a convenience over it -- see [sendContentResponse] for
     * why the two cannot share a path.
     */
    fun sendBytesResponse(body: ByteArray, code: Int, mimeType: String) =
        sendContentResponse(ContentData(body, mimeType), code)

    fun setStatusCode(code: Int) {
        requireNotSent("set the status to $code")
        jettyResponse?.status = code
        rptStatusCode = code
    }

    fun setResponseContentType(mimeType: String) {
        requireNotSent("set the content type to $mimeType")
        jettyResponse?.headers?.put("Content-Type", mimeType)
        rptResponseMimeType = mimeType
    }

    fun setResponseHeader(header: String, value: String) {
        requireNotSent("set the '$header' header")
        jettyResponse?.headers?.put(header, value)
        rptResponseHeaders[header.lowercase()] = mutableListOf(value)
    }

    override fun addResponseHeader(header: String, value: String) {
        requireNotSent("add a '$header' header")
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
        requireNotSent("redirect to $url")
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
