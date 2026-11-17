package com.dynamicruntime.common.webasset

import com.dynamicruntime.common.filekind.FileKind
import com.dynamicruntime.common.filekind.FileKinds

/**
 * One kind of image a web asset may be (issue #1130). Since #1834 it is a [FileKind], so a client's web assets, its
 * named images, and a trait's file field describe a kind the same way and are told it by the same bytes.
 */
typealias WebImageKind = FileKind

/**
 * One web-asset slot (issue #1130): its name ([WAS.slots]), what a page calls it, the largest image it takes, and -- for
 * a slot the page shell links under a fixed name (issue #1779) -- the one kind it must be ([only]); null for any kind.
 */
class WebSlotDef(val slot: String, val label: String, val maxSize: Int, val only: WebImageKind? = null)

/**
 * The images a client's web assets may be, and the slots they go in (issues #1130, #1336): plain data, in the kernel
 * so the self-serve create's card can refuse a file the server would -- too large, or not an image we take -- before
 * it creates anything. The backend's `WebAssetCheck` still decides, by the bytes; this is the part both sides agree on.
 */
@Suppress("ConstPropertyName")
object WebAssetKinds {
    // The registry's own kinds (issue #1834), so a check that compares kinds compares one instance of each.
    val png = FileKinds.png
    val svg = FileKinds.svg
    val ico = FileKinds.ico
    val jpeg = FileKinds.jpeg
    val webp = FileKinds.webp

    /** Every kind accepted, in the order a message lists them. */
    val kinds = listOf(png, svg, ico, jpeg, webp)

    /** The content types a file picker should offer: each kind's own and the others it is declared under. */
    val acceptedMimeTypes: List<String> = FileKinds.acceptedMimeTypes(kinds)

    /** The kind [mimeType] declares -- its parameters and case aside -- or null when it is none of ours. */
    fun declaredKind(mimeType: String): WebImageKind? = FileKinds.declaredKind(mimeType, kinds)

    private const val bytesPerKb = 1024

    /** The slots, in listing order. A favicon is small by nature; a brand mark may be a photograph. */
    val slotDefs = listOf(
        WebSlotDef(WAS.favicon, "Favicon", 256 * bytesPerKb),
        WebSlotDef(WAS.brandMark, "Brand mark", 1024 * bytesPerKb),
    )

    /** The slot named [slot], or null when there is none of that name. */
    fun slotDef(slot: String): WebSlotDef? = slotDefs.firstOrNull { it.slot == slot }

    /**
     * The slots only a web resources package fills (issue #1779): the images the page shell links, which an anonymous
     * visitor and a link preview are served. Never uploaded: what such a caller sees can vary only by the domain a link
     * uses, and only a client defined in code has one. Each is a PNG, since the shell names it so.
     */
    val packageOnlySlotDefs = listOf(
        WebSlotDef(WAS.shareImage, "Share image", 2048 * bytesPerKb, only = png),
        WebSlotDef(WAS.faviconPng, "PNG favicon", 256 * bytesPerKb, only = png),
        WebSlotDef(WAS.appleTouchIcon, "Apple touch icon", 256 * bytesPerKb, only = png),
    )

    /** The slot a package may fill named [slot]: an uploadable one, or one only a package fills; null for none. */
    fun packageSlotDef(slot: String): WebSlotDef? = slotDef(slot) ?: packageOnlySlotDefs.firstOrNull { it.slot == slot }

    /** Every slot a package may fill, in listing order. */
    val packageSlotDefs: List<WebSlotDef> get() = slotDefs + packageOnlySlotDefs
}
