package com.dynamicruntime.appui

import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.kdn.Startup
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank

/**
 * End-to-end coverage for the webapp host's content-serving path through the in-process [TestHttpClient]. The
 * webapp lives under the `app`-focus context root (`wa`), so these use the raw (unrouted) client calls (the
 * auto-routed calls go under the API root, `kda`). [AppUiComponent] is registered before boot -- the same
 * ordering the launcher uses -- so the instance includes [AppUiService] as a content server.
 */
class AppUiHttpTest : StringSpec({

    // Register the webapp host with the VM-global registry before any instance is booted, so the instance's
    // service tier includes it. Registration is idempotent, so repeated spec runs are harmless.
    InstanceRegistry.register(listOf(AppUiComponent()))

    // One shared instance (component/schema/service init is cached by instance name); each test varies only
    // the cheap context name.
    fun client(cxtName: String): TestHttpClient =
        TestHttpClient(Startup.mkTestBootCxt(cxtName, "appUiHttpTest").instanceConfig)

    "GET /wa serves the webapp HTML shell with the injected bootstrap config" {
        val resp = client("appPage").sendGetRequestRaw("/wa")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseMimeType shouldBe "text/html; charset=utf-8"
        val body = resp.rptResponseData!!
        body shouldContain "<div id=\"root\"></div>"
        // The bundle is referenced by an absolute path built from the live app context root.
        body shouldContain "src=\"/wa/webapp.js\""
        // The frontend bootstrap: context roots by focus, including this new `app` focus.
        body shouldContain "window.kdrCfg"
        body shouldContain "\"app\":\"wa\""
    }

    "GET /wa/webapp.js serves the embedded JS bundle" {
        val resp = client("appBundle").sendGetRequestRaw("/wa/webapp.js")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseMimeType shouldBe "application/javascript; charset=utf-8"
        resp.rptResponseData!!.shouldNotBeBlank()
    }

    "GET /wa/does-not-exist yields a friendly HTML 404 (app root, no match)" {
        val resp = client("appMiss").sendGetRequestRaw("/wa/does-not-exist")
        resp.rptStatusCode shouldBe 404
        resp.rptResponseMimeType shouldBe "text/html; charset=utf-8"
        resp.rptResponseData!! shouldContain "Not Found"
    }
})
