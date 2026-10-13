package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.ContentResources
import com.dynamicruntime.common.content.HashedFileServing
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.fileref.FREF
import com.dynamicruntime.common.fileref.FileRefService
import com.dynamicruntime.common.fileref.LFENV
import com.dynamicruntime.common.filestore.FMETA
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.configSlotFailures
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.http.request.ContextRoot
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.http.request.RequestHandler
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.parseDate
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File
import java.nio.file.Files

/**
 * Source configuration that names local files (issue #1154): a client shipped with two pictures, and a template
 * with one that a client built on it inherits. [missing] adds a ref to a file that is not there.
 */
class LocalFileRefComponent(val missing: Boolean = false) : ComponentDefinition {
    override val providerName: String = "localFileRefFixture"

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        // Named to sort before `copy`, where an upload lands: what is stored overrides what is shipped by being
        // stored, not by its configuration happening to come first.
        gedraConfig(cxt, shippedConfig, clientNamespace(shipped), shipped) {
            defineClient(def(shipped, "Shipped"))
            fileRef("logo", "The logo it ships with.") { local(root, "logo.png") }
            fileRef("mark") { local(root, "marks/mark.svg") }
            fileRef("badge", "The badge it ships with.") { local(root, "marks/mark.svg") }
            fileRef("fickle") { local(root, "fickle.png") }
            fileRef("brittle") { local(root, "brittle.png") }
            if (missing) fileRef("ghost") { local(root, "ghost.png") }
        },
        gedraConfig(cxt, "base", clientNamespace(template), template) {
            defineClient(def(template, "Template", ClientUsageType.template))
            fileRef("crest", "The template's crest.") { local(root, "marks/mark.svg") }
        },
        gedraConfig(cxt, "main", clientNamespace(built), built) {
            defineClient(def(built, "Built on the template", extends = template))
        },
    )

    private fun def(client: String, name: String, usage: ClientUsageType = ClientUsageType.dev, extends: String? = null) = ClientDef(
        clientId = client, name = name, usageType = usage, audience = ClientAudience.internal,
        enabledEnvironments = setOf(ENV.unit, ENV.local), extendsFromClientId = extends,
    )

    companion object {
        const val root = "brand"
        const val shippedConfig = "art"
        const val shipped = "lfrshipped"
        const val template = "lfrtemplate"
        const val built = "lfrbuilt"
    }
}

/**
 * File refs to local files (issue #1154), end to end: a ref in source configuration serves a file under a declared
 * root, by the hash of the file as it is; what is stored overrides what is shipped; and only the server's own code
 * and an administrator who sees every client may name a file.
 *
 * **An instance of its own** (issue #1075): where local files are is instance-wide, and the fixture's source
 * configuration names files that have to be there when it boots.
 */
