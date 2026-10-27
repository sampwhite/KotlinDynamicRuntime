package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.ComponentImageService
import com.dynamicruntime.common.content.IMGS
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.filestore.FMETA
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.http.request.ContextRoot
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.BCHK
import com.dynamicruntime.common.startup.BootCheckRegistry
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.webasset.WAS
import com.dynamicruntime.common.webasset.WebResourcesDef
import com.dynamicruntime.common.webasset.webResources
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Web resources packages (issue #1301): a component ships a client's favicon and brand mark, checked at boot; a client
 * defined in source code names one, and shows its images in every slot it has uploaded nothing to; an upload stands in
 * front of the package's image, and removing it brings the package's back. Only source code names a package. Each
 * instance its own, as the images' are: the boot itself is under test.
 */
class WebResourcesTest : StringSpec({
    val faulty = listOf(
        WebResourcesDef("wresfix:badSlot", mapOf("logo" to "fixture/dot.png")),
        webResources("wresfix:misnamed", favicon = "fixture/png-named.svg"),
        webResources("wresfix:missing", favicon = "fixture/none.png"),
        webResources("wresfix:unsafe", favicon = "../component-images/fixture-dot.png"),
        webResources("elsewhere:pkg", favicon = "fixture/dot.png"),
        webResources("wresfix:twice", favicon = "fixture/dot.png"),
        webResources("wresfix:twice", favicon = "fixture/dot.png"),
        webResources("wresfix:empty"),
    )
    // Package problems reported rather than refused, as in production: the faulty files are left out.
    val cxt = Startup.mkTestBootCxt(
        "wres1301", "wres1301", mapOf(IMGS.checkEnvVar.name to "warn"), listOf(WebResourcesFixture(listOf(WebResourcesFixture.full) + faulty)),
    )
    val admin = TestUser.create(cxt, "chief@wres1301.test", level = ROLE.admin, userClient = WebResourcesFixture.client)
    val member = TestUser.create(cxt, "member@wres1301.test", userClient = WebResourcesFixture.client)
    val packaged = ComponentImageService.get(cxt).packages.getValue(WebResourcesFixture.fullId)
    val faviconHash = packaged.getValue(WAS.favicon).meta.hash
    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    fun listed(): Map<Any?, Map<String, Any?>> = admin.getItems(UADEP.clientWeb).associateBy { it[WAS.slot] }

    fun shellAssets(user: TestUser): Map<String, Any?> =
        user.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()[HFLD.webAssets].toJsonMapOrEmpty()

    "a faulty package is a boot problem: refused outside production, and otherwise reported and left out" {
        shouldThrow<Exception> {
            Startup.mkTestBootCxt("wresStrict1301", "wresStrict1301", emptyMap(), listOf(WebResourcesFixture(listOf(WebResourcesFixture.full) + faulty.take(1))))
        }.message.orEmpty() shouldContain "wresfix:badSlot"
        val findings = BootCheckRegistry.get(cxt).results().single { it.name == BCHK.images }.findings
        // Seven: the package declared twice is one finding.
        findings shouldHaveSize 7
        findings.single { "wresfix:badSlot" in it } shouldContain "the slot 'logo'"
        findings.single { "wresfix:misnamed" in it } shouldContain "named as an SVG image, but its content is a PNG image"
        findings.single { "wresfix:missing" in it } shouldContain "no resource"
        findings.single { "wresfix:unsafe" in it } shouldContain "not a plain path"
        findings.single { "elsewhere:pkg" in it } shouldContain "not the component's own root"
        findings.single { "wresfix:twice" in it } shouldContain "declared 2 times"
        findings.single { "wresfix:empty" in it } shouldContain "names no file"
        // The good package is held whole; a package whose id was faulty is left out.
        packaged.keys.toList() shouldContainExactly listOf(WAS.favicon, WAS.brandMark)
        ComponentImageService.get(cxt).packages.containsKey("elsewhere:pkg") shouldBe false
    }

    "a source client naming a package no component ships refuses the boot" {
        shouldThrow<Exception> {
            Startup.mkTestBootCxt("wresUnknown1301", "wresUnknown1301", emptyMap(), listOf(WebResourcesFixture(emptyList())))
        }.message.orEmpty() shouldContain "names the web resources package '${WebResourcesFixture.fullId}', which no component ships"
    }

    "a client with a package shows its images with nothing uploaded, and an upload stands in front of them" {
        // Nothing uploaded: the package's, in the listing, the shell config and under the static address.
        listed().getValue(WAS.favicon).let { slot ->
            slot[WAS.source] shouldBe WAS.sourcePackage
            slot[WAS.packageId] shouldBe WebResourcesFixture.fullId
            slot[WAS.packaged] shouldBe true
            slot[WAS.name] shouldBe WAS.versionedName(WAS.favicon, faviconHash)
            slot[WAS.file].toJsonMapOrEmpty()[FMETA.fileName] shouldBe "dot.png"
        }
        listed().getValue(WAS.brandMark)[WAS.file].toJsonMapOrEmpty()[FMETA.mimeType] shouldBe "image/svg+xml"
        shellAssets(member)[WAS.favicon] shouldBe WAS.versionedName(WAS.favicon, faviconHash)
        val served = member.client.sendGetRequestRaw("/${ContextRoot.st}" + WAS.staticPath("kdr.en", WAS.versionedName(WAS.favicon, faviconHash)))
        served.rptStatusCode shouldBe EXC.ok
        served.rptResponseBytes!!.toList() shouldBe packaged.getValue(WAS.favicon).bytes.toList()
        admin.getItem(UADEP.clientWebDownloadMeta, mapOf(WAS.slot to WAS.favicon))[FMETA.hash] shouldBe faviconHash

        // An upload stands in front of it, for everyone in the client.
        val upload = admin.upload(UADEP.clientWebUpload, mapOf(WAS.slot to WAS.favicon, WAS.file to ContentData(png, "image/png", saveAsFilename = "own.png")))
        upload[EP.status].shouldBeNull()
        listed().getValue(WAS.favicon).let { slot ->
            slot[WAS.source] shouldBe WAS.sourceUpload
            slot[WAS.packaged] shouldBe true
            slot[WAS.file].toJsonMapOrEmpty()[FMETA.fileName] shouldBe "own.png"
        }
        shellAssets(member)[WAS.favicon] shouldBe listed().getValue(WAS.favicon)[WAS.name]

        // Removing the upload brings the package's back, and the removal says so.
        val removed = admin.postData(UADEP.clientWebRemove, mapOf(WAS.slot to WAS.favicon))
        removed[WAS.source] shouldBe WAS.sourcePackage
        removed[WAS.name] shouldBe WAS.versionedName(WAS.favicon, faviconHash)
        shellAssets(member)[WAS.favicon] shouldBe WAS.versionedName(WAS.favicon, faviconHash)
        // Removing again changes nothing: the package's image is not the client's to remove.
        admin.postData(UADEP.clientWebRemove, mapOf(WAS.slot to WAS.favicon))[WAS.source] shouldBe WAS.sourcePackage
    }

    "only source code names a package: a stored definition naming one is refused" {
        val client = "wresstored"
        val message = shouldThrow<KdrException> {
            GedraConfigService.get(cxt).writeConfig(
                cxt.mkSubContext("setup", client).also { it.userId = 13010L },
                gedraConfig(cxt, "main", clientNamespace(client), client) {
                    defineClient(
                        ClientDef(
                            clientId = client, name = "Stored", usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                            enabledEnvironments = setOf(ENV.unit, ENV.local), webResourcesId = WebResourcesFixture.fullId,
                        ),
                    )
                },
            )
        }.message.orEmpty()
        message shouldContain "only a client's source definition may"
        ClientService.get(cxt).known(client).shouldBeNull()
    }
})

/**
 * A component shipping the web resources [packages] it is given, under its own owner root `wresfix`, and a source client
 * naming [fullId] -- which it declares only when [packages] holds it.
 */
class WebResourcesFixture(private val packages: List<WebResourcesDef>) : ComponentDefinition {
    override val providerName: String = "webResourcesFixture"
    override val ownerRoot: String = "wresfix"

    override fun webResources(cxt: KdrCxt): List<WebResourcesDef> = packages

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = "Packaged", usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), webResourcesId = fullId,
                ),
            )
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        const val client = "wresclient"
        const val fullId = "wresfix:full"
        val full = webResources(fullId, favicon = "fixture/dot.png", brandMark = "fixture/mark.svg")
    }
}
