package com.dynamicruntime.appui

import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.webasset.WRES
import com.dynamicruntime.common.webasset.WebResourcesDef
import com.dynamicruntime.common.webasset.webResources
import com.dynamicruntime.kdn.Startup
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Coverage for a **deployment's default web resources package** ([WRES.defaultKey], issue #1779) standing in for the
 * built-in branding, which is how a deployment ships its own artwork without forking `:webapp`.
 *
 * [BrandingFixture] declares the package `brandfix:site`, whose files are under `web-resources/brandfix/` in the test
 * resources. It deliberately fills only *some* of the slots the shell links, and its brand mark is a PNG where the
 * shell's name says SVG, so these pin the per-asset fallback as well as the override.
 *
 * Each instance name gets its own config (service init is cached per instance), so a test that varies the config must
 * vary the instance name too.
 */
class AppUiBrandingTest : StringSpec({

    fun cxt(instanceName: String, config: Map<String, Any?>): KdrCxt =
        Startup.mkTestBootCxt("branding", instanceName, config, additionalComponents = listOf(AppUiComponent(), BrandingFixture()))

    fun client(instanceName: String, config: Map<String, Any?>): TestHttpClient = TestHttpClient(cxt(instanceName, config).instanceConfig)

    /** A client for an instance whose default package is the fixture's. */
    fun branded(): TestHttpClient = client("appUiBranded", mapOf(WRES.defaultKey to BrandingFixture.packageId))

    fun resourceBytes(path: String): ByteArray =
        AppUiBrandingTest::class.java.getResourceAsStream(path).shouldNotBeNull().use { it.readBytes() }

    "the default package overrides a text asset" {
        val resp = branded().sendGetRequestRaw("/wa/favicon.svg")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseMimeType shouldBe "image/svg+xml"
        val body = resp.rptResponseData.shouldNotBeNull()
        body shouldContain "Deployment override"
        // The built-in mark is gone, not merely supplemented.
        body shouldNotContain "Schema braces"
    }

    "the default package overrides a binary asset, byte-for-byte, and the shell links it by its own hash" {
        val http = branded()
        val shell = http.sendGetRequestRaw("/wa").rptResponseData.shouldNotBeNull()
        val linked = Regex("""/wa/(favicon-32\.png:[0-9a-f]+)""").find(shell).shouldNotBeNull().groupValues[1]
        val resp = http.sendGetRequestRaw("/wa/$linked")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseMimeType shouldBe "image/png"
        // Its own hash, so the address may be kept for good.
        resp.rptResponseHeaders["cache-control"].orEmpty().joinToString() shouldContain "immutable"
        val bytes = resp.rptResponseBytes.shouldNotBeNull()
        bytes.toList() shouldBe resourceBytes("/web-resources/brandfix/favicon-32.png").toList()
        // The override still travels the binary path: its 0x89 PNG magic survives, and it is not the built-in.
        bytes.take(4) shouldBe listOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        bytes.toList() shouldNotBe resourceBytes("/webapp/favicon-32.png").toList()
    }

    // The reason resolution is per asset rather than all-or-nothing: a deployment should be able to replace its logo
    // and inherit everything else. The fixture fills no Apple touch icon.
    "an asset the default package does not supply falls back to the built-in one" {
        val resp = branded().sendGetRequestRaw("/wa/apple-touch-icon.png")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseBytes.shouldNotBeNull().toList() shouldBe resourceBytes("/webapp/apple-touch-icon.png").toList()
    }

    // The shell serves `brand-mark.svg` as SVG; a package's PNG mark is shown by the page from the shell config, as a
    // client's is, and the fixed address keeps the built-in.
    "a package image of another kind than the asset's name says leaves the built-in at that address" {
        branded().sendGetRequestRaw("/wa/brand-mark.svg").rptResponseData.shouldNotBeNull() shouldContain "Schema braces"
    }

    "a default naming a package no component declares refuses the boot" {
        shouldThrow<KdrException> { cxt("appUiBrandingMissing", mapOf(WRES.defaultKey to "brandfix:nosuch")) }
            .message.shouldNotBeNull() shouldContain "brandfix:nosuch"
    }

    "with no default package, the built-in set is served" {
        val resp = client("appUiUnbranded", emptyMap()).sendGetRequestRaw("/wa/favicon.svg")
        resp.rptStatusCode shouldBe 200
        resp.rptResponseData.shouldNotBeNull() shouldContain "Schema braces"
    }
})

/** A component shipping the web resources package [packageId], under its own owner root `brandfix`. */
class BrandingFixture : ComponentDefinition {
    override val providerName: String = "brandingFixture"
    override val ownerRoot: String = "brandfix"

    override fun webResources(cxt: KdrCxt): List<WebResourcesDef> = listOf(
        webResources(
            packageId, favicon = "brandfix/favicon.svg", brandMark = "brandfix/favicon-32.png", faviconPng = "brandfix/favicon-32.png",
        ),
    )

    companion object {
        const val packageId = "brandfix:site"
    }
}
