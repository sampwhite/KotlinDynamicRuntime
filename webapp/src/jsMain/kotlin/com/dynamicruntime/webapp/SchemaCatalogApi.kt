package com.dynamicruntime.webapp

import com.dynamicruntime.common.endpoint.EI
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr
import kotlinx.coroutines.await
import kotlin.js.Promise
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toJsonListOfMaps

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
        val endpoints = results[EI.endpoints].toJsonListOfMaps().map { toEndpointInfo(it) }
        return Catalog(endpoints, results[SCH.dDefs].toJsonMapOrEmpty())
    }

    /** GET a single endpoint by exact method + path, in the same shape as the full catalog. */
    suspend fun fetchEndpoint(method: String, path: String): Catalog {
        val results = getJson("$schemaBase/endpoint?${EI.method}=$method&${EI.path}=${encode(path)}")[EP.results].toJsonMapOrEmpty()
        val endpoints = results[EI.endpoints].toJsonListOfMaps().map { toEndpointInfo(it) }
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
    suspend fun invoke(
        endpoint: EndpointInfo,
        body: Map<String, Any?>,
        multipart: Boolean = false,
    ): Map<String, Any?> {
        val url = apiRoot + endpoint.path
        val response = if (endpoint.method == "GET") {
            browserFetch(url + queryString(body)).await()
        } else {
            val init: dynamic = js("({})")
            init.method = endpoint.method
            if (multipart) {
                // An upload: the body is form parts, one of them the file itself. Deliberately no
                // Content-Type header -- the browser must set it, because only it knows the multipart
                // boundary it generated. Setting it by hand here produces a body the server cannot parse.
                init.body = formData(body)
            } else {
                val headers: dynamic = js("({})")
                headers["Content-Type"] = "application/json"
                init.headers = headers
                init.body = body.toJsonStr(compact = true)
            }
            browserFetch(url, init).await()
        }
        val map = readJson(response)
        if (!(response.ok as Boolean)) {
            error(map["message"] as? String ?: "${endpoint.method} $url failed with status ${response.status}")
        }
        return map
    }

    /**
     * The URL a file-download endpoint's content is at, for handing to the browser to fetch itself.
     *
     * A download is deliberately *not* run through [invoke]: the response is bytes, so parsing it as JSON
     * would corrupt it, and holding a file in memory to re-offer it would throw away the `Content-Disposition`
     * the server already sent. Letting the browser navigate to the URL is what makes that header do its job.
     */
    fun downloadUrl(endpoint: EndpointInfo, body: Map<String, Any?>): String =
        apiRoot + endpoint.path + queryString(body)

    /** Builds a `FormData` body: a picked file appends as a file part, anything else as its text value. */
    private fun formData(body: Map<String, Any?>): dynamic {
        val fd = newFormData()
        for ((k, v) in body) {
            when {
                v == null -> {}
                isBrowserFile(v) -> fd.append(k, v)
                else -> fd.append(k, v.toString())
            }
        }
        return fd
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

/** A new, empty browser `FormData`. */
private fun newFormData(): dynamic = js("new FormData()")

/**
 * Whether [v] is a browser `File` — what the schema form's file picker emits for a binary field, as opposed to
 * the strings and numbers every other widget produces.
 *
 * Duck-typed rather than an `instanceof File`, because the value crosses from a `dynamic` DOM event into a
 * Kotlin `Map<String, Any?>` and the wrapper type it lands as is not worth pinning: a thing with a name, a
 * size and a `slice` is a `Blob`/`File` for the purpose at hand, which is deciding whether `FormData.append`
 * should treat it as a part or as text.
 */
fun isBrowserFile(v: Any?): Boolean {
    val d = v.asDynamic()
    return jsTypeOf(d) == "object" && d.name != undefined && d.size != undefined && jsTypeOf(d.slice) == "function"
}

/** A picked file's name and size, for showing what was chosen where the file itself cannot go. ASCII only:
 *  this lands in a JSON preview, and the formatter escapes anything else into `\uXXXX` noise. */
fun browserFileLabel(v: Any?): String {
    val d = v.asDynamic()
    return "(file: ${d.name}, ${d.size} bytes)"
}

/**
 * Starts a browser download of [url]. Uses a transient anchor rather than navigating: a response carrying
 * `Content-Disposition: attachment` downloads without leaving the page, but an *error* response (a JSON 404)
 * would navigate the console away — and the whole point of a download button is not to lose your place. The
 * empty `download` attribute asks for a download while leaving the filename to the server's header.
 */
fun startDownload(url: String) {
    js(
        """
        (function () {
            var a = document.createElement('a');
            a.href = url;
            a.download = '';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
        })()
        """,
    )
}

/** Percent-encodes a query value via the browser's global `encodeURIComponent`. */
private fun encode(s: String): String = encodeURIComponent(s)

/** The browser's global `encodeURIComponent`, declared so [encode] actually passes its argument (rather than
 *  relying on a `js(...)` string capturing the local by name). */
private external fun encodeURIComponent(s: String): String