class LocalFileRefsTest : StringSpec({
    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    val otherPng = png + byteArrayOf(9, 9)
    val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 8 8"><circle cx="4" cy="4" r="3"/></svg>""".toByteArray()

    /** A location of local files with the fixture's root in it, and the pictures its configuration names. */
    fun location(): File {
        val dir = Files.createTempDirectory("kdrLocalFileRefs").toFile()
        val brand = File(dir, LocalFileRefComponent.root).apply { File(this, "marks").mkdirs() }
        File(brand, "logo.png").writeBytes(png)
        File(brand, "fickle.png").writeBytes(png)
        File(brand, "brittle.png").writeBytes(png)
        File(brand, "marks/mark.svg").writeBytes(svg)
        return dir
    }

    val dirs = mutableListOf<File>()
    afterSpec { dirs.forEach { it.deleteRecursively() } }

    val files = location().also { dirs.add(it) }
    val brand = File(files, LocalFileRefComponent.root)
    // Stored problems reported rather than refused, so a case can break a stored ref's file and load the client.
    val cxt = Startup.mkTestBootCxt(
        "localFileRefs", "localFileRefs1154",
        mapOf(LFENV.dir.name to files.absolutePath, GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name),
        listOf(LocalFileRefComponent()),
    )
    val svc = GedraConfigService.get(cxt)
    val refs = FileRefService.get(cxt)
    val shipped = LocalFileRefComponent.shipped

    fun adminOf(client: String) = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client, name = "Chief $client")

    fun memberOf(client: String) = TestUser.create(cxt, "member@$client.test", userClient = client)

    val full by lazy { TestUser.createFullAdmin(cxt, "full@localFileRefs.test") }

    /** A context that writes [client]'s configuration in-process, with no signed-in caller: a script, a simulation. */
    fun writerOf(client: String): KdrCxt = cxt.mkSubContext("write", client).also { it.userId = 11540L }

    /** Defines [client] in stored configuration, published and loaded. */
    fun defineClient(client: String) {
        svc.writeConfig(
            writerOf(client),
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = "Client $client", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
            },
        )
        svc.publish(writerOf(client), GedraId.of(GedraConfigType.configDoc, client, "main"))
        GedraConfigReload.reloadClient(cxt, client)
    }

    /** A POST that has to succeed, and its `results`: `postData` alone answers a refusal with an empty map. */
    fun posted(user: TestUser, path: String, data: Map<String, Any?>): Map<String, Any?> =
        user.client.sendJsonPostRequest(path, data).also { withClue(it) { it[EP.status].shouldBeNull() } }[EP.results].toJsonMapOrEmpty()

    fun static(user: TestUser, name: String): RequestHandler =
        user.client.sendGetRequestRaw("/${ContextRoot.st}" + FREF.staticPath("kdr.en", name))

    fun RequestHandler.header(name: String): String? = rptResponseHeaders[name.lowercase()]?.firstOrNull()

    fun nameOf(refId: String, bytes: ByteArray) = FREF.versionedName(refId, bytes.sha256Hex())

    fun listed(user: TestUser, client: String? = null): Map<Any?, Map<String, Any?>> =
        user.getItems(UADEP.clientFiles, client?.let { mapOf(FREF.client to it) }).associateBy { it[CCT.refId] }

    fun localEntry(refId: String, path: String, root: String = LocalFileRefComponent.root): Map<String, Any?> =
        mapOf(CCT.refId to refId, CCT.source to FREF.sourceLocal, CCT.root to root, CCT.path to path)

    /** One edit of the file-ref slot of [config]: what an administrator types by hand; [client] for one who names it. */
    fun slotEdit(action: GedraEditAction, entry: Map<String, Any?>, config: String = "main", client: String? = null): Map<String, Any?> = buildMap {
        client?.let { put(CFEP.client, it) }
        put(CFEP.name, config)
        put(CFEP.edits, listOf(mapOf(CFEP.slot to CCT.fileRefDef, GED.action to action.name, GE.data to entry)))
    }

    fun storedRefs(client: String, config: String = "main"): List<Map<String, Any?>> =
        svc.readLatest(cxt.mkSubContext("read", client), GedraId.of(GedraConfigType.configDoc, client, config))
            ?.entriesBySlot()?.get(CCT.fileRefDef).orEmpty()

    "a ref in source configuration names a local file, and the client's people are served it by the hash of the file as it is" {
        val admin = adminOf(shipped)
        val member = memberOf(shipped)

        val logo = listed(admin).getValue("logo")
        logo[CCT.source] shouldBe FREF.sourceLocal
        logo[CCT.root] shouldBe LocalFileRefComponent.root
        logo[CCT.path] shouldBe "logo.png"
        // Nobody authored a hash: it is the file's, as read.
        logo[CCT.hash] shouldBe png.sha256Hex()
        logo[FREF.name] shouldBe nameOf("logo", png)
        logo[CCT.description] shouldBe "The logo it ships with."
        logo[FREF.configName] shouldBe LocalFileRefComponent.shippedConfig
        logo[FREF.live] shouldBe true
        logo[FREF.missing] shouldBe false
        // Described as the store describes a file, by what reading it found -- never by what it is called.
        val meta = logo[FREF.file].toJsonMapOrEmpty()
        meta[FMETA.mimeType] shouldBe "image/png"
        meta[FMETA.fileName] shouldBe "logo.png"
        (meta[FMETA.size] as Number).toInt() shouldBe png.size
        // Nobody uploaded it: what stands for when is the moment the file last changed.
        meta[FMETA.uploadedAt].toString().parseDate().toEpochMilliseconds() shouldBe File(brand, "logo.png").lastModified()
        logo[FREF.uploadedByName].shouldBeNull()

        val served = static(member, nameOf("logo", png))
        served.rptStatusCode shouldBe EXC.ok
        served.rptResponseBytes!!.toList() shouldBe png.toList()
        served.rptResponseMimeType shouldBe "image/png"
        served.header(ContentResources.cacheControlHeader) shouldBe ContentResources.cacheControl
        served.header(HashedFileServing.noSniffHeader) shouldBe HashedFileServing.noSniff
        // An SVG goes out under the policy that keeps one from running anything, as an uploaded one does.
        val mark = static(member, nameOf("mark", svg))
        mark.rptResponseMimeType shouldBe HashedFileServing.svgMimeType
        mark.header(HashedFileServing.contentPolicyHeader).shouldNotBeNull()
        // The file itself, for an administrator's preview.
        val download = admin.client.sendGetRequest(UADEP.clientFilesDownload, mapOf(CCT.refId to "mark"))
        download.rptStatusCode shouldBe EXC.ok
        download.rptResponseBytes!!.toList() shouldBe svg.toList()

        // Nothing was copied anywhere: a client that only ships its pictures has nothing in the file store.
        FileStoreService.get(cxt).list(cxt, FileRefService.bucket, "clients/${LocalFileRefComponent.built}/").shouldBeEmpty()
        // And another client's people are not served it.
        defineClient("lfrother")
        static(memberOf("lfrother"), nameOf("logo", png)).rptStatusCode shouldBe EXC.notFound
    }

    "a file edited on disk is a new address, and the old one is answered with the current bytes and never kept" {
        val admin = adminOf(shipped)
        val member = memberOf(shipped)
        val file = File(brand, "fickle.png")
        val before = nameOf("fickle", png)
        static(member, before).header(ContentResources.cacheControlHeader) shouldBe ContentResources.cacheControl

        // Rewritten in place: no reload, no restart -- the bytes are hashed as they are served.
        file.writeBytes(otherPng)
        val stale = static(member, before)
        stale.rptResponseBytes!!.toList() shouldBe otherPng.toList()
        stale.header(ContentResources.cacheControlHeader) shouldBe ContentResources.noStore
        val fresh = static(member, nameOf("fickle", otherPng))
        fresh.rptResponseBytes!!.toList() shouldBe otherPng.toList()
        fresh.header(ContentResources.cacheControlHeader) shouldBe ContentResources.cacheControl
        listed(admin).getValue("fickle")[FREF.name] shouldBe nameOf("fickle", otherPng)

        // Something that is no picture put in its place is never sent, whatever it is called.
        file.writeText("password=hunter2")
        for (name in listOf(before, nameOf("fickle", otherPng), "fickle")) {
            val refused = static(member, name)
            refused.rptStatusCode shouldBe EXC.notFound
            refused.header(ContentResources.cacheControlHeader) shouldBe ContentResources.noStore
            refused.rptResponseData.toString() shouldNotContain "hunter2"
        }
        // Gone altogether: the same answer, and the listing says so without a hash to name it by.
        file.delete()
        static(member, before).rptStatusCode shouldBe EXC.notFound
        val gone = listed(admin).getValue("fickle")
        gone[FREF.missing] shouldBe true
        gone[CCT.hash].shouldBeNull()
        gone[FREF.name].shouldBeNull()
        gone[CCT.path] shouldBe "fickle.png"
        admin.expectError(EXC.notFound, UADEP.clientFilesDownload, args = mapOf(CCT.refId to "fickle"))

        // Put back, it is served again.
        file.writeBytes(png)
        static(member, before).header(ContentResources.cacheControlHeader) shouldBe ContentResources.cacheControl
    }

    "an upload overrides the image a client ships with, and removing it reveals the shipped one" {
        val admin = adminOf(shipped)
        val member = memberOf(shipped)
        val upload = admin.upload(UADEP.clientFilesUpload, mapOf(CCT.refId to "mark", FREF.file to ContentData(otherPng, "image/png", saveAsFilename = "ours.png", inLine = false)))
        withClue(upload) { upload[EP.status].shouldBeNull() }
        upload[EP.results].toJsonMapOrEmpty()[FREF.mode] shouldBe EDM.live

        // The stored ref takes the id, with no problem said of the one it replaces.
        val over = listed(admin).getValue("mark")
        over[CCT.source] shouldBe FREF.sourceStore
        over[FREF.configName] shouldBe CPY.copyConfigName
        over[CCT.root].shouldBeNull()
        static(member, "mark").rptResponseBytes!!.toList() shouldBe otherPng.toList()
        static(member, nameOf("mark", otherPng)).header(ContentResources.cacheControlHeader) shouldBe ContentResources.cacheControl
        // The address of the shipped file is answered with what the client now has, not kept.
        static(member, nameOf("mark", svg)).let {
            it.rptResponseBytes!!.toList() shouldBe otherPng.toList()
            it.header(ContentResources.cacheControlHeader) shouldBe ContentResources.noStore
        }

        posted(admin, UADEP.clientFilesRemove, mapOf(CCT.refId to "mark"))[CCT.refId] shouldBe "mark"
        listed(admin).getValue("mark")[CCT.source] shouldBe FREF.sourceLocal
        static(member, nameOf("mark", svg)).let {
            it.rptResponseBytes!!.toList() shouldBe svg.toList()
            it.header(ContentResources.cacheControlHeader) shouldBe ContentResources.cacheControl
        }
        // An upload that says nothing of the description keeps the one the client was shipped with.
        admin.upload(UADEP.clientFilesUpload, mapOf(CCT.refId to "badge", FREF.file to ContentData(otherPng, "image/png", inLine = false)))
            .let { withClue(it) { it[EP.status].shouldBeNull() } }
        listed(admin).getValue("badge").let {
            it[CCT.source] shouldBe FREF.sourceStore
            it[CCT.description] shouldBe "The badge it ships with."
        }
        posted(admin, UADEP.clientFilesRemove, mapOf(CCT.refId to "badge"))[CCT.refId] shouldBe "badge"

        // What ships is not the API's to take away: there is nothing stored to remove.
        admin.expectError(EXC.badInput, UADEP.clientFilesRemove, mapOf(CCT.refId to "mark"))[EP.errorMessage] as String shouldContain "No stored configuration"
    }

    "a client built on a template serves the template's local ref" {
        val built = LocalFileRefComponent.built
        refs.forClient(built).refs.keys shouldBe setOf("crest")
        val crest = static(memberOf(built), nameOf("crest", svg))
        crest.rptStatusCode shouldBe EXC.ok
        crest.rptResponseBytes!!.toList() shouldBe svg.toList()
        listed(adminOf(built)).getValue("crest")[CCT.source] shouldBe FREF.sourceLocal
    }

    "source configuration with a local ref passes the slot gate in its stored form" {
        val fixture = setOf(shipped, LocalFileRefComponent.template, LocalFileRefComponent.built)
        val configs = SchemaCollector.get(cxt).shouldNotBeNull().gedraConfigs.configs
            .filter { it.gedraId.client in fixture && !it.isStored && it.fileRefs.any { ref -> ref.source == FREF.sourceLocal } }
        configs.map { it.gedraId.client }.toSet() shouldBe fixture
        for (config in configs) {
            val entries = gedraConfigToEntries(config)
            configSlotFailures(cxt, entries).map { "${config.gedraId}: ${it.path}: ${it.message}" }.shouldBeEmpty()
            // No hash is written for a local file: there is none to author.
            entries.getValue(CCT.fileRefDef).forEach { it.containsKey(CCT.hash) shouldBe false }
        }
    }

    "a client's own administrator may not name a local file: a write, a patch and an import are each refused" {
        val client = "lfrwho"
        defineClient(client)
        val admin = adminOf(client)

        val patched = admin.expectError(EXC.notAuthorized, CFEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, localEntry("mine", "logo.png")))
        patched[EP.errorMessage] as String shouldContain "names a local file for the file ref 'mine'"
        admin.expectError(
            EXC.notAuthorized, CFEP.bundleWrite,
            mapOf(CFEP.name to "extra", CFEP.namespaceField to clientNamespace(client), CFEP.slots to mapOf(CCT.fileRefDef to listOf(localEntry("mine", "logo.png")))),
        )[EP.errorMessage] as String shouldContain "names a local file for the file ref 'mine'"
        // An import is not a client administrator's to make at all: refused before it is read.
        admin.expectError(
            EXC.notAuthorized, ACEP.import,
            mapOf(
                ACEP.bundlesField to listOf(
                    mapOf(CFEP.client to client, CFEP.name to "extra", CFEP.namespaceField to clientNamespace(client), CFEP.slots to mapOf(CCT.fileRefDef to listOf(localEntry("mine", "logo.png")))),
                ),
            ),
        )
        storedRefs(client).shouldBeEmpty()
        storedRefs(client, "extra").shouldBeEmpty()
        refs.forClient(client).refs.isEmpty() shouldBe true

        // The same bundle, written and then imported by one who sees every client, is taken each time.
        fun bundle(name: String, refId: String) = mapOf(
            CFEP.client to client, CFEP.name to name, CFEP.namespaceField to clientNamespace(client),
            CFEP.slots to mapOf(CCT.fileRefDef to listOf(localEntry(refId, "logo.png"))),
        )
        posted(full, ACEP.bundleWrite, bundle("extra", "mine"))[CFEP.version] shouldBe 1
        storedRefs(client, "extra") shouldBe listOf(localEntry("mine", "logo.png"))
        val imported = posted(full, ACEP.import, mapOf(ACEP.bundlesField to listOf(bundle("more", "theirs"))))
        withClue(imported) { imported[ACEP.failures].toJsonListOfMaps().shouldBeEmpty() }
        storedRefs(client, "more") shouldBe listOf(localEntry("theirs", "logo.png"))
    }

    "an administrator who sees every client, and the server's own code, may -- and what they wrote is carried by a client administrator's later edits" {
        val client = "lfrfull"
        defineClient(client)
        val admin = adminOf(client)
        val member = memberOf(client)

        // Through the API, by one who sees every client.
        posted(full, ACEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, localEntry("logo", "logo.png") + (CCT.description to "Ours."), client = client))
        // In-process, with no signed-in caller: a script provisioning a client.
        svc.writeConfig(writerOf(client), gedraConfig(cxt, "art", clientNamespace(client), client) { fileRef("mark") { local(LocalFileRefComponent.root, "marks/mark.svg") } }, trial = true)
        svc.publish(writerOf(client), GedraId.of(GedraConfigType.configDoc, client, "main"))
        svc.publish(writerOf(client), GedraId.of(GedraConfigType.configDoc, client, "art"))
        GedraConfigReload.reloadClient(cxt, client)
        static(member, nameOf("logo", png)).rptResponseBytes!!.toList() shouldBe png.toList()
        static(member, nameOf("mark", svg)).rptResponseBytes!!.toList() shouldBe svg.toList()
        storedRefs(client) shouldBe listOf(localEntry("logo", "logo.png") + (CCT.description to "Ours."))

        // The client's own administrator edits the same configuration, and the ref they did not write is carried:
        // a ref of their own beside it, by an upload's hash...
        val hash = otherPng.sha256Hex()
        FileStoreService.get(cxt).put(writerOf(client), FileRefService.bucket, FileRefService.keyOf(client, hash), ContentData(otherPng, "image/png"))
        posted(admin, CFEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, mapOf(CCT.refId to "banner", CCT.source to FREF.sourceStore, CCT.hash to hash)))
        storedRefs(client).map { it[CCT.refId] } shouldBe listOf("logo", "banner")
        // ...and its description is theirs to reword, since that names no file.
        posted(admin, CFEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, localEntry("logo", "logo.png") + (CCT.description to "Our logo.")))
        storedRefs(client).first()[CCT.description] shouldBe "Our logo."

        // What they may not do is point it somewhere else, or at another root.
        for (elsewhere in listOf(localEntry("logo", "marks/mark.svg"), localEntry("logo", "logo.png", root = "shared"))) {
            admin.expectError(EXC.notAuthorized, CFEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, elsewhere))[EP.errorMessage] as String shouldContain "names a local file"
        }
        storedRefs(client).first()[CCT.path] shouldBe "logo.png"

        // They may remove one, and replace one with a file of their own.
        posted(admin, UADEP.clientFilesRemove, mapOf(CCT.refId to "mark"))[FREF.configName] shouldBe "art"
        // (Their typed edits are published first: an upload does not publish changes it did not make.)
        svc.publish(writerOf(client), GedraId.of(GedraConfigType.configDoc, client, "main"))
        GedraConfigReload.reloadClient(cxt, client)
        refs.forClient(client).refs.keys shouldBe setOf("logo", "banner")
        admin.upload(UADEP.clientFilesUpload, mapOf(CCT.refId to "logo", FREF.file to ContentData(otherPng, "image/png", inLine = false)))
            .let { withClue(it) { it[EP.status].shouldBeNull() } }
        storedRefs(client).first() shouldBe mapOf(CCT.refId to "logo", CCT.source to FREF.sourceStore, CCT.hash to hash, CCT.description to "Our logo.")
        // Having replaced it, they cannot put the local file back.
        admin.expectError(EXC.notAuthorized, CFEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, localEntry("logo", "logo.png")))
    }

    "what even an administrator who sees every client writes is held to the rules: a plain path, a root, and a file that is there" {
        val client = "lfrrules"
        defineClient(client)
        fun typed(entry: Map<String, Any?>): String =
            full.expectError(EXC.badInput, ACEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, entry, client = client))[EP.errorMessage] as String

        // By the gate, as a matter of shape: before anything is looked for.
        for (path in listOf("../logo.png", "/etc/passwd", "marks\\mark.svg", ".hidden/logo.png", "marks/../logo.png", "")) {
            typed(localEntry("pic", path)) shouldContain "${CCT.fileRefDef}[pic].${CCT.path}"
        }
        typed(localEntry("pic", "logo.png", root = "../brand")) shouldContain "${CCT.fileRefDef}[pic].${CCT.root}"
        typed(localEntry("pic", "logo.png") - CCT.root) shouldContain "${CCT.fileRefDef}[pic].${CCT.root}"
        typed(localEntry("pic", "logo.png") + (CCT.hash to png.sha256Hex())) shouldContain "${CCT.fileRefDef}[pic].${CCT.hash}"
        typed(mapOf(CCT.refId to "pic", CCT.source to FREF.sourceStore, CCT.hash to png.sha256Hex(), CCT.path to "logo.png")) shouldContain "${CCT.fileRefDef}[pic].${CCT.path}"

        // By the trial, as a load would find it: one thing said, whatever is wrong with the file.
        File(brand, "notes.png").writeText("password=hunter2")
        for (entry in listOf(localEntry("pic", "absent.png"), localEntry("pic", "logo.png", root = "nowhere"), localEntry("pic", "notes.png"), localEntry("pic", "marks"))) {
            val said = typed(entry)
            said shouldContain "which is not there to be served as an image"
            said shouldNotContain "hunter2"
        }
        storedRefs(client).shouldBeEmpty()
    }

    // Whether a file is there changes with no configuration changing, so a write is compared with the client's refs
    // as they are now -- not as its last load found them.
    "a local file that breaks after its client loaded does not refuse the client's later edits, even the one that mends it" {
        val client = "lfrbroke"
        defineClient(client)
        val admin = adminOf(client)
        val file = File(brand, "breakable.png").also { it.writeBytes(png) }
        posted(full, ACEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, localEntry("pic", "breakable.png"), client = client))
        svc.publish(writerOf(client), GedraId.of(GedraConfigType.configDoc, client, "main"))
        GedraConfigReload.reloadClient(cxt, client)
        ClientConfigIssues.get(cxt).issuesFor(client).shouldBeEmpty()

        // Deleted behind the node's back: no write, no reload, nothing recorded.
        file.delete()
        ClientConfigIssues.get(cxt).issuesFor(client).shouldBeEmpty()
        static(memberOf(client), "pic").rptStatusCode shouldBe EXC.notFound

        // An unrelated edit of the same configuration is not refused for it...
        val hash = otherPng.sha256Hex()
        FileStoreService.get(cxt).put(writerOf(client), FileRefService.bucket, FileRefService.keyOf(client, hash), ContentData(otherPng, "image/png"))
        posted(admin, CFEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, mapOf(CCT.refId to "banner", CCT.source to FREF.sourceStore, CCT.hash to hash)))
        // ...the next load says what is wrong, once and by name...
        GedraConfigReload.reloadClient(cxt, client)
        ClientConfigIssues.get(cxt).issuesFor(client).single().message shouldBe
            "File ref 'pic' of client '$client' (config 'main') names the local file 'brand/breakable.png', which is not there to be served as an image."
        // ...and a ref that newly names a missing file is still refused, beside the one already broken.
        full.expectError(EXC.badInput, ACEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, localEntry("other", "absent.png"), client = client))[EP.errorMessage] as String shouldContain
            "File ref 'other' of client '$client'"
        // Its administrator takes the broken ref away.
        svc.publish(writerOf(client), GedraId.of(GedraConfigType.configDoc, client, "main"))
        posted(admin, UADEP.clientFilesRemove, mapOf(CCT.refId to "pic"))[CCT.refId] shouldBe "pic"
        ClientConfigIssues.get(cxt).issuesFor(client).shouldBeEmpty()
    }

    // Source problems are strict here, as they are on a developer's machine. A file that goes missing while the node
    // runs is the one problem no write could have been refused for, so the reload after a save must not refuse
    // for it either: the save is stored by then.
    "a shipped picture that goes missing does not fail the client's later saves: the reload reports it and carries on" {
        val admin = adminOf(shipped)
        val file = File(brand, "brittle.png")
        ClientConfigIssues.get(cxt).issuesFor(shipped).shouldBeEmpty()
        file.delete()

        // A save of something else: stored, published, and the client reloaded with it.
        val saved = admin.upload(UADEP.clientFilesUpload, mapOf(CCT.refId to "extra", FREF.file to ContentData(otherPng, "image/png", inLine = false)))
        withClue(saved) { saved[EP.status].shouldBeNull() }
        refs.forClient(shipped).refs.keys shouldContainAll setOf("extra", "brittle")
        ClientConfigIssues.get(cxt).issuesFor(shipped).single().message shouldBe
            "File ref 'brittle' of client '$shipped' (config '${LocalFileRefComponent.shippedConfig}') names the local file 'brand/brittle.png', which is not there to be served as an image."
        static(memberOf(shipped), "brittle").rptStatusCode shouldBe EXC.notFound
        // And the next one, which finds the problem already said.
        posted(admin, UADEP.clientFilesRemove, mapOf(CCT.refId to "extra"))[CCT.refId] shouldBe "extra"

        // Put back, the next reload has nothing to say.
        file.writeBytes(png)
        GedraConfigReload.reloadClient(cxt, shipped)
        ClientConfigIssues.get(cxt).issuesFor(shipped).shouldBeEmpty()
        static(memberOf(shipped), nameOf("brittle", png)).rptStatusCode shouldBe EXC.ok
    }

    // A client with a sandbox runs what it published; its drafts are run by the sandbox. What a draft already has is
    // no obstacle to the next draft edit -- and is an obstacle to publishing it, which is what would bring it to the
    // client's own people.
    "a draft ref whose file has gone does not refuse the next draft edit, and does refuse the publish" {
        val client = "lfrsand"
        defineClient(client)
        val admin = adminOf(client)
        posted(admin, UADEP.clientSandbox, mapOf(CLD.sandbox to true))
        val file = File(brand, "sandpic.png").also { it.writeBytes(png) }
        posted(full, ACEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, localEntry("pic", "sandpic.png"), client = client))
        GedraConfigReload.reloadClient(cxt, client)
        // The sandbox shows the draft; the client's own people are not served it yet.
        refs.forClient(sandboxOf(client)).refs.keys shouldBe setOf("pic")
        refs.forClient(client).refs.isEmpty() shouldBe true

        // Gone, with no reload: nothing has recorded it, for the client or for its sandbox.
        file.delete()
        ClientConfigIssues.get(cxt).issuesFor(sandboxOf(client)).shouldBeEmpty()
        val hash = otherPng.sha256Hex()
        FileStoreService.get(cxt).put(writerOf(client), FileRefService.bucket, FileRefService.keyOf(client, hash), ContentData(otherPng, "image/png"))
        posted(admin, CFEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, mapOf(CCT.refId to "banner", CCT.source to FREF.sourceStore, CCT.hash to hash)))
        storedRefs(client).map { it[CCT.refId] } shouldBe listOf("pic", "banner")

        // Publishing is the change that would hand the broken ref to the client's people, and is refused for it --
        // whether or not the sandbox has been reloaded to say so.
        val mainId = GedraId.of(GedraConfigType.configDoc, client, "main")
        shouldThrow<KdrException> { svc.publish(writerOf(client), mainId, trial = true) }.message.orEmpty() shouldContain "File ref 'pic' of client '$client'"
        GedraConfigReload.reloadClient(cxt, client)
        ClientConfigIssues.get(cxt).issuesFor(sandboxOf(client)).single().message shouldContain "File ref 'pic' of client '$client'"
        shouldThrow<KdrException> { svc.publish(writerOf(client), mainId, trial = true) }.message.orEmpty() shouldContain "File ref 'pic' of client '$client'"
        // With its file back, it publishes.
        file.writeBytes(png)
        svc.publish(writerOf(client), mainId, trial = true).isPublished shouldBe true
    }

    // The slot gate holds what an endpoint writes; source configuration and an in-process write never pass it. What
    // they declare is judged at the load, and a ref that is no good costs only itself.
    "a ref the gate never saw is judged at the load: a root that is no name, a path that is not plain, a pointer of both kinds" {
        val client = "lfrodd"
        defineClient(client)
        svc.writeConfig(
            writerOf(client),
            gedraConfig(cxt, "odd", clientNamespace(client), client) {
                fileRef("up") { local("../brand", "logo.png") }
                fileRef("out") { local(LocalFileRefComponent.root, "../secret.png") }
                fileRef("hidden") { local(LocalFileRefComponent.root, ".git/logo.png") }
                fileRefFromMap(localEntry("hashed", "logo.png") + (CCT.hash to png.sha256Hex()))
                fileRefFromMap(mapOf(CCT.refId to "both", CCT.source to FREF.sourceStore, CCT.hash to png.sha256Hex(), CCT.path to "logo.png"))
                fileRef("good") { local(LocalFileRefComponent.root, "logo.png") }
            },
        )
        svc.publish(writerOf(client), GedraId.of(GedraConfigType.configDoc, client, "odd"))
        GedraConfigReload.reloadClient(cxt, client)

        refs.forClient(client).refs.keys shouldBe setOf("good")
        val said = ClientConfigIssues.get(cxt).issuesFor(client).associate { it.elementId to it.message }
        said.keys shouldBe setOf("up", "out", "hidden", "hashed", "both")
        said.getValue("up") shouldContain "names no root of local files"
        said.getValue("out") shouldContain "which is not one a local file may have"
        said.getValue("hidden") shouldContain "a name in it begins with a dot"
        said.getValue("hashed") shouldContain "names a local file and carries a hash"
        said.getValue("both") shouldContain "names both bytes in the file store and a local file"
    }

    // In-process, where a caller's identity can be put on a context directly: the two ways the server's own code
    // answers for a local ref that the caller could not have written.
    "content the server's own code authored, and an entry a call puts back as its undo, are written whoever is signed in" {
        val client = "lfrundo"
        defineClient(client)
        val asAdmin = cxt.mkSubContext("asAdmin", client).also {
            it.userProfile = UserProfile(authId = "chief-$client", userId = 11541L, client = client, roles = setOf(ROLE.user, ROLE.admin))
        }
        val artId = GedraId.of(GedraConfigType.configDoc, client, "art")
        fun art(path: String) = gedraConfig(cxt, "art", clientNamespace(client), client) { fileRef("pic") { local(LocalFileRefComponent.root, path) } }
        fun artRefs() = storedRefs(client, "art")

        // A simulation provisioning a client is started by a signed-in caller, and what it writes is its own.
        shouldThrow<KdrException> { svc.writeConfig(asAdmin, art("logo.png")) }.code shouldBe EXC.notAuthorized
        artRefs().shouldBeEmpty()
        svc.writeConfig(asAdmin, art("logo.png"), authoredInCode = true)
        artRefs() shouldBe listOf(localEntry("pic", "logo.png"))

        // The client's administrator takes the ref out. Putting it back is naming a file, and refused...
        val before = artRefs().single()
        fun put(entry: Map<String, Any?>, restoring: List<Map<String, Any?>> = emptyList()) =
            svc.patchConfig(asAdmin, artId, restoring = restoring) { it + (CCT.fileRefDef to listOf(entry)) }
        svc.patchConfig(asAdmin, artId) { it - CCT.fileRefDef }
        artRefs().shouldBeEmpty()
        shouldThrow<KdrException> { put(before) }.code shouldBe EXC.notAuthorized
        // ...unless it is the call's own undo, putting back exactly what was there. Nothing else rides on it.
        shouldThrow<KdrException> { put(localEntry("pic", "marks/mark.svg"), restoring = listOf(before)) }.code shouldBe EXC.notAuthorized
        shouldThrow<KdrException> { put(localEntry("other", "logo.png"), restoring = listOf(before)) }.code shouldBe EXC.notAuthorized
        put(before, restoring = listOf(before))
        artRefs() shouldBe listOf(before)
    }

    "a local ref whose file is not there refuses the boot where a mistake in source is caught, and so does a root declared wrongly" {
        val dir = location().also { dirs.add(it) }
        // Each its own instance (issue #1075): the boot itself is under test.
        shouldThrow<KdrException> {
            Startup.mkTestBootCxt("localGhost", "localFileRefsGhost1154", mapOf(LFENV.dir.name to dir.absolutePath), listOf(LocalFileRefComponent(missing = true)))
        }.message.orEmpty() shouldContain "File ref 'ghost' of client '${LocalFileRefComponent.shipped}' (config '${LocalFileRefComponent.shippedConfig}') names the local file 'brand/ghost.png', which is not there to be served as an image."
        // With nowhere to look, every one of them is missing.
        shouldThrow<KdrException> {
            Startup.mkTestBootCxt("localNowhere", "localFileRefsNowhere1154", additionalComponents = listOf(LocalFileRefComponent()))
        }.message.orEmpty() shouldContain "which is not there to be served as an image"
        // A declaration that is wrong is a fence down, and refuses whatever the configuration holds.
        shouldThrow<KdrException> {
            Startup.mkTestBootCxt("localMalformed", "localFileRefsMalformed1154", mapOf(LFENV.dir.name to dir.absolutePath, LFENV.roots.name to "brand"))
        }.message.orEmpty() shouldContain "Refusing to start: ${LFENV.roots.name} holds 'brand', which is not `name=location`."
    }
})
