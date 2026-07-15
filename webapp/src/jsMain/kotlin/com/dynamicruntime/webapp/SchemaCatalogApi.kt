package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr
import kotlinx.coroutines.await
import kotlin.js.Promise
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonListOrEmpty

/**
 * The runtime's schema-catalog endpoints, under the `kda` API context root. In dev the webpack server proxies
 * `/kda` to the runtime on :7070 (see build.gradle.kts), so the calls are same-origin and need no CORS.
 */
private const val schemaBase = "/kda/schema"

/** The API context root every runtime endpoint is served under (proxied to :7070 in dev). */
private const val apiRoot = "/kda"

/** Binding to the browser's global `fetch` (named to avoid clashing with any wrapper `fetch`). */
@JsName("fetch")
private external fun browserFetch(input: String, init: dynamic = definedExternally): Promise<dynamic>

/**
 * Fetches endpoint schema from the runtime's `/schema/endpoints` catalog and parses it into the [Catalog]
 * model. Responses are parsed with the shared kernel's [jsonMap] (the same JSON parser the backend uses), so
 * numbers arrive as the kernel's own `Long`/`Double` — matching what the kernel validator expects — instead
 * of JS doubles.
 */
object SchemaCatalogApi {
    /** GET the whole catalog: every registered endpoint's rendering plus the shared `$defs`. An optional
     *  [namespace] narrows the results. */
    suspend fun fetchCatalog(namespace: String? = null): Catalog {
        val query = if (namespace != null) "?${EI.namespace}=$namespace" else ""
        val results = getJson("$schemaBase/endpoints$query")[EP.results].toJsonMapOrEmpty()
        val endpoints = results[EI.endpoints].toJsonListOrEmpty().map { toEndpointInfo(it.toJsonMapOrEmpty()) }
        return Catalog(endpoints, results[SCH.dDefs].toJsonMapOrEmpty())
    }

    /** GET a single endpoint by exact method + path, in the same shape as the full catalog. */
    suspend fun fetchEndpoint(method: String, path: String): Catalog {
        val results = getJson("$schemaBase/endpoint?${EI.method}=$method&${EI.path}=${encode(path)}")[EP.results].toJsonMapOrEmpty()
        val endpoints = results[EI.endpoints].toJsonListOrEmpty().map { toEndpointInfo(it.toJsonMapOrEmpty()) }
        return Catalog(endpoints, results[SCH.dDefs].toJsonMapOrEmpty())
    }

    private fun toEndpointInfo(m: Map<String, Any?>): EndpointInfo = EndpointInfo(
        path = m[EI.path] as? String ?: "",
        method = m[EI.method] as? String ?: "",
        kind = m[EI.kind] as? String ?: "",
        namespace = m[EI.namespace] as? String ?: "",
        description = m[EI.description] as? String,
        inputSchema = m[EI.inputSchema].toJsonMapOrEmpty(),
        outputSchema = m[EI.outputSchema].toJsonMapOrEmpty(),
    )

    /**
     * Executes [endpoint] with the (already coerced) [body] and returns its parsed response envelope. A GET
     * carries the fields in the query string (a nested value is JSON-encoded, which the runtime's coercion
     * reparses); a POST/PUT sends them as a JSON body, serialized with the shared kernel's [toJsonStr]. A
     * non-2xx response raises the runtime's error `message`.
     */
    suspend fun invoke(endpoint: EndpointInfo, body: Map<String, Any?>): Map<String, Any?> {
        val url = apiRoot + endpoint.path
        val response = if (endpoint.method == "GET") {
            browserFetch(url + queryString(body)).await()
        } else {
            val init: dynamic = js("({})")
            init.method = endpoint.method
            val headers: dynamic = js("({})")
            headers["Content-Type"] = "application/json"
            init.headers = headers
            init.body = body.toJsonStr(compact = true)
            browserFetch(url, init).await()
        }
        val map = readJson(response)
        if (!(response.ok as Boolean)) {
            error(map["message"] as? String ?: "${endpoint.method} $url failed with status ${response.status}")
        }
        return map
    }

    private suspend fun getJson(url: String): Map<String, Any?> {
        val response = browserFetch(url).await()
        if (!(response.ok as Boolean)) {
            error("GET $url failed with status ${response.status}")
        }
        return readJson(response)
    }

    /** Reads a fetch [response]'s body as JSON via the kernel parser (plain Kotlin Map/List/values). */
    private suspend fun readJson(response: dynamic): Map<String, Any?> {
        // `response` is dynamic, so `response.text()` is too; cast to a typed Promise so `.await()` resolves via
        // the Kotlin coroutines extension. The kernel's JSON parser then produces plain Kotlin Map/List/values.
        val text = (response.text() as Promise<String>).await()
        return text.jsonMap() ?: emptyMap()
    }

    /** Serializes [body] as a query string; scalars are stringified, a nested map/list is JSON-encoded. */
    private fun queryString(body: Map<String, Any?>): String {
        if (body.isEmpty()) {
            return ""
        }
        return "?" + body.entries.joinToString("&") { (k, v) ->
            val s = when (v) {
                null -> ""
                is Map<*, *>, is List<*> -> v.toJsonStr(compact = true)
                else -> v.toString()
            }
            "${encode(k)}=${encode(s)}"
        }
    }
}

/** Percent-encodes a query value via the browser's global `encodeURIComponent`. */
private fun encode(s: String): String = encodeURIComponent(s)

/** The browser's global `encodeURIComponent`, declared so [encode] actually passes its argument (rather than
 *  relying on a `js(...)` string capturing the local by name). */
private external fun encodeURIComponent(s: String): String


