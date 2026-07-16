package com.dynamicruntime.appui

import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.kdn.Startup
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain

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
        // The icon and stylesheet, like the bundle, are referenced by absolute paths built from the live app
        // context root.
        body shouldContain "href=\"/wa/favicon.svg\""
        body shouldContain "href=\"/wa/app.css\""
    }

    // The two shells (this one and the dev server's index.html) must not carry their own CSS -- that is how
    // they drifted apart before, leaving production largely unstyled while the dev server looked right. Both
    // link the webapp's single app.css instead, so an inline <style> block here is the bug growing back.
    "the shell carries no CSS of its own -- it links the webapp's single stylesheet" {
        val body = client("appNoInlineCss").sendGetRequestRaw("/wa").rptResponseData!!
        body shouldNotContain "<style>"
    }

    "GET /wa/app.css serves the webapp's stylesheet, whole" {
        val resp = client("appCss").sendGetRequestRaw("/wa/app.css")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseMimeType shouldBe "text/css; charset=utf-8"
        val css = resp.rptResponseData!!
        // Production gets the SAME sheet the dev server serves, not a subset. These are the classes the
        // previous hand-copied production sheet was missing -- one per area of the app -- so this fails if
        // production ever regresses to a partial copy.
        css shouldContain ".app-bar"
        css shouldContain ".app-menu"
        css shouldContain ".home-shell"
        css shouldContain ".markdown"
        css shouldContain ".schema-form"
        css shouldContain ".card"
    }

    "GET /wa/favicon.svg serves the embedded app icon" {
        val resp = client("appIcon").sendGetRequestRaw("/wa/favicon.svg")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseMimeType shouldBe "image/svg+xml"
        // The icon the webapp authored, embedded from its distribution rather than duplicated here.
        resp.rptResponseData!! shouldContain "<svg"
    }

    "GET /wa/brand-mark.svg serves the embedded brand mark" {
        val resp = client("appMark").sendGetRequestRaw("/wa/brand-mark.svg")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseMimeType shouldBe "image/svg+xml"
        resp.rptResponseData!! shouldContain "<svg"
    }

    // The frontend builds the brand mark's URL from the app context root in this bootstrap (it cannot hardcode
    // the path: the dev server serves from the origin root, appui from a context root). If the bootstrap ever
    // stops carrying `contextRoots.app`, the mark silently 404s in production only -- so pin the shape here.
    "the shell's bootstrap carries the app context root the frontend builds asset URLs from" {
        val body = client("appBootstrap").sendGetRequestRaw("/wa").rptResponseData!!
        body shouldContain "\"contextRoots\""
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
