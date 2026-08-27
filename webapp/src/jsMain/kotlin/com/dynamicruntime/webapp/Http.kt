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

/**
 * The whole of where the browser currently is -- path, query **and fragment**.
 *
 * The fragment is the part that matters and the part a server can never supply: browsers do not send anything
 * after `#`, and this app routes on it. A sign-in round trip that drops it returns the caller to the top of
 * the app instead of the page they were reading.
 */
private fun currentLocationWithFragment(): String =
    js("(window.location.pathname + window.location.search + window.location.hash)") as String

/** Navigates the whole window, leaving this app. */
private fun assignLocation(url: String) {
    js("window.location.assign(url)")
}


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
     * DELETE an API endpoint. Its input is [args], appended as an encoded query string, because this codebase
     * sends no DELETE body -- see `HttpMethod.DELETE`. Passing no body is what keeps that true: [requestJson]
     * sets a `Content-Type` and a body only when one is given. Encoding happens once here, through the shared
     * [queryString], so no caller has to reason about which of its values are safe to concatenate raw.
     */
    suspend fun deleteApi(path: String, args: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        requestJson("DELETE", apiRoot + path + queryString(args), null)

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

    /**
     * Sends the browser to an edge's sign-in page when [envelope] is that edge's "no environment session"
     * refusal, and returns the error to raise so the in-flight call still unwinds.
     *
     * **Carries the whole current location, fragment included.** The app routes on the fragment, and a
     * fragment is never sent to a server -- so a caller returned to the bare path lands at the top of the app
     * rather than where they were. The one moment it is knowable is here, in the browser, before navigating.
     *
     * Returns null when this is any other error, which is every ordinary failure: only an edge sends this
     * code, and only when the perimeter -- not the application -- stopped recognizing the caller.
     */
    private fun redirectToEnvAuthLogin(envelope: Map<String, Any?>?): ApiError? {
        if (envelope?.get(EP.errorCode) != EP.envAuthRequiredCode) {
            return null
        }
        val extra = envelope[EP.extraData] as? Map<*, *>
        val loginUrl = extra?.get(EP.envAuthLoginUrl) as? String ?: return null
        val here = currentLocationWithFragment()
        val sep = if (loginUrl.contains('?')) "&" else "?"
        assignLocation("$loginUrl$sep${EP.envAuthNextParam}=${encodeUriComponent(here)}")
        return ApiError(
            message = envelope[EP.errorMessage] as? String ?: "Environment sign-in is required.",
            fromFragment = false,
            status = (envelope[EP.status] as? Number)?.toInt(),
            errorCode = EP.envAuthRequiredCode,
            traceId = null,
        )
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
            // An edge in front of this deployment saying nobody is signed in any more (issue #419). It is not
            // an error this app can show its way out of: every later call will fail the same way, and the only
            // way back is out of the app entirely, to a sign-in page this app does not host and cannot render.
            //
            // Handled here rather than at each call site because it can arrive on ANY of them -- a session
            // expires between two background refreshes, not at a moment a screen chose.
            // Parse the body as the error envelope only if it *is* one. A non-JSON error body -- the terse
            // context-root 404, or a fragment 404 that `MarkdownFragmentService` serves as `text/plain` -- is
            // not, and `jsonMap()` **throws** on it rather than returning null (issue #469: that throw used to
            // escape and hide the 404 the stale-fragment recovery keys off). Catch it here, once.
            val env = runCatching { text.jsonMap() }.getOrNull()
            redirectToEnvAuthLogin(env)?.let { throw it }
            // Carry the whole error envelope up as a structured error (issue #111), so a display site can decide
            // how to present it -- designed fragment copy vs. a raw/internal message -- rather than seeing only a
            // string.
            throw ApiError(
                message = env?.get(EP.errorMessage) as? String ?: "$method $url failed with status ${response.status}",
                fromFragment = env?.get(EP.errorFromFragment) == true,
                // The envelope's status when present, else the transport status -- so a non-envelope error (a
                // fragment 404) still carries its code for a caller keying on it (issue #469; EditFormPage too).
                status = (env?.get(EP.status) as? Number)?.toInt() ?: (response.status as? Number)?.toInt(),
                errorCode = env?.get(EP.errorCode) as? String,
                traceId = traceId,
            )
        }
        return text
    }
}
