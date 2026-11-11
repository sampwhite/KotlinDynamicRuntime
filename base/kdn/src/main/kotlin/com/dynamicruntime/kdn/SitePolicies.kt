package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownDocDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.home.HCFG

/**
 * dynamicruntime.org's privacy policy and terms of service (issue #1711), which Google's sign-in verification needs on the
 * site's own domain, and its About page (issue #1769), which says the product is Gedra and where the name comes from.
 * Kept in the repository under `docs/dynamicruntime-org/`, copied onto the classpath by the build, and served as
 * documents, readable signed out.
 *
 * They are dynamicruntime.org's own words -- its operator, its contact, its monthly wipe -- so only a deployment that
 * names them has them: the `reference` flavor sets [HCFG.privacyPolicyDoc], [HCFG.termsOfServiceDoc], and
 * [HCFG.aboutDoc] to their ids ([KdnComponent.flavors]), and they are declared ([declared]) only where those keys name
 * them. Another deployment neither serves nor links them, and names its own pages in the same keys.
 */
@Suppress("ConstPropertyName")
object SitePolicies {
    const val privacyDocId = "dynamicruntime-org-privacy-policy"
    const val termsDocId = "dynamicruntime-org-terms-of-service"
    const val aboutDocId = "dynamicruntime-org-about-gedra"

    private val privacy = MarkdownDocDef(
        privacyDocId, "Privacy policy", "docs/dynamicruntime-org/privacy-policy.md",
        "What dynamicruntime.org keeps about you, why, who else handles it, and how it is deleted.",
        listed = false,
    )
    private val terms = MarkdownDocDef(
        termsDocId, "Terms of service", "docs/dynamicruntime-org/terms-of-service.md",
        "The terms for using dynamicruntime.org, a public demonstration that is wiped every month.",
        listed = false,
    )

    /** Its title is what the footer's link says. */
    private val about = MarkdownDocDef(
        aboutDocId, "About Gedra", "docs/dynamicruntime-org/about-gedra.md",
        "What Gedra is, where its name comes from, and who makes it.",
        listed = false,
    )

    /** The pages the deployment names as its site policies and About page, of these: none on one that names none. */
    fun declared(cxt: KdrCxt): List<MarkdownDocDef> {
        val named = listOf(HCFG.privacyPolicyDoc, HCFG.termsOfServiceDoc, HCFG.aboutDoc).map { cxt.instanceConfig.get(it) }.toSet()
        return listOf(privacy, terms, about).filter { it.docId in named }
    }
}
