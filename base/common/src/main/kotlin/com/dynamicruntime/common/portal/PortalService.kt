package com.dynamicruntime.common.portal

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ContentServer
import com.dynamicruntime.common.http.request.ContextFocus
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.toJsonStr

/** Portal paths. Kept short and referenced qualified, per the code guide. */
@Suppress("ConstPropertyName")
object PTL {
    /**
     * Application path (context-root-stripped) of the HTML portal page. Served under the content context
     * root, so the browser reaches it at e.g. `/cp/portal`; the section is `portal` (anonymous).
     */
    const val page = "/portal"

    /**
     * The platform's endpoint-introspection endpoint (owned by SchemaService), which the portal page calls to
     * list the instance's endpoints (with input schema and a shared `$defs`). A context-root-relative path:
     * the page prefixes it with the API root, and resolves each `$ref` against the returned `$defs` itself.
     */
    const val endpointsApi = "/schema/endpoints"
}

/**
 * Serves a self-contained, form-based UI for exploring and calling the instance's endpoints, entirely from
 * within the running server (no separate webapp/build step). It is purely a [ContentServer] (registered with
 * the [RequestService] during init) -- it contributes no endpoints of its own. It serves only the HTML page
 * at [PTL.page] (and redirects `/` to it).
 *
 * The page fetches the platform's [PTL.endpointsApi] (SchemaService's `/schema/endpoints`), which returns the
 * endpoints with their input schema plus a shared `$defs`, and renders a form per endpoint by resolving each
 * `$ref` against that `$defs` in the browser. There is no longer a server-side resolved-fields feed: `$ref`
 * resolution lives on the client (issue #42).
 */
class PortalService : ServiceInitializer, ContentServer {
    override val serviceName: String = PortalService.serviceName

    /** Registers this content server with the dispatcher (idempotent). */
    override fun checkInit(cxt: KdrCxt) {
        val requestService = RequestService.get(cxt) ?: return
        requestService.checkInit(cxt)
        requestService.addContentServer(this)
    }

    /**
     * Serves the portal page or redirects the content root to the page; passes on anything it does not own.
     * Only engages with content-focused requests, matching on the context-root-stripped [RequestHandler.appPath]
     * (so the configured content root, e.g. `cp`, is transparent here).
     */
    override fun serve(cxt: KdrCxt, handler: RequestHandler): Boolean {
        if (handler.focus != ContextFocus.content) {
            return false
        }
        return when (handler.appPath) {
            "/" -> {
                // The content root itself (e.g., /cp) redirects to the page (e.g., /cp/portal).
                handler.sendRedirect("/${handler.contextRoot}${PTL.page}")
                true
            }
            PTL.page -> {
                handler.sendStringResponse(PortalPage.render(bootstrapJson(cxt)), EXC.ok, "text/html; charset=utf-8")
                true
            }
            else -> false
        }
    }

    /** The frontend bootstrap config (context roots by focus) as JSON, for injection into the page. */
    private fun bootstrapJson(cxt: KdrCxt): String =
        (RequestService.get(cxt)?.frontendConfig() ?: emptyMap()).toJsonStr()

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "PortalService"

        fun get(cxt: KdrCxt): PortalService? = cxt.instanceConfig.get(serviceName) as? PortalService
    }
}
