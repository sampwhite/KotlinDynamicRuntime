package com.dynamicruntime.webapp

import com.dynamicruntime.common.content.CMK
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.RID
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.toJsonStr
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * The frontend's shared HTTP layer: the browser `fetch` plus the runtime's conventions (the `kda` API root,
 * the `st` static-content root, the JSON error envelope), so every widget-group's `*Api` object calls the
 * backend the same way. Responses are parsed with the shared kernel [jsonMap] (numbers arrive as the kernel's
 * own `Long`/`Double`, matching the validator). In dev the webpack server proxies `/kda` and `/st` to the
 * runtime, so calls are same-origin.
 */
/**
 * The context roots this deployment actually serves, read from the bootstrap config the shell injects as
 * `window.kdrCfg` (see `AppUiPage` / `PortalPage`), falling back to the defaults.
 *
 * The roots are **configurable per instance** -- `ACFG.apiContextRoot` and friends -- and the server has always
 * injected them for exactly this reason. Hardcoding them here quietly made that untrue: a deployment that
 * chose different roots served a front end that called the wrong paths, got the terse 404's `Not Found` body,
 * and reported it as "Unexpected character 'N' when parsing JSON". Found the first time anything configured
 * them, which was an edge node serving under `ea`.
 *
 * The fallbacks matter and are not decoration: on the webpack dev server there is no injected config at all,
 * and its proxy is written against these exact literals.
 */
private fun rootFor(focus: String, fallback: String): String {
    val configured = js("(window.kdrCfg && window.kdrCfg.contextRoots && window.kdrCfg.contextRoots[focus])")
    val root = configured as? String
    return if (root.isNullOrEmpty()) fallback else "/" + root
}

private val apiRoot: String by lazy { rootFor("api", "/kda") }
private val staticRoot: String by lazy { rootFor("static", "/st") }

/** The API root, for code outside this file that builds its own paths. */
val apiContextRoot: String get() = apiRoot

@JsName("fetch")
private external fun browserFetch(input: String, init: dynamic = definedExternally): Promise<dynamic>

/** The browser's `navigator.language` (e.g. `en-US`), or empty when unavailable. */
private fun navigatorLanguage(): String = js("(navigator && navigator.language) || ''") as String

object Http {
    /**
     * The static-content app id: a backend-owned base (`kdr`) plus a client-known suffix -- the browser's
     * locale -- since for an anonymous visitor the backend cannot know it. Opaque to the backend today (it
     * ignores the id), but this sets the contract so per-locale content resolves later.
     */
    val appId: String by lazy {
        val locale = navigatorLanguage().substringBefore('-').lowercase()
        if (locale.isEmpty()) "kdr" else "kdr.$locale"
    }

    /** GET a runtime API endpoint (under the `kda` root) and return its parsed JSON envelope. */
    suspend fun getApi(path: String): Map<String, Any?> = requestJson("GET", apiRoot + path, null)

    /** POST/PUT [body] as JSON to an API endpoint; returns the parsed envelope. */
    suspend fun sendApi(method: String, path: String, body: Map<String, Any?>): Map<String, Any?> =
        requestJson(method, apiRoot + path, body)

    /**
     * DELETE an API endpoint. Its input rides in [path]'s query string, exactly as [getApi]'s does, because
     * this codebase sends no DELETE body -- see `HttpMethod.DELETE`. Passing no body here is what keeps that
     * true: [requestJson] sets a `Content-Type` and a body only when one is given.
     */
    suspend fun deleteApi(path: String): Map<String, Any?> = requestJson("DELETE", apiRoot + path, null)

    /** GET a Markdown *fragment* file (`/st/<appId>/md/<fileId:buildId>`) as its `namespace -> key -> value` map. */
    suspend fun getFragments(fileId: String, buildId: String): Map<String, Any?> =
        requestJson("GET", "$staticRoot/$appId/${CMK.md}/$fileId:$buildId", null)

    /** GET a whole Markdown *document* (`/st/<appId>/doc/<docId:buildId>`) verbatim as text. */
    suspend fun getDoc(docId: String, buildId: String): String =
        requestText("GET", "$staticRoot/$appId/${CMK.doc}/$docId:$buildId", null)

    /** Runs a request and parses the JSON body; a non-2xx raises the runtime's [EP.errorMessage]. */
    private suspend fun requestJson(method: String, url: String, body: Map<String, Any?>?): Map<String, Any?> {
        val map = requestText(method, url, body).jsonMap() ?: emptyMap()
        // Notice when a newer web app has been deployed than the one running (issue #136): every endpoint
        // envelope carries the deployed bundle hash. A no-op for non-envelope responses (fragment/doc) and dev.
        observeWebAppHash(map[EP.webAppHash] as? String)
        return map
    }

    private suspend fun requestText(method: String, url: String, body: Map<String, Any?>?): String {
        val init: dynamic = js("({})")
        init.method = method
        // Same-origin credentials so the session cookie is sent and stored (the API and the app share an origin).
        init.credentials = "same-origin"
        // Client identity on every call (issue #105): the app id (content selection) and a fresh trace id (so
        // this call can be followed into the backend log). The backend also accepts these as `_appId`/`_traceId`
        // params, but the frontend always sends headers.
        val traceId = nextTraceId()
        val headers: dynamic = js("({})")
        headers[RID.appIdHeader] = appId
        headers[RID.traceIdHeader] = traceId
        init.headers = headers
        if (body != null) {
            headers["Content-Type"] = "application/json"
            init.body = body.toJsonStr(compact = true)
        }
        val response = browserFetch(url, init).await()
        val text = (response.text() as Promise<String>).await()
        if (!(response.ok as Boolean)) {
            // Carry the whole error envelope up as a structured error (issue #111), so a display site can decide
            // how to present it -- designed fragment copy vs. a raw/internal message -- rather than seeing only a
            // string. A non-JSON error body (e.g., the terse context-root 404) yields a null map and a fallback.
            val env = text.jsonMap()
            throw ApiError(
                message = env?.get(EP.errorMessage) as? String ?: "$method $url failed with status ${response.status}",
                fromFragment = env?.get(EP.errorFromFragment) == true,
                status = (env?.get(EP.status) as? Number)?.toInt(),
                errorCode = env?.get(EP.errorCode) as? String,
                traceId = traceId,
            )
        }
        return text
    }
}
