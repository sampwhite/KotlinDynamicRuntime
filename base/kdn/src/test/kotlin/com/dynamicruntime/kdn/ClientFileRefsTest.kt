package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.ContentResources
import com.dynamicruntime.common.content.HashedFileServing
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.fileref.FREF
import com.dynamicruntime.common.fileref.FileRefService
import com.dynamicruntime.common.filestore.FMETA
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientStoredEdit
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.ConfigWrite
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GC
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GCT
import com.dynamicruntime.common.gedra.GD
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigTopic
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.http.request.ContextRoot
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.http.request.TestHttpClient
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import com.dynamicruntime.common.webasset.WAS
import com.dynamicruntime.common.webasset.WebAssetCheck
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** A component whose source configuration declares a file ref -- which only stored configuration may hold. */
class SourceFileRefComponent : ComponentDefinition {
    override val providerName: String = "sourceFileRefFixture"

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = "Source ref", usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            fileRef("logo", "ab".repeat(32))
        },
    )

    companion object {
        const val client = "srcref1153"
    }
}

/**
 * A client's file refs (issue #1153), end to end on one booted instance, a client per case: an upload stores the
 * bytes under their hash and writes the ref into the client's configuration; the client's people are served it by
 * name and hash; a client with a sandbox drafts it there and publishes it; and what an administrator types into
 * the configuration by hand is held to the same rules as what an upload writes.
 */
