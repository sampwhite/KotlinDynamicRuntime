package com.dynamicruntime.webapp

import com.dynamicruntime.common.filestore.FMETA
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.byteSizeText
import com.dynamicruntime.common.util.joinAsPhrase
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.webasset.WAS

/**
 * A client's own web assets in the frontend (issue #1130): the tab icon and the brand mark its administrator
 * uploads. Two readers. The **shell** is handed the set slots by the home config (`state.webAssets`) and draws
 * the client's mark and tab icon in place of the deployment's; the **Clients page** lists every slot with what
 * it holds and uploads into or empties one (`WebAssetsSection`).
 *
 * Everything here but [applyClientFavicon] is pure, and covered under `jsNodeTest`. A size is worded by the
 * kernel's `byteSizeText`, which the backend's own refusals use.
 */

/**
 * `state.webAssets` as slot to image name: the slots this build knows, each with a name. Anything else the
 * config carries -- a slot from a newer backend, a value that is no name -- is left out rather than drawn.
 */
fun webAssetNamesFrom(raw: Any?): Map<String, String> =
    raw.toJsonMapOrEmpty().mapNotNull { (slot, name) ->
        (name as? String)?.takeIf { it.isNotEmpty() && slot in WAS.slots }?.let { slot to it }
    }.toMap()

/**
 * The mark the shell draws: the client's own where it has set one -- at the address [assetUrl] gives its name --
 * and otherwise the deployment's, [deploymentUrl]. With no config yet ([webAssets] null) it is the deployment's,
 * which is what the bar showed before a client could have its own.
 */
fun shellMarkUrl(webAssets: Map<String, String>?, deploymentUrl: String, assetUrl: (String) -> String): String =
    webAssets?.get(WAS.brandMark)?.let(assetUrl) ?: deploymentUrl

/** [shellMarkUrl] for the shell as it runs: the app bar's logo and the home hero both draw this. */
fun markUrlFor(config: HomeConfig?): String = shellMarkUrl(config?.webAssets, brandMarkUrl, Http::webAssetUrl)

/** The address of the client's own tab icon, or null when it has set none and the deployment's stands. */
fun clientFaviconUrl(config: HomeConfig): String? = config.webAssets[WAS.favicon]?.let(Http::webAssetUrl)

/** The image a slot holds, as the file store describes it. */
class WebAssetFile(
    val mimeType: String,
    val size: Long,
    val hash: String,
    /** When it was uploaded, as the wire's timestamp. */
    val uploadedAt: String,
    /** The name it was uploaded under, when it had one. */
    val fileName: String?,
)

/** One slot of a client's web assets as the listing answers it: what it takes, and [file] when it holds an image. */
class WebAssetSlot(
    val slot: String,
    val label: String,
    val maxSize: Long,
    /** The content types a file picker should offer. */
    val mimeTypes: List<String>,
    /** The kinds of image it takes, as a person names them. */
    val kinds: List<String>,
    val file: WebAssetFile?,
    val uploadedByName: String?,
)

/** The listing's items as slots; one without a slot name is not one. */
fun parseWebAssetSlots(items: List<Map<String, Any?>>): List<WebAssetSlot> = items.mapNotNull { item ->
    val slot = item[WAS.slot].toOptStr() ?: return@mapNotNull null
    val file = item[WAS.file].toJsonMapOrEmpty().takeIf { it.isNotEmpty() }?.let { meta ->
        WebAssetFile(
            mimeType = meta[FMETA.mimeType].toOptStr().orEmpty(),
            size = (meta[FMETA.size] as? Number)?.toLong() ?: 0L,
            hash = meta[FMETA.hash].toOptStr().orEmpty(),
            uploadedAt = meta[FMETA.uploadedAt].toOptStr().orEmpty(),
            fileName = meta[FMETA.fileName].toOptStr(),
        )
    }
    WebAssetSlot(
        slot = slot,
        label = item[WAS.label].toOptStr() ?: slot,
        maxSize = (item[WAS.maxSize] as? Number)?.toLong() ?: 0L,
        mimeTypes = item[WAS.mimeTypes].toJsonListOfStrings(),
        kinds = item[WAS.kinds].toJsonListOfStrings(),
        file = file,
        uploadedByName = item[WAS.uploadedByName].toOptStr(),
    )
}

/** What a slot takes, for the hint under it: `PNG, SVG, ICO, JPEG or WebP, up to 256 KB.` */
fun webAssetTakesText(slot: WebAssetSlot): String {
    val kinds = joinAsPhrase(slot.kinds, last = "or").ifEmpty { "An image" }
    return if (slot.maxSize > 0) "$kinds, up to ${byteSizeText(slot.maxSize)}." else "$kinds."
}

/**
 * What a slot holds, in a line: the image's kind of file, its size and the name it came under -- or, for an empty
 * one, that the client's people see the deployment's.
 */
