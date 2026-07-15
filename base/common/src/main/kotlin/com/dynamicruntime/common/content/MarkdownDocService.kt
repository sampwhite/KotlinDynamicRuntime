package com.dynamicruntime.common.content

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ContentServer
import com.dynamicruntime.common.http.request.ContextFocus
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.startup.ServiceInitializer

/**
 * Serves whole Markdown **documents** verbatim, under the static context root (`ContextRoot.st`). The sibling
 * of [MarkdownFragmentService], and the other half of the content story: a *fragment* file is a bag of small,
 * individually addressable snippets (parsed into a `namespace -> key -> value` map), whereas a *document* is
 * one Markdown file rendered as a page (a README, a help page). The two cannot share a format -- the fragment
 * syntax claims `# ` lines as its delimiters, which are exactly a document's Markdown headings -- so a
 * document is returned as-is and rendered by the caller (`String.renderMarkdown()` in the kernel, so the
 * frontend and backend render identically).
 *
 * Like the fragment server this is a [ContentServer], not a JSON API endpoint: it needs a permanent
 * `Cache-Control` and returns text, and it deliberately does **not** appear in the `/schema/endpoints` catalog.
 *
 * Request shape: `/<staticRoot>/<appId>/doc/<docId:buildId>`, e.g. `/st/myapp/doc/readme:9f3ac1`.
 *  - `appId` is opaque and **ignored for now** (as with fragments).
 *  - `buildId` is a cache-busting content hash ([docBuildId]); it is **stripped and ignored** for the lookup.
 *  - `docId` names the resource read from `md-docs/<docId>.md` on the classpath.
 */
class MarkdownDocService : ServiceInitializer, ContentServer {
    override val serviceName: String = MarkdownDocService.serviceName

    /** Registers this content server with the dispatcher (idempotent). */
    override fun checkInit(cxt: KdrCxt) {
        val requestService = RequestService.get(cxt) ?: return
        requestService.checkInit(cxt)
        requestService.addContentServer(this)
    }

    override fun serve(cxt: KdrCxt, handler: RequestHandler): Boolean {
        if (handler.focus != ContextFocus.static) {
            return false
        }
        // appPath is context-root-stripped: "/<appId>/doc/<docId:buildId>" -> ["", appId, "doc", docWithBuild].
        val segments = handler.appPath.split('/')
        if (segments.size != 4 || segments[2] != docMarker) {
            return false
        }
        val docId = segments[3].substringBefore(':') // strip the ":buildId" cache-busting suffix
        if (!ContentResources.isSafeFileId(docId)) {
            handler.sendStringResponse("Bad document id.", EXC.badInput, "text/plain")
            return true
        }
        val text = ContentResources.readText(resourceDir, docId)
        if (text == null) {
            handler.sendStringResponse("No document '$docId'.", EXC.notFound, "text/plain")
            return true
        }
        // Immutable, long-lived cache: the versioned URL is the cache key, so a new buildId is a new URL.
        handler.setResponseHeader("Cache-Control", MarkdownFragmentService.cacheControl)
        handler.sendStringResponse(text, EXC.ok, contentType)
        return true
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "MarkdownDocService"

        /** The `doc` path segment marking a whole-document request under the static root. */
        const val docMarker = CMK.doc

        /** Classpath resource directory holding the `<docId>.md` document files. */
        const val resourceDir = "md-docs"

        /** Returned as Markdown source; the caller renders it (the frontend with the kernel renderer). */
        const val contentType = "text/markdown; charset=utf-8"

        fun get(cxt: KdrCxt): MarkdownDocService? =
            cxt.instanceConfig.get(serviceName) as? MarkdownDocService

        /**
         * The cache-busting build id for a document (see [ContentResources.buildId]): a memoized content hash,
         * or null if the resource is absent. Used by the code handing the frontend a `docId:buildId` (the home
         * UI-config endpoint); the document request itself only strips it.
         */
        fun docBuildId(docId: String): String? = ContentResources.buildId(resourceDir, docId)
    }
}