class ClientFileRefsTest : StringSpec({
    val cxt = TestInstances.default("clientFileRefs1153")
    val svc = GedraConfigService.get(cxt)
    val refs = FileRefService.get(cxt)
    val files = FileStoreService.get(cxt)

    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    val otherPng = png + byteArrayOf(9, 9)
    val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 8 8"><circle cx="4" cy="4" r="3"/></svg>""".toByteArray()

    /** Defines [client] on the instance [on], published and loaded. */
    fun defineClient(client: String, on: KdrCxt = cxt) {
        val setup = on.mkSubContext("setup", client).also { it.userId = 11530L }
        val svc = GedraConfigService.get(on)
        svc.writeConfig(
            setup,
            gedraConfig(on, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = "Client $client", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
            },
        )
        svc.publish(setup, GedraId.of(GedraConfigType.configDoc, client, "main"))
        GedraConfigReload.reloadClient(on, client)
    }

    fun adminOf(client: String, on: KdrCxt = cxt) =
        TestUser.create(on, "chief@$client.test", level = ROLE.admin, userClient = client, name = "Chief $client")

    /** A context that writes [client]'s configuration in-process, as a script or a restore would. */
    fun writerOf(client: String, on: KdrCxt = cxt): KdrCxt = on.mkSubContext("write", client).also { it.userId = 11534L }

    /** Puts [bytes] where a ref of [client]'s names them, past the upload: what an import's files would be. */
    fun plant(client: String, bytes: ByteArray, on: KdrCxt = cxt): String {
        FileStoreService.get(on).put(writerOf(client, on), FileRefService.bucket, FileRefService.keyOf(client, bytes.sha256Hex()), ContentData(bytes, "image/png"))
        return bytes.sha256Hex()
    }

    fun image(bytes: ByteArray, type: String = "image/png", name: String = "pic") = ContentData(bytes, type, saveAsFilename = name, inLine = false)

    /** Uploads as [user]; the whole envelope, so a refusal can be read from it as a success can. */
    fun upload(user: TestUser, refId: String, content: ContentData, client: String? = null, description: String? = null): Map<String, Any?> =
        user.upload(
            UADEP.clientFilesUpload,
            buildMap {
                put(CCT.refId, refId)
                put(FREF.file, content)
                client?.let { put(FREF.client, it) }
                description?.let { put(CCT.description, it) }
            },
        )

    fun uploaded(user: TestUser, refId: String, content: ContentData, client: String? = null, description: String? = null): Map<String, Any?> =
        upload(user, refId, content, client, description).also { it[EP.status].shouldBeNull() }[EP.results].toJsonMapOrEmpty()

    fun refused(user: TestUser, refId: String, content: ContentData, status: Int = EXC.badInput, client: String? = null, description: String? = null): String {
        val env = upload(user, refId, content, client, description)
        (env[EP.status] as? Number)?.toInt() shouldBe status
        return env[EP.errorMessage] as String
    }

    /** The refs [user] is listed, by id. */
    fun listed(user: TestUser, client: String? = null): Map<Any?, Map<String, Any?>> =
        user.getItems(UADEP.clientFiles, client?.let { mapOf(FREF.client to it) }).associateBy { it[CCT.refId] }

    /** A GET of the static address of [name] (`logo:<hash>`, or a bare id) as [user]. */
    fun static(user: TestUser, name: String): RequestHandler =
        user.client.sendGetRequestRaw("/${ContextRoot.st}" + FREF.staticPath("kdr.en", name))

    fun RequestHandler.header(name: String): String? = rptResponseHeaders[name.lowercase()]?.firstOrNull()

    fun nameOf(refId: String, bytes: ByteArray) = FREF.versionedName(refId, bytes.sha256Hex())

    /** The entries of [client]'s `copy` configuration in the file-ref slot, as stored. */
    fun storedRefs(client: String, on: KdrCxt = cxt): List<Map<String, Any?>> =
        GedraConfigService.get(on).readLatest(on.mkSubContext("read", client), GedraId.of(GedraConfigType.configDoc, client, CPY.copyConfigName))
            ?.entriesBySlot()?.get(CCT.fileRefDef).orEmpty()

    /** One edit of the file-ref slot through the configuration's own patch endpoint: what an administrator types by hand. */
    fun slotEdit(action: GedraEditAction, entry: Map<String, Any?>, config: String = CPY.copyConfigName): Map<String, Any?> = mapOf(
        CFEP.name to config,
        CFEP.edits to listOf(mapOf(CFEP.slot to CCT.fileRefDef, GED.action to action.name, GE.data to entry)),
    )

    "an upload stores the bytes under their hash, writes the ref into the configuration, and is served by name" {
        val client = "refone"
        defineClient(client)
        val admin = adminOf(client)
        val member = TestUser.create(cxt, "member@$client.test", userClient = client)
        listed(admin).keys.shouldBeEmpty()

        val change = uploaded(admin, "logo", image(png, name = "our logo.png"), description = "The logo.")
        change[FREF.client] shouldBe client
        change[FREF.configName] shouldBe CPY.copyConfigName
        // No sandbox, so it is live as it is made.
        change[FREF.mode] shouldBe EDM.live
        val hash = png.sha256Hex()
        change[FREF.ref].toJsonMapOrEmpty()[FREF.name] shouldBe nameOf("logo", png)

        // The entry holds the pointer and what was authored, and nothing of what the file is.
        storedRefs(client) shouldBe listOf(
            mapOf(CCT.refId to "logo", CCT.source to FREF.sourceStore, CCT.hash to hash, CCT.description to "The logo."),
        )
        // The bytes are at a key made of their hash, recorded as the client's.
        val kept = files.get(cxt, FileRefService.bucket, FileRefService.keyOf(client, hash)).shouldNotBeNull()
        kept.bytes.toList() shouldBe png.toList()
        kept.meta.client shouldBe client

        val shown = listed(admin).getValue("logo")
        shown[CCT.hash] shouldBe hash
        shown[CCT.description] shouldBe "The logo."
        shown[FREF.live] shouldBe true
        shown[FREF.missing] shouldBe false
        // An upload is held by stored configuration, which is what makes it the API's to remove.
        shown[FREF.stored] shouldBe true
        shown[FREF.configName] shouldBe CPY.copyConfigName
        shown[FREF.uploadedByName] shouldBe "Chief $client"
        val meta = shown[FREF.file].toJsonMapOrEmpty()
        meta[FMETA.mimeType] shouldBe "image/png"
        meta[FMETA.fileName] shouldBe "our logo.png"
        (meta[FMETA.size] as Number).toInt() shouldBe png.size

        // Anybody in the client is served it, at the address its name makes -- kept for good when the hash is its own.
        val served = static(member, nameOf("logo", png))
        served.rptStatusCode shouldBe EXC.ok
        served.rptResponseBytes!!.toList() shouldBe png.toList()
        served.rptResponseMimeType shouldBe "image/png"
        served.header(ContentResources.cacheControlHeader) shouldBe ContentResources.cacheControl
        served.header(HashedFileServing.noSniffHeader) shouldBe HashedFileServing.noSniff
        for (other in listOf(FREF.versionedName("logo", "0".repeat(64)), "logo")) {
            val unmatched = static(member, other)
            unmatched.rptResponseBytes!!.toList() shouldBe png.toList()
            unmatched.header(ContentResources.cacheControlHeader) shouldBe ContentResources.noStore
        }
        // What a ref takes is the backend's to say (issue #1176), so a page offers what will be accepted: the limits
        // an upload is held to, and no root where the deployment has opened none.
        val takes = admin.getItem(UADEP.clientFilesTakes)
        (takes[WAS.maxSize] as Number).toInt() shouldBe FREF.maxSize
        takes[WAS.kinds] shouldBe listOf("PNG", "SVG", "ICO", "JPEG", "WebP")
        // Every type an upload may honestly be sent as, and no other: a picker offers exactly these.
        takes[WAS.mimeTypes] shouldBe WebAssetCheck.acceptedMimeTypes
        (takes[WAS.mimeTypes] as List<*>) shouldContainAll listOf("image/png", "image/svg+xml", "image/x-icon", "image/jpeg", "image/webp")
        (takes[FREF.refLimit] as Number).toInt() shouldBe FREF.maxRefs
        takes[FREF.roots] shouldBe emptyList<String>()
        // Asked as the page asks it, naming the client; and the administrator of a client's to ask, as every call
        // beside it: not a member's, not another client's, not of a client nobody declared.
        admin.getItem(UADEP.clientFilesTakes, mapOf(FREF.client to client))[WAS.kinds] shouldBe takes[WAS.kinds]
        member.expectError(EXC.notAuthorized, UADEP.clientFilesTakes)
        val seesAll = TestUser.createFullAdmin(cxt, "full@fileRefs.test")
        (seesAll.getItem(UADEP.clientFilesTakes, mapOf(FREF.client to client))[FREF.refLimit] as Number).toInt() shouldBe FREF.maxRefs
        seesAll.expectError(EXC.notFound, UADEP.clientFilesTakes, args = mapOf(FREF.client to "nosuchclient1176"))
        TestUser.create(cxt, "solo@refonepublic.test", level = ROLE.admin, userClient = CL.public).expectError(EXC.notAuthorized, UADEP.clientFilesTakes)

        // The description is reworded, and cleared, without touching the file (issue #1176): the entry keeps its hash.
        fun described(text: String) = admin.client.sendJsonPostRequest(UADEP.clientFilesDescribe, mapOf(CCT.refId to "logo", CCT.description to text))
        described("Our logo, on a dark ground.").let {
            it[EP.status].shouldBeNull()
            val reworded = it[EP.results].toJsonMapOrEmpty()
            reworded[FREF.mode] shouldBe EDM.live
            reworded[FREF.ref].toJsonMapOrEmpty()[CCT.description] shouldBe "Our logo, on a dark ground."
        }
        storedRefs(client) shouldBe listOf(
            mapOf(CCT.refId to "logo", CCT.source to FREF.sourceStore, CCT.hash to hash, CCT.description to "Our logo, on a dark ground."),
        )
        described("   ")[EP.status].shouldBeNull()
        storedRefs(client) shouldBe listOf(mapOf(CCT.refId to "logo", CCT.source to FREF.sourceStore, CCT.hash to hash))
        listed(admin).getValue("logo").containsKey(CCT.description) shouldBe false
        // Saying what it already says writes nothing: no revision is made for it.
        val versionBefore = svc.readLatest(cxt.mkSubContext("read", client), GedraId.of(GedraConfigType.configDoc, client, CPY.copyConfigName))!!.version
        described("")[EP.status].shouldBeNull()
        svc.readLatest(cxt.mkSubContext("read", client), GedraId.of(GedraConfigType.configDoc, client, CPY.copyConfigName))!!.version shouldBe versionBefore
        described("The logo.")[EP.status].shouldBeNull()
        // Held to what a description may be, and to a ref there is.
        (described("x".repeat(FREF.maxDescriptionLength + 1))[EP.status] as Number).toInt() shouldBe EXC.badInput
        admin.expectError(EXC.badInput, UADEP.clientFilesDescribe, mapOf(CCT.refId to "banner", CCT.description to "None such."))[EP.errorMessage] as String shouldContain
            "No stored configuration"
        member.expectError(EXC.notAuthorized, UADEP.clientFilesDescribe, mapOf(CCT.refId to "logo", CCT.description to "Mine."))

        val absent = static(member, "banner:${"0".repeat(64)}")
        absent.rptStatusCode shouldBe EXC.notFound
        absent.header(ContentResources.cacheControlHeader) shouldBe ContentResources.noStore
        absent.header(HashedFileServing.noSniffHeader) shouldBe HashedFileServing.noSniff
    }

    "a ref is its client's: another client's people are not served it, and its administrator names no other client" {
        val (ours, theirs) = "refours" to "reftheirs"
        defineClient(ours)
        defineClient(theirs)
        val ourAdmin = adminOf(ours)
        val theirAdmin = adminOf(theirs)
        uploaded(ourAdmin, "logo", image(png))
        val ourName = nameOf("logo", png)

        static(theirAdmin, ourName).rptStatusCode shouldBe EXC.notFound
        TestHttpClient(cxt.instanceConfig).sendGetRequestRaw("/${ContextRoot.st}" + FREF.staticPath("kdr.en", ourName)).rptStatusCode shouldBe EXC.notFound
        // Their own ref of the same id is theirs, and our address gets them theirs, not kept.
        uploaded(theirAdmin, "logo", image(otherPng))
        val crossed = static(theirAdmin, ourName)
        crossed.rptResponseBytes!!.toList() shouldBe otherPng.toList()
        crossed.header(ContentResources.cacheControlHeader) shouldBe ContentResources.noStore

        refused(theirAdmin, "logo", image(png), EXC.notAuthorized, client = ours)
        theirAdmin.expectError(EXC.notAuthorized, UADEP.clientFiles, args = mapOf(FREF.client to ours))
        theirAdmin.expectError(EXC.notAuthorized, UADEP.clientFilesRemove, mapOf(FREF.client to ours, CCT.refId to "logo"))
        theirAdmin.expectError(EXC.notAuthorized, UADEP.clientFilesDownload, args = mapOf(FREF.client to ours, CCT.refId to "logo"))
        theirAdmin.expectError(EXC.notAuthorized, UADEP.clientFilesDownloadMeta, args = mapOf(FREF.client to ours, CCT.refId to "logo"))
        // Naming our bytes by their hash in their own configuration finds no such file: it is looked for under them.
        val typed = theirAdmin.expectError(
            EXC.badInput, CFEP.bundlePatch,
            slotEdit(GedraEditAction.addOrReplace, mapOf(CCT.refId to "stolen", CCT.source to FREF.sourceStore, CCT.hash to png.sha256Hex())),
        )
        typed[EP.errorMessage] as String shouldContain "not in the file store"

        // One who sees every client names any, and a client nobody declared is a 404 from each call.
        val full = TestUser.createFullAdmin(cxt, "full@fileRefs.test")
        listed(full, ours).getValue("logo")[FREF.name] shouldBe ourName
        val download = full.client.sendGetRequest(UADEP.clientFilesDownload, mapOf(FREF.client to ours, CCT.refId to "logo", CCT.hash to png.sha256Hex()))
        download.rptResponseBytes!!.toList() shouldBe png.toList()
        download.header(ContentResources.cacheControlHeader) shouldBe ContentResources.privateCacheControl
        download.header(ContentResources.varyHeader) shouldBe ContentResources.varyByCookie
        // A download, offered under the ref's own id and its kind's extension -- never the name it was uploaded
        // under, whose extension can say anything.
        download.header(ContentData.contentDispositionKey) shouldBe "attachment; filename=\"logo.png\""
        full.client.sendGetRequest(UADEP.clientFilesDownload, mapOf(FREF.client to ours, CCT.refId to "logo"))
            .header(ContentResources.cacheControlHeader) shouldBe ContentResources.noStore
        val nobody = "nosuchclient1153"
        refused(full, "logo", image(png), EXC.notFound, client = nobody) shouldContain "no client"
        full.expectError(EXC.notFound, UADEP.clientFiles, args = mapOf(FREF.client to nobody))
        full.expectError(EXC.notFound, UADEP.clientFilesDownload, args = mapOf(FREF.client to ours, CCT.refId to "banner"))
        // What the file is, without it (issue #1131): by the download's inputs, the record the listing shows, and
        // the same 404 for a ref that is not there.
        val meta = full.getItem(UADEP.clientFilesDownloadMeta, mapOf(FREF.client to ours, CCT.refId to "logo"))
        meta shouldBe listed(full, ours).getValue("logo")[FREF.file]
        meta[FMETA.hash] shouldBe png.sha256Hex()
        full.expectError(EXC.notFound, UADEP.clientFilesDownloadMeta, args = mapOf(FREF.client to ours, CCT.refId to "banner"))[EP.errorMessage] shouldBe
            full.expectError(EXC.notFound, UADEP.clientFilesDownload, args = mapOf(FREF.client to ours, CCT.refId to "banner"))[EP.errorMessage]
        full.expectError(EXC.notFound, UADEP.clientFilesDownloadMeta, args = mapOf(FREF.client to nobody, CCT.refId to "logo"))
        // Somebody who administers nothing reaches none of it.
        TestUser.create(cxt, "member@$ours.test", userClient = ours).expectError(EXC.notAuthorized, UADEP.clientFiles)
    }

    "a replaced ref names new bytes and leaves the old ones, and a removed ref is gone with its file left behind" {
        val client = "refswap"
        defineClient(client)
        val admin = adminOf(client)
        uploaded(admin, "hero", image(png), description = "First.")
        // No description sent: the ref keeps the one it had. A blank one clears it.
        uploaded(admin, "hero", image(svg, "image/svg+xml", "hero.svg"))
        storedRefs(client).single() shouldBe mapOf(
            CCT.refId to "hero", CCT.source to FREF.sourceStore, CCT.hash to svg.sha256Hex(), CCT.description to "First.",
        )
        // A change answers the ref as it now is, flag and all: what the page redraws the row from.
        uploaded(admin, "hero", image(svg, "image/svg+xml"), description = "")[FREF.ref].toJsonMapOrEmpty()[FREF.stored] shouldBe true
        storedRefs(client).single().containsKey(CCT.description) shouldBe false

        // The old address gets the new bytes, not kept; the new one is kept, and an SVG goes out able to run nothing.
        val stale = static(admin, nameOf("hero", png))
        stale.rptResponseBytes!!.toList() shouldBe svg.toList()
        stale.header(ContentResources.cacheControlHeader) shouldBe ContentResources.noStore
        val current = static(admin, nameOf("hero", svg))
        current.header(ContentResources.cacheControlHeader) shouldBe ContentResources.cacheControl
        current.header(HashedFileServing.contentPolicyHeader) shouldBe HashedFileServing.svgContentPolicy
        // Nothing was written over: the first upload's bytes are where they were.
        files.meta(cxt, FileRefService.bucket, FileRefService.keyOf(client, png.sha256Hex())).shouldNotBeNull()

        // Told "from the draft" by a page that last saw a sandbox the client no longer has, a removal would take the
        // file from the client's people at once: it is refused, and nothing is removed. So with each other change.
        val notADraft = "This would take effect at once for client '$client', not as a draft"
        admin.expectError(EXC.conflict, UADEP.clientFilesRemove, mapOf(CCT.refId to "hero", FREF.mode to EDM.draft))[EP.errorMessage] as String shouldContain notADraft
        admin.expectError(EXC.conflict, UADEP.clientFilesDescribe, mapOf(CCT.refId to "hero", CCT.description to "Reworded.", FREF.mode to EDM.draft))[EP.errorMessage] as String shouldContain notADraft
        storedRefs(client).single()[CCT.refId] shouldBe "hero"
        val removed = admin.postData(UADEP.clientFilesRemove, mapOf(CCT.refId to "hero", FREF.mode to EDM.live))
        removed[FREF.mode] shouldBe EDM.live
        removed.containsKey(FREF.ref) shouldBe false
        listed(admin).keys.shouldBeEmpty()
        storedRefs(client).shouldBeEmpty()
        static(admin, nameOf("hero", svg)).rptStatusCode shouldBe EXC.notFound
        files.meta(cxt, FileRefService.bucket, FileRefService.keyOf(client, svg.sha256Hex())).shouldNotBeNull()
        // There is nothing left to remove.
        admin.expectError(EXC.badInput, UADEP.clientFilesRemove, mapOf(CCT.refId to "hero"))
    }

    "what is refused is refused before anything is stored" {
        val client = "refrefuse"
        defineClient(client)
        val admin = adminOf(client)

        for (bad in listOf("my logo", "logo.png", "2logo", "kdr:logo", "a".repeat(FREF.maxRefIdLength + 1))) {
            refused(admin, bad, image(png))
        }
        refused(admin, "page", image("<html><script>x()</script></html>".toByteArray(), "text/html")) shouldContain "PNG, SVG, ICO, JPEG, WebP"
        refused(admin, "page", image("<html/>".toByteArray())) shouldContain "not one"
        refused(admin, "empty", image(ByteArray(0))) shouldContain "empty"
        refused(admin, "big", image(png + ByteArray(FREF.maxSize))) shouldContain "at most 2 MB"
        (admin.upload(UADEP.clientFilesUpload, mapOf(CCT.refId to "logo"))[EP.status] as Number).toInt() shouldBe EXC.badInput
        admin.upload(UADEP.clientFilesUpload, mapOf(CCT.refId to "logo", FREF.file to "not a file"))[EP.errorMessage] shouldBe "No file was sent."

        listed(admin).keys.shouldBeEmpty()
        storedRefs(client).shouldBeEmpty()
        files.list(cxt, FileRefService.bucket, "clients/$client/").shouldBeEmpty()
    }

    "a client with a sandbox drafts a ref there, and its own people are served it once it is published" {
        val client = "refsbx"
        defineClient(client)
        val admin = adminOf(client)
        admin.postData(UADEP.clientSandbox, mapOf(CLD.sandbox to true))
        val sandbox = sandboxOf(client)
        val inSandbox = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = sandbox)
        inSandbox.selfClient() shouldBe sandbox

        // A page says how it told its user a change would take effect (issues #1176, #1191). One it called live that
        // is a draft goes ahead -- nothing reaches the client's people -- and the answer says it is a draft.
        admin.upload(UADEP.clientFilesUpload, mapOf(CCT.refId to "logo", FREF.file to image(png), FREF.mode to EDM.live)).let {
            it[EP.status].shouldBeNull()
            it[EP.results].toJsonMapOrEmpty()[FREF.mode] shouldBe EDM.draft
        }
        // A draft: the sandbox, which runs the latest revision, shows it; the client runs what it published.
        admin.upload(UADEP.clientFilesUpload, mapOf(CCT.refId to "logo", FREF.file to image(png), FREF.mode to EDM.draft)).let {
            it[EP.status].shouldBeNull()
            it[EP.results].toJsonMapOrEmpty()[FREF.mode] shouldBe EDM.draft
        }
        val name = nameOf("logo", png)
        static(inSandbox, name).rptResponseBytes!!.toList() shouldBe png.toList()
        static(admin, name).rptStatusCode shouldBe EXC.notFound
        // The administrator is listed the draft, told it is not live, and may look at it from outside the sandbox.
        listed(admin).getValue("logo")[FREF.live] shouldBe false
        listed(inSandbox).getValue("logo")[FREF.live] shouldBe false
        // A sandbox's copy of its parent's configuration keeps what kind of configuration held the ref.
        listed(inSandbox).getValue("logo")[FREF.stored] shouldBe true
        admin.client.sendGetRequest(UADEP.clientFilesDownload, mapOf(CCT.refId to "logo")).rptResponseBytes!!.toList() shouldBe png.toList()

        // Published, it is the client's people's.
        val owner = cxt.mkSubContext("publish", client).also { it.userId = 11531L }
        svc.publish(owner, GedraId.of(GedraConfigType.configDoc, client, CPY.copyConfigName))
        GedraConfigReload.reloadClient(cxt, client)
        static(admin, name).rptResponseBytes!!.toList() shouldBe png.toList()
        listed(admin).getValue("logo")[FREF.live] shouldBe true

        // An upload made in the sandbox is not refused, as a web asset's is: it is the parent's next draft.
        val drafted = uploaded(inSandbox, "logo", image(otherPng))
        drafted[FREF.client] shouldBe client
        drafted[FREF.mode] shouldBe EDM.draft
        static(inSandbox, "logo").rptResponseBytes!!.toList() shouldBe otherPng.toList()
        // The client still serves what it published -- the bytes were not written over, being at another key.
        static(admin, name).rptResponseBytes!!.toList() shouldBe png.toList()
        static(admin, name).header(ContentResources.cacheControlHeader) shouldBe ContentResources.cacheControl
        listed(admin).getValue("logo")[FREF.live] shouldBe false
        // A removal is a draft too: the sandbox loses the ref, the client keeps serving it.
        inSandbox.postData(UADEP.clientFilesRemove, mapOf(CCT.refId to "logo"))[FREF.mode] shouldBe EDM.draft
        static(inSandbox, "logo").rptStatusCode shouldBe EXC.notFound
        static(admin, name).rptStatusCode shouldBe EXC.ok
    }

    // Issue #1188: what a draft removed is still served, so it is listed, previewed from what is served, and can be
    // put back -- as it is published, with nothing uploaded again.
    "a ref a draft removed is listed as such, previewed from what is served, and a restore puts it back" {
        val client = "refrestore"
        defineClient(client)
        val admin = adminOf(client)
        uploaded(admin, "logo", image(png), description = "The logo.")
        uploaded(admin, "kept", image(otherPng))
        val published = storedRefs(client).single { it[CCT.refId] == "logo" }
        // A client with no sandbox runs what it is shown: there is nothing of its to put back.
        admin.expectError(EXC.badInput, UADEP.clientFilesRestore, mapOf(CCT.refId to "logo"))[EP.errorMessage].toString() shouldContain "has no sandbox"

        admin.postData(UADEP.clientSandbox, mapOf(CLD.sandbox to true))
        val sandbox = sandboxOf(client)
        val inSandbox = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = sandbox)
        val removed = admin.postData(UADEP.clientFilesRemove, mapOf(CCT.refId to "logo", FREF.mode to EDM.draft))
        removed[FREF.mode] shouldBe EDM.draft
        // The answer is the row as the listing now draws it, not a row gone.
        removed[FREF.ref].toJsonMapOrEmpty().let {
            it[FREF.removedInDraft] shouldBe true
            it[FREF.live] shouldBe true
            it[CCT.description] shouldBe "The logo."
        }
        for (user in listOf(admin, inSandbox)) {
            listed(user).getValue("logo")[FREF.removedInDraft] shouldBe true
            listed(user).getValue("kept").containsKey(FREF.removedInDraft) shouldBe false
            // In the place it had, so a page that drew it there at once does not see it move on the next read.
            user.getItems(UADEP.clientFiles).map { it[CCT.refId] } shouldBe listOf("logo", "kept")
        }
        // What the sandbox shows has no such ref; what the client's people are served does.
        admin.client.sendGetRequest(UADEP.clientFilesDownload, mapOf(CCT.refId to "logo")).rptStatusCode shouldBe EXC.notFound
        admin.client.sendGetRequest(UADEP.clientFilesDownload, mapOf(CCT.refId to "logo", FREF.served to true)).rptResponseBytes!!.toList() shouldBe png.toList()
        admin.getItem(UADEP.clientFilesDownloadMeta, mapOf(CCT.refId to "logo", FREF.served to true))[FMETA.hash] shouldBe png.sha256Hex()

        // Only a removed one is put back: not one the draft still holds, nor one nobody is served.
        admin.expectError(EXC.badInput, UADEP.clientFilesRestore, mapOf(CCT.refId to "kept"))[EP.errorMessage].toString() shouldContain "still holds"
        admin.expectError(EXC.badInput, UADEP.clientFilesRestore, mapOf(CCT.refId to "ghost"))[EP.errorMessage].toString() shouldContain "served no file ref 'ghost'"

        // Put back from the sandbox's own page: the entry as it is published, a draft, an ordinary row again.
        val restored = inSandbox.postData(UADEP.clientFilesRestore, mapOf(CCT.refId to "logo", FREF.mode to EDM.draft))
        restored[FREF.mode] shouldBe EDM.draft
        restored[FREF.ref].toJsonMapOrEmpty().containsKey(FREF.removedInDraft) shouldBe false
        restored[FREF.ref].toJsonMapOrEmpty()[FREF.live] shouldBe true
        storedRefs(client).single { it[CCT.refId] == "logo" } shouldBe published
        listed(admin).getValue("logo").containsKey(FREF.removedInDraft) shouldBe false
        static(inSandbox, "logo").rptResponseBytes!!.toList() shouldBe png.toList()

        // Removed again and published, it is gone: nobody is served it.
        admin.postData(UADEP.clientFilesRemove, mapOf(CCT.refId to "logo"))
        svc.publish(cxt.mkSubContext("publish", client).also { it.userId = 11531L }, GedraId.of(GedraConfigType.configDoc, client, CPY.copyConfigName))
        GedraConfigReload.reloadClient(cxt, client)
        listed(admin).keys shouldBe setOf("kept")
    }

    "a restore is a ref more in the draft, refused for one already holding as many as a client is served" {
        val full = "refrestorefull"
        defineClient(full)
        val fullAdmin = adminOf(full)
        uploaded(fullAdmin, "logo", image(png))
        fullAdmin.postData(UADEP.clientSandbox, mapOf(CLD.sandbox to true))
        fullAdmin.postData(UADEP.clientFilesRemove, mapOf(CCT.refId to "logo"))
        val hash = plant(full, otherPng)
        svc.writeConfig(writerOf(full), gedraConfig(cxt, "many", clientNamespace(full), full) { for (n in 1..FREF.maxRefs) fileRef("r$n", hash) })
        GedraConfigReload.reloadClient(cxt, full)
        // Said before anything is tried, in the words every change at the limit is told.
        fullAdmin.expectError(EXC.badInput, UADEP.clientFilesRestore, mapOf(CCT.refId to "logo"))[EP.errorMessage].toString() shouldBe
            "Client '$full' has ${FREF.maxRefs} file refs, which is as many as a client is served. Remove one first."
    }

    // Issue #1192: a client that runs only its published configuration, with no sandbox, is served what it published;
    // a draft that dropped the entry leaves nothing a removal could take, and the row says so rather than offering one.
    // Who set a ref is the entry's own stamp, not the file's record of whoever first stored its bytes.
    "a row offers what the write would do, and says who set the ref" {
        val client = "refrowacts"
        defineClient(client)
        val admin = adminOf(client)
        uploaded(admin, "logo", image(png))
        listed(admin).getValue("logo").let {
            it[FREF.removable] shouldBe true
            it[FREF.setBy] shouldBe "Chief $client"
            it[FREF.setAt].shouldNotBeNull()
        }
        // The same bytes, set as another ref by somebody else: its file's record is the first upload's.
        val other = TestUser.create(cxt, "second@$client.test", level = ROLE.admin, userClient = client, name = "Second $client")
        uploaded(other, "again", image(png))
        listed(admin).getValue("again").let {
            it[FREF.setBy] shouldBe "Second $client"
            it[FREF.uploadedByName] shouldBe "Chief $client"
        }

        svc.setPublishedOnly(writerOf(client), client, true)
        svc.writeConfig(writerOf(client), gedraConfig(cxt, CPY.copyConfigName, clientNamespace(client), client) { fileRef("again", png.sha256Hex()) })
        GedraConfigReload.reloadClient(cxt, client)
        listed(admin).getValue("logo").let {
            it[FREF.live] shouldBe true
            it[FREF.removable] shouldBe false
            it.containsKey(FREF.setBy) shouldBe false
        }
        admin.expectError(EXC.badInput, UADEP.clientFilesRemove, mapOf(CCT.refId to "logo"))
    }

    // Issue #1171: a client set to run only its published configuration, with no sandbox, has drafts no node runs, so
    // no load records what they already have. A draft ref whose file went missing is the draft's own problem, not one
    // every later draft write brings; a draft write that adds one is still refused.
    "a published-only client with no sandbox: a draft's missing file does not refuse unrelated draft writes" {
        val client = "refunjudged"
        defineClient(client)
        val writer = writerOf(client)
        svc.setPublishedOnly(writer, client, true)
        val hash = plant(client, png)
        svc.writeConfig(writer, gedraConfig(cxt, "art", clientNamespace(client), client) { fileRef("pic", hash) }, trial = true)
        // The draft's file goes: deleted from the store, no configuration changing.
        files.delete(writer, FileRefService.bucket, FileRefService.keyOf(client, hash)) shouldBe true
        refs.forget(client, hash)

        // An unrelated draft write goes through: the missing file is the draft's already. A whole write, and a patch.
        svc.writeConfig(writer, gedraConfig(cxt, "words", clientNamespace(client), client) { cfact("unrelated", "carry", "Unrelated.") }, trial = true)
        svc.patchConfig(writer, GedraId.of(GedraConfigType.configDoc, client, "words"), trial = true) { it }
        // So does one of the same configuration that leaves the ref naming what it named: rewording it.
        svc.writeConfig(writer, gedraConfig(cxt, "art", clientNamespace(client), client) { fileRef("pic", hash, "Reworded.") }, trial = true)
            .shouldNotBeNull()
        // A second ref to the same missing file is a problem the write brings.
        shouldThrow<KdrException> {
            svc.writeConfig(writer, gedraConfig(cxt, "art", clientNamespace(client), client) { fileRef("pic", hash); fileRef("also", hash) }, trial = true)
        }.message.orEmpty() shouldContain "'also'"
        // One that adds a ref of its own to a file that is not there is refused, as ever.
        val missing = otherPng.sha256Hex()
        shouldThrow<KdrException> {
            svc.writeConfig(writer, gedraConfig(cxt, "more", clientNamespace(client), client) { fileRef("ghost", missing) }, trial = true)
        }.message.orEmpty() shouldContain "'ghost'"
    }

    "an entry typed into the configuration is held to what an upload writes" {
        val client = "reftyped"
        defineClient(client)
        val admin = adminOf(client)
        uploaded(admin, "logo", image(png))
        val hash = png.sha256Hex()
        fun typed(entry: Map<String, Any?>) = admin.expectError(EXC.badInput, CFEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, entry))

        // The shape first, by the slot's own schema: an id that is no name, a hash that is not one, another source.
        typed(mapOf(CCT.refId to "my logo", CCT.source to FREF.sourceStore, CCT.hash to hash))
        typed(mapOf(CCT.refId to "alias", CCT.source to FREF.sourceStore, CCT.hash to "abc"))
        typed(mapOf(CCT.refId to "alias", CCT.source to FREF.sourceStore, CCT.hash to hash.uppercase()))
        typed(mapOf(CCT.refId to "alias", CCT.source to "elsewhere", CCT.hash to hash))
        typed(mapOf(CCT.refId to "alias", CCT.source to FREF.sourceStore))
        // Then what a load would find: a hash the store holds no file for is refused as a write.
        typed(mapOf(CCT.refId to "alias", CCT.source to FREF.sourceStore, CCT.hash to "0".repeat(64)))[EP.errorMessage] as String shouldContain
            "not in the file store"
        // A second id for bytes the client already holds is a ref like any other.
        admin.postData(CFEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, mapOf(CCT.refId to "alias", CCT.source to FREF.sourceStore, CCT.hash to hash)))
        storedRefs(client).map { it[CCT.refId] } shouldContainExactly listOf("logo", "alias")

        // What is served is held to on every read, since an entry can be typed: bytes at a ref's key that no checked
        // upload put there -- a page, say -- are no file of the ref's, whatever the entry names.
        val page = "<html><script>x()</script></html>".toByteArray()
        files.put(cxt.mkSubContext("planted", client), FileRefService.bucket, FileRefService.keyOf(client, page.sha256Hex()), ContentData(page, "text/html"))
        admin.postData(CFEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, mapOf(CCT.refId to "page", CCT.source to FREF.sourceStore, CCT.hash to page.sha256Hex())))
        GedraConfigReload.reloadClient(cxt, client)
        refs.forClient(client).refs.keys shouldContainExactly setOf("logo", "alias", "page")
        static(admin, "page").rptStatusCode shouldBe EXC.notFound
        admin.expectError(EXC.notFound, UADEP.clientFilesDownload, args = mapOf(CCT.refId to "page"))
        static(admin, "alias").rptResponseBytes!!.toList() shouldBe png.toList()

        // The same id in two of a client's configurations is one the load would leave out, so the write is refused.
        shouldThrow<KdrException> {
            svc.writeConfig(writerOf(client), gedraConfig(cxt, "twice", clientNamespace(client), client) { fileRef("logo", hash) }, trial = true)
        }.message.orEmpty() shouldContain "more than once"
    }

    "an upload refuses what it can before it stores anything" {
        val client = "refearly"
        defineClient(client)
        val admin = adminOf(client)
        fun stored(bytes: ByteArray) = files.meta(cxt, FileRefService.bucket, FileRefService.keyOf(client, bytes.sha256Hex()))

        refused(admin, "logo", image(png), description = "x".repeat(FREF.maxDescriptionLength + 1))
        stored(png).shouldBeNull()

        // A configuration other than the editors' own, holding the ref, with somebody's unpublished changes: an
        // upload would publish them with it, so it is refused -- and the new bytes are not left behind for it.
        val writer = writerOf(client)
        val art = GedraId.of(GedraConfigType.configDoc, client, "art")
        val hash = plant(client, png)
        svc.writeConfig(writer, gedraConfig(cxt, "art", clientNamespace(client), client) { fileRef("banner", hash) }, trial = true)
        svc.publish(writer, art)
        GedraConfigReload.reloadClient(cxt, client)
        svc.writeConfig(writer, gedraConfig(cxt, "art", clientNamespace(client), client) { fileRef("banner", hash, "Half done.") }, trial = true)
        refused(admin, "banner", image(otherPng)) shouldContain "unpublished changes"
        stored(otherPng).shouldBeNull()
        // Published, the same upload is taken, into the configuration that held the ref.
        svc.publish(writer, art)
        uploaded(admin, "banner", image(otherPng))[FREF.configName] shouldBe "art"

        // A client already served as many refs as it may have is told so before the file is stored.
        val full = "refatcap"
        defineClient(full)
        val fullHash = plant(full, png)
        svc.writeConfig(writerOf(full), gedraConfig(cxt, "many", clientNamespace(full), full) { for (n in 1..FREF.maxRefs) fileRef("r$n", fullHash) }, trial = true)
        svc.publish(writerOf(full), GedraId.of(GedraConfigType.configDoc, full, "many"))
        GedraConfigReload.reloadClient(cxt, full)
        refs.forClient(full).refs.size shouldBe FREF.maxRefs
        val fullAdmin = adminOf(full)
        refused(fullAdmin, "fresh", image(otherPng)) shouldContain "as many as a client is served"
        files.meta(cxt, FileRefService.bucket, FileRefService.keyOf(full, otherPng.sha256Hex())).shouldBeNull()
        // Replacing one it has is no addition.
        uploaded(fullAdmin, "r7", image(otherPng))
        // An entry typed into its configuration is held to the same limit, by the write's trial.
        val typed = fullAdmin.expectError(
            EXC.badInput, CFEP.bundlePatch,
            slotEdit(GedraEditAction.addOrReplace, mapOf(CCT.refId to "fresh", CCT.source to FREF.sourceStore, CCT.hash to fullHash), "many"),
        )
        typed[EP.errorMessage] as String shouldContain "has more than ${FREF.maxRefs} file refs"
    }

    "the first uploads of a client each keep the other's ref, and what another editor wrote" {
        val client = "reffirst"
        defineClient(client)
        val admin = adminOf(client)
        val bound = writerOf(client)
        // The editors' own configuration is made empty and then patched, never written with the entry in it: asked
        // for twice it is the one configuration, and making it again is refused rather than done over.
        val made = ClientStoredEdit.ensureEditConfig(bound, client)
        made.entries.shouldBeEmpty()
        ClientStoredEdit.ensureEditConfig(bound, client).configId shouldBe made.configId
        shouldThrow<KdrException> {
            svc.writeConfig(bound, gedraConfig(cxt, CPY.copyConfigName, clientNamespace(client), client) {}, mustBeNew = true)
        }.code shouldBe EXC.conflict

        svc.patchConfig(bound, made.configId) { it + (CCT.cfactDef to listOf(mapOf(CCT.name to "firstEdit", CCT.group to "first", CCT.description to "Another editor's."))) }
        uploaded(admin, "one", image(png))
        uploaded(admin, "two", image(otherPng))
        storedRefs(client).map { it[CCT.refId] } shouldContainExactly listOf("one", "two")
        svc.readLatest(bound, made.configId)!!.entriesBySlot().getValue(CCT.cfactDef).single()[CCT.name] shouldBe "firstEdit"
    }

    "a file that is gone is noticed, and uploading it again puts it back" {
        val client = "refrepair"
        defineClient(client)
        val admin = adminOf(client)
        uploaded(admin, "logo", image(png))
        val key = FileRefService.keyOf(client, png.sha256Hex())
        files.delete(cxt, FileRefService.bucket, key) shouldBe true

        // Serving reads the store, so it says so at once.
        static(admin, "logo").rptStatusCode shouldBe EXC.notFound
        // A listing goes on what the store last said, for a while -- an object is not expected to go -- and then asks.
        listed(admin).getValue("logo")[FREF.missing] shouldBe false
        // The download and the meta read beside it ask the store now (issue #1131): one answer from the two. And
        // what the meta read was told is what this node now remembers, so the listing says so too.
        admin.expectError(EXC.notFound, UADEP.clientFilesDownload, args = mapOf(CCT.refId to "logo"))
        admin.expectError(EXC.notFound, UADEP.clientFilesDownloadMeta, args = mapOf(CCT.refId to "logo"))
        listed(admin).getValue("logo")[FREF.missing] shouldBe true
        try {
            refs.nanoTime = { System.nanoTime() + (FileRefService.askedMemoMs + 1) * 1_000_000 }
            val shown = listed(admin).getValue("logo")
            shown[FREF.missing] shouldBe true
            shown.containsKey(FREF.file) shouldBe false
        } finally {
            refs.nanoTime = System::nanoTime
        }
        // The same file uploaded again is written again, though its hash is one the client already names.
        uploaded(admin, "logo", image(png))
        files.meta(cxt, FileRefService.bucket, key).shouldNotBeNull()
        static(admin, "logo").rptResponseBytes!!.toList() shouldBe png.toList()
        listed(admin).getValue("logo")[FREF.missing] shouldBe false
    }

    "an object at a ref's key that is not what the ref names is no file, to the meta read as to the download" {
        val client = "refforeign"
        defineClient(client)
        val admin = adminOf(client)
        uploaded(admin, "logo", image(png))
        uploaded(admin, "mark", image(svg, "image/svg+xml"))
        val writer = writerOf(client)
        // Written past the upload, straight at the keys: other bytes under the logo's hash, and the mark's own bytes
        // recorded as a type no ref may be.
        files.put(writer, FileRefService.bucket, FileRefService.keyOf(client, png.sha256Hex()), ContentData(otherPng, "image/png"))
        files.put(writer, FileRefService.bucket, FileRefService.keyOf(client, svg.sha256Hex()), ContentData(svg, "text/plain"))

        for (refId in listOf("logo", "mark")) {
            admin.expectError(EXC.notFound, UADEP.clientFilesDownload, args = mapOf(CCT.refId to refId))
            admin.expectError(EXC.notFound, UADEP.clientFilesDownloadMeta, args = mapOf(CCT.refId to refId))
        }
        // Nor does the listing describe a file it would not serve, once it asks the store again rather than going
        // on what the store said at the upload.
        try {
            refs.nanoTime = { System.nanoTime() + (FileRefService.askedMemoMs + 1) * 1_000_000 }
            for (refId in listOf("logo", "mark")) listed(admin).getValue(refId)[FREF.missing] shouldBe true
        } finally {
            refs.nanoTime = System::nanoTime
        }
    }

    // What a bulk import writes per client (`writeConfigs`, judged together), called as the import calls it: on the
    // caller's own context, which is not the client's.
    "an import is refused for the files it does not bring, and for more refs than a client is served" {
        val client = "refimport"
        defineClient(client)
        fun config(name: String, build: com.dynamicruntime.common.gedra.GedraConfigBuilder.() -> Unit) =
            ConfigWrite(gedraConfig(cxt, name, clientNamespace(client), client, build = build))

        val absent = shouldThrow<KdrException> {
            svc.writeConfigs(cxt, client, listOf(config("art") { fileRef("ghost", "0".repeat(64)); fileRef("spirit", "1".repeat(64)) }), trial = true)
        }
        absent.code shouldBe EXC.badInput
        // It names each ref whose file is not here, which is what says what to copy across first.
        absent.message.orEmpty() shouldContain "File ref 'ghost' of client '$client'"
        absent.message.orEmpty() shouldContain "File ref 'spirit' of client '$client'"
        absent.message.orEmpty() shouldContain "not in the file store"

        // Two configurations that are each within the limit and together are not: judged as the set they are.
        val hash = plant(client, png)
        val half = FREF.maxRefs / 2 + 1
        val tooMany = shouldThrow<KdrException> {
            svc.writeConfigs(
                cxt, client,
                listOf(config("art") { for (n in 1..half) fileRef("a$n", hash) }, config("more") { for (n in 1..half) fileRef("b$n", hash) }),
                trial = true,
            )
        }
        tooMany.message.orEmpty() shouldContain "Client '$client' has more than ${FREF.maxRefs} file refs"
        // Nothing of either refused import was stored.
        svc.listConfigs(writerOf(client)).map { it.configId.baseId } shouldContainExactly listOf("main")
    }

    // The rolling-deploy hazard the slot brought to light: every write passes through the model, which keeps only
    // the slots it knows -- so a node one release behind would delete a newer release's entries from each
    // configuration it edited. The slot here stands for one this node does not read.
    "a write does not delete the entries of a slot this node does not read" {
        val client = "refcarry"
        val writer = cxt.mkSubContext("carry", client).also { it.userId = 11533L }
        val classId = GedraId.of(GedraConfigType.configDoc, client, "main")
        val main = svc.writeConfig(writer, gedraConfig(cxt, "main", clientNamespace(client), client) { cfact("carryOne", "carry", "One.") })
        val future = "kdr:futureSlotDef"
        val planted = listOf(
            mapOf(GE.traitId to future, GE.data to mapOf("id" to "a")),
            mapOf(GE.traitId to future, GE.data to mapOf("id" to "b")),
        )
        val sqlCxt = SqlTopicService.mkSqlCxt(cxt, gedraConfigTopic)
        val table = cxt.getGlobalSchema().tables.getValue(GCT.gedraConfig)
        sqlCxt.sqlDb.withSession(cxt) {
            val plant = SqlStmtUtil.prepareSql(
                sqlCxt, "plantFutureSlot", table.columns,
                "update t:${GCT.gedraConfig} set c:${GC.data} = :${GC.data}, c:${PF.updatedAt} = :${PF.updatedAt} " +
                    "where c:${GC.gedraId} = :${GC.gedraId}",
            )
            sqlCxt.sqlDb.executeStatement(
                cxt, plant,
                mapOf(
                    GC.gedraId to main.gedraId.fullId,
                    GC.data to mapOf(GD.entries to main.entries + planted, GC.namespace to clientNamespace(client)),
                    PF.updatedAt to SqlTopicUtil.nextUpdatedAt(cxt, main.updatedAt),
                ),
            ) shouldBe 1
        }
        fun futureOf(entries: List<Map<String, Any?>>) = entries.filter { it[GE.traitId] == future }.map { it[GE.data] }
        val both = listOf(mapOf("id" to "a"), mapOf("id" to "b"))

        // An edit of another slot, as a copy or menu editor makes one -- trialled, as theirs are: a slot this node
        // does not read is nothing the write did, so it is not what the trial judges the write by.
        val patched = svc.patchConfig(writer, classId, trial = true) { current ->
            current + (CCT.cfactDef to listOf(mapOf(CCT.name to "carryTwo", CCT.group to "carry", CCT.description to "Two.")))
        }
        patched.entriesBySlot().getValue(CCT.cfactDef).single()[CCT.name] shouldBe "carryTwo"
        futureOf(patched.entries) shouldBe both
        // An authoritative write of the whole configuration, which says nothing of the slot.
        val rewritten = svc.writeConfig(writer, gedraConfig(cxt, "main", clientNamespace(client), client) { cfact("carryThree", "carry", "Three.") })
        futureOf(rewritten.entries) shouldBe both
        // And the copy a revert makes of a published revision.
        svc.publish(writer, classId)
        val reopened = svc.revertToEditable(writer, classId)
        reopened.version shouldBe rewritten.version + 1
        futureOf(reopened.entries) shouldBe both
    }

    // The cases below run where a problem in stored configuration is forgiven -- reported, and the rest loaded -- as
    // it is on every node but a test's: what a load keeps cannot be seen where the first problem refuses it.
    val forgiving by lazy {
        // Its own instance (issue #1075): the stored check's mode is instance-wide.
        Startup.mkTestBootCxt("fileRefsWarn", "fileRefsWarn1153", mapOf(GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name))
    }

    fun problemsOf(client: String) = ClientConfigIssues.get(forgiving).issuesFor(client).map { it.message }

    "a load keeps the refs it can serve and says of each other one why it left it out" {
        val w = forgiving
        val client = "refload"
        defineClient(client, w)
        val wsvc = GedraConfigService.get(w)
        val hash = plant(client, png, w)
        val gone = "0".repeat(64)
        // Stored in-process, which holds an entry to nothing: what a restore or a hand edit can leave in a row.
        wsvc.writeConfig(
            writerOf(client, w),
            gedraConfig(w, "arefs", clientNamespace(client), client) {
                fileRef("logo", hash, "The first claim on the id.")
                fileRefFromMap(mapOf(CCT.refId to "not a name", CCT.source to FREF.sourceStore, CCT.hash to hash))
                fileRefFromMap(mapOf(CCT.refId to "later", CCT.source to "elsewhere", CCT.hash to hash))
                fileRefFromMap(mapOf(CCT.refId to "nohash", CCT.source to FREF.sourceStore, CCT.hash to "abc"))
                fileRef("gone", gone)
            },
        )
        wsvc.writeConfig(writerOf(client, w), gedraConfig(w, "bmore", clientNamespace(client), client) { fileRef("logo", hash, "A second."); fileRef("extra", hash) })
        GedraConfigReload.reloadClient(w, client)

        val kept = FileRefService.get(w).forClient(client).refs
        kept.keys shouldContainExactly setOf("logo", "gone", "extra")
        kept.getValue("logo").def.description shouldBe "The first claim on the id."
        val problems = problemsOf(client)
        problems.size shouldBe 5
        problems.single { it.contains("not a name") } shouldContain "whose id is no name"
        problems.single { it.contains("'later'") } shouldContain "the source 'elsewhere', which this node does not read"
        problems.single { it.contains("'nohash'") } shouldContain "the hash 'abc', which is not one"
        problems.single { it.contains("'logo'") } shouldContain "declared more than once"
        problems.single { it.contains("'gone'") } shouldContain "names a file that is not in the file store ($gone)"

        // A ref whose file is missing is kept, listed as missing, and answered as not found.
        val admin = adminOf(client, w)
        val shown = listed(admin)
        shown.getValue("gone")[FREF.missing] shouldBe true
        shown.getValue("gone").containsKey(FREF.file) shouldBe false
        shown.getValue("logo")[FREF.missing] shouldBe false
        static(admin, "gone").rptStatusCode shouldBe EXC.notFound
        static(admin, "extra").rptResponseBytes!!.toList() shouldBe png.toList()
        // And none of it stops an edit of something else in that configuration: what it already had is not judged.
        val patched = admin.postData(CFEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, mapOf(CCT.refId to "another", CCT.source to FREF.sourceStore, CCT.hash to hash), "arefs"))
        patched[CFEP.name] shouldBe "arefs"
        wsvc.publish(writerOf(client, w), GedraId.of(GedraConfigType.configDoc, client, "arefs"))
        admin.postData(UADEP.clientFilesRemove, mapOf(CCT.refId to "gone"))[CCT.refId] shouldBe "gone"
    }

    "a client is served a bounded number of refs, and one over it can still take refs away" {
        val w = forgiving
        val client = "refcap"
        defineClient(client, w)
        val wsvc = GedraConfigService.get(w)
        val wrefs = FileRefService.get(w)
        val hash = plant(client, png, w)
        val over = FREF.maxRefs + 5
        // More than a client is served, however they came to be stored: across two configurations here.
        wsvc.writeConfig(writerOf(client, w), gedraConfig(w, "amany", clientNamespace(client), client) { for (n in 1..150) fileRef("r$n", hash) })
        wsvc.writeConfig(writerOf(client, w), gedraConfig(w, "bmany", clientNamespace(client), client) { for (n in 151..over) fileRef("r$n", hash) })
        for (name in listOf("amany", "bmany")) wsvc.publish(writerOf(client, w), GedraId.of(GedraConfigType.configDoc, client, name))
        GedraConfigReload.reloadClient(w, client)

        // It is served the first of them, as many as a client is, and told once that it holds more.
        wrefs.forClient(client).refs.size shouldBe FREF.maxRefs
        problemsOf(client).single() shouldBe "Client '$client' has more than ${FREF.maxRefs} file refs, which is as many as a client is served."

        // Over its limit, it is not stranded there: taking a ref away introduces no problem it did not have --
        // whichever configuration it is taken from, and so whatever order a trial then reads them in.
        val admin = adminOf(client, w)
        // Copy may name a ref it is served, and not one past the limit, which it is not (issue #1186).
        fun intro(value: String) =
            mapOf(COV.fileId to HFRAG.home, CPY.changes to listOf(mapOf(COV.namespaceField to "home", COV.key to "intro", COV.value to value)))
        admin.expectError(EXC.badInput, CPY.applyPath, data = intro("![Late](ref:r$over)"))[EP.errorMessage].toOptStr().orEmpty() shouldContain
            "shows the image ref:r$over, and the client has no file ref 'r$over'."
        admin.postData(CPY.applyPath, intro("![Early](ref:r10)"))[CPY.mode] shouldBe EDM.live
        admin.postData(UADEP.clientFilesRemove, mapOf(CCT.refId to "r1"))[CCT.refId] shouldBe "r1"
        admin.postData(UADEP.clientFilesRemove, mapOf(CCT.refId to "r$over"))[CCT.refId] shouldBe "r$over"
        wrefs.forClient(client).refs.size shouldBe FREF.maxRefs
        // An upload of a new one is still refused, before it stores the file -- told how many must go to make room,
        // since taking one it is served away only brings one it holds into service (issue #1169).
        val full = refused(admin, "fresh", image(otherPng))
        full shouldContain "Client '$client' holds ${over - 2} file refs, more than the ${FREF.maxRefs} a client is served."
        full shouldContain "Remove ${over - 2 - FREF.maxRefs + 1} first."
        // Brought back within it, it has no problem left.
        for (n in 2..5) admin.postData(UADEP.clientFilesRemove, mapOf(CCT.refId to "r$n"))[CCT.refId] shouldBe "r$n"
        wrefs.forClient(client).refs.size shouldBe FREF.maxRefs - 1
        problemsOf(client).shouldBeEmpty()
        uploaded(admin, "fresh", image(otherPng))
    }

    // A client with a sandbox runs what it published, so its drafts are loaded only by the sandbox -- and a problem a
    // draft already has is recorded there. The next write to the client has to recognise it as one it already had.
    "a draft ref whose file went missing does not refuse the client's later edits" {
        val w = forgiving
        val client = "refdraft"
        defineClient(client, w)
        val admin = adminOf(client, w)
        admin.postData(UADEP.clientSandbox, mapOf(CLD.sandbox to true))
        uploaded(admin, "logo", image(png))[FREF.mode] shouldBe EDM.draft

        FileStoreService.get(w).delete(w, FileRefService.bucket, FileRefService.keyOf(client, png.sha256Hex())) shouldBe true
        FileRefService.get(w).forget(client, png.sha256Hex())
        GedraConfigReload.reloadClient(w, client)
        // Found by the sandbox's load, and worded by the client whose configuration it is.
        problemsOf(client).none { it.contains("not in the file store") } shouldBe true
        problemsOf(sandboxOf(client)).single { it.contains("not in the file store") } shouldContain "File ref 'logo' of client '$client' (config 'copy')"

        // An unrelated draft edit of the same configuration goes through.
        uploaded(admin, "banner", image(otherPng))[FREF.mode] shouldBe EDM.draft
        storedRefs(client, w).map { it[CCT.refId] } shouldContainExactly listOf("logo", "banner")
    }

    "a file ref declared in source code refuses the boot, where a mistake in source is caught" {
        val failure = shouldThrow<KdrException> {
            // Its own instance (issue #1075): the boot itself is under test.
            Startup.mkTestBootCxt("srcRef", "sourceFileRef1153", additionalComponents = listOf(SourceFileRefComponent()))
        }
        failure.message.orEmpty() shouldContain "declared in source code"
        // Nothing of the kind is served on the instance every other case runs on.
        refs.forClient(SourceFileRefComponent.client).refs.isEmpty() shouldBe true
    }
})
