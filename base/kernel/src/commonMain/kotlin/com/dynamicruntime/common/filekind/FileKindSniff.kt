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
 * - **MP4**: an `ftyp` box at offset 4 naming one of [mp4Brands], as its major brand or among the compatible brands
 *   the box lists -- a camera may write a brand of its own as the major one and list `isom` after it. A QuickTime
 *   movie (`qt  `, what a Mac or an iPhone exports, whatever its file is named) is the same box structure and counts
 *   as one (issue #1840). The same box opens HEIC and AVIF *images*, so a major brand in [notMp4Brands] refuses the
 *   file whatever it lists. Whether the video inside is one a browser plays is judged when the upload finishes, not
 *   here: a QuickTime movie of ProRes is refused then, as an MP4 of HEVC is.
 *
 * SVG has no mark -- it is a text document -- and is judged by parsing it, on the server only (`WebAssetCheck`). Nor
 * has CSV ([FileKinds.parsedKinds]), which an upload finish parses whole; no rule here answers it.
 * Every kind with a mark has its rule in [rules], one place, which a test holds to the registry's kinds.
 *
 * Theme: #DFILE.
 */
@Suppress("ConstPropertyName")
object FileKindSniff {
    /** How many bytes of a file the rules read: enough for an EBML header's document type and an `ftyp` box's brands. */
    const val headSize = 64

    private val pngStart = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private val jpegStart = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val icoStart = byteArrayOf(0, 0, 1, 0)
    private val gif87 = "GIF87a".encodeToByteArray()
    private val gif89 = "GIF89a".encodeToByteArray()
    private val riff = "RIFF".encodeToByteArray()
    private val webpMark = "WEBP".encodeToByteArray()
    private val pdfStart = "%PDF-".encodeToByteArray()
    private val ebmlStart = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    /** EBML's `DocType` element id, followed in a WebM file by its length and `webm`. */
    private val ebmlDocType = byteArrayOf(0x42, 0x82.toByte())
    private val webmName = "webm".encodeToByteArray()
    private val ftyp = "ftyp".encodeToByteArray()

    /** Where a WebP's own mark sits: after `RIFF` and the four bytes of its length. */
    private const val webpMarkAt = 8

    /** Where an MP4's `ftyp` box type sits: after the four bytes of the box's size. */
    private const val ftypAt = 4

    /** Where the major brand sits: after the box's size and type. */
    private const val majorBrandAt = 8

    /** Where the compatible brands begin: after the major brand and the four bytes of its minor version. */
    private const val compatibleBrandsAt = 16

    private const val brandSize = 4

    /**
     * The brands that make an `ftyp` file MP4: the ISO base media brands, MP4 versions 1 and 2, H.264's own, Apple's
     * M4V, DASH segments, and QuickTime's (`qt  `), whose movies a browser plays as MP4 when their video is H.264.
     */
    val mp4Brands: Set<String> = setOf(
        "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "iso7", "iso8", "iso9", "mp41", "mp42", "avc1", "M4V ", "dash", "qt  ",
    )

    /**
     * Major brands that are not MP4 video however MP4-like the brands they list: the still images and image sequences
     * of HEIF and AVIF, which list ISO brands too.
     */
    val notMp4Brands: Set<String> = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1", "avif", "avis")

    private fun ByteArray.holds(mark: ByteArray, at: Int = 0): Boolean =
        size >= at + mark.size && mark.indices.all { this[at + it] == mark[it] }

    private fun ByteArray.asciiAt(at: Int, length: Int): String? =
        if (size < at + length) null else decodeToString(at, at + length)

    /** The rule for each kind with a mark, by kind id. */
    private val rules: Map<String, (ByteArray) -> Boolean> = mapOf(
        FKIND.png to { head -> head.holds(pngStart) },
        FKIND.jpeg to { head -> head.holds(jpegStart) },
        FKIND.gif to { head -> head.holds(gif87) || head.holds(gif89) },
        FKIND.webp to { head -> head.holds(riff) && head.holds(webpMark, webpMarkAt) },
        FKIND.ico to { head -> head.holds(icoStart) },
        FKIND.pdf to { head -> head.holds(pdfStart) },
        FKIND.webm to { head -> head.holds(ebmlStart) && isWebmDocType(head) },
        FKIND.mp4 to { head -> isMp4(head) },
    )

    /** Whether [kind] has a rule here: every data kind must but a parsed one ([FileKinds.parsedKinds]), and SVG has none. */
    fun hasRule(kind: FileKind): Boolean = kind.id in rules

    /** Whether [head], the start of a file, holds [kind]'s mark. False for a kind with no mark ([FKIND.svg]). */
    fun matches(kind: FileKind, head: ByteArray): Boolean = rules[kind.id]?.invoke(head) == true

    /** Whether [head] opens with an `ftyp` box naming an MP4 brand, major or compatible, and no brand that is not MP4. */
    private fun isMp4(head: ByteArray): Boolean {
        if (!head.holds(ftyp, ftypAt)) return false
        val major = head.asciiAt(majorBrandAt, brandSize) ?: return false
        if (major in notMp4Brands) return false
        if (major in mp4Brands) return true
        // The box's own size bounds its brands; only those within the bytes read are seen.
        val boxSize = ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or
            ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
        val end = minOf(head.size, headSize, boxSize.coerceAtLeast(0))
        return (compatibleBrandsAt..end - brandSize step brandSize).any { head.asciiAt(it, brandSize) in mp4Brands }
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
