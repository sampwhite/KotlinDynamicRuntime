package com.dynamicruntime.common.filekind

import com.dynamicruntime.common.exception.KdrException

/**
 * Which video an MP4 holds (issue #1837), found by walking its boxes: the top level, read a header at a time, to the
 * `moov` box; then, read whole, its tracks (`trak`), each one's handler (`mdia/hdlr`) and its sample description
 * (`mdia/minf/stbl/stsd`), whose entries name each track's codec. A video track is one whose handler is `vide`.
 *
 * **Why a browser cares.** An MP4 is a container; what plays is its codec. Every browser plays H.264 (`avc1`, or
 * `avc3`, which carries its parameters in-band); HEVC (`hvc1`, `hev1`) plays in some and not others. So a data file's
 * MP4 is held to H.264, and HEVC -- what a phone records by default -- is refused with a message saying so, rather
 * than stored to show nothing for half its viewers. A video converted to H.264 is a deferred job.
 *
 * A QuickTime movie is walked the same way (issue #1840): its boxes are the ones an MP4's came from, its index often
 * after its media, which the walk reaches a header at a time.
 *
 * Pure: the bytes come from a reader the caller supplies, a range at a time, so a 2 GB file is read a few headers and
 * one `moov` box at a time -- on S3, a request each.
 *
 * Theme: #DFILE.
 */
@Suppress("ConstPropertyName")
object Mp4Video {
    /** The largest `moov` box read: the index of a long video is a few megabytes; more is no video we take. */
    const val maxMoovBytes = 16 * 1024 * 1024

    /** The most top-level boxes walked looking for `moov`. */
    const val maxTopBoxes = 1000

    /** How deep boxes are walked inside `moov`. */
    const val maxDepth = 12

    /** The codecs a browser plays everywhere: H.264, its parameters in the sample description or in the stream. */
    val h264: Set<String> = setOf("avc1", "avc3")

    /** HEVC (H.265): what a phone records by default, and not played by every browser. */
    val hevc: Set<String> = setOf("hvc1", "hev1")

    private const val videoHandler = "vide"

    /**
     * The codecs of the video tracks of an MP4 of [size] bytes, read through [read] (an offset and a length within
     * the file, answering those bytes). Refuses, as bad input, a file whose boxes do not make an MP4 we can read.
     */
    fun videoCodecs(size: Long, read: (offset: Long, length: Int) -> ByteArray): List<String> {
        var offset = 0L
        var boxes = 0
        while (offset + 8 <= size) {
            if (++boxes > maxTopBoxes) throw bad("it has more than $maxTopBoxes boxes before its index")
            val header = read(offset, minOf(16L, size - offset).toInt())
            val (boxSize, headerSize) = boxSizeOf(header, 0, size - offset) ?: throw bad("a box at $offset has no size it can have")
            val type = typeAt(header, 4)
            if (type == "moov") {
                if (boxSize > maxMoovBytes) throw bad("its index is ${boxSize} bytes, more than the $maxMoovBytes taken")
                val moov = read(offset, boxSize.toInt())
                return tracks(moov, headerSize, moov.size, 0)
                    .filter { it.first == videoHandler }.flatMap { it.second }
            }
            offset += boxSize
        }
        throw bad("it has no index (moov box)")
    }

    /**
     * What stops [codecs], an MP4's video codecs, being a video every browser plays, as a sentence for the uploader;
     * null when nothing does.
     */
    fun h264Problem(codecs: List<String>): String? = when {
        codecs.isEmpty() -> "This MP4 holds no video track. Upload a video recorded or exported as H.264."
        codecs.any { it in hevc } ->
            "This video is HEVC (H.265), which not every browser plays. Export it as H.264 -- most phones and editors " +
                "offer 'Most compatible' or 'H.264' -- and upload that."
        codecs.any { it !in h264 } ->
            "This video's codec is ${codecs.filterNot { it in h264 }.distinct().joinToString(", ") { "'$it'" }}, which not every " +
                "browser plays. Export it as H.264 and upload that."
        else -> null
    }

    /** Each `trak` in [buf] from [start] to [end]: its handler type, and the formats its sample description names. */
    private fun tracks(buf: ByteArray, start: Int, end: Int, depth: Int): List<Pair<String?, List<String>>> =
        children(buf, start, end, depth).filter { it.type == "trak" }.map { trak ->
            val mdia = children(buf, trak.payload, trak.end, depth + 1).firstOrNull { it.type == "mdia" }
                ?: return@map null to emptyList()
            val inMdia = children(buf, mdia.payload, mdia.end, depth + 2)
            // `hdlr` is a full box: version and flags (4), a pre-defined word (4), then the handler type.
            val handler = inMdia.firstOrNull { it.type == "hdlr" }?.let { if (it.payload + 12 <= it.end) typeAt(buf, it.payload + 8) else null }
            val stsd = inMdia.firstOrNull { it.type == "minf" }
                ?.let { minf -> children(buf, minf.payload, minf.end, depth + 3).firstOrNull { it.type == "stbl" } }
                ?.let { stbl -> children(buf, stbl.payload, stbl.end, depth + 4).firstOrNull { it.type == "stsd" } }
            // `stsd` is a full box (4) with an entry count (4); each entry is a box whose type is its format.
            handler to (stsd?.let { children(buf, it.payload + 8, it.end, depth + 5).map { e -> e.type } } ?: emptyList())
        }

    private class Box(val type: String, val payload: Int, val end: Int)

    /** The boxes laid end to end in [buf] from [start] to [end]. */
    private fun children(buf: ByteArray, start: Int, end: Int, depth: Int): List<Box> {
        if (depth > maxDepth) throw bad("its boxes nest deeper than $maxDepth")
        val out = mutableListOf<Box>()
        var at = start
        while (at + 8 <= end) {
            val (size, header) = boxSizeOf(buf, at, (end - at).toLong()) ?: throw bad("a box inside its index has no size it can have")
            out += Box(typeAt(buf, at + 4), at + header, at + size.toInt())
            at += size.toInt()
        }
        return out
    }

    /**
     * A box's size and header length at [at] in [bytes]: its 32-bit size, or the 64-bit one after it when that is 1,
     * or [room] when it is 0 (to the end). Null when the size is less than its header or more than [room].
     */
    private fun boxSizeOf(bytes: ByteArray, at: Int, room: Long): Pair<Long, Int>? {
        if (bytes.size < at + 8) return null
        val small = u32(bytes, at)
        val (size, header) = when (small) {
            0L -> room to 8
            1L -> {
                if (bytes.size < at + 16) return null
                ((u32(bytes, at + 8) shl 32) or u32(bytes, at + 12)) to 16
            }
            else -> small to 8
        }
        return if (size < header || size > room) null else size to header
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun typeAt(b: ByteArray, at: Int): String = b.decodeToString(at, at + 4)

    private fun bad(why: String): KdrException = KdrException.mkInput("This is not an MP4 video we can read: $why.")
}
