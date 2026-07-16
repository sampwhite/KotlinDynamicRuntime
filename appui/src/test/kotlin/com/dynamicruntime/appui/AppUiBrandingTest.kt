package com.dynamicruntime.appui

import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.kdn.Startup
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Coverage for a **deployment overriding the app's branding** ([AUIC.appUiBrandingDir]) — the point of which
 * is that a deployment can ship its own artwork without forking `:webapp`.
 *
 * `appui/src/test/resources/testBranding/` stands in for a deployment's branding directory (in production it
 * would ride on the runtime classpath, typically from the `:customConfig` project). It deliberately supplies
 * only *some* of the set, so these can pin the per-asset fallback as well as the override.
 *
 * Each instance name gets its own config (service init is cached per instance), so a test that varies the
 * config must vary the instance name too.
 */
class AppUiBrandingTest : StringSpec({

    InstanceRegistry.register(listOf(AppUiComponent()))

    fun client(instanceName: String, config: Map<String, Any?>): TestHttpClient =
        TestHttpClient(Startup.mkTestBootCxt("branding", instanceName, config).instanceConfig)

    /** A client for an instance whose deployment supplies `testBranding/`. */
    fun branded(): TestHttpClient = client("appUiBranded", mapOf(AUIC.appUiBrandingDir to "testBranding"))

    fun resourceBytes(path: String): ByteArray =
        AppUiBrandingTest::class.java.getResourceAsStream(path).shouldNotBeNull().use { it.readBytes() }

    "a deployment's branding directory overrides a text asset" {
        val resp = branded().sendGetRequestRaw("/wa/favicon.svg")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseMimeType shouldBe "image/svg+xml"
        val body = resp.rptResponseData.shouldNotBeNull()
        body shouldContain "Deployment override"
        // The built-in mark is gone, not merely supplemented.
        body shouldNotContain "Schema braces"
    }

    "a deployment's branding directory overrides a binary asset, byte-for-byte" {
        val resp = branded().sendGetRequestRaw("/wa/favicon-32.png")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseMimeType shouldBe "image/png"
        val bytes = resp.rptResponseBytes.shouldNotBeNull()
        bytes.toList() shouldBe resourceBytes("/testBranding/favicon-32.png").toList()
        // The override still travels the binary path: its 0x89 PNG magic survives, and it is not the built-in.
        bytes.take(4) shouldBe listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        bytes.toList() shouldNotBe resourceBytes("/webapp/favicon-32.png").toList()
    }

    // The reason resolution is per asset rather than all-or-nothing: a deployment should be able to replace
    // its logo and inherit everything else. testBranding/ supplies no apple-touch-icon.
    "an asset the deployment does not supply falls back to the built-in one" {
        val resp = branded().sendGetRequestRaw("/wa/apple-touch-icon.png")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseBytes.shouldNotBeNull().toList() shouldBe
            resourceBytes("/webapp/apple-touch-icon.png").toList()
    }

    // A typo'd or absent directory must not break the app -- it serves the built-in set (and warns at startup,
    // since silently-correct-looking branding is exactly the thing that wastes an afternoon).
    "a configured branding directory that supplies nothing falls back to the built-in set" {
        val resp = client("appUiBrandingMissing", mapOf(AUIC.appUiBrandingDir to "noSuchBrandingDir"))
            .sendGetRequestRaw("/wa/favicon.svg")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseData.shouldNotBeNull() shouldContain "Schema braces"
    }

    "with no branding configured, the built-in set is served" {
        val resp = client("appUiUnbranded", emptyMap()).sendGetRequestRaw("/wa/favicon.svg")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseData.shouldNotBeNull() shouldContain "Schema braces"
    }
})
