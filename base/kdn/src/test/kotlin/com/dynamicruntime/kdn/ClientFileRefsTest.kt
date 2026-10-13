package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.ContentResources
import com.dynamicruntime.common.content.HashedFileServing
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
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GC
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
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
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

    /** Defines [client], published and loaded. */
    fun defineClient(client: String) {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 11530L }
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

    fun adminOf(client: String) = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client, name = "Chief $client")

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

    fun refused(user: TestUser, refId: String, content: ContentData, status: Int = EXC.badInput, client: String? = null): String {
        val env = upload(user, refId, content, client)
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
    fun storedRefs(client: String): List<Map<String, Any?>> =
        svc.readLatest(cxt.mkSubContext("read", client), GedraId.of(GedraConfigType.configDoc, client, CPY.copyConfigName))
            ?.entriesBySlot()?.get(CCT.fileRefDef).orEmpty()

    /** One edit of the file-ref slot through the configuration's own patch endpoint: what an administrator types by hand. */
    fun slotEdit(action: GedraEditAction, entry: Map<String, Any?>): Map<String, Any?> = mapOf(
        CFEP.name to CPY.copyConfigName,
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
        // Naming our bytes by their hash in their own configuration finds no such file: it is looked for under them.
        val typed = theirAdmin.expectError(
            EXC.badInput, CFEP.bundlePatch,
            slotEdit(GedraEditAction.addOrReplace, mapOf(CCT.refId to "stolen", CCT.source to FREF.sourceStore, CCT.hash to png.sha256Hex())),
        )
        typed[EP.errorMessage] as String shouldContain "not in the file store"

        // One who sees every client names any, and a client nobody declared is a 404 from each call.
        val full = TestUser.createFullAdmin(cxt, "full@fileRefs.test")
        listed(full, ours).getValue("logo")[FREF.name] shouldBe ourName
        val download = full.client.sendGetRequest(UADEP.clientFilesDownload, mapOf(FREF.client to ours, CCT.refId to "logo", FREF.expectedHash to png.sha256Hex()))
        download.rptResponseBytes!!.toList() shouldBe png.toList()
        download.header(ContentResources.cacheControlHeader) shouldBe ContentResources.privateCacheControl
        download.header(ContentResources.varyHeader) shouldBe ContentResources.varyByCookie
        download.header(ContentData.contentDispositionKey).orEmpty() shouldContain "attachment"
        val nobody = "nosuchclient1153"
        refused(full, "logo", image(png), EXC.notFound, client = nobody) shouldContain "no client"
        full.expectError(EXC.notFound, UADEP.clientFiles, args = mapOf(FREF.client to nobody))
        full.expectError(EXC.notFound, UADEP.clientFilesDownload, args = mapOf(FREF.client to ours, CCT.refId to "banner"))
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
        uploaded(admin, "hero", image(svg, "image/svg+xml"), description = "")
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

        val removed = admin.postData(UADEP.clientFilesRemove, mapOf(CCT.refId to "hero"))
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

        // A draft: the sandbox, which runs the latest revision, shows it; the client runs what it published.
        uploaded(admin, "logo", image(png))[FREF.mode] shouldBe EDM.draft
        val name = nameOf("logo", png)
        static(inSandbox, name).rptResponseBytes!!.toList() shouldBe png.toList()
        static(admin, name).rptStatusCode shouldBe EXC.notFound
        // The administrator is listed the draft, told it is not live, and may look at it from outside the sandbox.
        listed(admin).getValue("logo")[FREF.live] shouldBe false
        listed(inSandbox).getValue("logo")[FREF.live] shouldBe false
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

        // A client holds a bounded number: one more than that is refused whichever configuration would hold it.
        val writer = cxt.mkSubContext("cap", client).also { it.userId = 11532L }
        val tooMany = shouldThrow<KdrException> {
            svc.writeConfig(writer, gedraConfig(cxt, "many", clientNamespace(client), client) { for (n in 1 until FREF.maxRefs) fileRef("r$n", hash) })
        }
        tooMany.code shouldBe EXC.badInput
        tooMany.message.orEmpty() shouldContain "may hold ${FREF.maxRefs}"
        // And the same id in two of a client's configurations is one the load would drop, so the write is refused.
        shouldThrow<KdrException> {
            svc.writeConfig(writer, gedraConfig(cxt, "twice", clientNamespace(client), client) { fileRef("logo", hash) }, trial = true)
        }.message.orEmpty() shouldContain "more than once"
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

        // An edit of another slot, as a copy or menu editor makes one.
        val patched = svc.patchConfig(writer, classId) { current ->
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
