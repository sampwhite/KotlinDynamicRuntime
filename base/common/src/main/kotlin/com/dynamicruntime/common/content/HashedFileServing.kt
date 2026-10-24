package com.dynamicruntime.common.content

import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.filestore.StoredFile
import com.dynamicruntime.common.http.request.ContextFocus
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.WebRequest

/**
 * How a stored file goes out at an address that names it by its content: `/<appId>/<marker>/<name:hash>` under the
 * static root, and its named download beside it. One place, because every server of uploaded files has to answer
 * the same three questions the same way -- a client's web assets (issue #1130) and its file refs (issue #1153)
 * so far -- and a second copy is how two of them come to differ on the one that matters.
 *
 * - **Whose file** is never the address's to say. The address holds a name and a hash; the server decides whose
 *   file that name is from who is signed in, and hands it here.
 * - **How long it may be kept** is [ContentResources]' rule: an address whose hash is the hash of the bytes returned
 *   is kept for good, and every other answer -- another hash, none, no such file -- is `no-store`, so a shared cache
 *   never holds one client's file at an address that does not name it.
 * - **What a browser may do with it** when sent straight to the address rather than drawing it in a page:
 *   [guardHeaders], on every answer.
 */
@Suppress("ConstPropertyName")
object HashedFileServing {
    /** What a static address asks for: a [name], and the [hash] it expects that name's file to have ("" when it gave none). */
    class Address(val name: String, val hash: String)

    /** How many segments `/<appId>/<marker>/<name:hash>` splits into, the leading empty one counted. */
    private const val pathSegments = 4

    /** Tells a browser to take the declared type as given, and never to guess a page out of a file. */
    const val noSniffHeader = "X-Content-Type-Options"
    const val noSniff = "nosniff"

    const val contentPolicyHeader = "Content-Security-Policy"

    /**
     * What an uploaded SVG may do when a browser opens it as a document: draw itself, with its own inline styles,
     * and nothing else -- no script, no request, no form, and no place in this origin (`sandbox`).
     */
    const val svgContentPolicy = "default-src 'none'; style-src 'unsafe-inline'; sandbox"

    const val svgMimeType = "image/svg+xml"

    /**
     * The address [handler]'s request names under [marker], or null when the request is not for it: another focus,
     * another marker, or another shape. Split at the [CMK.nameSep] after the [nameSeps] a name served under [marker]
     * holds -- none for a file ref or a web asset, one for a component image's rooted name (`sample:diagram`, issue
     * #1339) -- the one constant both sides build an address with: whatever follows it is the hash, whatever that
     * holds.
     */
    fun addressOf(handler: RequestHandler, marker: String, nameSeps: Int = 0): Address? {
        if (handler.focus != ContextFocus.static) return null
        // appPath is context-root-stripped: "/<appId>/<marker>/<name:hash>" -> ["", appId, marker, nameWithHash].
        val segments = handler.appPath.split('/')
        if (segments.size != pathSegments || segments[2] != marker) return null
        val named = segments[3]
        var at = -1
        repeat(nameSeps + 1) { at = named.indexOf(CMK.nameSep, at + 1).also { if (it < 0) return Address(named, "") } }
        return Address(named.substring(0, at), named.substring(at + 1))
    }

    /**
     * Answers [address] with [file], or with a 404 saying [missing] when there is none. Always a response: once a
     * server has claimed an address, no other is asked.
     */
    fun send(handler: RequestHandler, address: Address, file: StoredFile?, missing: String) {
        if (file == null) {
            handler.setResponseHeader(ContentResources.cacheControlHeader, ContentResources.noStore)
            // Its words may repeat what the address named, so it too is never to be read as anything but text.
            handler.setResponseHeader(noSniffHeader, noSniff)
            handler.sendStringResponse(missing, EXC.notFound, "text/plain")
            return
        }
        handler.setResponseHeader(
            ContentResources.cacheControlHeader, ContentResources.cacheHeaderFor(address.hash == file.meta.hash),
        )
        guardHeaders(file.meta.mimeType).forEach { (header, value) -> handler.setResponseHeader(header, value) }
        handler.sendBytesResponse(file.bytes, EXC.ok, file.meta.mimeType)
    }

    /**
     * The headers a file of [mimeType] is sent with beyond its type and caching: `nosniff`, and for an SVG its policy.
     *
     * An SVG is told by its type as a browser reads it -- parameters and case aside -- and not by an exact match:
     * a type recorded as `image/svg+xml; charset=utf-8` is drawn as an SVG, and must not go out without the policy
     * that keeps one from running anything.
     */
    fun guardHeaders(mimeType: String): Map<String, String> = buildMap {
        put(noSniffHeader, noSniff)
        if (mimeType.substringBefore(';').trim().lowercase() == svgMimeType) put(contentPolicyHeader, svgContentPolicy)
    }

    /**
     * Sets the headers of a **named download** of [file] on [web]: an API answer behind a sign-in, for an
     * administrator looking at a file that is not their own client's to be served. The static address's rule with one
     * difference -- even an address that carries the file's own hash ([expectedHash]) is the caller's own browser's
     * to keep and no shared cache's, and only for the session that was allowed it, since who may have the file is
     * what varies here.
     */
    fun downloadHeaders(web: WebRequest, file: StoredFile, expectedHash: String?) {
        web.addResponseHeader(ContentResources.cacheControlHeader, ContentResources.privateCacheHeaderFor(expectedHash == file.meta.hash))
        web.addResponseHeader(ContentResources.varyHeader, ContentResources.varyByCookie)
        guardHeaders(file.meta.mimeType).forEach { (header, value) -> web.addResponseHeader(header, value) }
    }
}