fun webAssetHoldsText(slot: WebAssetSlot): String {
    val file = slot.file ?: return "Not set: the deployment's ${slot.label.lowercase()} is shown."
    return listOfNotNull(file.mimeType.ifEmpty { null }, byteSizeText(file.size), file.fileName).joinToString(" · ")
}

/** Who uploaded a slot's image and when, or null for an empty slot: `Uploaded 2026-10-12 19:49 UTC by Chief One`. */
fun webAssetUploadedText(slot: WebAssetSlot): String? {
    val file = slot.file ?: return null
    val at = file.uploadedAt.takeIf { it.isNotEmpty() }?.let { " ${formatTimestamp(it)}" }.orEmpty()
    val by = slot.uploadedByName?.let { " by $it" }.orEmpty()
    return "Uploaded$at$by"
}

/**
 * What is wrong with a picked file of [size] bytes for [slot] before it is sent, or null: only what the browser
 * can know without reading it, which is that it is too large. Whether it is the image it claims to be is the
 * backend's to say, in its own words.
 */
fun webAssetFileProblem(slot: WebAssetSlot, size: Long): String? =
    if (slot.maxSize in 1 until size) {
        "This file is ${byteSizeText(size)}; a ${slot.label.lowercase()} may be at most ${byteSizeText(slot.maxSize)}."
    } else {
        null
    }

/**
 * The question asked before a slot of [clientId]'s is emptied, naming where that acts as every delete's does
 * (issue #1049): `Remove the favicon of acme (live data)?`. There is no draft to remove it from -- it is gone for
 * the client's people as it is confirmed -- which [webAssetRemoveConsequence] says under it.
 */
fun webAssetRemoveQuestion(slot: WebAssetSlot, clientId: String): String =
    "Remove the ${slot.label.lowercase()} of ${deleteTargetText(clientId)}?"

/** What emptying a slot does, under [webAssetRemoveQuestion]. */
fun webAssetRemoveConsequence(slot: WebAssetSlot): String =
    "Its people see the deployment's ${slot.label.lowercase()} again, at once."

/** The two things done to a slot, as [webAssetBusyKey] names the one under way. */
@Suppress("ConstPropertyName")
object WACT {
    const val upload = "upload"
    const val remove = "remove"
}

/** What is under way on a page of slots: which slot, and which of [WACT]'s actions -- so the control that was used is the one that shows it. */
fun webAssetBusyKey(slot: String, action: String): String = "$slot/$action"

/** [slots] with [changed] in the place of the slot it is, as an upload or a removal answered it. */
fun withWebAssetSlot(slots: List<WebAssetSlot>?, changed: WebAssetSlot): List<WebAssetSlot>? =
    slots?.map { if (it.slot == changed.slot) changed else it }

/**
 * The inputs of the address a slot's image is previewed from on the Clients page: the **named** download, since
 * the client on that page need not be the caller's own, with the image's hash so that a replaced image is a new
 * address -- a browser draws an address it has already drawn without asking again. Null for an empty slot.
 */
fun webAssetPreviewArgs(clientId: String, slot: WebAssetSlot): Map<String, Any?>? =
    slot.file?.let { mapOf(WAS.client to clientId, WAS.slot to slot.slot, WAS.hash to it.hash) }

/** [webAssetPreviewArgs] as the address itself. */
fun webAssetPreviewUrl(clientId: String, slot: WebAssetSlot): String? =
    webAssetPreviewArgs(clientId, slot)?.let { Http.apiUrl(UADEP.clientWebDownload, it) }

/** The `id` of the one `<link rel="icon">` that is the client's, so it is found again and never duplicated. */
private const val clientIconId = "kdr-client-icon"

/** The deployment's own icon links, taken out of the page while a client's stands in for them. */
private var deploymentIcons: List<Any>? = null

/**
 * Makes the tab icon the client's own, at [url], or the deployment's again when [url] is null.
 *
 * The page's shell declares the deployment's icons, and a browser picks among every `rel="icon"` link it finds by
 * its own rules -- so adding one beside them does not reliably win. The deployment's are therefore taken **out** of
 * the page while a client's stands in, and put back as they were when the client has none (a sign-out, a switch to
 * a client that set none). The first paint is still the deployment's: the shell cannot know the client until the
 * config is read.
 */
fun applyClientFavicon(url: String?) {
    val doc: dynamic = js("document")
    val head: dynamic = doc.head ?: return
    val current: dynamic = doc.getElementById(clientIconId)
    if (url == null) {
        if (current != null) head.removeChild(current)
        deploymentIcons?.forEach { head.appendChild(it) }
        deploymentIcons = null
        return
    }
    if (current != null) {
        if (current.getAttribute("href") != url) current.setAttribute("href", url)
        return
    }
    val found: dynamic = doc.querySelectorAll("link[rel~='icon']")
    val originals = List(found.length as Int) { found[it].unsafeCast<Any>() }
    originals.forEach { head.removeChild(it) }
    deploymentIcons = originals
    val link: dynamic = doc.createElement("link")
    link.id = clientIconId
    link.rel = "icon"
    link.href = url
    head.appendChild(link)
}
