package com.dynamicruntime.common.content

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ContentServer
import com.dynamicruntime.common.http.request.ContextFocus
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.parseMarkdownFragments
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves Markdown *fragment* files as a two-tier `namespace -> (key -> value)` JSON map (issue #59), under the
 * static context root (`ContextRoot.st`). A [ContentServer], not a JSON API endpoint: it needs a permanent
 * `Cache-Control` and returns a free-form map, and it deliberately does **not** appear in the `/schema/endpoints`
 * API catalog. See `webapp/CLAUDE.md` for the frontend-facing contract.
 *
 * Request shape: `/<staticRoot>/<appId>/md/<fileId:buildId>`, e.g. `/st/myapp/md/emailForms:9f3ac1`.
 *  - `appId` is an opaque, frontend-constructed id (application + optional account/locale suffixes). It is
 *    **ignored for now**; a future backend may return different content per `appId`.
 *  - `buildId` is a cache-busting suffix (a content hash; see [fragmentBuildId]). It is **stripped and
 *    ignored** for the lookup -- its only job is to make the URL change when the file changes, so the
 *    permanent cache (browser or CDN) refetches. A rebuild with unchanged content keeps the same URL.
 *  - `fileId` names the resource read from `md-fragments/<fileId>.md` on the classpath. If the owning
 *    component/module is not in the deployment, the resource is simply absent and the request 404s.
 */
class MarkdownFragmentService : ServiceInitializer, ContentServer {
    override val serviceName: String = MarkdownFragmentService.serviceName

    /** Registers this content server with the dispatcher (idempotent). */
    override fun checkInit(cxt: KdrCxt) {
        val requestService = RequestService.get(cxt) ?: return
        requestService.checkInit(cxt)
        requestService.addContentServer(this)
    }

    @Suppress("DuplicatedCode")
    override fun serve(cxt: KdrCxt, handler: RequestHandler): Boolean {
        if (handler.focus != ContextFocus.static) {
            return false
        }
        // appPath is context-root-stripped: "/<appId>/md/<fileId:buildId>" -> ["", appId, "md", fileWithBuild].
        val segments = handler.appPath.split('/')
        if (segments.size != 4 || segments[2] != mdMarker) {
            return false
        }
        val fileId = segments[3].substringBefore(':') // strip the ":buildId" cache-busting suffix
        if (!ContentResources.isSafeFileId(fileId)) {
            handler.sendStringResponse("Bad fragment file id.", EXC.badInput, "text/plain")
            return true
        }
        val text = ContentResources.readText(resourceDir, fileId)
        if (text == null) {
            handler.sendStringResponse("No fragment file '$fileId'.", EXC.notFound, "text/plain")
            return true
        }
        val fragments = text.parseMarkdownFragments()
        // Immutable, long-lived cache: the versioned URL is the cache key, so a new buildId is a new URL.
        handler.setResponseHeader("Cache-Control", cacheControl)
        handler.sendJsonResponse(fragments, EXC.ok)
        return true
    }

    /** Fragment files parsed once and memoized by fileId. Classpath resources today, fixed at build. */
    private val parsedByFileId = ConcurrentHashMap<String, Map<String, Map<String, String>>>()

    /**
     * The value at `<fileId>.md` → [namespace] → [key], or null when the file or entry is absent. Used
     * server-side (issue #108: rendering error copy) rather than over HTTP, and memoized because it is hit per
     * error -- it must not re-read and reparse the source each time.
     *
     * An **instance** method taking [cxt], deliberately not a static helper: the source is the classpath today,
     * but this is where a future version resolves a fragment through [cxt] -- a database, or an HTTP call to
     * another service, for a per-account or per-version copy. Callers reach it via [get]; the seam is in place
     * so that change stays inside here.
     */
    fun resolveFragment(cxt: KdrCxt, fileId: String, namespace: String, key: String): String? {
        val parsed = parsedByFileId.getOrPut(fileId) {
            ContentResources.readText(resourceDir, fileId)?.parseMarkdownFragments() ?: emptyMap()
        }
        return parsed[namespace]?.get(key)
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "MarkdownFragmentService"

        /** The `md` path segment marking a Markdown-fragment request under the static root. */
        const val mdMarker = CMK.md

        /** Classpath resource directory holding the `<fileId>.md` fragment files. */
        const val resourceDir = "md-fragments"

        /** Permanent, shared cache: safe because the `buildId` in the URL changes whenever content changes. */
        const val cacheControl = "public, max-age=31536000, immutable"

        fun get(cxt: KdrCxt): MarkdownFragmentService? =
            cxt.instanceConfig.get(serviceName) as? MarkdownFragmentService

        /**
         * The cache-busting build id for a fragment file (see [ContentResources.buildId]): a memoized content
         * hash, or null if the resource is absent. Used by the code that hands a component its
         * `fileId:buildId` (the UI-config endpoints); the fragment request itself only strips it.
         */
        fun fragmentBuildId(fileId: String): String? = ContentResources.buildId(resourceDir, fileId)
    }
}
