package com.dynamicruntime.common.http.request

/** What a request for some content's bytes is answered with (issue #1836): see [ByteRanges.resolve]. */
sealed class RangeAnswer {
    /** The whole content, with a 200. */
    object Full : RangeAnswer()

    /** The bytes [start] to [end], both included, with a 206 and a `Content-Range`. */
    class Partial(val start: Long, val end: Long) : RangeAnswer() {
        val length: Long get() = end - start + 1
    }

    /** No body: the caller's copy, named by its `If-None-Match`, is current -- a 304. */
    object NotModified : RangeAnswer()

    /** A range that lies wholly past the end of the content: a 416, saying how large the content is. */
    object Unsatisfiable : RangeAnswer()
}

/**
 * How a request's `Range`, `If-Range`, and `If-None-Match` headers are answered for content of a known size and
 * strong entity tag (issue #1836): what a video player seeking through a file relies on. Pure, so every case of RFC
 * 9110's rules is tested apart from a server.
 *
 * **One range, or the whole.** A request for several ranges is answered with the whole content (a 200), which RFC 9110
 * allows -- a server may ignore `Range` -- and no player needs a `multipart/byteranges` answer. A `Range` this does not
 * read (another unit, a malformed list, a first byte past the last) is likewise ignored, never refused: what is refused
 * is only a well-formed range lying wholly past the end, which is the one case a 416 means.
 */
@Suppress("ConstPropertyName")
object ByteRanges {
    const val rangeHeader = "Range"
    const val ifRangeHeader = "If-Range"
    const val ifNoneMatchHeader = "If-None-Match"
    const val acceptRangesHeader = "Accept-Ranges"
    const val contentRangeHeader = "Content-Range"
    const val etagHeader = "ETag"
    const val bytesUnit = "bytes"

    /** The strong entity tag for content of [hash]: the hash in quotes, as HTTP spells one. */
    fun etagOf(hash: String): String = "\"$hash\""

    /** The `Content-Range` of [answer] out of [size] bytes. */
    fun contentRange(answer: RangeAnswer.Partial, size: Long): String = "$bytesUnit ${answer.start}-${answer.end}/$size"

    /** The `Content-Range` of a 416: no range, and the size the caller can ask within. */
    fun unsatisfiedRange(size: Long): String = "$bytesUnit */$size"

    /**
     * How to answer for content of [size] bytes whose strong entity tag is [etag] (null when it has none), given the
     * request's [range], [ifRange], and [ifNoneMatch] headers (null when absent):
     *
     * 1. An `If-None-Match` naming [etag] (or `*`) is [RangeAnswer.NotModified], whatever else is asked.
     * 2. No `Range`, one this does not read, or more than one range is [RangeAnswer.Full].
     * 3. An `If-Range` that is not [etag] exactly -- a date, a weak tag, another tag -- is [RangeAnswer.Full]: the
     *    caller's partial copy is of other bytes, so it is given these whole.
     * 4. Otherwise one range: `first-last`, `first-`, or `-suffixLength`, cut to the content's end. One that starts
     *    at or past the end, or a suffix of nothing, is [RangeAnswer.Unsatisfiable].
     */
    fun resolve(range: String?, ifRange: String?, ifNoneMatch: String?, etag: String?, size: Long): RangeAnswer {
        if (etag != null && ifNoneMatch != null && matchesAny(ifNoneMatch, etag)) return RangeAnswer.NotModified
        val spec = range?.trim()?.takeIf { it.isNotEmpty() } ?: return RangeAnswer.Full
        if (ifRange != null && (etag == null || ifRange.trim() != etag)) return RangeAnswer.Full
        val unitEnd = spec.indexOf('=')
        if (unitEnd < 0 || !spec.substring(0, unitEnd).trim().equals(bytesUnit, ignoreCase = true)) return RangeAnswer.Full
        val ranges = spec.substring(unitEnd + 1).split(',').map { it.trim() }
        if (ranges.size != 1) return RangeAnswer.Full
        val one = ranges.single()
        val dash = one.indexOf('-')
        if (dash < 0) return RangeAnswer.Full
        val firstText = one.substring(0, dash).trim()
        val lastText = one.substring(dash + 1).trim()
        if (firstText.isEmpty()) {
            // A suffix: the last so many bytes.
            val suffix = digits(lastText) ?: return RangeAnswer.Full
            if (suffix == 0L || size == 0L) return RangeAnswer.Unsatisfiable
            return RangeAnswer.Partial(maxOf(0L, size - suffix), size - 1)
        }
        val first = digits(firstText) ?: return RangeAnswer.Full
        val last = if (lastText.isEmpty()) null else (digits(lastText) ?: return RangeAnswer.Full)
        if (last != null && last < first) return RangeAnswer.Full
        if (first >= size) return RangeAnswer.Unsatisfiable
        return RangeAnswer.Partial(first, minOf(last ?: (size - 1), size - 1))
    }

    /** [text] as a whole number when it is digits alone and fits one; null otherwise. */
    private fun digits(text: String): Long? = if (text.isNotEmpty() && text.all { it in '0'..'9' }) text.toLongOrNull() else null

    /** Whether the entity-tag list [header] names [etag] -- weakly, as `If-None-Match` compares -- or is `*`. */
    private fun matchesAny(header: String, etag: String): Boolean {
        val bare = etag.removePrefix("W/")
        return header.split(',').map { it.trim() }.any { it == "*" || it.removePrefix("W/") == bare }
    }
}
