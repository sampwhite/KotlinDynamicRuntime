package com.dynamicruntime.common.http.request

/**
 * Well-known context roots: the leading path segment that binds a class of traffic to a slice of
 * functionality (API endpoints today; content serving later). Each kind is configured under its own key
 * (e.g. `ACFG.apiContextRoot`, defaulting to [kda]); a request whose leading segment matches no configured
 * root is fast-failed with a short 404. More roots will be added over time.
 */
@Suppress("ConstPropertyName")
object ContextRoot {
    /** The default API context root -- "Kotlin DynamicRuntime API". */
    const val kda = "kda"
}
