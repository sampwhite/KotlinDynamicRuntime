package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.ContentResources
import com.dynamicruntime.common.content.MarkdownDocService
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.home.HDOC
import com.dynamicruntime.common.http.request.TestHttpClient
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The Markdown *document* content server's caching contract (issue #472). A document served under
 * `/st/<appId>/doc/<docId:buildId>` gets the permanent, immutable cache header only when the request's build
 * id matches the one this node has for the document -- so the URL cannot promise a year of caching for bytes
 * it does not name. An absent or unmatched id still serves the current document, but with `no-store`; and
 * unlike a fragment, a document does **not** 404 on a mismatch (a user recovers by navigating away and back).
 * Driven through the real pipeline against the embedded `md-docs/readme.md` resource.
 */
class MarkdownDocEndpointTest : StringSpec({

    "a matching build id serves the document with the permanent immutable header" {
        val cxt = Startup.mkTestBootCxt("doc", "markdownDocMatchTest")
        val client = TestHttpClient(cxt.instanceConfig)
        val buildId = MarkdownDocService.docBuildId(HDOC.readme).shouldNotBeNull()

        val handler = client.sendGetRequestRaw("/st/myapp/doc/${HDOC.readme}:$buildId")

        handler.rptStatusCode shouldBe EXC.ok
        handler.rptResponseHeaders["cache-control"] shouldBe listOf(ContentResources.cacheControl)
        (handler.rptResponseData ?: "") shouldContain "# KotlinDynamicRuntime"
    }

    "a build id that does not match still serves the current document, but uncached" {
        val cxt = Startup.mkTestBootCxt("docStale", "markdownDocStaleTest")
        val client = TestHttpClient(cxt.instanceConfig)

        // A browser holding a previous ref (e.g. across a deploy) asks for an old hash. It must get the current
        // document -- documents do not 404 on a mismatch -- but never let a shared cache keep it under this URL.
        val handler = client.sendGetRequestRaw("/st/myapp/doc/${HDOC.readme}:staleHash")

        handler.rptStatusCode shouldBe EXC.ok
        handler.rptResponseHeaders["cache-control"] shouldBe listOf(ContentResources.noStore)
        (handler.rptResponseData ?: "") shouldContain "# KotlinDynamicRuntime"
    }

    "a bare URL with no build id serves the document uncached" {
        val cxt = Startup.mkTestBootCxt("docBare", "markdownDocBareTest")
        val client = TestHttpClient(cxt.instanceConfig)

        // No `:buildId` at all -- the URL names no particular version, so it may be answered but not stored.
        val handler = client.sendGetRequestRaw("/st/myapp/doc/${HDOC.readme}")

        handler.rptStatusCode shouldBe EXC.ok
        handler.rptResponseHeaders["cache-control"] shouldBe listOf(ContentResources.noStore)
        (handler.rptResponseData ?: "") shouldContain "# KotlinDynamicRuntime"
    }

    "a missing document 404s and is not cached" {
        val cxt = Startup.mkTestBootCxt("docMissing", "markdownDocMissingTest")
        val client = TestHttpClient(cxt.instanceConfig)

        // A cached negative answer would outlive a rolling deploy in which one node 404s what another serves.
        val handler = client.sendGetRequestRaw("/st/myapp/doc/no-such-document:1")

        handler.rptStatusCode shouldBe EXC.notFound
        handler.rptResponseHeaders["cache-control"] shouldBe listOf(ContentResources.noStore)
    }
})
