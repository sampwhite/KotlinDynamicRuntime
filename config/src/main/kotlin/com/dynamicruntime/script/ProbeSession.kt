package com.dynamicruntime.script

import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.client.KdrHttpClient
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.test.TEP
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonStr
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** The instance a probe talks to unless told otherwise. Never 7070 -- that is the developer's own server. */
const val defaultProbeUrl = "http://localhost:7071"

/**
 * One HTTP response, as a probe reads it: the status, and the envelope parsed into a map.
 *
 * Both matter and neither substitutes for the other -- a refusal is a status with an `errorMessage` and no
 * payload, which is exactly the case a scenario most often wants to report.
 */
class ProbeResponse(val statusCode: Int, val body: Map<String, Any?>, val rawBody: String) {
    /** A general endpoint's payload (`results`), or empty when the call did not return one. */
    val results: Map<String, Any?> get() = body[EP.results].toJsonMapOrEmpty()

    /** The error sentence, when this is a non-2xx envelope; null on success. */
    val errorMessage: String? get() = body[EP.errorMessage] as? String

    /** The off-contract `_meta` bag -- where a `_debug` tag's output arrives. */
    val meta: Map<String, Any?> get() = body[EP.meta].toJsonMapOrEmpty()

    val isSuccess: Boolean get() = statusCode in 200..299
}

/**
 * A single caller against a running instance: its own cookie jar, so a login sticks, and several callers can be
 * alive at once (issue #215).
 *
 * **Why this exists rather than curl.** Cookies here live in the process. Passing a cookie *file* through a
 * shell variable is how a probe comes to run anonymously without saying so: while verifying #211, that mistake
 * produced a clean-looking table of identical numbers for three different callers, which read as data and cost
 * far more to unpick than a crash would have. A wrong probe must fail, not answer plausibly -- hence
 * [becomeUser] throwing rather than returning an unauthenticated session.
 *
 * Method names deliberately echo `TestHttpClient`'s, so a scenario that earns its keep can be promoted into a
 * kotest test with mechanical edits instead of a rewrite. The one divergence is that a response carries its
 * own [ProbeResponse.statusCode], because out here every call crosses a real socket and can be refused.
 */
@Suppress("ConstPropertyName")
class ProbeSession(val label: String, val baseUrl: String = defaultProbeUrl) : AutoCloseable {
    // A cookie-keeping standalone client so a login sticks across calls. Standalone -- not the instance's
    // `fast`/`slow` clients -- because a script host has no instance config behind it (issue #420).
    private val httpClient = KdrHttpClient("probe-$label", keepCookies = true)

    /** The acting user's info once [becomeUser] has run; null while anonymous. */
    var userInfo: Map<String, Any?>? = null
        private set

    /**
     * Logs in as [email] through the test-only `becomeUser` endpoint, creating the user at [level] if it does
     * not exist, and keeps the session. [level] is a rung of the privilege ladder and applies only to a user
     * being *created* -- becoming one that already exists gets whoever is there, at whatever level they have.
     *
     * Throws when the call is refused. An unauthenticated session that reports success would make every later
     * result anonymous while looking deliberate, which is the failure this whole class is shaped against.
     */
    fun becomeUser(
        email: String,
        level: String = ROLE.user,
        capabilities: List<String> = emptyList(),
    ): Map<String, Any?> {
        val resp = sendPostRequest(
            TEP.becomeUser,
            mapOf(TEP.email to email, TEP.level to level, TEP.capabilities to capabilities),
        )
        if (!resp.isSuccess) {
            throw KdrException(
                "Could not become '$email' at level '$level' (HTTP ${resp.statusCode}): " +
                    "${resp.errorMessage ?: resp.rawBody.take(200)}. Is a test instance running at $baseUrl?",
            )
        }
        userInfo = resp.results
        return resp.results
    }

    /** A GET, returning the parsed envelope. Mirrors `TestHttpClient.sendJsonGetRequest`. */
    fun sendJsonGetRequest(path: String, args: Map<String, Any?>? = null): Map<String, Any?> =
        sendGetRequest(path, args).body

    /** A POST, returning the parsed envelope. Mirrors `TestHttpClient.sendJsonPostRequest`. */
    fun sendJsonPostRequest(path: String, data: Map<String, Any?>): Map<String, Any?> =
        sendPostRequest(path, data).body

    /** A GET, returning status as well as body -- the form to use when a refusal is a possible outcome. */
    fun sendGetRequest(path: String, args: Map<String, Any?>? = null): ProbeResponse =
        send("GET", url(path, args))

    /**
     * A DELETE, returning status as well as body. Like a GET, its input is [args] in the query string --
     * this codebase sends no DELETE body (see `HttpMethod.DELETE`), and neither does its probe.
     */
    fun sendDeleteRequest(path: String, args: Map<String, Any?>? = null): ProbeResponse =
        send("DELETE", url(path, args))

    /** A POST, returning status as well as body. */
    fun sendPostRequest(path: String, data: Map<String, Any?>): ProbeResponse =
        sendEditRequest(HttpMethod.POST, path, data)

    /**
     * A body-carrying request under [method] -- POST or PUT. The verb actually reaches the wire: `call PUT`
     * used to send a POST while printing "PUT", which is the failure mode a probe exists to prevent.
     */
    fun sendEditRequest(method: HttpMethod, path: String, data: Map<String, Any?>): ProbeResponse {
        if (method != HttpMethod.POST && method != HttpMethod.PUT) {
            throw KdrException("sendEditRequest sends a JSON body, so its method must be POST or PUT, not ${method.name}.")
        }
        return send(method.name, url(path, null), contentType = "application/json", body = data.toJsonStr())
    }

    private fun query(args: Map<String, Any?>?): String =
        args?.takeIf { it.isNotEmpty() }?.let { params ->
            "?" + params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v?.toString() ?: "")}" }
        } ?: ""

    private fun url(path: String, args: Map<String, Any?>?): String = "$baseUrl$apiRoot$path${query(args)}"

    private fun send(method: String, url: String, contentType: String? = null, body: String? = null): ProbeResponse {
        val response = try {
            httpClient.send(method, url, contentType = contentType, body = body)
        } catch (e: KdrException) {
            // A connection refusal arrives as a cause with a null message, so fall back to the type -- "(null)"
            // from the tool whose entire job is diagnosis would be its own small joke.
            val root = e.cause ?: e
            val why = root.message?.takeIf { it.isNotBlank() } ?: root::class.simpleName ?: "unknown error"
            throw KdrException("Could not reach $baseUrl -- is an instance running there? ($why)", e)
        }
        val raw = response.body
        // A non-JSON body is not a parse failure worth throwing over: an HTML error page or an empty 404 is
        // itself the answer, and the status already says so. Keep the raw text for the caller to show.
        val parsed = try {
            raw.jsonMap() ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
        return ProbeResponse(response.status, parsed, raw)
    }

    private fun encode(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

    /** Stops the underlying HTTP client and releases its threads. */
    override fun close() = httpClient.close()

    companion object {
        /** The API context root every endpoint path hangs off. */
        const val apiRoot = "/kda"
    }
}
