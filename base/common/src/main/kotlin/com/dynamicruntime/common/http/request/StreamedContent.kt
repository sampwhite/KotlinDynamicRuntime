package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.exception.KdrException
import java.io.InputStream

/**
 * Content sent as a stream, a range at a time (issue #1836): what a handler returns for a file too large to hold --
 * a video -- where [ContentData] holds its bytes. The response honors HTTP byte ranges, so a player seeks by asking
 * for the part it needs ([ByteRanges]), and nothing is read but the bytes sent.
 *
 * [open] is asked for one range, `offset` and `length` within [size], and answers a stream the response reads to its
 * end and closes. It is asked after the answer is decided and before anything is sent, so a failure to open is an
 * ordinary error response; one while the bytes are going out can only end the connection, the status being sent.
 *
 * [hash] is the content's own (a SHA-256), and becomes its strong `ETag`, which a range request is checked against
 * (`If-Range`) and a conditional one answered by (`If-None-Match`). Content with no hash is still served in ranges,
 * with no tag to make them conditional.
 */
class StreamedContent(
    /** The `Content-Type` of the content. */
    val mimeType: String,
    /** How many bytes the content is. */
    val size: Long,
    /** The content's hash, its strong entity tag; null when it has none. */
    val hash: String? = null,
    /** The name offered when the content is saved, as [ContentData.saveAsFilename]. */
    val saveAsFilename: String? = null,
    /** True to let the browser show the content, false to make it a download, as [ContentData.inLine]. */
    val inLine: Boolean = true,
    /** Opens the bytes from `offset`, `length` of them. */
    val open: (offset: Long, length: Long) -> InputStream,
) {
    init {
        if (size < 0) throw KdrException("Content cannot be $size bytes.")
    }

    /** The `Content-Disposition` value, or null when the header adds nothing, as [ContentData.contentDispositionHeader]. */
    fun contentDispositionHeader(): String? = ContentData.dispositionHeader(inLine, saveAsFilename)

    /**
     * Whether the content is media a browser plays or draws -- an image, a video, a sound -- rather than a document it
     * would render as a page of the app's own origin. SVG is a document, whatever its type says.
     */
    fun isMedia(): Boolean {
        val bare = mimeType.substringBefore(';').trim().lowercase()
        return bare != svgType && mediaFamilies.any { bare.startsWith(it) }
    }

    @Suppress("ConstPropertyName")
    companion object {
        const val svgType = "image/svg+xml"
        val mediaFamilies = listOf("image/", "video/", "audio/")
    }
}
