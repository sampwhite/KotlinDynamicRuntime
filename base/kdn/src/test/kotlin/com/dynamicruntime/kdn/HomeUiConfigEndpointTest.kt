package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownDocService
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.home.HCFG
import com.dynamicruntime.common.home.HDOC
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFEAT
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.util.renderMarkdown
import com.dynamicruntime.common.util.toJsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Exercises the home/shell UI-config endpoint: the manifest the frontend fetches to build the home page. It is
 * anonymous, carries the shared `{ fragments, features, state }` envelope, resolves its layout flags from the
 * deployment's instance config, and offers the README as a versioned Markdown document link.
 */
class HomeUiConfigEndpointTest : StringSpec({

    fun results(resp: Map<String, Any?>): Map<String, Any?> = resp.getValue(EP.results)!!.toJsonMap()
    fun Map<String, Any?>.obj(key: String): Map<String, Any?> = getValue(key)!!.toJsonMap()
    fun Map<String, Any?>.list(key: String): List<Any?> = getValue(key) as List<*>

    "home ui config is anonymous and carries the fragments/features/state envelope" {
        val cxt = Startup.mkTestBootCxt("uiHome", "uiHomeTest")
        val client = TestHttpClient(cxt.instanceConfig)

        // No login: the home page is the shell a logged-out visitor lands on.
        val cfg = results(client.sendJsonGetRequest(HEP.homeUiConfig))

        val frag = cfg.list(UIC.fragments).first()!!.toJsonMap()
        frag[UIC.fileId] shouldBe HFRAG.home
        (frag[UIC.buildId] as String).isNotEmpty() shouldBe true

        // The links default to a left nav bar alone (the other two presentations are opt-in).
        val features = cfg.obj(UIC.features)
        features[HFEAT.leftBar] shouldBe true
        features[HFEAT.topBar] shouldBe false
        features[HFEAT.inlineLinks] shouldBe false
    }

    "home ui config offers the readme as a versioned document link" {
        val cxt = Startup.mkTestBootCxt("uiHomeLinks", "uiHomeLinksTest")
        val client = TestHttpClient(cxt.instanceConfig)

        val links = results(client.sendJsonGetRequest(HEP.homeUiConfig)).obj(UIC.state).list(HFLD.links)
        val readme = links.map { it!!.toJsonMap() }.single { it[HFLD.id] == HDOC.readme }
        readme[HFLD.docId] shouldBe HDOC.readme
        // The build id is what makes the document's URL cache-immutable, so it must be present.
        (readme[HFLD.buildId] as String).isNotEmpty() shouldBe true
        (readme[HFLD.label] as String).isNotEmpty() shouldBe true
    }

    "layout flags come from the deployment's instance config" {
        val cxt = Startup.mkTestBootCxt("uiHomeCfg", "uiHomeCfgTest")
        // A deployment that wants a top menu bar and inline links instead of the default left bar.
        cxt.instanceConfig.put(HCFG.homeTopBar, true)
        cxt.instanceConfig.put(HCFG.homeLeftBar, false)
        cxt.instanceConfig.put(HCFG.homeInlineLinks, true)
        val client = TestHttpClient(cxt.instanceConfig)

        val features = results(client.sendJsonGetRequest(HEP.homeUiConfig)).obj(UIC.features)
        features[HFEAT.topBar] shouldBe true
        features[HFEAT.leftBar] shouldBe false
        features[HFEAT.inlineLinks] shouldBe true
    }

    "the readme document is served whole and renders as Markdown" {
        val cxt = Startup.mkTestBootCxt("uiHomeDoc", "uiHomeDocTest")
        val client = TestHttpClient(cxt.instanceConfig)

        val buildId = MarkdownDocService.docBuildId(HDOC.readme)
        buildId.shouldNotBeNull()
        // The document server lives under the *static* root (not the API root), and takes the cache-busting
        // suffix, so the request goes out unrouted.
        val resp = client.sendGetRequestRaw("/st/testapp/doc/${HDOC.readme}:$buildId")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseHeaders["cache-control"]?.first() shouldContain "immutable"

        // It is the raw README (headings intact -- the fragment format could not carry it), and the kernel
        // renderer turns it into HTML the frontend can show.
        val markdown = resp.rptResponseData ?: ""
        markdown shouldContain "# KotlinDynamicRuntime"
        val html = markdown.renderMarkdown()
        html shouldContain "<h1>KotlinDynamicRuntime</h1>"
        html shouldContain "<pre><code"
    }

    "docBuildId is stable for a present document and null for an absent one" {
        val first = MarkdownDocService.docBuildId(HDOC.readme)
        first.shouldNotBeNull()
        MarkdownDocService.docBuildId(HDOC.readme) shouldBe first // memoized, same value
        MarkdownDocService.docBuildId("no-such-document") shouldBe null
    }
})
