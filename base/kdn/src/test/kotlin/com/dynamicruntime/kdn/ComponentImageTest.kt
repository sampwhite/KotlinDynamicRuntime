package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.ComponentImageDef
import com.dynamicruntime.common.content.ComponentImageService
import com.dynamicruntime.common.content.IMGS
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.content.componentImage
import com.dynamicruntime.common.content.fragmentInline
import com.dynamicruntime.common.content.sharedCopyNamingNoImage
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.BCHK
import com.dynamicruntime.common.startup.BootCheckRegistry
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Images a component ships in its resources (issue #1339): checked at boot, served under the static root by name and
 * content, handed to the webapp in the shell config, and named from copy as `img:<name>`, where copy naming one no
 * component ships is a problem of the copy. Each instance its own: the boot itself is under test.
 */
class ComponentImageTest : StringSpec({
    val dot = componentImage(ImageFixture.dot, "fixture-dot.png")
    val faulty = listOf(
        componentImage("imgfix:script", "fixture-script.svg"),
        componentImage("imgfix:text", "fixture-text.svg"),
        componentImage("imgfix:missing", "fixture-missing.png"),
        componentImage("elsewhere:dot", "fixture-dot.png"),
        componentImage("imgfix:twice", "fixture-dot.png"),
        componentImage("imgfix:twice", "fixture-dot.png"),
    )
    // Image problems reported rather than refused, as in production: the faulty ones are left out.
    val cxt = Startup.mkTestBootCxt(
        "img1339", "img1339", mapOf(IMGS.checkEnvVar.name to "warn"), listOf(ImageFixture(listOf(dot) + faulty)),
    )
    val images = ComponentImageService.get(cxt)
    val served = images.servedNames().getValue(ImageFixture.dot)
    val hash = served.substringAfterLast(':')

    "a faulty image is a boot problem: refused outside production, and otherwise reported and left out" {
        val message = shouldThrow<Exception> {
            Startup.mkTestBootCxt("imgStrict1339", "imgStrict1339", emptyMap(), listOf(ImageFixture(faulty.take(1))))
        }.message.orEmpty()
        message shouldContain "imgfix:script"
        // Reported, one finding a fault.
        val findings = BootCheckRegistry.get(cxt).results().single { it.name == BCHK.images }.findings
        findings shouldHaveSize 5
        findings.single { "imgfix:script" in it } shouldContain "not an image"
        findings.single { "imgfix:missing" in it } shouldContain "no resource"
        findings.single { "elsewhere:dot" in it } shouldContain "not the component's own root"
        findings.single { "imgfix:twice" in it } shouldContain "declared 2 times"
        images.served.keys shouldContainExactly setOf(ImageFixture.dot)
    }

    "an image is served by name and content, as a file ref is" {
        val http = TestHttpClient(cxt.instanceConfig)
        fun get(name: String) = http.sendGetRequestRaw("/st/myapp/img/$name")
        // Its own hash: kept for good, as the image.
        get(served).let {
            it.rptStatusCode shouldBe EXC.ok
            it.rptResponseMimeType shouldBe "image/png"
            it.rptResponseHeaders["cache-control"]?.single().orEmpty() shouldContain "immutable"
        }
        // Another hash, or none: the image as it is now, never kept.
        get("${ImageFixture.dot}:0000").rptResponseHeaders["cache-control"]?.single() shouldBe "no-store"
        get(ImageFixture.dot).rptStatusCode shouldBe EXC.ok
        // No such image, and one left out for a fault: a 404, never kept.
        get("imgfix:script:$hash").let {
            it.rptStatusCode shouldBe EXC.notFound
            it.rptResponseHeaders["cache-control"]?.single() shouldBe "no-store"
        }
    }

    "the shell config hands the webapp each image's served name" {
        val state = TestHttpClient(cxt.instanceConfig).sendJsonGetRequest(HEP.homeUiConfig)[EP.results].toJsonMapOrEmpty()
            .let { it[UIC.state].toJsonMapOrEmpty() }
        state[HFLD.images].toJsonMapOrEmpty() shouldBe mapOf(ImageFixture.dot to served)
    }

    "copy every client shares naming an image no component ships is a fragment problem" {
        val copy = fragmentInline(HFRAG.home, origin = "imageTest") {
            namespace("home") { key("note", "![Shipped](img:${ImageFixture.dot}) and ![Not shipped](img:imgfix:none)") }
        }
        sharedCopyNamingNoImage(cxt, listOf(copy)) shouldBe
            listOf("'${HFRAG.home}' home.note shows the image img:imgfix:none, which no component ships")
    }

    "a client's copy naming an image no component ships is refused with its configuration" {
        val client = "imgcopy1339"
        fun write(note: String) {
            val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
                fragmentOverlay(HFRAG.home) { namespace("home") { key("note", note) } }
            }
            GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("imgcopy", client), config)
            GedraConfigReload.reloadClient(cxt, client)
        }
        write("![Shipped](img:${ImageFixture.dot})")
        shouldThrow<Exception> { write("![Not shipped](img:imgfix:none)") }.message.orEmpty() shouldContain
            "shows the image img:imgfix:none, which no component ships"
    }
})

/** A component shipping the [images] it is given, under its own owner root `imgfix`. */
class ImageFixture(private val images: List<ComponentImageDef>) : ComponentDefinition {
    override val providerName: String = "imageFixture"
    override val ownerRoot: String = "imgfix"

    override fun images(cxt: KdrCxt): List<ComponentImageDef> = images

    @Suppress("ConstPropertyName")
    companion object {
        const val dot = "imgfix:dot"
    }
}
