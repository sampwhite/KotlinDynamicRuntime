package com.dynamicruntime.webapp

import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * The runtime's schema-catalog endpoints, under the `kda` API context root. In dev the webpack server proxies
 * `/kda` to the runtime on :7070 (see build.gradle.kts), so the calls are same-origin and need no CORS.
 */
private const val schemaBase = "/kda/schema"

/** Binding to the browser's global `fetch` (named to avoid clashing with any wrapper `fetch`); the catalog is
 *  plain JSON, so the webapp needs no HTTP-client library. */
@JsName("fetch")
private external fun browserFetch(input: String, init: dynamic = definedExternally): Promise<dynamic>

/**
 * Fetches endpoint schema from the runtime's `/schema/endpoints` catalog and parses it into the pure-Kotlin
 * [Catalog] model the display engine renders. This is the general replacement for the old bespoke Todo client:
 * every endpoint (Todo included) is discovered and rendered from the same catalog form.
 */
object SchemaCatalogApi {
    /** GET the whole catalog: every registered endpoint's rendering plus the shared `$defs`. An optional
     *  [namespace] narrows the results (the endpoint's `namespace` query filter). */
    suspend fun fetchCatalog(namespace: String? = null): Catalog {
        val query = if (namespace != null) "?${SK.namespace}=$namespace" else ""
        val results = getJson("$schemaBase/endpoints$query")["results"].asMap()
        val endpoints = results[SK.endpoints].asList().map { toEndpointInfo(it.asMap()) }
        return Catalog(endpoints, results[SK.ref_defs].asMap())
    }

    /** GET a single endpoint by exact method + path, in the same shape as the full catalog (a one- or
     *  zero-element `endpoints` list plus the shared `$defs`). */
    suspend fun fetchEndpoint(method: String, path: String): Catalog {
        val results = getJson("$schemaBase/endpoint?${SK.method}=$method&${SK.path}=${encode(path)}")["results"].asMap()
        val endpoints = results[SK.endpoints].asList().map { toEndpointInfo(it.asMap()) }
        return Catalog(endpoints, results[SK.ref_defs].asMap())
    }

    private fun toEndpointInfo(m: Map<String, Any?>): EndpointInfo = EndpointInfo(
        path = m[SK.path] as? String ?: "",
        method = m[SK.method] as? String ?: "",
        kind = m[SK.kind] as? String ?: "",
        namespace = m[SK.namespace] as? String ?: "",
        description = m[SK.description] as? String,
        inputSchema = m[SK.inputSchema].asMap(),
        outputSchema = m[SK.outputSchema].asMap(),
    )

    private suspend fun getJson(url: String): Map<String, Any?> {
        val response = browserFetch(url).await()
        if (!(response.ok as Boolean)) {
            error("GET $url failed with status ${response.status}")
        }
        // `response` is dynamic, so `response.json()` is dynamic too; cast it to a typed Promise so `.await()`
        // resolves via the Kotlin coroutines extension rather than a (nonexistent) JS `await` method.
        val json = (response.json() as Promise<dynamic>).await()
        return jsToKotlin(json).asMap()
    }
}

private fun encode(s: String): String = js("encodeURIComponent(s)") as String

/**
 * Recursively converts a parsed-JSON `dynamic` value into plain Kotlin `Map`/`List`/primitives, so the rest of
 * the engine ([SchemaView]) never touches `dynamic`. Objects become insertion-ordered [LinkedHashMap]s and
 * arrays become [ArrayList]s; strings/numbers/booleans/null pass through.
 */
private fun jsToKotlin(value: dynamic): Any? = when {
    value == null -> null
    js("Array.isArray(value)") as Boolean -> {
        val n = value.length as Int
        val list = ArrayList<Any?>(n)
        for (i in 0 until n) list.add(jsToKotlin(value[i]))
        list
    }
    jsTypeOf(value) == "object" -> {
        val map = LinkedHashMap<String, Any?>()
        val keys = js("Object.keys(value)")
        val n = keys.length as Int
        for (i in 0 until n) {
            val k = keys[i] as String
            map[k] = jsToKotlin(value[k])
        }
        map
    }
    else -> value
}
