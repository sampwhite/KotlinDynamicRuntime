package com.dynamicruntime.common.filekind

/**
 * Whether a file of a kind is shown in the page or only offered as a download (issue #1834). A closed operational set:
 * it decides the `Content-Disposition` a file is served with, and nothing a client configures may add to it.
 */
@Suppress("EnumEntryName")
enum class FileDisposition {
    /** Shown where it is linked: an image drawn, a video played. */
    inline,

    /** Offered only as a download, never rendered by the browser on the app's own origin. */
    attachment,
}

/**
 * One kind of file (issues #1130, #1834): what a person calls it, the content type it is stored and served as, the
 * other types an upload may honestly declare it under -- browsers do not agree on an ICO's -- and, for the kinds a
 * trait's file field may hold ([FileKinds.kinds]), the largest file of the kind and whether it is shown or downloaded.
 *
 * **A kind is told by its bytes** ([FileKindSniff]), never by the type an upload declares or by a file's name. Which
 * kinds there are is code, not configuration: each is a promise that its bytes have been looked at and that serving
 * them from the app's own origin is safe, which is why HTML, script, and SVG are never among the data kinds.
 */
class FileKind(
    /** The kind's id ([FKIND]), what a field's `g-fileKinds` names. */
    val id: String,
    val label: String,
    /** What sort of file it is ([FKFAM]): how a form previews one. */
    val family: String,
    val mimeType: String,
    /** What a file of this kind is called after its dot, for a name offered when one is saved. */
    val extension: String,
    val alsoDeclaredAs: Set<String> = emptySet(),
    /** The largest file of this kind a trait's file field may hold; a field may only lower it (`g-fileMaxSize`). */
    val maxSize: Long,
    val disposition: FileDisposition = FileDisposition.inline,
) {
    override fun toString(): String = id
}

/** The ids of the file kinds (issue #1834): the values of a field's `g-fileKinds`. */
@Suppress("ConstPropertyName")
object FKIND {
    const val png = "png"
    const val jpeg = "jpeg"
    const val webp = "webp"
    const val gif = "gif"
    const val mp4 = "mp4"
    const val webm = "webm"
    const val pdf = "pdf"

    /** Kinds only a client's web assets and named images take, never a trait's file field. */
    const val svg = "svg"
    const val ico = "ico"
}

/** What sort of file a kind is (issue #1834), which is how a form previews one. */
@Suppress("ConstPropertyName")
object FKFAM {
    const val image = "image"
    const val video = "video"
    const val document = "document"
}

/**
 * The kinds of file there are (issue #1834): plain data, in the kernel so the browser can refuse a file the server
 * would -- not a kind the field takes, or too large -- before it uploads a byte. The server still decides, by the
 * bytes ([FileKindSniff]); this is the part both sides agree on.
 *
 * [kinds] are those a trait's file field may hold. [svg] and [ico] are kinds too, so a client's web assets and named
 * images are described the same way (`WebAssetKinds`), but no data field may name them: an SVG is a document that can
 * carry script, which only the named images' own check reads, and an ICO is a favicon's format and nothing else's.
 *
 * Theme: #DFILE.
 */
object FileKinds {
    private const val mb = 1024L * 1024L

    /** Video's largest: under the 5 GB a single S3 `CopyObject` can move, which finishing an upload relies on. */
    private const val videoMax = 2048L * mb

    val png = FileKind(FKIND.png, "PNG", FKFAM.image, "image/png", "png", maxSize = 20 * mb)
    val jpeg = FileKind(FKIND.jpeg, "JPEG", FKFAM.image, "image/jpeg", "jpg", setOf("image/jpg", "image/pjpeg"), maxSize = 20 * mb)
    val webp = FileKind(FKIND.webp, "WebP", FKFAM.image, "image/webp", "webp", maxSize = 20 * mb)
    val gif = FileKind(FKIND.gif, "GIF", FKFAM.image, "image/gif", "gif", maxSize = 20 * mb)
    val mp4 = FileKind(FKIND.mp4, "MP4 video", FKFAM.video, "video/mp4", "mp4", maxSize = videoMax)
    val webm = FileKind(FKIND.webm, "WebM video", FKFAM.video, "video/webm", "webm", maxSize = videoMax)
    val pdf = FileKind(FKIND.pdf, "PDF", FKFAM.document, "application/pdf", "pdf", maxSize = 50 * mb, disposition = FileDisposition.attachment)

    /** Not a data kind (see the class note); its size is the named images' own limit, the largest any slot takes. */
    val svg = FileKind(FKIND.svg, "SVG", FKFAM.image, "image/svg+xml", "svg", maxSize = 2 * mb)

    /** Not a data kind (see the class note). */
    val ico = FileKind(FKIND.ico, "ICO", FKFAM.image, "image/x-icon", "ico", setOf("image/vnd.microsoft.icon", "image/ico"), maxSize = 2 * mb)

    /** The kinds a trait's file field may hold, in the order a message lists them. */
    val kinds: List<FileKind> = listOf(png, jpeg, webp, gif, mp4, webm, pdf)

    /** The data kind with id [id], or null when there is none of that id. */
    fun byId(id: String): FileKind? = kinds.firstOrNull { it.id == id }

    /** The kind among [among] that [mimeType] declares -- its parameters and case aside -- or null when it is none. */
    fun declaredKind(mimeType: String, among: List<FileKind> = kinds): FileKind? {
        val bare = mimeType.substringBefore(';').trim().lowercase()
        return among.firstOrNull { it.mimeType == bare || bare in it.alsoDeclaredAs }
    }

    /** The content types a file picker should offer for [among]: each kind's own and the others it is declared under. */
    fun acceptedMimeTypes(among: List<FileKind>): List<String> = among.flatMap { listOf(it.mimeType) + it.alsoDeclaredAs }
}
