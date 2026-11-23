package com.dynamicruntime.webapp

import com.dynamicruntime.common.datafile.DFILE
import com.dynamicruntime.common.datafile.DFSTAT
import com.dynamicruntime.common.filekind.FKIND
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SFMT
import com.dynamicruntime.common.schema.SchType
import com.dynamicruntime.common.schema.parseSchemaTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A form's file field (issue #1840), the pure half: judging a pick, planning and resuming an upload, reading a record. */
class DataFileUploadTest {
    private val mb = 1024L * 1024L

    /** The type of a file field taking [kinds], at most [maxSize] when given, parsed as a backend writes it. */
    private fun fileField(vararg kinds: String, maxSize: Long? = null): SchType {
        val field = mutableMapOf<String, Any?>(
            SCH.type to SCT.string, SCH.format to SFMT.dataFile, SCH.fileKinds to kinds.toList(),
        )
        if (maxSize != null) field[SCH.fileMaxSize] = maxSize
        val schema = mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("f" to field))
        return parseSchemaTypes(mapOf("t.T" to schema)).getValue("t.T").properties.getValue("f").valueType
    }

    private val png =
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(56)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(60)
    private val pdf = "%PDF-1.7\n".encodeToByteArray() + ByteArray(55)

    @Test
    fun aFileFieldDrawsTheFileControlAndAReadOnlyOneItsValue() {
        val field = fileField(FKIND.png)
        assertEquals(ControlKind.DataFile, controlKind(field, required = false, editable = true))
        assertEquals(ControlKind.ReadOnly, controlKind(field, required = true, editable = false))
    }

    @Test
    fun aPickIsJudgedByItsBytesAndItsSizeAgainstTheField() {
        val photo = fileField(FKIND.png, FKIND.jpeg, FKIND.webp, maxSize = 5 * mb)
        assertEquals(FKIND.jpeg, judgePick(photo, jpeg, 1000).kind?.id)
        assertNull(judgePick(photo, jpeg, 1000).problem)

        // A PDF picked for a photo: what it is is said, not just that it is wrong.
        assertEquals("This field takes only PNG, JPEG, or WebP; this file is a PDF file.", judgePick(photo, pdf, 1000).problem)
        assertEquals("This field takes only PNG, JPEG, or WebP; this file is none of these.", judgePick(photo, ByteArray(64), 1000).problem)
        assertEquals("A PNG file here is at most 5 MB; this one is 5.1 MB.", judgePick(photo, png, 5 * mb + 100_000).problem)
        assertEquals("This file is empty.", judgePick(photo, ByteArray(0), 0).problem)

        // With no limit of its own, the kind's is the one in force.
        assertNull(judgePick(fileField(FKIND.png), png, 19 * mb).problem)
        assertTrue(judgePick(fileField(FKIND.png), png, 21 * mb).problem.orEmpty().startsWith("A PNG file here is at most 20 MB"))
    }

    @Test
    fun aTableIsTakenByWhatItIsNot() {
        // A CSV table has no mark: a table field takes a pick that is no other kind, and the server parses it.
        val lines = parseSchemaTypes(
            mapOf(
                "t.Line" to mapOf(SCH.type to SCT.kObject, SCH.properties to mapOf("a" to mapOf(SCH.type to SCT.string))),
                "t.T" to mapOf(
                    SCH.type to SCT.kObject,
                    SCH.properties to mapOf(
                        "f" to mapOf(
                            SCH.type to SCT.string, SCH.format to SFMT.dataFile, SCH.fileKinds to listOf(FKIND.csv),
                            SCH.fileRows to mapOf(SCH.dRef to "t.Line"),
                        ),
                    ),
                ),
            ),
        ).getValue("t.T").properties.getValue("f").valueType
        assertEquals(FKIND.csv, judgePick(lines, "a\r\nx\r\n".encodeToByteArray(), 6).kind?.id)
        assertEquals("This field takes only CSV table; this file is a PNG file.", judgePick(lines, png, 64).problem)
        // A field that takes no table takes no text.
        assertEquals(
            "This field takes only PNG; this file is none of these.",
            judgePick(fileField(FKIND.png), "a\r\nx\r\n".encodeToByteArray(), 6).problem,
        )
    }

    @Test
    fun thePickerOffersTheFieldsKindsAndTheNoteSaysThem() {
        val photo = fileField(FKIND.png, FKIND.jpeg, maxSize = 5 * mb)
        assertEquals(listOf("image/png", ".png", "image/jpeg", "image/jpg", "image/pjpeg", ".jpg"), dataFileAccept(photo))
        assertEquals("PNG or JPEG, up to 5 MB.", dataFileHint(photo))
        // A limit no tighter than the kinds' own is not said.
        assertEquals("PDF.", dataFileHint(fileField(FKIND.pdf)))
        // A kind known by another name says so: a `.mov` is welcome where an MP4 is.
        assertEquals("MP4 video (a QuickTime .mov too) or WebM video.", dataFileHint(fileField(FKIND.mp4, FKIND.webm)))
        assertEquals("A, B, or C", orList(listOf("A", "B", "C")))
    }

    @Test
    fun aSmallFileGoesInOneRequestAndALargeOneInChunksThatResume() {
        val chunk = DFILE.chunkBytes.toLong()
        assertTrue(sentWhole(chunk))
        assertFalse(sentWhole(chunk + 1))

        val size = 2 * chunk + 500
        assertEquals(chunk, chunkEnd(0, size, chunk))
        assertEquals(size, chunkEnd(2 * chunk, size, chunk))

        assertEquals(chunk, resumeOffset(chunk, size, chunk))
        // Every byte taken and not finished: the last chunk again, which finishes it.
        assertEquals(2 * chunk, resumeOffset(size, size, chunk))
        assertEquals(chunk, resumeOffset(2 * chunk, 2 * chunk, chunk))
        assertEquals(50, uploadPercent(size / 2, size))
        assertEquals(0, uploadPercent(0, 0))
    }

    @Test
    fun aFailedRequestIsRetriedResyncedOrStoppedByWhatCameBack() {
        assertEquals(SendFailure.retry, sendFailure(null, false))
        assertEquals(SendFailure.retry, sendFailure(503, false))
        assertEquals(SendFailure.retry, sendFailure(429, false))
        assertEquals(SendFailure.resync, sendFailure(409, true))
        // A 409 that says nowhere -- an upload past its hours -- cannot be gone on with.
        assertEquals(SendFailure.stop, sendFailure(409, false))
        assertEquals(SendFailure.stop, sendFailure(400, false))
        assertEquals(SendFailure.stop, sendFailure(413, false))

        assertEquals(listOf(1000L, 2000L, 4000L, 8000L), (1..uploadRetries).map { retryDelayMs(it) })
        assertNull(retryDelayMs(uploadRetries + 1))

        // A last chunk answered 429 while another request finishes the file is sent again past the claim's lease,
        // since the server renews a working finish's claim, and given up on at three times it.
        assertEquals(finishPollMs, finishingWaitMs(0))
        assertEquals(finishPollMs, finishingWaitMs(DFILE.finishLeaseMs + 1))
        assertNull(finishingWaitMs(3 * DFILE.finishLeaseMs))
    }

    @Test
    fun aListOfFilesIsKeyedByItsFilesNotTheirPlaces() {
        assertEquals(listOf("a#0", "b#0", "a#1", "#0", "#1"), elementKeys(listOf("a", "b", "a", null, "")))
        // Removing the first leaves the others' keys as they were, so each field keeps its own upload.
        assertEquals(elementKeys(listOf("x", "y", "z")).drop(1), elementKeys(listOf("y", "z")))
    }

    @Test
    fun aRecordReadsAsAViewAndAFormsFilesAsMany() {
        val record = mapOf(
            DFILE.fileId to "gd.df.acme.x1", DFILE.name to "wall.png", DFILE.kind to FKIND.png, DFILE.size to 1200L,
            DFILE.status to DFSTAT.ready,
        )
        val view = dataFileViewFrom("ignored", record)
        assertEquals("gd.df.acme.x1", view.fileId)
        assertEquals("wall.png", view.title)
        assertEquals("PNG", view.kind?.label)
        assertTrue(view.readable)

        val unnamed = dataFileViewFrom("gd.df.acme.x2", mapOf(DFILE.kind to FKIND.mp4, DFILE.status to DFSTAT.uploading))
        assertEquals("MP4 video file", unnamed.title)
        assertFalse(unnamed.readable)

        val views = dataFileViewsFrom(mapOf("gd.df.acme.x1" to record, "gd.df.acme.x3" to mapOf(DFILE.kind to FKIND.pdf)))
        assertEquals(listOf("gd.df.acme.x1", "gd.df.acme.x3"), views.map { it.fileId })
    }
}
