package com.dynamicruntime.common.http.request

/**
 * Extra information about a request when it arrived over HTTP (as opposed to a local,
 * in-process endpoint-to-endpoint call). Ported from dn's `DnRequestInfo`.
 */
class RequestInfo(
    userAgent: String?,
    forwardedFor: String?,
    /** Whether the request came from the load balancer (its user agent contains "ELB-Health"). */
    val isFromLoadBalancer: Boolean,
    val queryParams: Map<String, Any?>,
    val postData: Map<String, Any?>?,
) {
    val userAgent: String? = userAgent?.takeUnless { it.isEmpty() }
    val forwardedFor: String? = forwardedFor?.takeUnless { it.isEmpty() }

    /** Whether the request was proxied (a forwarded-for address is present). */
    val isProxied: Boolean = this.forwardedFor != null
}
