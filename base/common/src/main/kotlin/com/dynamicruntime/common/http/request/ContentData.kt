package com.dynamicruntime.common.http.request

import com.dynamicruntime.common.exception.KdrException

/**
 * A piece of content and everything needed to handle it: the content itself ([bytes] or [text]), what kind of
 * thing it is ([mimeType]), whether it is meant to be rendered or downloaded ([inLine]), what to call it if
 * saved ([saveAsFilename]), and what identifies these exact bytes ([hash]).
 *
 * It is one value so that content can be *passed around* — which is what the coming file interface needs, and
 * why the content lives here rather than beside it. Uploading and downloading are the same content travelling
 * in opposite directions: a download is a [ContentData] handed to [RequestHandler.sendContentResponse], and an
 * upload is a [ContentData] built from the request. A type that described content but did not contain it would
 * force every layer in between to carry a body alongside it and keep the two in step.
 *
 * **[isBinary] says which of [bytes] and [text] is the content**, and is stated rather than inferred. It is
 * not the same question as "is [bytes] populated": a file read from disk arrives as bytes whether or not it is
 * text, and the MIME type cannot settle it either — SVG is an image that *is* text, while `application/json`
 * is text that is not an image. The two constructors below set the flag to match the content they are given,
 * so the ordinary paths cannot disagree with themselves.
 *
 * A caller that only has a MIME type still says nothing more: the [RequestHandler] overloads taking a bare
 * `mimeType` build the plain-inline default, so the app's own assets stay one-liners.
 */
class ContentData(
    /** The `Content-Type` of the content, e.g. `image/png` or `text/css; charset=utf-8`. */
    val mimeType: String,

    /** True when [bytes] carries the content, false when [text] does. See the class note: this is a statement,
     *  not a guess at which field happens to be populated. */
    val isBinary: Boolean,

    /** The content when [isBinary]; null otherwise. */
    val bytes: ByteArray? = null,

    /** The content when not [isBinary]; null otherwise. */
    val text: String? = null,

    /**
     * The name to save the content under, or null for none. A *suggestion* to the browser, and only that: it
     * is advisory when [inLine] (used if the reader chooses to save), and the offered name when not.
     *
     * Sanitized before it reaches the header ([contentDispositionHeader]) — it is the field here most likely
     * to be built from data rather than a constant, so it is treated as untrusted.
     */
    val saveAsFilename: String? = null,

    /**
     * True (the default) to let the browser render the content in place; false to make it a download. Maps to
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
     * It is here because the callers that will want it are the ones already computing it: `ContentResources`
     * hashes content (CRC32) to build the cache-busting `buildId` in a content URL. The intended use is an
     * `ETag`, letting a conditional request (`If-None-Match`) be answered with a 304 rather than the body.
     * Deliberately not wired up: an ETag is a cache-correctness contract, and it should be added when
     * something needs it, together with the conditional-request handling that makes it mean anything.
     */
    val hash: String? = null,
) {
    init {
        // The flag and the content have to agree, or a caller reads the wrong field and finds nothing. Caught
        // here rather than left to surface downstream as an empty response or a mystery NPE. Note "empty" is
        // a legitimate value -- a zero-length upload is ByteArray(0), not null.
        if (isBinary && bytes == null) {
            throw KdrException("ContentData is binary ('$mimeType') but carries no bytes.")
        }
        if (!isBinary && text == null) {
            throw KdrException("ContentData is text ('$mimeType') but carries no text.")
        }
    }

    /** Binary content: an image, a PDF, an uploaded file. Sets [isBinary]. */
    constructor(
        bytes: ByteArray,
        mimeType: String,
        saveAsFilename: String? = null,
        inLine: Boolean = true,
        hash: String? = null,
    ) : this(mimeType, isBinary = true, bytes = bytes, saveAsFilename = saveAsFilename, inLine = inLine, hash = hash)

    /** Text content: a page, a stylesheet, JSON, a CSV export. Clears [isBinary]. */
    constructor(
        text: String,
        mimeType: String,
        saveAsFilename: String? = null,
        inLine: Boolean = true,
        hash: String? = null,
    ) : this(mimeType, isBinary = false, text = text, saveAsFilename = saveAsFilename, inLine = inLine, hash = hash)

    /**
     * The content as bytes, whatever it is carried as — [text] is UTF-8 encoded, which is lossless in that
     * direction. For writing content to a file or a stream, which does not care which of the two it was.
     *
     * There is deliberately no counterpart returning the content as a String: decoding arbitrary bytes as text
     * is *not* lossless (every byte that is not valid UTF-8 becomes U+FFFD), and offering it would invite
     * exactly the corruption [RequestHandler.sendBytesResponse] exists to avoid. Read [text] when not
     * [isBinary].
     */
    fun contentBytes(): ByteArray = if (isBinary) bytes ?: ByteArray(0) else (text ?: "").encodeToByteArray()

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
