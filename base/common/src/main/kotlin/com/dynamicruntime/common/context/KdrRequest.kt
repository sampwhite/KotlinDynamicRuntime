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
}
