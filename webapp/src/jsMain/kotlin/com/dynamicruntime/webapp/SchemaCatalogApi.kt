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
import com.dynamicruntime.common.util.toJsonListOfStrings

/**
 * The runtime's schema-catalog endpoints, under the `kda` API context root. In dev the webpack server proxies
 * `/kda` to the runtime on :7070 (see build.gradle.kts), so the calls are same-origin and need no CORS.
 */
private val schemaBase: String get() = apiContextRoot + "/schema"

/** The API context root every runtime endpoint is served under (proxied to :7070 in dev). */
private val apiRoot: String get() = apiContextRoot

/** Binding to the browser's global `fetch` (named to avoid clashing with any wrapper `fetch`). */
@JsName("fetch")
private external fun browserFetch(input: String, init: dynamic = definedExternally): Promise<dynamic>

/**
 * The `limit` [SchemaCatalogApi.fetchCatalog] asks for: the whole catalog, not a page of it. The catalog is a
 * fixed introspection surface (a few tens of endpoints), and the page shows and filters all of it -- deriving
 * the tag-filter options or `findFormCreateEndpoint` from a truncated page would silently drop endpoints past
 * the cut (issue #489). Left generous rather than exact so a growing surface never quietly re-hits the default
 * `defaultListLimit` of 100; the backend simply `take`s this many.
 */
private const val fullCatalogLimit = 1000

/**
 * Fetches endpoint schema from the runtime's `/schema/endpoints` catalog and parses it into the [Catalog]
 * model. Responses are parsed with the shared kernel's [jsonMap] (the same JSON parser the backend uses), so
 * numbers arrive as the kernel's own `Long`/`Double` — matching what the kernel validator expects — instead
 * of JS doubles.
 */
object SchemaCatalogApi {
    /**
     * GET the whole catalog: every registered endpoint's rendering plus the shared `$defs`. An optional
     * [namespace] narrows the results; an optional [client] shows *that* client's surface -- its endpoints and
     * its `$defs` (issues #387, #394), for a caller who holds `allClients`. Null [client] is the caller's own.
     */
    suspend fun fetchCatalog(namespace: String? = null, client: String? = null): Catalog {
        val query = queryString(buildMap {
            namespace?.let { put(EI.namespace, it) }
            client?.let { put(EI.client, it) }
            // Ask for the whole catalog, not the default first 100 -- the page filters over the full set.
            put(EP.limit, fullCatalogLimit)
        })
        val results = getJson("$schemaBase/endpoints$query")[EP.results].toJsonMapOrEmpty()
        return toCatalog(results)
    }

    /** GET a single endpoint by exact method + path, in the same shape as the full catalog. */
    suspend fun fetchEndpoint(method: String, path: String): Catalog {
        val results = getJson("$schemaBase/endpoint?${EI.method}=$method&${EI.path}=${encodeUriComponent(path)}")[EP.results].toJsonMapOrEmpty()
        return toCatalog(results)
    }

    /** The `results` map of `/schema/endpoints` (or `/schema/endpoint`) as a [Catalog]. One reader, so the two
     *  feeds cannot drift in how they parse the shared shape (issue #489 added `filtersAvailable`). */
    private fun toCatalog(results: Map<String, Any?>): Catalog = Catalog(
        endpoints = results[EI.endpoints].toJsonListOfMaps().map { toEndpointInfo(it) },
        defs = results[SCH.dDefs].toJsonMapOrEmpty(),
        // Default true when the field is absent (an older node, a hand-built store): the safe reading is that
        // filtering is allowed, and the endpoints the response already carries are what the page can show.
        filtersAvailable = results[EI.filtersAvailable] as? Boolean ?: true,
    )

    private fun toEndpointInfo(m: Map<String, Any?>): EndpointInfo = EndpointInfo(
        path = m[EI.path] as? String ?: "",
        method = m[EI.method] as? String ?: "",
        kind = m[EI.kind] as? String ?: "",
        namespace = m[EI.namespace] as? String ?: "",
        description = m[EI.description] as? String,
        inputSchema = m[EI.inputSchema].toJsonMapOrEmpty(),
        outputSchema = m[EI.outputSchema].toJsonMapOrEmpty(),
        publicApi = m[EI.publicApi] as? Boolean ?: false,
        tags = m[EI.tags].toJsonListOfStrings(),
    )

    /**
     * Executes [endpoint] with the (already coerced) [body] and returns its parsed response envelope. A GET
     * or DELETE carries the fields in the query string (a nested value is JSON-encoded, which the runtime's
     * coercion reparses); a POST/PUT sends them as a JSON body, serialized with the shared kernel's
     * [toJsonStr]. A non-2xx response raises the runtime's error `message`.
     *
     * DELETE joins the query-string side rather than the body side deliberately: DELETE bodies are legal but
     * poorly handled by intermediaries, so nothing here sends one (issue #335).
     */
    suspend fun invoke(
        endpoint: EndpointInfo,
        body: Map<String, Any?>,
        multipart: Boolean = false,
    ): Map<String, Any?> {
        val url = apiRoot + endpoint.path
        val response = if (endpoint.method == "GET" || endpoint.method == "DELETE") {
            // The method is set explicitly rather than left to fetch's GET default, since DELETE takes the
            // same query-string treatment but is not the default.
            val init: dynamic = js("({})")
            init.method = endpoint.method
            val headers: dynamic = js("({})")
            applyRequestHeaders(headers)
            init.headers = headers
            browserFetch(url + queryString(body), init).await()
        } else {
            val init: dynamic = js("({})")
            init.method = endpoint.method
            val headers: dynamic = js("({})")
            applyRequestHeaders(headers)
            if (multipart) {
                // An upload: the body is form parts, one of them the file itself. Deliberately no
                // Content-Type header -- the browser must set it, because only it knows the multipart
                // boundary it generated. Setting it by hand here produces a body the server cannot parse.
                init.headers = headers
                init.body = formData(body)
            } else {
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
        val init: dynamic = js("({})")
        val headers: dynamic = js("({})")
        // The standard request headers (issue #105, #517), so the catalog's own GETs carry the trace id and the
        // env-debug `_debug` tag exactly as `Http` does -- the two tags this box is most useful for reach here.
        applyRequestHeaders(headers)
        init.headers = headers
        val response = browserFetch(url, init).await()
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
    // Null first, and not as part of the chain below: `typeof null` is "object", so a null clears the type
    // test and then throws on the very next term. The parameter is `Any?` and every caller reads it out of a
    // `Map<String, Any?>`, so null is an ordinary value here -- a key the form does not hold, or one whose
    // value is JSON null -- rather than a case that should never arise (issue #260).
    if (v == null) {
        return false
    }
    val d = v.asDynamic()
    return jsTypeOf(d) == "object" && d.name != undefined && d.size != undefined && jsTypeOf(d.slice) == "function"
}

/** A picked file's name and size, for showing what was chosen where the file itself cannot go. ASCII only:
 *  this lands in a JSON preview, and the formatter escapes anything else into `\uXXXX` noise.
 *
 *  Only meaningful for a value [isBrowserFile] accepted, which is the gate both callers apply: this reads
 *  `name` and `size` straight off the value, so anything else yields "(file: undefined, undefined bytes)" at
 *  best and throws at worst. Keeping the precondition rather than inventing a fallback is deliberate -- there
 *  is no honest label for a non-file, and a plausible-looking one would hide the miswiring that produced it. */
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



