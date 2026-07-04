package com.dynamicruntime.common.http.request

import kotlin.time.Instant

/**
 * The web request/response as the dispatcher, endpoints, and (later) the auth layer
 * see it -- an abstraction over the concrete transport so nothing above this line
 * touches Jetty directly. [RequestHandler] implements it twice over: backed by a live
 * Jetty exchange, and backed by in-memory capture for the in-process test client.
 *
 * Ported from dn's `DnServletHandler`; renamed since kd2 has no servlet layer.
 */
interface WebRequest {
    /** The request path (e.g. "/health"). */
    val target: String

    /** Originating user agent, or null. */
    val userAgent: String?

    /** `X-Forwarded-For` address, or null when not proxied. */
    val forwardedFor: String?

    fun getHeaderNames(): List<String>
    fun getRequestHeader(header: String): String?
    fun getRequestHeaders(header: String): List<String>

    /** Request cookies by name (parsed lazily). */
    fun getRequestCookies(): Map<String, String>

    fun addResponseHeader(header: String, value: String)

    /** Adds a `Set-Cookie` response header (Path=/, HttpOnly, secure when proxied), optionally with an expiry. */
    fun addResponseCookie(name: String, value: String, expire: Instant?)

    fun sendRedirect(url: String)

    fun hasResponseBeenSent(): Boolean
    fun setResponseHasBeenSent(sent: Boolean)
}
