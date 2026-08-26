package com.dynamicruntime.common.http.client

import com.dynamicruntime.common.exception.ACT
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.exception.SRC
import org.eclipse.jetty.client.ContentResponse
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.client.StringRequestContent
import org.eclipse.jetty.http.HttpCookieStore
import org.eclipse.jetty.util.thread.QueuedThreadPool
import java.util.concurrent.TimeUnit

/** Timeout profiles for the two named outbound clients (issue #420). */
@Suppress("ConstPropertyName")
object HTTPC {
    // `fast` -- the default. Nearly every outbound call we make should be answered promptly.
    const val fastConnectMs = 5_000L
    const val fastRequestMs = 30_000L
    const val fastIdleMs = 30_000L

    // `slow` -- chosen explicitly, for a service known to take minutes.
    const val slowConnectMs = 10_000L
    const val slowRequestMs = 300_000L
    const val slowIdleMs = 60_000L
}

/** A completed outbound call: the HTTP status and the response body. Jetty stays hidden behind this. */
class KdrHttpResponse(val status: Int, val body: String) {
    /** Whether the status is a 2xx. A non-2xx is *returned*, not thrown -- the status is itself the answer. */
    val isSuccess: Boolean get() = status in 200..299
}

/**
 * The one outbound HTTP client (issue #420): a thin, Jetty-hiding layer over `org.eclipse.jetty.client.HttpClient`,
 * so a call site names a method, a URL, headers and a body and gets back a [KdrHttpResponse] -- and never sees a
 * Jetty type, the way `KdrLogger` hides log4j2.
 *
 * **The underlying Jetty client is started lazily, on the first request.** Starting one spins up a thread pool
 * and a scheduler, and the vast majority of instances (every test that makes no outbound call) should not pay
 * for that. So constructing a `KdrHttpClient` is cheap; the threads appear only when someone actually calls out.
 * The pool is a **daemon** pool, so an unclosed client cannot by itself keep the JVM alive (which is what a
 * short-lived script host such as `ProbeSession` relies on); [close] stops it cleanly when there is an owner to
 * do so.
 *
 * Two callers exist: the instance's `fast`/`slow` clients (see `OutboundHttpService`), whose cookie store is
 * **empty** so one caller's cookies never leak to another; and a standalone client with [keepCookies] on, which
 * is how `ProbeSession` keeps a login across calls without an instance config behind it.
 */
class KdrHttpClient(
    private val name: String,
    private val connectTimeoutMs: Long = HTTPC.fastConnectMs,
    private val requestTimeoutMs: Long = HTTPC.fastRequestMs,
    private val idleTimeoutMs: Long = HTTPC.fastIdleMs,
    private val keepCookies: Boolean = false,
) : AutoCloseable {
    // Read lock-free once started; the lock is taken only to start it, so several first-callers cannot each
    // spin up a Jetty client. Null before first use and after close.
    @Volatile
    private var jetty: HttpClient? = null

    // Terminal: once closed, a further request must fail rather than silently start a fresh, unowned client
    // (whose threads nothing would then stop). Distinct from `jetty == null`, which also means "not yet started".
    @Volatile
    private var closed = false

    private fun client(): HttpClient {
        jetty?.let { return it }
        synchronized(this) {
            jetty?.let { return it }
            if (closed) {
                throw KdrException("This outbound HTTP client ('$name') has been closed.")
            }
            val client = HttpClient()
            val pool = QueuedThreadPool()
            pool.setName("kdr-http-$name")
            pool.setDaemon(true)
            client.executor = pool
            client.connectTimeout = connectTimeoutMs
            client.idleTimeout = idleTimeoutMs
            // A caller keeps its own cookies (ProbeSession); a shared client keeps none, so no cross-caller leak.
            client.httpCookieStore = if (keepCookies) HttpCookieStore.Default() else HttpCookieStore.Empty()
            try {
                client.start()
            } catch (e: Exception) {
                throw KdrException(
                    "Could not start the '$name' outbound HTTP client.", e, EXC.internalError, SRC.network, ACT.io,
                )
            }
            // After start: `start()` repopulates the default protocol handlers, so clear them here. We want the
            // response the server actually sent -- Jetty otherwise intercepts a 401 to auto-negotiate
            // authentication (which, with none configured, becomes an exception rather than a status) and
            // silently follows redirects, but a caller that asked for the status wants the status. The client is
            // not yet published to other threads, so this is safe.
            client.protocolHandlers.clear()
            jetty = client
            return client
        }
    }

    /** A GET, returning the response. A non-2xx comes back on the response rather than throwing. */
    fun get(url: String, headers: Map<String, String> = emptyMap()): KdrHttpResponse =
        send("GET", url, headers)

    /** A POST carrying [body] as [contentType]. */
    fun post(url: String, contentType: String, body: String, headers: Map<String, String> = emptyMap()): KdrHttpResponse =
        send("POST", url, headers, contentType, body)

    /**
     * The general form: any [method], with optional [headers] and an optional [body] sent as [contentType].
     * Throws [KdrException] only when the call could not complete (connect refused, timeout) -- an HTTP error
     * status is returned on the [KdrHttpResponse], because the status is the answer the caller asked for.
     */
    fun send(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        contentType: String? = null,
        body: String? = null,
    ): KdrHttpResponse {
        // Resolve (and lazily start) the client outside the try, so a closed-client or start failure surfaces
        // as itself rather than being re-wrapped below as a transport failure.
        val jettyClient = client()
        val response: ContentResponse = try {
            val request = jettyClient.newRequest(url)
                .method(method)
                .timeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            if (headers.isNotEmpty()) {
                request.headers { fields -> headers.forEach { (k, v) -> fields.put(k, v) } }
            }
            if (body != null) {
                request.body(StringRequestContent(contentType ?: "text/plain", body))
            }
            request.send()
        } catch (e: Exception) {
            throw KdrException(
                "Could not complete the $method request to '$url'.", e, EXC.internalError, SRC.network, ACT.io,
            )
        }
        return KdrHttpResponse(response.status, response.contentAsString ?: "")
    }

    /**
     * Stops the Jetty client and releases its threads. Terminal and idempotent: safe to call when the client
     * was never started, and a later request throws rather than reviving a closed client.
     */
    override fun close() {
        synchronized(this) {
            closed = true
            jetty?.let { runCatching { it.stop() } }
            jetty = null
        }
    }
}
