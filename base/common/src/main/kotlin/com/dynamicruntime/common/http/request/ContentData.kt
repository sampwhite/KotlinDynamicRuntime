package com.dynamicruntime.common.http.request

/**
 * How a response body is to be *handled* — as distinct from what it contains. A body needs more said about it
 * than its bytes: what kind of thing it is ([mimeType]), whether the browser should render it or download it
 * ([inLine]), what to call it if saved ([saveAsFilename]), and what identifies this exact content ([hash]).
 * [RequestHandler.sendBytesResponse] and [RequestHandler.sendStringResponse] take one of these.
 *
 * It exists because those grew a bare `mimeType` parameter, which is the only one of the four a caller could
 * express. Serving a PDF or a CSV export means deciding *download, called this* — a decision with nowhere to
 * live. Adding parameters one at a time would push that choice onto every call site; a single value carries it
 * from the [ContentServer] that knows the answer to the handler that writes the header.
 *
 * A caller that only has a MIME type says nothing more: the [RequestHandler] overloads taking a bare
 * `mimeType` build the plain-inline default, so the app's own assets stay one-liners.
 */
class ContentData(
    /** The `Content-Type` of the body, e.g. `image/png` or `text/css; charset=utf-8`. */
    val mimeType: String,

    /**
     * The name to save the body under, or null for none. A *suggestion* to the browser, and only that: it is
     * advisory when [inLine] (used if the reader chooses to save), and the offered name when not.
     *
     * Sanitized before it reaches the header ([contentDispositionHeader]) — it is the one field here likely to
     * be built from data rather than a constant, so it is treated as untrusted.
     */
    val saveAsFilename: String? = null,

    /**
     * True (the default) to let the browser render the body in place; false to make it a download. Maps to
     * `Content-Disposition: inline` / `attachment`.
     *
     * The default is true because it is what the runtime serves today — pages, styles, scripts, icons — and
     * because it matches HTTP's own default, so a caller that says nothing gets standard behaviour.
     *
     * Named `inLine`, not `inline`, to stay clear of the Kotlin modifier of that name.
     */
    val inLine: Boolean = true,

    /**
     * A content hash identifying exactly these bytes, or null when none was computed. **Carried, not yet
     * acted on** — nothing reads it today.
     *
     * It is here because the callers that will want it are the ones already computing it: [ContentResources]
     * hashes content (CRC32) to build the cache-busting `buildId` in a content URL. The intended use is an
     * `ETag`, letting a conditional request (`If-None-Match`) be answered with a 304 rather than the body.
     * Deliberately not wired up: an ETag is a cache-correctness contract, and it should be added when
     * something needs it, together with the conditional-request handling that makes it mean anything.
     */
    val hash: String? = null,
) {
    /**
     * The `Content-Disposition` value for this content, or **null when the header should be omitted** —
     * plain-inline content needs no header, since `inline` is HTTP's default and saying so adds nothing.
     *
     * The filename is sanitized to a bare, safe name ([safeFilename]): the raw value could carry a `"` and
     * break out of the quoted string, or a CR/LF and inject a header of the attacker's choosing, and could
     * name a path rather than a file. A name that is not plain ASCII is *additionally* carried in RFC 6266's
     * `filename*` form, which is how a non-ASCII name survives at all; the plain `filename` keeps an ASCII
     * approximation for anything that ignores it.
     */
    fun contentDispositionHeader(): String? {
        val disposition = if (inLine) inlineDisposition else attachmentDisposition
        val name = saveAsFilename?.let { safeFilename(it) }
        if (name.isNullOrEmpty()) {
            // With no name to offer, only "attachment" carries information; "inline" is already the default.
            return if (inLine) null else disposition
        }
        // The quoted-string form takes ASCII only, and neither a quote nor a backslash can appear in it: a
        // quote would close the string early and let what follows be read as parameters. Both are replaced
        // rather than backslash-escaped -- escaping is legal but notoriously unevenly parsed, and a filename
        // is a suggestion, so a degraded name beats an ambiguous header.
        val ascii = name.map { if (it.code in 0x20..0x7E && it != '"' && it != '\\') it else '_' }.joinToString("")
        val header = "$disposition; filename=\"$ascii\""
        // Only a genuinely non-ASCII name needs the extended form. Adding it merely because the quoted form
        // escaped a quote would hand back the very character that was just removed.
        val needsExtended = name.any { it.code > 0x7E }
        return if (needsExtended) "$header; filename*=UTF-8''${rfc5987Encode(name)}" else header
    }

    companion object {
        const val contentDispositionKey = "Content-Disposition"
        const val inlineDisposition = "inline"
        const val attachmentDisposition = "attachment"

        /** Longest filename offered; long enough for any real name, short enough to bound the header. */
        const val maxFilenameLength = 200

        /**
         * Reduces [raw] to a bare filename safe to put in a header: the last path segment (so a value naming a
         * path offers only its file), with control characters — CR and LF above all, which would otherwise let
         * the value inject headers of its own — removed, and length bounded. May return an empty string, which
         * means "offer no name".
         */
        fun safeFilename(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            return base.filter { it.code >= 0x20 && it.code != 0x7F }.trim().take(maxFilenameLength)
        }

        /** Percent-encodes [name] for RFC 5987 / RFC 6266's `filename*`: UTF-8 bytes, `attr-char` kept raw. */
        fun rfc5987Encode(name: String): String {
            val out = StringBuilder()
            for (byte in name.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt().toChar()
                if (byte >= 0 && (c.isLetterOrDigit() || c in "!#$&+-.^_`|~")) {
                    out.append(c)
                } else {
                    out.append('%').append("%02X".format(byte.toInt() and 0xFF))
                }
            }
            return out.toString()
        }
    }
}
