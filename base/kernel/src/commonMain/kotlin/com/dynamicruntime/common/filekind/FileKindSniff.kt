package com.dynamicruntime.common.filekind

/**
 * Which kind of file some bytes are, by how they begin (issue #1834): the rule the server decides by and the browser
 * checks with before it uploads anything. Only the first [headSize] bytes are read, so a 2 GB video is judged by a
 * slice of it.
 *
 * Each rule is a file format's own mark, read narrowly:
 *
 * - **PNG, JPEG, GIF, WebP, ICO**: their signatures (WebP's `WEBP` after `RIFF` and a length).
 * - **PDF**: `%PDF-`.
 * - **WebM**: an EBML header whose document type is `webm`. A Matroska file (`matroska`) has the same header and
 *   is refused: a browser plays WebM, not Matroska in general.
 * - **MP4**: an `ftyp` box at offset 4 whose major brand is one of [mp4Brands]. The same box opens HEIC and AVIF
 *   *images* and QuickTime movies, which are not MP4 video, so the brand decides.
 *
 * SVG has no mark -- it is a text document -- and is judged by parsing it, on the server only (`WebAssetCheck`).
 *
 * Theme: #DFILE.
 */
object FileKindSniff {
    /** How many bytes of a file the rules read: enough for an EBML header's document type. */
    const val headSize = 64

    private val pngStart = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private val jpegStart = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val icoStart = byteArrayOf(0, 0, 1, 0)
    private val gif87 = ascii("GIF87a")
    private val gif89 = ascii("GIF89a")
    private val riff = ascii("RIFF")
    private val webpMark = ascii("WEBP")
    private val pdfStart = ascii("%PDF-")
    private val ebmlStart = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    /** EBML's `DocType` element id, followed in a WebM file by its length and `webm`. */
    private val ebmlDocType = byteArrayOf(0x42, 0x82.toByte())
    private val webmName = ascii("webm")
    private val ftyp = ascii("ftyp")

    /** Where a WebP's own mark sits: after `RIFF` and the four bytes of its length. */
    private const val webpMarkAt = 8

    /** Where an MP4's `ftyp` box type sits: after the four bytes of the box's size. */
    private const val ftypAt = 4

    /** Where the major brand sits: after the box's size and type. */
    private const val brandAt = 8

    /**
     * The major brands that make an `ftyp` file MP4 video a browser plays: the ISO base media brands, MP4 versions 1
     * and 2, H.264's own, Apple's M4V, and DASH segments. Not `qt  ` (QuickTime), nor `heic`, `mif1`, or `avif`
     * (still images in the same container).
     */
    val mp4Brands: Set<String> = setOf("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ", "dash")

    private fun ascii(s: String): ByteArray = ByteArray(s.length) { s[it].code.toByte() }

    private fun ByteArray.holds(mark: ByteArray, at: Int = 0): Boolean =
        size >= at + mark.size && mark.indices.all { this[at + it] == mark[it] }

    private fun ByteArray.asciiAt(at: Int, length: Int): String? =
        if (size < at + length) null else (at until at + length).map { this[it].toInt().toChar() }.joinToString("")

    /** Whether [head], the start of a file, holds [kind]'s mark. False for a kind with no mark ([FKIND.svg]). */
    fun matches(kind: FileKind, head: ByteArray): Boolean = when (kind.id) {
        FKIND.png -> head.holds(pngStart)
        FKIND.jpeg -> head.holds(jpegStart)
        FKIND.gif -> head.holds(gif87) || head.holds(gif89)
        FKIND.webp -> head.holds(riff) && head.holds(webpMark, webpMarkAt)
        FKIND.ico -> head.holds(icoStart)
        FKIND.pdf -> head.holds(pdfStart)
        FKIND.webm -> head.holds(ebmlStart) && isWebmDocType(head)
        FKIND.mp4 -> head.holds(ftyp, ftypAt) && head.asciiAt(brandAt, 4) in mp4Brands
        else -> false
    }

    /** Whether the EBML header in [head] names its document type `webm`. */
    private fun isWebmDocType(head: ByteArray): Boolean {
        val limit = minOf(head.size, headSize)
        for (at in ebmlStart.size until limit - ebmlDocType.size) {
            if (head.holds(ebmlDocType, at)) {
                // The element's size is one EBML varint byte: 0x84 says four bytes of name follow.
                val sizeAt = at + ebmlDocType.size
                return sizeAt < limit && head[sizeAt] == 0x84.toByte() && head.holds(webmName, sizeAt + 1)
            }
        }
        return false
    }

    /**
     * The first of [among] whose mark [head] holds, or null when it holds none of theirs. Order matters where marks
     * are weak -- an ICO's is four common bytes -- so a caller lists such a kind last.
     */
    fun sniff(head: ByteArray, among: List<FileKind> = FileKinds.kinds): FileKind? = among.firstOrNull { matches(it, head) }
}
