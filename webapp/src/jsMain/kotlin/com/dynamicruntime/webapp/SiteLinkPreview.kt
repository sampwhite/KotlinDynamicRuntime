package com.dynamicruntime.webapp

import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.webasset.WAS
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h4
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffectOnce
import react.useState
import web.cssom.ClassName
import web.window.WindowTarget
import web.window._blank

/*
 * What a link to the site shows (issue #1783), for a deployment's administrator: the share image, the PNG favicon, and
 * the Apple touch icon the page shell links, each with where it comes from, and the title and description a preview
 * draws. Read from `/admin/site/linkPreview`, which applies the rules the shell serves by, so the two cannot disagree;
 * the images are drawn from the addresses the shell links them at. The site's, the same for every client, so the
 * Clients page shows it only to a caller who sees every client.
 */

/** One image the page shell links: its slot, what it is called, its file under the app root, and where it comes from. */
class ShellImageView(val slot: String, val label: String, val appFile: String, val source: String, val packageId: String?)

/** What a link to the site shows: its [images], the preview's [title] and [description], and the site's [publicUrl]. */
class SiteLinkPreviewView(val images: List<ShellImageView>, val title: String, val description: String, val publicUrl: String?)

/** The preview as the endpoint answers it. An image with no file this build can draw is left out. Pure. */
fun siteLinkPreviewFrom(raw: Map<String, Any?>): SiteLinkPreviewView = SiteLinkPreviewView(
    images = raw[WAS.images].toJsonListOfMaps().mapNotNull { m ->
        val slot = m[WAS.slot].toOptStr() ?: return@mapNotNull null
        val file = m[WAS.appFile].toOptStr()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        ShellImageView(slot, m[WAS.label].toOptStr() ?: slot, file, m[WAS.source].toOptStr().orEmpty(), m[WAS.packageId].toOptStr())
    },
    title = raw[WAS.title].toOptStr().orEmpty(),
    description = raw[WAS.description].toOptStr().orEmpty(),
    publicUrl = raw[WAS.publicUrl].toOptStr()?.takeIf { it.isNotBlank() },
)

/** Where [image] comes from, in words: the deployment's default package by name, or built in. Pure. */
fun shellImageSourceText(image: ShellImageView): String = when (image.source) {
    WAS.sourceDefault -> image.packageId?.let { "From the deployment's default package, $it." } ?: "From the deployment's default package."
    else -> "Built in."
}

/** LinkedIn's Post Inspector, asked about [siteUrl]: how a post linking the site will show it. Pure. */
fun postInspectorUrl(siteUrl: String): String = "https://www.linkedin.com/post-inspector/inspect/" + encodeUriComponent(siteUrl)

/** Where the shell serves [image]: its file under the app root, the address a crawler is given. */
private fun shellImageUrl(image: ShellImageView): String = appRootPrefix() + "/" + image.appFile

/**
 * The block on a client's page, in its Images panel (issue #1783): what a link to the site shows, read-only. It says the
 * images and words are the site's, the same for every client and set in the deployment's code and copy, so nobody
 * takes them for this client's. Read once as it mounts; the read is cancelled if the page goes first.
 */
val SiteLinkPreviewBlock = FC<Props> {
    var preview by useState<SiteLinkPreviewView?>(null)
    var loadError by useState<DisplayError?>(null)

    useEffectOnce {
        try {
            preview = ClientsApi.siteLinkPreview()
        } catch (e: Throwable) {
            // userFacingError rethrows a cancellation: the page went before the answer.
            loadError = userFacingError(e)
        }
    }

    h4 { +"What a link to this site shows" }
    p {
        className = ClassName("subtitle")
        +"These are the site's, the same for every client: set in the deployment's code and copy, not uploaded, so "
        +"nothing on this page changes them."
    }
    val shown = preview
    val failed = loadError
    when {
        failed != null -> errorText("Couldn't load what a link to the site shows.", failed)
        shown == null -> p {
            className = ClassName("subtitle")
            +"Loading…"
        }
        else -> {
            val (share, icons) = shown.images.partition { it.slot == WAS.shareImage }
            // The share image as a preview card draws it: the image at its 1200 by 630 shape, the words beneath.
            div {
                className = ClassName("link-preview-card")
                share.firstOrNull()?.let { image ->
                    img {
                        className = ClassName("link-preview-image")
                        src = shellImageUrl(image)
                        alt = image.label
                    }
                }
                div {
                    className = ClassName("link-preview-title")
                    +shown.title
                }
                if (shown.description.isNotEmpty()) {
                    div {
                        className = ClassName("link-preview-description")
                        +shown.description
                    }
                }
            }
            for (image in share + icons) {
                div {
                    className = ClassName("link-preview-row")
                    if (image.slot != WAS.shareImage) {
                        img {
                            // The touch icon drawn as a home screen draws it, rounded; the favicon as a tab does.
                            className = ClassName(if (image.slot == WAS.appleTouchIcon) "link-preview-icon touch" else "link-preview-icon")
                            src = shellImageUrl(image)
                            alt = image.label
                        }
                    }
                    span {
                        className = ClassName("link-preview-label")
                        +image.label
                    }
                    span {
                        className = ClassName("subtitle")
                        +shellImageSourceText(image)
                    }
                }
            }
            val url = shown.publicUrl
            if (url != null) {
                p {
                    a {
                        href = postInspectorUrl(url)
                        target = WindowTarget._blank
                        rel = "noopener"
                        +"Check it on LinkedIn"
                    }
                    +" (Post Inspector, for $url)"
                }
            } else {
                p {
                    className = ClassName("subtitle")
                    +"This deployment names no public address (KDR_PUBLIC_URL), so there is no address to check on LinkedIn."
                }
            }
        }
    }
}
