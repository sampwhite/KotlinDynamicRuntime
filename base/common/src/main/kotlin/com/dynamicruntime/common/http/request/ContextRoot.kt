package com.dynamicruntime.common.http.request

/**
 * Well-known context roots: the leading path segment that binds a class of traffic to a slice of
 * functionality. Each kind is configured under its own key ([com.dynamicruntime.common.context.ACFG.apiContextRoot],
 * [com.dynamicruntime.common.context.ACFG.contentContextRoot]) and maps to a [ContextFocus]; a request whose
 * leading segment matches no configured root is fast-failed with a short 404. More roots will be added over time.
 */
@Suppress("ConstPropertyName")
object ContextRoot {
    /** The default API context root -- "Kotlin DynamicRuntime API". */
    const val kda = "kda"

    /** The default content context root -- "content port" (serves HTML/static content, e.g. the portal). */
    const val cp = "cp"
}

/**
 * The kind of functionality a context root targets, which decides how the dispatcher handles a request under
 * it: [api] routes to JSON endpoints; every other focus routes to [ContentServer]s (with a friendly 404 when
 * none serves it). The value for the matched root is set on the request ([RequestHandler.focus]) so content
 * servers can decide whether a given request is theirs to serve.
 */
@Suppress("EnumEntryName")
enum class ContextFocus { api, content }
