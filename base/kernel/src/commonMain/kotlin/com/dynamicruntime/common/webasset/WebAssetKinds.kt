package com.dynamicruntime.common.webasset

/**
 * One kind of image a web asset may be (issue #1130): what a person calls it, the content type it is stored and
 * served as, and the other types an upload may honestly declare it under -- browsers do not agree on an ICO's.
 */
class WebImageKind(
    val label: String,
    val mimeType: String,
    /** What a file of this kind is called after its dot, for a name offered when one is saved. */
    val extension: String,
    val alsoDeclaredAs: Set<String> = emptySet(),
)

/** One web-asset slot (issue #1130): its name ([WAS.slots]), what a page calls it, and the largest image it takes. */
class WebSlotDef(val slot: String, val label: String, val maxSize: Int)

/**
 * The images a client's web assets may be, and the slots they go in (issues #1130, #1336): plain data, in the kernel
 * so the self-serve create's card can refuse a file the server would -- too large, or not an image we take -- before
 * it creates anything. The backend's `WebAssetCheck` still decides, by the bytes; this is the part both sides agree on.
 */
object WebAssetKinds {
    val png = WebImageKind("PNG", "image/png", "png")
    val svg = WebImageKind("SVG", "image/svg+xml", "svg")
    val ico = WebImageKind("ICO", "image/x-icon", "ico", setOf("image/vnd.microsoft.icon", "image/ico"))
    val jpeg = WebImageKind("JPEG", "image/jpeg", "jpg", setOf("image/jpg", "image/pjpeg"))
    val webp = WebImageKind("WebP", "image/webp", "webp")

    /** Every kind accepted, in the order a message lists them. */
    val kinds = listOf(png, svg, ico, jpeg, webp)

    /** The content types a file picker should offer: each kind's own and the others it is declared under. */
    val acceptedMimeTypes: List<String> = kinds.flatMap { listOf(it.mimeType) + it.alsoDeclaredAs }

    /** The kind [mimeType] declares -- its parameters and case aside -- or null when it is none of ours. */
    fun declaredKind(mimeType: String): WebImageKind? {
        val bare = mimeType.substringBefore(';').trim().lowercase()
        return kinds.firstOrNull { it.mimeType == bare || bare in it.alsoDeclaredAs }
    }

    private const val bytesPerKb = 1024

    /** The slots, in listing order. A favicon is small by nature; a brand mark may be a photograph. */
    val slotDefs = listOf(
        WebSlotDef(WAS.favicon, "Favicon", 256 * bytesPerKb),
        WebSlotDef(WAS.brandMark, "Brand mark", 1024 * bytesPerKb),
    )

    /** The slot named [slot], or null when there is none of that name. */
    fun slotDef(slot: String): WebSlotDef? = slotDefs.firstOrNull { it.slot == slot }
}
