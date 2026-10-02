package com.dynamicruntime.webapp

import com.dynamicruntime.common.content.CMK
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.RID
import com.dynamicruntime.common.gedra.DSV
import com.dynamicruntime.common.util.Parsed
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.jsonMapOrNull
import com.dynamicruntime.common.util.jsonMapResult
import com.dynamicruntime.common.util.toJsonStr
import kotlinx.coroutines.await
import kotlin.coroutines.cancellation.CancellationException
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

/**
 * The `_debug` request tags an env-debug operator sets in the app bar (issue #517, slice 3), riding the
 * `X-Kdr-Debug` header on every request the frontend makes -- turning on a diagnostic tag (`explainAccess`,
 * `explainScope`, ...) without editing URLs. The backend treats the header as a baseline an explicit `_debug`
 * param can override per request.
 *
 * Held in **`sessionStorage`, not `localStorage`**, on purpose: it accompanies the `kdrEnvDebug` session
 * cookie, which dies with the browser session, so the tags must not outlive the debug state that authorizes
 * them. It still survives a reload -- what the issue's "hides it but does not erase it" asks -- since a reload
 * is the same session.
 */
private const val debugTagsStorageKey = "kdrDebugTags"

/** The tags currently set, or "" -- read wherever a request is built and wherever the box initializes. */
fun debugRequestTags(): String = sessionStorageGet(debugTagsStorageKey)?.trim() ?: ""

/** Persist the tags for this session; "" (or blank) clears them so no header is sent. */
fun setDebugRequestTags(value: String) = sessionStorageSet(debugTagsStorageKey, value.trim())

// `sessionStorage` throws in a private window or when site data is blocked. A session convenience -- the debug
// tags, Design View's switch -- must never be the thing that breaks a page, so a failure is caught and recovered
// from -- but it is *reported*, not swallowed (webapp/CLAUDE.md): a quietly missing store is exactly the kind of
// thing a `[kdr]` console line exists to make visible. The raw `js(...)` accessors let the JS exception reach a Kotlin `try`.
internal fun sessionStorageGet(key: String): String? =
    try {
        js("window.sessionStorage.getItem(key)") as? String
    } catch (e: Throwable) {
        console.warn("$errorLogPrefix could not read '$key' from sessionStorage: ${e.message}")
        null
    }

internal fun sessionStorageSet(key: String, value: String) {
    try {
        js("window.sessionStorage.setItem(key, value)")
    } catch (e: Throwable) {
        console.warn("$errorLogPrefix could not persist '$key' to sessionStorage: ${e.message}")
    }
}

/**
 * Applies the headers every frontend request carries onto [headers]: the app id and a fresh trace id (issue
 * #105); only while the session is in debug, the env-debug operator's `_debug` tags (issue #517); and the
 * Design View request while the session has it on (issue #972).
 *
 * Shared so a caller that builds its own `fetch` (the endpoint catalog's `SchemaCatalogApi`) sends exactly what
 * [Http] does, rather than each request path deciding for itself which of these it remembers.
 *
 * The debug flag is checked **before** the store is read: an ordinary session never touches `sessionStorage`
 * on the request path, and the tags are read only when they can actually be sent.
 *
 * Returns the fresh trace id it minted, so a caller that reports an error can correlate it (see [Http]).
 */
fun applyRequestHeaders(headers: dynamic): String {
    val traceId = nextTraceId()
    headers[RID.appIdHeader] = Http.appId
    headers[RID.traceIdHeader] = traceId
    if (appConfig().envAuthDebug) {
        val debugTags = debugRequestTags()
        if (debugTags.isNotEmpty()) headers[EP.debugHeader] = debugTags
    }
    // Design View (issue #972): asked for on every request while the session has it on. Unlike the debug tags it
    // needs no flag of the shell's to send -- the backend honors it only for a client administrator.
    if (designViewRequested()) headers[EP.viewHeader] = DSV.design
    return traceId
}

