package com.dynamicruntime.common.filekind

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A file's kind is told by how its bytes begin, read narrowly (issue #1834). */
class FileKindSniffTest {
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }

    /** An MP4-style file: a box size, `ftyp`, then [brand]. */
    private fun ftyp(brand: String): ByteArray = bytes(0, 0, 0, 0x20) + ascii("ftyp") + ascii(brand) + ByteArray(20)

    /** An EBML header whose `DocType` is [docType]. */
    private fun ebml(docType: String): ByteArray =
        bytes(0x1A, 0x45, 0xDF, 0xA3, 0x9F, 0x42, 0x86, 0x81, 0x01, 0x42, 0x82, 0x80 or docType.length) + ascii(docType) +
            ByteArray(16)

    private val png = bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
    private val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)
    private val gif = ascii("GIF89a") + ByteArray(6)
    private val webp = ascii("RIFF") + bytes(1, 2, 3, 4) + ascii("WEBPVP8 ")
    private val pdf = ascii("%PDF-1.7\n")

    @Test
    fun eachKindIsToldByItsMark() {
        assertEquals(FileKinds.png, FileKindSniff.sniff(png))
        assertEquals(FileKinds.jpeg, FileKindSniff.sniff(jpeg))
        assertEquals(FileKinds.gif, FileKindSniff.sniff(gif))
        assertEquals(FileKinds.gif, FileKindSniff.sniff(ascii("GIF87a") + ByteArray(6)))
        assertEquals(FileKinds.webp, FileKindSniff.sniff(webp))
        assertEquals(FileKinds.pdf, FileKindSniff.sniff(pdf))
        assertEquals(FileKinds.webm, FileKindSniff.sniff(ebml("webm")))
        for (brand in FileKindSniff.mp4Brands) assertEquals(FileKinds.mp4, FileKindSniff.sniff(ftyp(brand)), brand)
    }

    @Test
    fun aContainerSharedWithOtherFormatsIsToldByItsBrandOrType() {
        // HEIC and AVIF images, and QuickTime movies, open with the same `ftyp` box as MP4.
        for (brand in listOf("heic", "mif1", "avif", "qt  ")) assertNull(FileKindSniff.sniff(ftyp(brand)), brand)
        // Matroska has WebM's header, and a browser does not play Matroska in general.
        assertNull(FileKindSniff.sniff(ebml("matroska")))
    }

    @Test
    fun filesDressedAsAKindTheyAreNotAreNone() {
        assertNull(FileKindSniff.sniff(ascii("<!DOCTYPE html><html><script>alert(1)</script>")))
        assertNull(FileKindSniff.sniff(ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"/>")))
        assertNull(FileKindSniff.sniff(ascii("PDF-1.7 not quite")))
        assertNull(FileKindSniff.sniff(ascii("RIFF") + bytes(1, 2, 3, 4) + ascii("WAVEfmt ")))
        assertNull(FileKindSniff.sniff(ByteArray(0)))
        assertNull(FileKindSniff.sniff(bytes(0x89, 'P'.code)))
    }

    @Test
    fun theWeakIconMarkIsNoDataKindAndOnlyFoundWhenAsked() {
        val ico = bytes(0, 0, 1, 0, 1, 0, 16, 16)
        assertNull(FileKindSniff.sniff(ico))
        assertEquals(FileKinds.ico, FileKindSniff.sniff(ico, listOf(FileKinds.png, FileKinds.ico)))
        // A PNG is found ahead of an ICO, which is why a caller lists the ICO last.
        assertEquals(FileKinds.png, FileKindSniff.sniff(png, listOf(FileKinds.png, FileKinds.ico)))
    }

    @Test
    fun aDeclaredTypeNamesAKindOnlyAmongThoseAsked() {
        assertEquals(FileKinds.jpeg, FileKinds.declaredKind("IMAGE/JPG; q=1"))
        assertEquals(FileKinds.mp4, FileKinds.declaredKind("video/mp4"))
        assertNull(FileKinds.declaredKind("image/svg+xml"))
        assertNull(FileKinds.declaredKind("text/html"))
        assertTrue("image/pjpeg" in FileKinds.acceptedMimeTypes(listOf(FileKinds.jpeg)))
    }

    @Test
    fun noDataKindIsAScriptableOne() {
        val ids = FileKinds.kinds.map { it.id }
        assertTrue(FKIND.svg !in ids && FKIND.ico !in ids, ids.toString())
        assertTrue(FileKinds.kinds.none { "html" in it.mimeType || "script" in it.mimeType || "svg" in it.mimeType })
        assertEquals(FileDisposition.attachment, FileKinds.pdf.disposition)
        // Video stays under the 5 GB a single S3 copy moves.
        assertTrue(FileKinds.kinds.all { it.maxSize < 5L * 1024 * 1024 * 1024 })
    }
}
