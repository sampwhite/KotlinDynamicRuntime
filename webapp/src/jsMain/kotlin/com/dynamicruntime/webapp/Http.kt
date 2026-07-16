package com.dynamicruntime.webapp

import com.dynamicruntime.common.content.CMK
import com.dynamicruntime.common.endpoint.EP
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
private const val apiRoot = "/kda"
private const val staticRoot = "/st"

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

    /** GET a Markdown *fragment* file (`/st/<appId>/md/<fileId:buildId>`) as its `namespace -> key -> value` map. */
    suspend fun getFragments(fileId: String, buildId: String): Map<String, Any?> =
        requestJson("GET", "$staticRoot/$appId/${CMK.md}/$fileId:$buildId", null)

    /** GET a whole Markdown *document* (`/st/<appId>/doc/<docId:buildId>`) verbatim as text. */
    suspend fun getDoc(docId: String, buildId: String): String =
        requestText("GET", "$staticRoot/$appId/${CMK.doc}/$docId:$buildId", null)

    /** Runs a request and parses the JSON body; a non-2xx raises the runtime's [EP.errorMessage]. */
    private suspend fun requestJson(method: String, url: String, body: Map<String, Any?>?): Map<String, Any?> =
        requestText(method, url, body).jsonMap() ?: emptyMap()

    private suspend fun requestText(method: String, url: String, body: Map<String, Any?>?): String {
        val init: dynamic = js("({})")
        init.method = method
        // Same-origin credentials so the session cookie is sent and stored (the API and the app share an origin).
        init.credentials = "same-origin"
        if (body != null) {
            val headers: dynamic = js("({})")
            headers["Content-Type"] = "application/json"
            init.headers = headers
            init.body = body.toJsonStr(compact = true)
        }
        val response = browserFetch(url, init).await()
        val text = (response.text() as Promise<String>).await()
        if (!(response.ok as Boolean)) {
            val message = text.jsonMap()?.get(EP.errorMessage) as? String
            error(message ?: "$method $url failed with status ${response.status}")
        }
        return text
    }
}