/** The API root, for code outside this file that builds its own paths. */
val apiContextRoot: String get() = apiRoot

@JsName("fetch")
private external fun browserFetch(input: String, init: dynamic = definedExternally): Promise<dynamic>

/** A request the browser completed: its [response] (any status) and the body read as [text]. */
internal class Fetched(val response: dynamic, val text: String)

/**
 * Runs the browser's `fetch` of [url] with [init] and reads the body (issue #967) -- the **one** transport
 * boundary every API request goes through, [Http]'s and the catalog's own. The browser rejects the `fetch` (or
 * the body read) when there is no answer -- offline, a reset connection, a blocked request -- and that is a
 * value here, [ApiResult.Failed], not a fault. A completed request is [ApiResult.Ok] whatever its status; what a
 * non-2xx means is the caller's to read. A coroutine's cancellation is not a failure of the request and is never
 * caught.
 */
internal suspend fun fetchCompleted(method: String, url: String, init: dynamic): ApiResult<Fetched> =
    try {
        val response = browserFetch(url, init).await()
        ApiResult.Ok(Fetched(response, (response.text() as Promise<String>).await()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        ApiResult.Failed(ApiFailure("$method $url could not be completed: ${e.message}", e))
    }

/**
 * A successful response's [text] as its JSON map (empty for an empty body), or -- when it is not JSON, because the
 * server or something in front of it sent the wrong thing -- no usable answer, [ApiResult.Failed], with the
 * parser's own account of where.
 */
internal fun jsonBody(method: String, url: String, text: String): ApiResult<Map<String, Any?>> =
    when (val parsed = text.jsonMapResult()) {
        is Parsed.Ok -> ApiResult.Ok(parsed.value ?: emptyMap())
        is Parsed.Failed -> ApiResult.Failed(
            ApiFailure("$method $url answered with a body that is not JSON: ${parsed.problems.first().message}"),
        )
    }

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

/**
 * A full-window navigation to [url], leaving this single-page app entirely (issue #486).
 *
 * A hash change re-routes *within* the app; this replaces the page. Env logout needs it: the destination is
 * served by the edge rather than being a route within this app, so there is nothing to route to. The public
 * seam over the file-local [assignLocation], which the 401 redirect already uses.
 *
 * **It does not guard [url]** -- callers pass a value they have already proven same-origin (`sameOriginPath`
 * in the kernel). Anything reaching here navigates the whole window.
 */
fun leaveAppTo(url: String) = assignLocation(url)


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

    /**
     * GET a runtime API endpoint (under the `kda` root) and return its parsed JSON envelope. Throws the server's
     * refusal as an [ApiError] and no usable answer as an [ApiFailure]; [getApiResult] returns them instead.
     */
    suspend fun getApi(path: String): Map<String, Any?> = getApiResult(path).orThrow()

    /** [getApi] as an [ApiResult] (issue #967). */
    suspend fun getApiResult(path: String): ApiResult<Map<String, Any?>> = requestJson("GET", apiRoot + path, null)

    /** POST/PUT [body] as JSON to an API endpoint; returns the parsed envelope. */
    suspend fun sendApi(method: String, path: String, body: Map<String, Any?>): Map<String, Any?> =
        sendApiResult(method, path, body).orThrow()

    /** [sendApi] as an [ApiResult] (issue #967). */
    suspend fun sendApiResult(method: String, path: String, body: Map<String, Any?>): ApiResult<Map<String, Any?>> =
        requestJson(method, apiRoot + path, body)

    /**
     * DELETE an API endpoint. Its input is [args], appended as an encoded query string, because this codebase
     * sends no DELETE body -- see `HttpMethod.DELETE`. Passing no body is what keeps that true: [requestJson]
     * sets a `Content-Type` and a body only when one is given. Encoding happens once here, through the shared
     * [queryString], so no caller has to reason about which of its values are safe to concatenate raw.
     */
    suspend fun deleteApi(path: String, args: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        requestJson("DELETE", apiRoot + path + queryString(args), null).orThrow()

    /** GET a Markdown *fragment* file (`/st/<appId>/md/<fileId:buildId>`) as its `namespace -> key -> value` map. */
    suspend fun getFragments(fileId: String, buildId: String): Map<String, Any?> =
        requestJson("GET", "$staticRoot/$appId/${CMK.md}/$fileId:$buildId", null).orThrow()

    /** GET a whole Markdown *document* (`/st/<appId>/doc/<docId:buildId>`) verbatim as text. */
    suspend fun getDoc(docId: String, buildId: String): String =
        requestText("GET", "$staticRoot/$appId/${CMK.doc}/$docId:$buildId", null).orThrow()

    /**
     * Runs a request and parses the JSON body. A success whose body is not JSON is no usable answer -- the server
     * or something in front of it sent the wrong thing -- so it comes back [ApiResult.Failed], located as the
     * parser located it, rather than as a throw from inside the parser.
     */
    private suspend fun requestJson(
        method: String,
        url: String,
        body: Map<String, Any?>?,
    ): ApiResult<Map<String, Any?>> {
        val text = when (val sent = requestText(method, url, body)) {
            is ApiResult.Ok -> sent.value
            is ApiResult.Refused -> return sent
            is ApiResult.Failed -> return sent
        }
        val map = when (val parsed = jsonBody(method, url, text)) {
            is ApiResult.Ok -> parsed.value
            else -> return parsed
        }
        // Notice when a newer web app has been deployed than the one running (issue #136): every endpoint
        // envelope carries the deployed bundle hash. A no-op for non-envelope responses (fragment/doc) and dev.
        observeWebAppHash(map[EP.webAppHash] as? String)
        return ApiResult.Ok(map)
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

    /**
     * Runs a request (issue #967): the body of a 2xx, the server's refusal of anything else, or [ApiResult.Failed]
     * when the browser could not complete the request at all.
     */
    private suspend fun requestText(method: String, url: String, body: Map<String, Any?>?): ApiResult<String> {
        val init: dynamic = js("({})")
        init.method = method
        // Same-origin credentials so the session cookie is sent and stored (the API and the app share an origin).
        init.credentials = "same-origin"
        // The identity + diagnostic headers every call carries (issue #105, #517): app id, a fresh trace id,
        // and the operator's `_debug` tags while in debug. Shared with the catalog's own fetch, so both agree.
        val headers: dynamic = js("({})")
        val traceId = applyRequestHeaders(headers)
        init.headers = headers
        if (body != null) {
            headers["Content-Type"] = "application/json"
            init.body = body.toJsonStr(compact = true)
        }
        val fetched = when (val done = fetchCompleted(method, url, init)) {
            is ApiResult.Ok -> done.value
            is ApiResult.Refused -> return done
            is ApiResult.Failed -> return done
        }
        val response = fetched.response
        val text = fetched.text
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
            val env = text.jsonMapOrNull()
            redirectToEnvAuthLogin(env)?.let { return ApiResult.Refused(it) }
            // Carry the whole error envelope up as a structured error (issue #111), so a display site can decide
            // how to present it -- designed fragment copy vs. a raw/internal message -- rather than seeing only a
            // string.
            return ApiResult.Refused(ApiError(
                message = env?.get(EP.errorMessage) as? String ?: "$method $url failed with status ${response.status}",
                fromFragment = env?.get(EP.errorFromFragment) == true,
                // The envelope's status when present, else the transport status -- so a non-envelope error (a
                // fragment 404) still carries its code for a caller keying on it (issue #469; EditFormPage too).
                status = (env?.get(EP.status) as? Number)?.toInt() ?: (response.status as? Number)?.toInt(),
                errorCode = env?.get(EP.errorCode) as? String,
                traceId = traceId,
            ))
        }
        return ApiResult.Ok(text)
    }
}
