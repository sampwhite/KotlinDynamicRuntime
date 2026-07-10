package com.dynamicruntime.kdn

import com.dynamicruntime.common.http.request.TestHttpClient
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * End-to-end coverage for the portal's content-serving path through the in-process [TestHttpClient]. The
 * portal lives under the content context root (`cp`), so these use the raw (unrouted) client calls; the
 * auto-routed calls go under the API root (`kda`). Also pins the three distinct 404 flavors: terse (unknown
 * root), friendly HTML (known content root, no match), and the JSON envelope (API root, no endpoint).
 */
class PortalHttpTest : StringSpec({

    // One shared instance (the expensive part -- component/schema/service init -- is cached by instance name,
    // so it happens once); each test varies only the inexpensive context name.
    fun client(cxtName: String): TestHttpClient =
        TestHttpClient(Startup.mkTestBootCxt(cxtName, "portalHttpTest").instanceConfig)

    "GET /cp/portal serves the HTML page with the injected bootstrap config" {
        val resp = client("portalPage").sendGetRequestRaw("/cp/portal")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseMimeType shouldBe "text/html; charset=utf-8"
        val body = resp.rptResponseData!!
        body shouldContain "KDR Endpoint Portal"
        // The frontend bootstrap: context roots by focus, so the page's JS can build backend URLs.
        body shouldContain "window.kdrCfg"
        body shouldContain "\"api\":\"kda\""
        body shouldContain "\"content\":\"cp\""
    }

    "GET /cp redirects to the portal page" {
        val resp = client("portalRoot").sendGetRequestRaw("/cp")
        resp.rptStatusCode shouldBe 303
        resp.rptResponseHeaders["location"] shouldBe mutableListOf("/cp/portal")
    }

    "GET / (the bare root) redirects to the content root, which then lands on the portal" {
        val resp = client("bareRoot").sendGetRequestRaw("/")
        resp.rptStatusCode shouldBe 303
        resp.rptResponseHeaders["location"] shouldBe mutableListOf("/cp")
    }

    "GET /cp/does-not-exist yields a friendly HTML 404 (content root, no match)" {
        val resp = client("portalMiss").sendGetRequestRaw("/cp/does-not-exist")
        resp.rptStatusCode shouldBe 404
        resp.rptResponseMimeType shouldBe "text/html; charset=utf-8"
        resp.rptResponseData!! shouldContain "Not Found"
    }

    "GET /kda/portal is not served under the API root (JSON 404, not the portal page)" {
        val resp = client("portalWrongRoot").sendGetRequestRaw("/kda/portal")
        resp.rptStatusCode shouldBe 404
        resp.rptResponseMimeType shouldBe "application/json" // the API error envelope, not HTML
    }

    "an unknown context root is a terse 404, distinct from the friendly content 404" {
        val resp = client("portalProbe").sendGetRequestRaw("/zz/portal")
        resp.rptStatusCode shouldBe 404
        resp.rptResponseMimeType shouldBe "text/plain"
        resp.rptResponseData shouldBe "Not Found"
    }
})
