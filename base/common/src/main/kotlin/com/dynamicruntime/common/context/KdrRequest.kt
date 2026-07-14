package com.dynamicruntime.common.context

import com.dynamicruntime.common.endpoint.KdrEndpoint
import com.dynamicruntime.common.http.request.RequestInfo
import com.dynamicruntime.common.http.request.WebRequest

/**
 * The incoming request being processed within a [KdrCxt]. A context is "in a request" exactly when
 * [KdrCxt.request] is non-null. Carries what code deep in the call stack may need to reach back to
 * without threading it through every call. A mutable response accumulator will be a separate
 * `KdrResponse`, added when richer response handling lands.
 *
 * Named with the `Kdr` prefix per the naming guide: bare `Request` collides badly with web/servlet types.
 */
class KdrRequest(
    /**
     * The context the request began in (the original parent; sub contexts inherit the same request). Let's
     * code deep in the call stack that holds only a request reach back to the originating context — e.g.,
     * for interval timing in logs.
     */
    val cxt: KdrCxt,
    /**
     * Schema-validated and coerced request data — the meat of the request. This is the same reference the
     * endpoint handler receives directly as a parameter, exposed here for code deep in the call stack.
     */
    val requestData: Map<String, Any?>,
    /** The endpoint being applied when the request is executing one. */
    val endpoint: KdrEndpoint? = null,
    /** The underlying web request/response, when the request arrived over the wire (null for local calls). */
    val webRequest: WebRequest? = null,
    /** HTTP-level metadata (user agent, forwarded-for, …), present when the request came over HTTP. */
    val requestInfo: RequestInfo? = null,
) {
    /**
     * Extra response structure a handler wants to return alongside the primary payload. When non-empty it is
     * emitted under the off-contract `_meta` key of the response envelope. A minimal precursor to a full
     * `KdrResponse`; lets a handler (e.g. `/schema/sample` under `explainInput`) attach diagnostic data.
     */
    val responseMeta: MutableMap<String, Any?> = mutableMapOf()

    /**
     * Set true by a login handler once it has authenticated the user (and set [KdrCxt.userProfile]), to have
     * the post-dispatch auth hook write the session auth cookie (and record/refresh the device cookie). A
     * request whose profile was merely *restored* from an existing cookie leaves this false, so the cookie is
     * not needlessly rewritten. (Auth handling is minimal until a full `KdrResponse` lands; issue #67.)
     */
    var setAuthCookie: Boolean = false

    /** Set true by the logout handler to have the auth hook clear the session auth cookie. */
    var clearAuth: Boolean = false

    /**
     * Set true by a **verification-code** login handler to have the auth hook mark the current device
     * (the `kdrDevice` cookie) *familiar* for this user, so a later password login is permitted from it.
     * A password login leaves this false: passwords ride existing trust but never grant it (issue #69).
     */
    var trustDevice: Boolean = false
}
