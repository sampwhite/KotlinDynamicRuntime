package com.dynamicruntime.common.filekind

import com.dynamicruntime.common.exception.KdrException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An MP4's video codecs are found by walking its boxes, a range at a time (issue #1837). */
class Mp4VideoTest {
    private fun ascii(s: String): ByteArray = s.encodeToByteArray()
    private fun u32(n: Long): ByteArray = ByteArray(4) { (n ushr (24 - 8 * it)).toByte() }
    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { acc, b -> acc + b }
        return u32(8L + body.size) + ascii(type) + body
    }

    private fun track(handler: String, codec: String): ByteArray {
        val hdlr = box("hdlr", ByteArray(4), ByteArray(4), ascii(handler), ByteArray(12))
        val stsd = box("stsd", ByteArray(4), u32(1), box(codec, ByteArray(20)))
        return box("trak", box("tkhd", ByteArray(84)), box("mdia", hdlr, box("minf", box("stbl", stsd))))
    }

    private val ftyp = box("ftyp", ascii("isom"), ByteArray(4), ascii("isom"))

    /** The codecs of [file], read a range at a time, counting the reads. */
    private fun codecs(file: ByteArray, reads: MutableList<Pair<Long, Int>> = mutableListOf()): List<String> =
        Mp4Video.videoCodecs(file.size.toLong()) { offset, length ->
            reads += offset to length
            file.copyOfRange(offset.toInt(), offset.toInt() + length)
        }

    @Test
    fun theVideoTracksCodecIsFoundAndASoundTrackIsNot() {
        val file = ftyp + box("moov", track("soun", "mp4a"), track("vide", "avc1")) + box("mdat", ByteArray(100))
        assertEquals(listOf("avc1"), codecs(file))
        assertNull(Mp4Video.h264Problem(listOf("avc1")))
    }

    @Test
    fun anIndexAfterTheDataIsReachedByHeadersAloneNotByReadingTheData() {
        // A 64-bit `mdat` before the index, as a long recording has: skipped by its size, never read.
        val data = ByteArray(5000)
        val bigMdat = u32(1) + ascii("mdat") + ByteArray(4) + u32(16L + data.size) + data
        val file = ftyp + bigMdat + box("moov", track("vide", "avc3"))
        val reads = mutableListOf<Pair<Long, Int>>()
        assertEquals(listOf("avc3"), codecs(file, reads))
        assertTrue(reads.none { it.second > 1000 && it.first == ftyp.size.toLong() }, reads.toString())
    }

    @Test
    fun anythingButH264IsAProblemSaidForTheUploader() {
        assertTrue(Mp4Video.h264Problem(listOf("hvc1"))!!.contains("HEVC"))
        assertTrue(Mp4Video.h264Problem(listOf("av01"))!!.contains("'av01'"))
        // A codec a person knows by name is said by it, beside its code (issue #1840): a QuickTime master's ProRes.
        assertTrue(Mp4Video.h264Problem(listOf("apcn"))!!.contains("ProRes ('apcn')"))
        assertTrue(Mp4Video.h264Problem(emptyList())!!.contains("no video track"))
    }

    @Test
    fun boxesThatMakeNoMp4AreRefused() {
        assertFailsWith<KdrException> { codecs(ftyp + box("mdat", ByteArray(10))) }
        assertFailsWith<KdrException> { codecs(ftyp + u32(99999) + ascii("moov")) }
        assertFailsWith<KdrException> { codecs(ftyp + u32(4) + ascii("free")) }
    }
}
