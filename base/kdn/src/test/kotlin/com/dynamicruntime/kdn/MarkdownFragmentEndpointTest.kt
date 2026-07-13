package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.util.jsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * End-to-end test of the Markdown fragment content server (issue #59): a GET under the static root
 * `/st/<appId>/md/<fileId:buildId>` returns the fragment file parsed into a two-tier JSON map, with an
 * immutable cache header. Exercised through the real request pipeline against the classpath resource
 * `md-fragments/sample.md`.
 */
class MarkdownFragmentEndpointTest : StringSpec({

    "the static root serves a fragment file as a two-tier map with an immutable cache header" {
        val cxt = Startup.mkTestBootCxt("md", "markdownFragmentTest")
        val client = TestHttpClient(cxt.instanceConfig)

        // The buildId is a content hash of the resource; the endpoint strips and ignores it (cache-busting only).
        val buildId = MarkdownFragmentService.fragmentBuildId("sample").shouldNotBeNull()
        val handler = client.sendGetRequestRaw("/st/myapp/md/sample:$buildId")

        handler.rptStatusCode shouldBe EXC.ok
        handler.rptResponseMimeType shouldBe "application/json"
        handler.rptResponseHeaders["cache-control"] shouldBe listOf(MarkdownFragmentService.cacheControl)

        val map = handler.rptResponseData.shouldNotBeNull().jsonMap().shouldNotBeNull()
        @Suppress("UNCHECKED_CAST")
        val email = map["email"] as Map<String, Any?>
        email["subject"] shouldBe "Your verification code"
        // The value's ${...} placeholder survives verbatim -- the frontend resolves it at render time.
        (email["body"] as String) shouldContain $$"${code}"
        @Suppress("UNCHECKED_CAST")
        (map["portal"] as Map<String, Any?>)["welcome"] shouldBe "Welcome to the portal."
    }

    "an unknown appId is ignored -- content is still served" {
        val cxt = Startup.mkTestBootCxt("md2", "markdownFragmentAppIdTest")
        val client = TestHttpClient(cxt.instanceConfig)
        val buildId = MarkdownFragmentService.fragmentBuildId("sample").shouldNotBeNull()
        // A different appId (with account/locale-style suffixes) resolves the same file for now.
        val handler = client.sendGetRequestRaw("/st/myapp.acme.en/md/sample:$buildId")
        handler.rptStatusCode shouldBe EXC.ok
    }

    "a missing fragment file returns 404" {
        val cxt = Startup.mkTestBootCxt("md404", "markdownFragmentMissingTest")
        val client = TestHttpClient(cxt.instanceConfig)
        val handler = client.sendGetRequestRaw("/st/myapp/md/does-not-exist:1")
        handler.rptStatusCode shouldBe EXC.notFound
    }
})
