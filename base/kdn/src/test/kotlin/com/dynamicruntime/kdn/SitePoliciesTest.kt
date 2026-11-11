package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.ComponentImageService
import com.dynamicruntime.common.content.MarkdownDocDef
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.home.HCFG
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.home.sitePolicyFaults
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.webasset.WAS
import com.dynamicruntime.common.webasset.WRES
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

/**
 * A deployment's privacy policy and terms of service (issue #1711), and its About page (issue #1769): the `reference`
 * flavor names dynamicruntime.org's, which kdn then declares as documents; the shell config tells every caller, signed
 * in or out, which documents they are; and a signed-out caller can read them. A deployment that names none has neither
 * the links nor the pages.
 *
 * Theme: #DOCDR.
 */
class SitePoliciesTest : StringSpec({
    val reference = TestInstances.reference("sitePolicies1711")
    val plain = TestInstances.default("sitePoliciesPlain1711")

    /** The shell config's state, as a signed-out caller is given it. */
    fun signedOutState(on: KdrCxt): Map<String, Any?> =
        TestHttpClient(on.instanceConfig).sendJsonGetRequest(HEP.homeUiConfig)[EP.results].toJsonMapOrEmpty()[UIC.state].toJsonMapOrEmpty()

    "the reference deployment names its policies to a signed-out caller, who can read them" {
        val state = signedOutState(reference)
        state[HFLD.privacyPolicy] shouldBe SitePolicies.privacyDocId
        state[HFLD.termsOfService] shouldBe SitePolicies.termsDocId
        // And its About page (issue #1769), by its title, which the footer's link says.
        state[HFLD.about] shouldBe SitePolicies.aboutDocId
        state[HFLD.aboutTitle] shouldBe "About Gedra"
        // Each is a document of the shell's, unlisted, so the documents page opens it; and served to anyone.
        val links = state[HFLD.links].toJsonListOfMaps().associateBy { it[HFLD.docId].toOptStr() }
        val http = TestHttpClient(reference.instanceConfig)
        val pages = listOf(
            SitePolicies.privacyDocId to "# Privacy policy",
            SitePolicies.termsDocId to "# Terms of service",
            SitePolicies.aboutDocId to "# About Gedra",
        )
        for ((docId, title) in pages) {
            val link = links[docId].shouldNotBeNull()
            val served = http.sendGetRequestRaw("/st/app/doc/$docId:${link[HFLD.buildId]}")
            served.rptStatusCode shouldBe EXC.ok
            served.rptResponseData.orEmpty() shouldStartWith title
        }
        // The terms link to the policy by its path in the repository, which the app turns into its page.
        val terms = http.sendGetRequestRaw("/st/app/doc/${SitePolicies.termsDocId}:${links[SitePolicies.termsDocId]?.get(HFLD.buildId)}")
        terms.rptResponseData.orEmpty() shouldContain "[Privacy policy](privacy-policy.md)"
    }

    "the reference deployment presents the product as Gedra, and a plain one keeps the runtime's name" {
        // Issue #1769: the wordmark, the tab and link-preview title, and the home page's title, from the flavor's copy.
        fun copy(on: KdrCxt, ns: String, key: String) = MarkdownFragmentService.get(on).resolveFragment(on, HFRAG.home, ns, key)
        copy(reference, HFRAG.homeNs, HFRAG.brand) shouldBe "Gedra"
        copy(reference, HFRAG.siteNs, HFRAG.title) shouldBe "Try Gedra"
        copy(reference, HFRAG.homeNs, HFRAG.title) shouldBe "Try Gedra"
        copy(plain, HFRAG.homeNs, HFRAG.brand) shouldBe "KDR"
        // The link preview's image is Gedra's, from the web resources package the flavor makes the default (issue #1779);
        // a plain deployment names none.
        reference.instanceConfig.get(WRES.defaultKey) shouldBe GedraBranding.webResources
        ComponentImageService.get(reference).packageFile(GedraBranding.webResources, WAS.shareImage).shouldNotBeNull()
        plain.instanceConfig.get(WRES.defaultKey).shouldBeNull()
    }

    "a deployment that names none has neither the links nor the pages" {
        val state = signedOutState(plain)
        state[HFLD.privacyPolicy].shouldBeNull()
        state[HFLD.termsOfService].shouldBeNull()
        state[HFLD.about].shouldBeNull()
        state[HFLD.links].toJsonListOfMaps().mapNotNull { it[HFLD.docId].toOptStr() }
            .filter { it.startsWith("dynamicruntime-org-") }.shouldBeEmpty()
    }

    "a policy naming a document no component declares refuses the boot" {
        val doc = MarkdownDocDef("ours", "Ours", "docs/ours.md", "Our policy.", listed = false)
        val config = mapOf(HCFG.privacyPolicyDoc to "ours", HCFG.termsOfServiceDoc to "missing", HCFG.aboutDoc to "gone")
        sitePolicyFaults(config::get, listOf(doc)) shouldContainExactly listOf(
            "'${HCFG.termsOfServiceDoc}' names the document 'missing', which no component declares.",
            "'${HCFG.aboutDoc}' names the document 'gone', which no component declares.",
        )
        sitePolicyFaults(emptyMap<String, Any?>()::get, emptyList()).shouldBeEmpty()
    }
})
