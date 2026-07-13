package com.dynamicruntime.common.content

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.ContentServer
import com.dynamicruntime.common.http.request.ContextFocus
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.RequestService
import com.dynamicruntime.common.startup.ServiceInitializer
import com.dynamicruntime.common.util.parseMarkdownFragments
import java.util.zip.CRC32

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
        if (!isSafeFileId(fileId)) {
            handler.sendStringResponse("Bad fragment file id.", EXC.badInput, "text/plain")
            return true
        }
        val text = readResource("/$resourceDir/$fileId.md")
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

    @Suppress("ConstPropertyName")
    companion object {
        const val serviceName = "MarkdownFragmentService"

        /** The `md` path segment marking a Markdown-fragment request under the static root. */
        const val mdMarker = "md"

        /** Classpath resource directory holding the `<fileId>.md` fragment files. */
        const val resourceDir = "md-fragments"

        /** Permanent, shared cache: safe because the `buildId` in the URL changes whenever content changes. */
        const val cacheControl = "public, max-age=31536000, immutable"

        fun get(cxt: KdrCxt): MarkdownFragmentService? =
            cxt.instanceConfig.get(serviceName) as? MarkdownFragmentService

        /**
         * The cache-busting build id for a fragment file: a content hash (CRC32, hex) of the resource bytes,
         * or null if the resource is absent. A content hash (rather than a timestamp) is jar-agnostic and
         * changes only when the content changes -- so an unchanged file keeps its URL across rebuilds. Used by
         * the (future) code that hands a component its `fileId:buildId`; the endpoint itself only strips it.
         */
        fun fragmentBuildId(fileId: String): String? {
            if (!isSafeFileId(fileId)) return null
            val bytes = readResourceBytes("/$resourceDir/$fileId.md") ?: return null
            val crc = CRC32()
            crc.update(bytes)
            return crc.value.toString(16)
        }

        /** A fragment id must be a plain file-name token (guards the classpath lookup against traversal). */
        private fun isSafeFileId(fileId: String): Boolean =
            fileId.isNotEmpty() && fileId.all { it.isLetterOrDigit() || it == '-' || it == '_' }

        private fun readResourceBytes(path: String): ByteArray? =
            MarkdownFragmentService::class.java.getResourceAsStream(path)?.use { it.readBytes() }

        private fun readResource(path: String): String? =
            readResourceBytes(path)?.toString(Charsets.UTF_8)
    }
}
