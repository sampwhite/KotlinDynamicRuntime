package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.ContentResources
import com.dynamicruntime.common.content.MarkdownDocService
import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.home.HDOC
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.schema.schemaDialectMarkdown
import com.dynamicruntime.common.util.jsonMap
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The static content servers for Markdown (issue #1739 gathered these): fragment files as two-tier maps (issue #59), and
 * documents with their build-id caching contract (issue #472). Each part below is one of the specs that used to be a file
 * of its own, kept whole.
 */
class MarkdownContentEndpointTest : StringSpec({
    include(markdownFragmentEndpointTests())
    include(markdownDocEndpointTests())
})

/**
 * End-to-end test of the Markdown fragment content server (issue #59): a GET under the static root
 * `/st/<appId>/md/<fileId:buildId>` returns the fragment file parsed into a two-tier JSON map, with an
 * immutable cache header. Exercised through the real request pipeline against the classpath resource
 * `md-fragments/sample.md`.
 */
private fun markdownFragmentEndpointTests() = stringSpec {

    "the static root serves a fragment file as a two-tier map with an immutable cache header" {
        val cxt = TestInstances.default("md")
        val client = TestHttpClient(cxt.instanceConfig)

        // The buildId is a content hash of the merged content, and since #456 it *selects* what is served.
        val buildId = MarkdownFragmentService.fragmentBuildId(cxt, "sample").shouldNotBeNull()
        val handler = client.sendGetRequestRaw("/st/myapp/md/sample:$buildId")

        handler.rptStatusCode shouldBe EXC.ok
        handler.rptResponseMimeType shouldBe "application/json"
        handler.rptResponseHeaders["cache-control"] shouldBe listOf(ContentResources.cacheControl)

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
        val cxt = TestInstances.default("md2")
        val client = TestHttpClient(cxt.instanceConfig)
        val buildId = MarkdownFragmentService.fragmentBuildId(cxt, "sample").shouldNotBeNull()
        // A different appId (with client/locale-style suffixes) resolves the same file for now.
        val handler = client.sendGetRequestRaw("/st/myapp.acme.en/md/sample:$buildId")
        handler.rptStatusCode shouldBe EXC.ok
    }

    "a missing fragment file returns 404" {
        val cxt = TestInstances.default("md404")
        val client = TestHttpClient(cxt.instanceConfig)
        val handler = client.sendGetRequestRaw("/st/myapp/md/does-not-exist:1")
        handler.rptStatusCode shouldBe EXC.notFound
    }
}

/**
 * The Markdown *document* content server's caching contract (issue #472). A document served under
 * `/st/<appId>/doc/<docId:buildId>` gets the permanent, immutable cache header only when the request's build
 * id matches the one this node has for the document -- so the URL cannot promise a year of caching for bytes
 * it does not name. An absent or unmatched id still serves the current document, but with `no-store`; and
 * unlike a fragment, a document does **not** 404 on a mismatch (a user recovers by navigating away and back).
 * Driven through the real pipeline against the embedded `md-docs/readme.md` resource.
 */
private fun markdownDocEndpointTests() = stringSpec {

    "a matching build id serves the document with the permanent immutable header" {
        val cxt = TestInstances.default("doc")
        val client = TestHttpClient(cxt.instanceConfig)
        val buildId = MarkdownDocService.docBuildId(HDOC.readme).shouldNotBeNull()

        val handler = client.sendGetRequestRaw("/st/myapp/doc/${HDOC.readme}:$buildId")

        handler.rptStatusCode shouldBe EXC.ok
        handler.rptResponseHeaders["cache-control"] shouldBe listOf(ContentResources.cacheControl)
        (handler.rptResponseData ?: "") shouldContain "# KotlinDynamicRuntime"
    }

    "a build id that does not match still serves the current document, but uncached" {
        val cxt = TestInstances.default("docStale")
        val client = TestHttpClient(cxt.instanceConfig)

        // A browser holding a previous ref (e.g., across a deploy) asks for an old hash. It must get the current
        // document -- documents do not 404 on a mismatch -- but never let a shared cache keep it under this URL.
        val handler = client.sendGetRequestRaw("/st/myapp/doc/${HDOC.readme}:staleHash")

        handler.rptStatusCode shouldBe EXC.ok
        handler.rptResponseHeaders["cache-control"] shouldBe listOf(ContentResources.noStore)
        (handler.rptResponseData ?: "") shouldContain "# KotlinDynamicRuntime"
    }

    "a bare URL with no build id serves the document uncached" {
        val cxt = TestInstances.default("docBare")
        val client = TestHttpClient(cxt.instanceConfig)

        // No `:buildId` at all -- the URL names no particular version, so it may be answered but not stored.
        val handler = client.sendGetRequestRaw("/st/myapp/doc/${HDOC.readme}")

        handler.rptStatusCode shouldBe EXC.ok
        handler.rptResponseHeaders["cache-control"] shouldBe listOf(ContentResources.noStore)
        (handler.rptResponseData ?: "") shouldContain "# KotlinDynamicRuntime"
    }

    // Issue #1347: a generated document has no resource. Its text is made from code, and its build id is a hash of
    // that text, so it is cached the same way -- and, like every document, served without signing in.
    "a generated document is served from its generator, under a build id of its text" {
        val cxt = TestInstances.default("docGenerated")
        val client = TestHttpClient(cxt.instanceConfig)
        val doc = MarkdownDocService.declaredDoc(cxt, HDOC.schemaDialect).shouldNotBeNull()
        val buildId = MarkdownDocService.docBuildId(doc).shouldNotBeNull()
        MarkdownDocService.docBuildId(HDOC.schemaDialect) shouldBe null

        val handler = client.sendGetRequestRaw("/st/myapp/doc/${HDOC.schemaDialect}:$buildId")

        handler.rptStatusCode shouldBe EXC.ok
        handler.rptResponseHeaders["cache-control"] shouldBe listOf(ContentResources.cacheControl)
        (handler.rptResponseData ?: "") shouldBe schemaDialectMarkdown()
    }

    "a missing document 404s and is not cached" {
        val cxt = TestInstances.default("docMissing")
        val client = TestHttpClient(cxt.instanceConfig)

        // A cached negative answer would outlive a rolling deploy in which one node 404s what another serves.
        val handler = client.sendGetRequestRaw("/st/myapp/doc/no-such-document:1")

        handler.rptStatusCode shouldBe EXC.notFound
        handler.rptResponseHeaders["cache-control"] shouldBe listOf(ContentResources.noStore)
    }
}
