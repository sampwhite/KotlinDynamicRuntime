package com.dynamicruntime.webapp

import com.dynamicruntime.common.home.HFLD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A deployment's privacy policy and terms (issue #1711): carried from the shell config, and made into links. */
class SitePoliciesTest {
    private fun factsFrom(state: Map<String, Any?>) = shellFactsOf(homeConfigFrom(UiConfig(FragmentRef("home", "b1"), emptyMap(), emptyMap(), state)))

    @Test
    fun theShellConfigsPoliciesBecomeLinksToTheirDocumentPagesPrivacyFirst() {
        val facts = factsFrom(mapOf(HFLD.privacyPolicy to "site-privacy", HFLD.termsOfService to "site-terms"))
        val links = sitePolicyLinks(facts)
        assertEquals(listOf(SITEW.privacyPolicy, SITEW.termsOfService), links.map { it.label })
        assertEquals(listOf(docHref("site-privacy"), docHref("site-terms")), links.map { it.href })
    }

    @Test
    fun aDeploymentWithOneOrNoneLinksOnlyWhatItHas() {
        assertEquals(listOf(SITEW.termsOfService), sitePolicyLinks(factsFrom(mapOf(HFLD.termsOfService to "site-terms"))).map { it.label })
        assertTrue(sitePolicyLinks(factsFrom(emptyMap())).isEmpty())
        // Before the shell has been told, as on a page's first render: nothing to draw.
        assertTrue(sitePolicyLinks(ShellFacts()).isEmpty())
        assertTrue(sitePolicyLinks(factsFrom(mapOf(HFLD.privacyPolicy to ""))).isEmpty())
    }
}
