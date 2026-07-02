package com.dynamicruntime.common.context

/**
 * The incoming request being processed within a [KdrCxt]. A stub for now (as [UserProfile] is) — it will
 * grow to carry the endpoint being applied, the raw web request, and processing flags as endpoint
 * execution is built out. A context is "in a request" exactly when [KdrCxt.request] is non-null. The
 * mutable response accumulator will be a separate `KdrResponse`, added when execution lands.
 *
 * Named with the `Kdr` prefix per the naming guide: bare `Request` collides badly with web/servlet types.
 */
class KdrRequest(
    /**
     * The context the request began in (the original parent; sub contexts inherit the same request). Lets
     * code deep in the call stack that holds only a request reach back to the originating context — e.g.
     * for interval timing in logs.
     */
    val cxt: KdrCxt,
    /**
     * Schema-validated and coerced request data — the meat of the request. This is the same reference the
     * endpoint handler receives directly as a parameter, exposed here for code deep in the call stack.
     */
    val requestData: Map<String, Any?>,
)
