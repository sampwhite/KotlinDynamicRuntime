package com.dynamicruntime.webapp

import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonMap
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * The runtime's schema-catalog endpoints, under the `kda` API context root. In dev the webpack server proxies
 * `/kda` to the runtime on :7070 (see build.gradle.kts), so the calls are same-origin and need no CORS.
 */
private const val schemaBase = "/kda/schema"

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
        val query = if (namespace != null) "?${EK.namespace}=$namespace" else ""
        val results = getJson("$schemaBase/endpoints$query")[EK.results].jsonObj()
        val endpoints = results[EK.endpoints].jsonArr().map { toEndpointInfo(it.jsonObj()) }
        return Catalog(endpoints, results[SCH.dDefs].jsonObj())
    }

    /** GET a single endpoint by exact method + path, in the same shape as the full catalog. */
    suspend fun fetchEndpoint(method: String, path: String): Catalog {
        val results = getJson("$schemaBase/endpoint?${EK.method}=$method&${EK.path}=${encode(path)}")[EK.results].jsonObj()
        val endpoints = results[EK.endpoints].jsonArr().map { toEndpointInfo(it.jsonObj()) }
        return Catalog(endpoints, results[SCH.dDefs].jsonObj())
    }

    private fun toEndpointInfo(m: Map<String, Any?>): EndpointInfo = EndpointInfo(
        path = m[EK.path] as? String ?: "",
        method = m[EK.method] as? String ?: "",
        kind = m[EK.kind] as? String ?: "",
        namespace = m[EK.namespace] as? String ?: "",
        description = m[EK.description] as? String,
        inputSchema = m[EK.inputSchema].jsonObj(),
        outputSchema = m[EK.outputSchema].jsonObj(),
    )

    private suspend fun getJson(url: String): Map<String, Any?> {
        val response = browserFetch(url).await()
        if (!(response.ok as Boolean)) {
            error("GET $url failed with status ${response.status}")
        }
        // `response` is dynamic, so `response.text()` is too; cast to a typed Promise so `.await()` resolves via
        // the Kotlin coroutines extension. The kernel's JSON parser then produces plain Kotlin Map/List/values.
        val text = (response.text() as Promise<String>).await()
        return text.jsonMap() ?: emptyMap()
    }
}

private fun encode(s: String): String = js("encodeURIComponent(s)") as String

/** Null-tolerant view of a parsed-JSON value as a `Map`, via the kernel's `toJsonMap` coercion. */
private fun Any?.jsonObj(): Map<String, Any?> = if (this is Map<*, *>) toJsonMap() else emptyMap()

/** Null-tolerant view of a parsed-JSON value as a `List`. */
private fun Any?.jsonArr(): List<Any?> = if (this is List<*>) this else emptyList()
