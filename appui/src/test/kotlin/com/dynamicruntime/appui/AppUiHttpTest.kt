package com.dynamicruntime.appui

import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.kdn.Startup
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
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
    // the inexpensive context name.
    fun client(cxtName: String): TestHttpClient =
        TestHttpClient(Startup.mkTestBootCxt(cxtName, "appUiHttpTest").instanceConfig)

    // The embedded resource a request should serve, read from the classpath the same way AppUiService does --
    // so a byte comparison against it proves the response path preserved the file exactly.
    fun resourceBytes(path: String): ByteArray =
        AppUiHttpTest::class.java.getResourceAsStream(path).shouldNotBeNull().use { it.readBytes() }

    "GET /wa serves the webapp HTML shell with the injected bootstrap config" {
        val resp = client("appPage").sendGetRequestRaw("/wa")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseMimeType shouldBe "text/html; charset=utf-8"
        val body = resp.rptResponseData!!
        body shouldContain "<div id=\"root\"></div>"
        // The bundle is referenced by an absolute path built from the live app context root, carrying a
        // `:<hash>` cache-busting suffix (issue #137).
        body shouldContain "src=\"/wa/webapp.js:"
        // The frontend bootstrap: context roots by focus, including this new `app` focus.
        body shouldContain "window.kdrCfg"
        body shouldContain "\"app\":\"wa\""
        // The icons and stylesheet, like the bundle, are referenced by absolute, hash-suffixed paths.
        body shouldContain "href=\"/wa/favicon.svg:"
        body shouldContain "href=\"/wa/favicon-32.png:"
        body shouldContain "href=\"/wa/apple-touch-icon.png:"
        body shouldContain "href=\"/wa/app.css:"
        // The shell itself is never cached, so a reload always fetches the current hashed asset URLs.
        resp.rptResponseHeaders["cache-control"]?.firstOrNull() shouldBe "no-cache"
    }

    "a hash-suffixed asset URL serves the resource and is cached immutably; the bare URL is not (issue #137)" {
        val c = client("appCache")
        // The shell names the versioned bundle URL; pull the exact suffix out and fetch it.
        val shell = c.sendGetRequestRaw("/wa").rptResponseData!!
        val versioned = Regex("src=\"/wa/(webapp\\.js:[0-9a-f]+)\"").find(shell)!!.groupValues[1]

        val hashed = c.sendGetRequestRaw("/wa/$versioned")
        hashed.rptStatusCode shouldBe 200
        hashed.rptResponseMimeType shouldBe "application/javascript; charset=utf-8"
        hashed.rptResponseHeaders["cache-control"]?.firstOrNull() shouldBe "public, max-age=31536000, immutable"

        // The bare URL still serves the same resource, but without the immutable header (it can go stale).
        val bare = c.sendGetRequestRaw("/wa/webapp.js")
        bare.rptStatusCode shouldBe 200
        bare.rptResponseHeaders["cache-control"] shouldBe null
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

    // --- the raster icons: binary, so they must survive as bytes ------------------------------------------

    // The point of the binary response path. A PNG's header is 0x89 'P' 'N' 'G' -- 0x89 is not valid UTF-8,
    // so if these bytes ever went out through the text path, they would come back as "U+FFFD" and the image
    // would be silently corrupt. Comparing against the resource on the classpath proves the whole chain
    // (Gradle copy -> classpath -> read -> response) is byte-exact, which is the only thing that matters here.
    "GET /wa/favicon-32.png serves the PNG icon byte-for-byte" {
        val resp = client("appPng").sendGetRequestRaw("/wa/favicon-32.png")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseMimeType shouldBe "image/png"
        val bytes = resp.rptResponseBytes.shouldNotBeNull()
        // A binary response is bytes only -- it is never also offered as a (lossy) String.
        resp.rptResponseData shouldBe null
        bytes.toList() shouldBe resourceBytes("/webapp/favicon-32.png").toList()
        // The PNG magic number, intact: the high bit in 0x89 is exactly what a UTF-8 round trip destroys.
        bytes.take(4) shouldBe listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    }

    "GET /wa/apple-touch-icon.png serves the iOS icon byte-for-byte" {
        val resp = client("appTouchIcon").sendGetRequestRaw("/wa/apple-touch-icon.png")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseMimeType shouldBe "image/png"
        resp.rptResponseBytes.shouldNotBeNull().toList() shouldBe
            resourceBytes("/webapp/apple-touch-icon.png").toList()
    }

    "GET /wa/favicon.ico serves the ICO byte-for-byte" {
        val resp = client("appIco").sendGetRequestRaw("/wa/favicon.ico")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseMimeType shouldBe "image/x-icon"
        val bytes = resp.rptResponseBytes.shouldNotBeNull()
        bytes.toList() shouldBe resourceBytes("/webapp/favicon.ico").toList()
        // ICO header: reserved 0x0000 then type 0x0001.
        bytes.take(4) shouldBe listOf(0, 0, 1, 0).map { it.toByte() }
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
