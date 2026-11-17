package com.dynamicruntime.common.webasset

import com.dynamicruntime.common.content.ComponentImageService
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.filestore.StoredFile
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.user.INVITE
import com.dynamicruntime.common.util.renderMarkdownText

/**
 * What a link to the site shows (issues #1688, #1779, #1783): the rules the page shell serves its images and preview
 * words by, here so the shell (`AppUiService`, in `appui`) and the page that shows them to a deployment's administrator
 * cannot disagree.
 *
 * - **Each image** is the deployment's default package's (`WRES.defaultKey`) when it ships one of the kind the shell
 *   names, else the runtime's own, built in.
 * - **The words** are the `home` file's `site` copy as a client reads it, reduced to plain text: the caller's client's
 *   for the shell, and [CL.public]'s -- an anonymous visitor's, which a link preview's crawler is -- for the page.
 */
object SiteLinkPreview {
    /** The default package's image for [slot] when it is a [mimeType] one, the kind the shell names; null for the built-in. */
    fun defaultPackageImage(cxt: KdrCxt, slot: String, mimeType: String): StoredFile? {
        val packageId = WRES.defaultId(cxt) ?: return null
        return ComponentImageService.getOrNull(cxt)?.packageFile(packageId, slot)?.takeIf { it.meta.mimeType == mimeType }
    }

    /** The preview's title as [client] reads it: its `site.title` as plain text, else [WAS.defaultSiteTitle]. */
    fun title(cxt: KdrCxt, client: String?): String = siteWords(cxt, client, HFRAG.title) ?: WAS.defaultSiteTitle

    /** The preview's description as [client] reads it: its `site.description` as plain text, empty for none. */
    fun description(cxt: KdrCxt, client: String?): String = siteWords(cxt, client, HFRAG.siteDescription).orEmpty()

    /** The `home` file's `site.`[key] as [client] reads it, reduced to plain text, or null when it is empty. */
    private fun siteWords(cxt: KdrCxt, client: String?, key: String): String? =
        MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, HFRAG.home, client)?.content?.get(HFRAG.siteNs)?.get(key)
            ?.renderMarkdownText()?.trim()?.ifEmpty { null }

    /**
     * The preview a deployment's administrator is shown (issue #1783): each image the shell links, with its file and
     * where it comes from, and the words as an anonymous visitor reads them, with the site's public address where the
     * deployment names one.
     */
    fun preview(cxt: KdrCxt): Map<String, Any?> {
        val packageId = WRES.defaultId(cxt)
        val images = WebAssetKinds.packageOnlySlotDefs.map { def ->
            val kind = def.only ?: WebAssetKinds.png
            val fromDefault = defaultPackageImage(cxt, def.slot, kind.mimeType) != null
            buildMap {
                put(WAS.slot, def.slot)
                put(WAS.label, def.label)
                WAS.shellFileOf(def.slot)?.let { put(WAS.appFile, it) }
                put(WAS.source, if (fromDefault) WAS.sourceDefault else WAS.sourceBuiltIn)
                if (fromDefault) put(WAS.packageId, packageId)
            }
        }
        return buildMap {
            put(WAS.images, images)
            put(WAS.title, title(cxt, CL.public))
            put(WAS.description, description(cxt, CL.public))
            INVITE.configuredPublicUrl(cxt)?.let { put(WAS.publicUrl, it) }
        }
    }
}
