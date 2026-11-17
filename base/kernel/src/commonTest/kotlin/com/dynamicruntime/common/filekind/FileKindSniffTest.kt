package com.dynamicruntime.common.filekind

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A file's kind is told by how its bytes begin, read narrowly (issue #1834). */
class FileKindSniffTest {
    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    private fun ascii(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }

    /** An MP4-style file: a box size, `ftyp`, [brand], a minor version, and [compatible] brands. */
    private fun ftyp(brand: String, vararg compatible: String): ByteArray {
        val box = ascii("ftyp") + ascii(brand) + ByteArray(4) + compatible.fold(ByteArray(0)) { acc, b -> acc + ascii(b) }
        return bytes(0, 0, 0, box.size + 4) + box + ByteArray(20)
    }

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
    fun anMp4IsAlsoToldByItsCompatibleBrands() {
        // A camera may write its own brand as the major one and list `isom` after it.
        assertEquals(FileKinds.mp4, FileKindSniff.sniff(ftyp("XAVC", "XAVC", "mp42", "iso2")))
        assertEquals(FileKinds.mp4, FileKindSniff.sniff(ftyp("MSNV", "MSNV", "isom")))
        assertNull(FileKindSniff.sniff(ftyp("XAVC", "XAVC")))
        // An image's major brand refuses it, however MP4-like the brands it lists.
        assertNull(FileKindSniff.sniff(ftyp("heic", "mif1", "heic", "iso8")))
        assertNull(FileKindSniff.sniff(ftyp("avif", "avif", "mif1", "miaf", "isom")))
        // A brand past the box's own size is not one of its brands.
        val short = bytes(0, 0, 0, 16) + ascii("ftyp") + ascii("XAVC") + ByteArray(4) + ascii("isom") + ByteArray(20)
        assertNull(FileKindSniff.sniff(short))
    }

    @Test
    fun everyKindWithAMarkHasItsRule() {
        // A data kind added without a rule would be offered to forms and never recognized in an upload.
        for (kind in FileKinds.kinds + FileKinds.ico) assertTrue(FileKindSniff.hasRule(kind), kind.id)
        assertTrue(!FileKindSniff.hasRule(FileKinds.svg))
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
