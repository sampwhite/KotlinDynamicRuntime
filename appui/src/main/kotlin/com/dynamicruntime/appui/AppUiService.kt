package com.dynamicruntime.appui

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ContentServer
import com.dynamicruntime.common.http.request.ContextFocus
import com.dynamicruntime.common.http.request.LogRequest
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.toJsonStr

/**
 * Webapp host paths, resource locations, and MIME types. Kept short and referenced qualified, per the code
 * guide. The [bundleResource] path is the contract with `appui/build.gradle.kts`, which copies the webapp's
 * production bundle onto the classpath under `webapp/`; the two must agree.
 */
@Suppress("ConstPropertyName")
object AUI {
    /** Application path (context-root-stripped) of the webapp's JS bundle, e.g. reached at `/wa/webapp.js`. */
    const val bundlePath = "/webapp.js"

    /** Application path of the bundle's sourcemap. */
    const val bundleMapPath = "/webapp.js.map"

    /** Classpath location the build embeds the bundle at (see `appui/build.gradle.kts`). */
    const val bundleResource = "/webapp/webapp.js"

    /** Classpath location of the embedded sourcemap. */
    const val bundleMapResource = "/webapp/webapp.js.map"

    /** Application path of the app's icon, e.g. reached at `/wa/favicon.svg`; declared by [AppUiPage]. */
    const val faviconPath = "/favicon.svg"

    /** Classpath location of the embedded icon (authored in `webapp/src/jsMain/resources`). */
    const val faviconResource = "/webapp/favicon.svg"

    /** Application path of the brand mark (the app bar's logo and the home hero), e.g. `/wa/brand-mark.svg`.
     *  The frontend builds this URL from the live context root — see `brandMarkUrl` in the webapp. */
    const val brandMarkPath = "/brand-mark.svg"

    /** Classpath location of the embedded brand mark. */
    const val brandMarkResource = "/webapp/brand-mark.svg"

    const val htmlMimeType = "text/html; charset=utf-8"
    const val jsMimeType = "application/javascript; charset=utf-8"
    const val jsonMimeType = "application/json"
    const val svgMimeType = "image/svg+xml"
}

/**
 * Serves the self-contained webapp entirely from within the running server, under its own context root (the
 * `app` focus, e.g. `/wa`). Like [com.dynamicruntime.common.portal.PortalService] it is purely a
 * [ContentServer] -- registered with the [RequestService] during init -- and contributes no endpoints. It
 * serves:
 *
 *  - The HTML shell at the app root (`appPath == "/"`), rendered by [AppUiPage];
 *  - The webapp's JS bundle (and sourcemap), read from the classpath resource the build embedded; and
 *  - The app's static assets — its icon and brand mark — embedded from the same `:webapp` distribution the
 *    dev server serves them from.
 *
 * The bundle is the Kotlin/JS `:webapp` module's *production* output. Because the page is served same-origin
 * with the API context root, the webapp's relative `/kda/...` calls reach the runtime directly -- no CORS, no
 * proxy, and no separate webpack dev server.
 */
class AppUiService : ServiceInitializer, ContentServer {
    override val serviceName: String = AppUiService.serviceName

    /** Registers this content server with the dispatcher (idempotent). */
    override fun checkInit(cxt: KdrCxt) {
        val requestService = RequestService.get(cxt) ?: return
        requestService.checkInit(cxt)
        requestService.addContentServer(this)
    }

    /**
     * Serves the webapp shell and its bundle; passes on anything it does not own. Only engages with
     * app-focused requests, matching on the context-root-stripped [RequestHandler.appPath] (so the configured
     * app root, e.g. `wa`, is transparent here).
     */
    override fun serve(cxt: KdrCxt, handler: RequestHandler): Boolean {
        if (handler.focus != ContextFocus.app) {
            return false
        }
        return when (handler.appPath) {
            "/" -> {
                val html = AppUiPage.render(bootstrapJson(cxt), handler.contextRoot)
                handler.sendStringResponse(html, EXC.ok, AUI.htmlMimeType)
                true
            }
            AUI.bundlePath -> serveResource(cxt, handler, AUI.bundleResource, AUI.jsMimeType)
            AUI.bundleMapPath -> serveResource(cxt, handler, AUI.bundleMapResource, AUI.jsonMimeType)
            AUI.faviconPath -> serveResource(cxt, handler, AUI.faviconResource, AUI.svgMimeType)
            AUI.brandMarkPath -> serveResource(cxt, handler, AUI.brandMarkResource, AUI.svgMimeType)
            else -> false
        }
    }

    /**
     * Serves an embedded classpath resource. A missing resource means the `:webapp` bundle was never built
     * into this module's resources; it is logged and passed on (returns false) so the friendly content 404
     * fires rather than a hard error.
     */
    private fun serveResource(cxt: KdrCxt, handler: RequestHandler, resourcePath: String, mimeType: String): Boolean {
        val body = readResource(resourcePath)
        if (body == null) {
            LogRequest.warn(
                cxt,
                "Webapp resource '$resourcePath' is not on the classpath (build ':webapp' first?); " +
                    "passing on ${handler.logRequestUri}.",
            )
            return false
        }
        handler.sendStringResponse(body, EXC.ok, mimeType)
        return true
    }

    /** Reads an embedded resource as UTF-8 text, or null if it is absent. */
    private fun readResource(path: String): String? =
        this::class.java.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }

    /** The frontend bootstrap config (context roots by focus) as JSON, for injection into the page. */
    private fun bootstrapJson(cxt: KdrCxt): String =
        (RequestService.get(cxt)?.frontendConfig() ?: emptyMap()).toJsonStr()

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "AppUiService"

        fun get(cxt: KdrCxt): AppUiService? = cxt.instanceConfig.get(serviceName) as? AppUiService
    }
}
