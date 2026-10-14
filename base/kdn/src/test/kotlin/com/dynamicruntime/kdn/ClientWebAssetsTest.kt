package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.CMK
import com.dynamicruntime.common.content.ContentResources
import com.dynamicruntime.common.content.HashedFileServing
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.filestore.FMETA
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.http.request.ContextRoot
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.webasset.ClientWebAssetService
import com.dynamicruntime.common.webasset.WAS
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * A client's own favicon and brand mark (issue #1130), end to end on one booted instance, a client per case: its
 * administrator uploads an image into a slot, the listing says what is there, the shell config hands the client's
 * people its name, and the static root serves it to them -- and to nobody else, whatever address they spell.
 */
class ClientWebAssetsTest : StringSpec({
    val cxt = TestInstances.default("clientWebAssets1130")
    val svc = GedraConfigService.get(cxt)

    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    val otherPng = png + byteArrayOf(9, 9)
    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16)
    val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 8 8"><circle cx="4" cy="4" r="3"/></svg>""".toByteArray()

    /** Defines [client], published and loaded. */
    fun defineClient(client: String) {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 11300L }
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = "Client $client", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
            },
        )
        svc.publish(setup, GedraId.of(GedraConfigType.configDoc, client, "main"))
        GedraConfigReload.reloadClient(cxt, client)
    }

    fun image(bytes: ByteArray, type: String, name: String = "logo") = ContentData(bytes, type, saveAsFilename = name, inLine = false)

    /** Uploads as [user]; the whole envelope, so a refusal can be read from it as a success can. */
    fun upload(user: TestUser, slot: String, content: ContentData, client: String? = null): Map<String, Any?> =
        user.upload(UADEP.clientWebUpload, buildMap { put(WAS.slot, slot); put(WAS.file, content); client?.let { put(WAS.client, it) } })

    fun uploaded(user: TestUser, slot: String, content: ContentData, client: String? = null): Map<String, Any?> =
        upload(user, slot, content, client).also { it[EP.status].shouldBeNull() }[EP.results].toJsonMapOrEmpty()

    fun refused(user: TestUser, slot: String, content: ContentData, status: Int = EXC.badInput, client: String? = null): String {
        val env = upload(user, slot, content, client)
        (env[EP.status] as? Number)?.toInt() shouldBe status
        return env[EP.errorMessage] as String
    }

    /** The slots [user] is listed, by slot. */
    fun listed(user: TestUser, client: String? = null): Map<Any?, Map<String, Any?>> =
        user.getItems(UADEP.clientWeb, client?.let { mapOf(WAS.client to it) }).associateBy { it[WAS.slot] }

    /** What the shell config hands [user] of their client's web assets. */
    fun shellAssets(user: TestUser): Map<String, Any?> =
        user.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()[HFLD.webAssets].toJsonMapOrEmpty()

    /** A GET of the static address of [name] (`favicon:<hash>`, or a bare slot) as [user]. */
    fun static(user: TestUser, name: String): RequestHandler =
        user.client.sendGetRequestRaw("/${ContextRoot.st}" + WAS.staticPath("kdr.en", name))

    fun RequestHandler.header(name: String): String? = rptResponseHeaders[name.lowercase()]?.firstOrNull()

    "an upload is listed with its metadata, handed to the client's people by name, and served to them" {
        val client = "webone"
        defineClient(client)
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client, name = "Chief One")
        val member = TestUser.create(cxt, "member@$client.test", userClient = client)

        // Nothing set: every slot is listed, empty, and the shell is handed nothing -- the deployment's images.
        listed(admin).keys.toList() shouldContainExactly WAS.slots
        listed(admin).getValue(WAS.favicon).containsKey(WAS.file) shouldBe false
        shellAssets(member).shouldBeEmpty()

        val slot = uploaded(admin, WAS.favicon, image(png, "image/png", "our icon.png"))
        val name = WAS.versionedName(WAS.favicon, png.sha256Hex())
        slot[WAS.name] shouldBe name
        slot[WAS.label] shouldBe "Favicon"
        slot[WAS.kinds] shouldBe listOf("PNG", "SVG", "ICO", "JPEG", "WebP")
        (slot[WAS.maxSize] as Number).toInt() shouldBe 256 * 1024

        val shown = listed(admin).getValue(WAS.favicon)
        shown[WAS.name] shouldBe name
        shown[WAS.uploadedByName] shouldBe "Chief One"
        val meta = shown[WAS.file].toJsonMapOrEmpty()
        meta[FMETA.mimeType] shouldBe "image/png"
        (meta[FMETA.size] as Number).toInt() shouldBe png.size
        meta[FMETA.hash] shouldBe png.sha256Hex()
        meta[FMETA.fileName] shouldBe "our icon.png"
        (meta[FMETA.uploadedBy] as Number).toLong() shouldBe admin.userId
        // The file is the client's, whoever uploaded it.
        meta[FMETA.client] shouldBe client
        listed(admin).getValue(WAS.brandMark).containsKey(WAS.file) shouldBe false

        // At once, for everybody in the client: the member's shell is handed the name.
        shellAssets(member) shouldBe mapOf(WAS.favicon to name)

        // The address that names exactly these bytes is kept for good.
        val served = static(member, name)
        served.rptStatusCode shouldBe EXC.ok
        served.rptResponseBytes!!.toList() shouldBe png.toList()
        served.rptResponseMimeType shouldBe "image/png"
        served.header("Cache-Control") shouldBe ContentResources.cacheControl
        served.header(HashedFileServing.noSniffHeader) shouldBe HashedFileServing.noSniff
        // Any other address gets the image too, and may not be kept: a wrong hash, and none.
        for (other in listOf(WAS.versionedName(WAS.favicon, "0".repeat(64)), WAS.favicon)) {
            val unmatched = static(member, other)
            unmatched.rptResponseBytes!!.toList() shouldBe png.toList()
            unmatched.header("Cache-Control") shouldBe ContentResources.noStore
        }
        // What follows the first colon is the hash, whatever it holds: a second colon is part of a hash that is not
        // the image's, and not part of the slot's name.
        val twoColons = static(member, "${WAS.favicon}:abc:def")
        twoColons.rptStatusCode shouldBe EXC.ok
        twoColons.rptResponseBytes!!.toList() shouldBe png.toList()
        twoColons.header("Cache-Control") shouldBe ContentResources.noStore
        // An empty slot, and a slot there is none of, are 404s nothing may keep.
        for (absent in listOf(WAS.brandMark, "banner:abc")) {
            val missing = static(member, absent)
            missing.rptStatusCode shouldBe EXC.notFound
            missing.header(ContentResources.cacheControlHeader) shouldBe ContentResources.noStore
            // Its words repeat what the address named, so it too is never to be read as anything but text.
            missing.header(HashedFileServing.noSniffHeader) shouldBe HashedFileServing.noSniff
        }
    }

    "whose image is served is who is signed in, never what the address says" {
        val (ours, theirs) = "webours" to "webtheirs"
        defineClient(ours)
        defineClient(theirs)
        val ourAdmin = TestUser.create(cxt, "chief@$ours.test", level = ROLE.admin, userClient = ours)
        val theirAdmin = TestUser.create(cxt, "chief@$theirs.test", level = ROLE.admin, userClient = theirs)
        val ourName = uploaded(ourAdmin, WAS.brandMark, image(png, "image/png"))[WAS.name] as String

        // Their people have no brand mark, and asking by our image's address does not get them ours.
        shellAssets(theirAdmin).shouldBeEmpty()
        static(theirAdmin, ourName).rptStatusCode shouldBe EXC.notFound
        // Once they have their own, our address gets them theirs -- and under no-store, since it does not name it,
        // which is what keeps a shared cache from holding their image at our address.
        uploaded(theirAdmin, WAS.brandMark, image(otherPng, "image/png"))
        val crossed = static(theirAdmin, ourName)
        crossed.rptResponseBytes!!.toList() shouldBe otherPng.toList()
        crossed.header("Cache-Control") shouldBe ContentResources.noStore
        static(ourAdmin, ourName).rptResponseBytes!!.toList() shouldBe png.toList()
        // And somebody not signed in is in no client that has set one.
        TestHttpClient(cxt.instanceConfig).sendGetRequestRaw("/${ContextRoot.st}" + WAS.staticPath("kdr.en", ourName)).rptStatusCode shouldBe EXC.notFound

        // A client's administrator names no client but their own; one who sees every client names any.
        refused(theirAdmin, WAS.favicon, image(png, "image/png"), EXC.notAuthorized, client = ours)
        theirAdmin.expectError(EXC.notAuthorized, UADEP.clientWeb, args = mapOf(WAS.client to ours))
        theirAdmin.expectError(EXC.notAuthorized, UADEP.clientWebRemove, mapOf(WAS.client to ours, WAS.slot to WAS.brandMark))
        theirAdmin.expectError(EXC.notAuthorized, UADEP.clientWebDownload, args = mapOf(WAS.client to ours, WAS.slot to WAS.brandMark))
        theirAdmin.expectError(EXC.notAuthorized, UADEP.clientWebDownloadMeta, args = mapOf(WAS.client to ours, WAS.slot to WAS.brandMark))
        val full = TestUser.createFullAdmin(cxt, "full@webassets.test")
        listed(full, ours).getValue(WAS.brandMark)[WAS.name] shouldBe ourName
        uploaded(full, WAS.favicon, image(svg, "image/svg+xml"), client = ours)
        // Uploaded by somebody of another client, and still our client's file.
        listed(ourAdmin).getValue(WAS.favicon)[WAS.file].toJsonMapOrEmpty()[FMETA.client] shouldBe ours
        // A client nobody declared is one answer to every call that names it -- not an empty listing for one,
        // a missing image for another and a refusal only for an upload.
        val nobody = "nosuchclient1130"
        refused(full, WAS.favicon, image(png, "image/png"), EXC.notFound, client = nobody) shouldContain "no client"
        full.expectError(EXC.notFound, UADEP.clientWeb, args = mapOf(WAS.client to nobody))[EP.errorMessage] shouldBe "This node knows no client '$nobody'."
        full.expectError(EXC.notFound, UADEP.clientWebRemove, mapOf(WAS.client to nobody, WAS.slot to WAS.favicon))
        full.expectError(EXC.notFound, UADEP.clientWebDownload, args = mapOf(WAS.client to nobody, WAS.slot to WAS.favicon))[EP.errorMessage] shouldBe
            "This node knows no client '$nobody'."
        full.expectError(EXC.notFound, UADEP.clientWebDownloadMeta, args = mapOf(WAS.client to nobody, WAS.slot to WAS.favicon))[EP.errorMessage] shouldBe
            "This node knows no client '$nobody'."
        // Somebody who administers nothing reaches none of it.
        val member = TestUser.create(cxt, "member@$ours.test", userClient = ours)
        member.expectError(EXC.notAuthorized, UADEP.clientWeb)
        refused(member, WAS.favicon, image(png, "image/png"), EXC.notAuthorized)
    }

    "a replaced image takes the old one's place at once, and a removed one gives the deployment's back" {
        val client = "webswap"
        defineClient(client)
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        val first = uploaded(admin, WAS.brandMark, image(png, "image/png"))[WAS.name] as String
        val second = uploaded(admin, WAS.brandMark, image(jpeg, "image/jpeg"))[WAS.name] as String
        second shouldNotBe first

        shellAssets(admin) shouldBe mapOf(WAS.brandMark to second)
        // A page built before the replace still asks by the old name: it is given the new image, not kept.
        val stale = static(admin, first)
        stale.rptResponseBytes!!.toList() shouldBe jpeg.toList()
        stale.rptResponseMimeType shouldBe "image/jpeg"
        stale.header("Cache-Control") shouldBe ContentResources.noStore
        static(admin, second).header("Cache-Control") shouldBe ContentResources.cacheControl

        val emptied = admin.postData(UADEP.clientWebRemove, mapOf(WAS.slot to WAS.brandMark))
        emptied[WAS.slot] shouldBe WAS.brandMark
        emptied.containsKey(WAS.file) shouldBe false
        shellAssets(admin).shouldBeEmpty()
        listed(admin).getValue(WAS.brandMark).containsKey(WAS.name) shouldBe false
        static(admin, second).rptStatusCode shouldBe EXC.notFound
        // Emptying an empty slot is no error.
        admin.postData(UADEP.clientWebRemove, mapOf(WAS.slot to WAS.brandMark)).containsKey(WAS.file) shouldBe false
    }

    "an upload that is not the image it says it is, or is too large, is refused and stores nothing" {
        val client = "webrefuse"
        defineClient(client)
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

        refused(admin, WAS.favicon, image("<html><script>x()</script></html>".toByteArray(), "text/html")) shouldContain "PNG, SVG, ICO, JPEG, WebP"
        refused(admin, WAS.favicon, image(jpeg, "image/png")) shouldContain "sent as a PNG image, but its content is a JPEG image"
        refused(admin, WAS.favicon, image("<html/>".toByteArray(), "image/png")) shouldContain "not one"
        refused(admin, WAS.favicon, image("""<svg xmlns="http://www.w3.org/2000/svg" onload="x()"/>""".toByteArray(), "image/svg+xml")) shouldContain "event handler"
        refused(admin, WAS.favicon, image(ByteArray(0), "image/png")) shouldContain "empty"
        // Each slot has its own size: what a brand mark may be, a favicon may not.
        val big = png + ByteArray(300 * 1024)
        refused(admin, WAS.favicon, image(big, "image/png")) shouldContain "at most 256 KB"
        uploaded(admin, WAS.brandMark, image(big, "image/png"))
        refused(admin, WAS.brandMark, image(png + ByteArray(1024 * 1024), "image/png")) shouldContain "at most 1 MB; this file is 1.1 MB"
        // A slot there is none of; an upload with no file; and one whose `file` is a field's text, not a file.
        refused(admin, "banner", image(png, "image/png"))
        (admin.upload(UADEP.clientWebUpload, mapOf(WAS.slot to WAS.favicon))[EP.status] as Number).toInt() shouldBe EXC.badInput
        val textAsFile = admin.upload(UADEP.clientWebUpload, mapOf(WAS.slot to WAS.favicon, WAS.file to "not a file"))
        (textAsFile[EP.status] as Number).toInt() shouldBe EXC.badInput
        textAsFile[EP.errorMessage] shouldBe "No file was sent."

        listed(admin).getValue(WAS.favicon).containsKey(WAS.file) shouldBe false
        shellAssets(admin).keys.toList() shouldContainExactly listOf(WAS.brandMark)
    }

    "an SVG is served so that it can run nothing, and the named download is a file nothing keeps" {
        val client = "websvg"
        defineClient(client)
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        val name = uploaded(admin, WAS.favicon, image(svg, "image/svg+xml", "mark.svg"))[WAS.name] as String

        val served = static(admin, name)
        served.rptResponseBytes!!.toList() shouldBe svg.toList()
        served.rptResponseMimeType shouldBe "image/svg+xml"
        served.header(HashedFileServing.contentPolicyHeader) shouldBe HashedFileServing.svgContentPolicy
        served.header(HashedFileServing.noSniffHeader) shouldBe HashedFileServing.noSniff

        // What the page of a client that is not the caller's own previews: the image, as a download.
        val full = TestUser.createFullAdmin(cxt, "full@$client.test")
        val download = full.client.sendGetRequest(UADEP.clientWebDownload, mapOf(WAS.client to client, WAS.slot to WAS.favicon))
        download.rptStatusCode shouldBe EXC.ok
        download.rptResponseBytes!!.toList() shouldBe svg.toList()
        download.header("Cache-Control") shouldBe ContentResources.noStore
        download.header(HashedFileServing.contentPolicyHeader) shouldBe HashedFileServing.svgContentPolicy
        // With the image's hash the address names the image, so the caller's own browser may keep it -- no shared
        // cache, since it is behind a sign-in -- and a hash that is not the image's changes nothing but that.
        fun downloadAs(hash: String) = full.client.sendGetRequest(UADEP.clientWebDownload, mapOf(WAS.client to client, WAS.slot to WAS.favicon, WAS.hash to hash))
        downloadAs(svg.sha256Hex()).header("Cache-Control") shouldBe ContentResources.privateCacheControl
        downloadAs("0".repeat(64)).header("Cache-Control") shouldBe ContentResources.noStore
        // Kept for the session that was allowed it: who may have the image is the one thing that varies here.
        downloadAs(svg.sha256Hex()).header(ContentResources.varyHeader) shouldBe ContentResources.varyByCookie
        downloadAs("0".repeat(64)).rptResponseBytes!!.toList() shouldBe svg.toList()
        download.header(ContentData.contentDispositionKey).orEmpty() shouldContain "attachment"
        download.header(ContentData.contentDispositionKey).orEmpty() shouldContain "mark.svg"
        // An empty slot has nothing to download.
        full.expectError(EXC.notFound, UADEP.clientWebDownload, args = mapOf(WAS.client to client, WAS.slot to WAS.brandMark))

        // What the image is, without it (issue #1131): by the download's inputs, the record the listing shows.
        val meta = full.getItem(UADEP.clientWebDownloadMeta, mapOf(WAS.client to client, WAS.slot to WAS.favicon))
        meta shouldBe listed(full, client).getValue(WAS.favicon)[WAS.file]
        meta[FMETA.hash] shouldBe svg.sha256Hex()
        meta[FMETA.fileName] shouldBe "mark.svg"
        // An empty slot is the same 404 from both, in the same words.
        full.expectError(EXC.notFound, UADEP.clientWebDownloadMeta, args = mapOf(WAS.client to client, WAS.slot to WAS.brandMark))[EP.errorMessage] shouldBe
            full.expectError(EXC.notFound, UADEP.clientWebDownload, args = mapOf(WAS.client to client, WAS.slot to WAS.brandMark))[EP.errorMessage]
        // The client's own administrator asks it of their own client without naming it.
        admin.getItem(UADEP.clientWebDownloadMeta, mapOf(WAS.slot to WAS.favicon)) shouldBe meta
        // A raster carries no policy: there is nothing in one to run.
        uploaded(admin, WAS.brandMark, image(png, "image/png"))
        static(admin, WAS.brandMark).header(HashedFileServing.contentPolicyHeader).shouldBeNull()
    }

    "a sandbox shows its parent's images and may not change them" {
        val client = "websbx"
        defineClient(client)
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        val name = uploaded(admin, WAS.favicon, image(png, "image/png"))[WAS.name] as String
        admin.postData(UADEP.clientSandbox, mapOf(CLD.sandbox to true))
        val sandbox = sandboxOf(client)
        val inSandbox = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = sandbox)
        inSandbox.selfClient() shouldBe sandbox

        // The sandbox's people see the parent's, by the same name.
        shellAssets(inSandbox) shouldBe mapOf(WAS.favicon to name)
        static(inSandbox, name).rptResponseBytes!!.toList() shouldBe png.toList()
        listed(inSandbox).getValue(WAS.favicon)[WAS.name] shouldBe name
        // A change would be live for the parent's people, which nothing done in a sandbox may be.
        refused(inSandbox, WAS.favicon, image(otherPng, "image/png")) shouldContain "is a sandbox"
        inSandbox.expectError(EXC.badInput, UADEP.clientWebRemove, mapOf(WAS.slot to WAS.favicon))
        val full = TestUser.createFullAdmin(cxt, "full@$client.test")
        refused(full, WAS.favicon, image(otherPng, "image/png"), client = sandbox) shouldContain "made on that client's own page"
        listed(admin).getValue(WAS.favicon)[WAS.name] shouldBe name
    }

    "an image another node stored is seen once this node's memo has aged, and one this node stored at once" {
        val client = "webmemo"
        defineClient(client)
        val admin = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
        val assets = ClientWebAssetService.get(cxt)
        var now = 0L
        assets.nanoTime = { now }
        try {
            val first = uploaded(admin, WAS.favicon, image(png, "image/png"))[WAS.name] as String
            shellAssets(admin) shouldBe mapOf(WAS.favicon to first)

            // Another node's upload reaches this one only as a change in the store both read.
            val key = ClientWebAssetService.keyOf(client, WAS.favicon)
            FileStoreService.get(cxt).put(cxt.mkSubContext("otherNode", client), ClientWebAssetService.bucket, key, image(otherPng, "image/png"))
            val second = WAS.versionedName(WAS.favicon, otherPng.sha256Hex())
            shellAssets(admin) shouldBe mapOf(WAS.favicon to first)
            now += (ClientWebAssetService.memoMs - 1) * 1_000_000
            shellAssets(admin) shouldBe mapOf(WAS.favicon to first)
            now += 2 * 1_000_000
            shellAssets(admin) shouldBe mapOf(WAS.favicon to second)

            FileStoreService.get(cxt).delete(cxt, ClientWebAssetService.bucket, key)
            shellAssets(admin) shouldBe mapOf(WAS.favicon to second)
            // The page that edits the slots asks the store, not the memo -- and what it reads becomes the memo.
            listed(admin).getValue(WAS.favicon).containsKey(WAS.name) shouldBe false
            shellAssets(admin).shouldBeEmpty()
            // The static address never goes by the memo: it reads the image, so it cannot serve one that is gone.
            static(admin, second).rptStatusCode shouldBe EXC.notFound
        } finally {
            assets.nanoTime = System::nanoTime
        }
    }

    "the static marker is the kernel's, so the address the frontend builds is the one served" {
        WAS.staticPath("kdr", "favicon:abc") shouldBe "/kdr/${CMK.web}/favicon:abc"
    }
})
