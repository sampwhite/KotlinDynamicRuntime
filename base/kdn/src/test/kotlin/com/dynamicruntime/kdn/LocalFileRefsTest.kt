package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.ContentResources
import com.dynamicruntime.common.content.HashedFileServing
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.fileref.FREF
import com.dynamicruntime.common.fileref.FileRefService
import com.dynamicruntime.common.fileref.LFENV
import com.dynamicruntime.common.fileref.LocalFileSource
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
import com.dynamicruntime.common.gedra.GedraConfigOrigin
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
import com.dynamicruntime.common.user.ADEP
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
            // A client's own ref (issue #1164): each client built on the template reads its own directory.
            fileRef("own") { clientLocal(openRoot, "own.png") }
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

        /** The root the test instances open to clients. */
        const val openRoot = "approved"
        const val shippedConfig = "art"
        const val shipped = "lfrshipped"
        const val template = "lfrtemplate"
        const val built = "lfrbuilt"
    }
}

/**
 * File refs to local files (issue #1154), end to end: a ref in source configuration serves a file under a declared
 * root, by the hash of the file as it is; what is stored overrides what is shipped; only the server's own code and an
 * administrator who sees every client may name a file in a root; and a client's own administrator may name one in
 * the client's own directory of a root the deployment opened to clients (issue #1164).
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
        // What the template's own ref names, for the template and for the client built on it: a file each.
        for ((client, bytes) in listOf(LocalFileRefComponent.template to svg, LocalFileRefComponent.built to png)) {
            File(dir, "${LocalFileRefComponent.openRoot}/$client").mkdirs()
            File(dir, "${LocalFileRefComponent.openRoot}/$client/own.png").writeBytes(bytes)
        }
        return dir
    }

    val dirs = mutableListOf<File>()
    afterSpec { dirs.forEach { it.deleteRecursively() } }

    val openRoot = LocalFileRefComponent.openRoot
    val files = location().also { dirs.add(it) }
    val brand = File(files, LocalFileRefComponent.root)

    /** Puts [bytes] at [path] in [client]'s own directory of the root open to clients, as the deployment would. */
    fun approve(client: String, path: String, bytes: ByteArray): File =
        File(files, "$openRoot/$client/$path").also {
            it.parentFile.mkdirs()
            it.writeBytes(bytes)
        }

    // Stored problems reported rather than refused, so a case can break a stored ref's file and load the client.
    val cxt = Startup.mkTestBootCxt(
        "localFileRefs", "localFileRefs1154",
        mapOf(
            LFENV.dir.name to files.absolutePath, GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name,
            // One root opened to clients (issue #1164), made when a case needs it: it is read as it is, each time.
            LFENV.clientRoots.name to openRoot,
        ),
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

    /** A client's own ref: the file at [path] in its client's directory of a root open to clients. */
    fun ownEntry(refId: String, path: String, root: String = openRoot): Map<String, Any?> =
        mapOf(CCT.refId to refId, CCT.source to FREF.sourceClientLocal, CCT.root to root, CCT.path to path)

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
        // Shipped with the client, in source code: no stored configuration holds it, so it is not the API's to remove.
        logo[FREF.stored] shouldBe false
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
        // What it is, without it (issue #1131): for a local file, what the disk says of it now.
        val markMeta = admin.getItem(UADEP.clientFilesDownloadMeta, mapOf(CCT.refId to "mark"))
        markMeta[FMETA.hash] shouldBe svg.sha256Hex()
        markMeta[FMETA.size] shouldBe svg.size.toLong()

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
        admin.expectError(EXC.notFound, UADEP.clientFilesDownloadMeta, args = mapOf(CCT.refId to "fickle"))

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
        over[FREF.stored] shouldBe true
        over[FREF.configName] shouldBe CPY.copyConfigName
        over[CCT.root].shouldBeNull()
        static(member, "mark").rptResponseBytes!!.toList() shouldBe otherPng.toList()
        static(member, nameOf("mark", otherPng)).header(ContentResources.cacheControlHeader) shouldBe ContentResources.cacheControl
        // The address of the shipped file is answered with what the client now has, not kept.
        static(member, nameOf("mark", svg)).let {
            it.rptResponseBytes!!.toList() shouldBe otherPng.toList()
            it.header(ContentResources.cacheControlHeader) shouldBe ContentResources.noStore
        }

        // The removal answers the ref that is still there under the id: the shipped one, which no stored
        // configuration holds -- how a page knows what it has just revealed, and that it cannot be removed.
        val revealed = posted(admin, UADEP.clientFilesRemove, mapOf(CCT.refId to "mark"))
        revealed[CCT.refId] shouldBe "mark"
        revealed[FREF.ref].toJsonMapOrEmpty().let {
            it[CCT.source] shouldBe FREF.sourceLocal
            it[FREF.stored] shouldBe false
        }
        listed(admin).getValue("mark").let {
            it[CCT.source] shouldBe FREF.sourceLocal
            it[FREF.stored] shouldBe false
        }
        // What it says is its source configuration's, and not the API's to reword.
        admin.expectError(EXC.badInput, UADEP.clientFilesDescribe, mapOf(CCT.refId to "logo", CCT.description to "Reworded."))[EP.errorMessage] as String shouldContain
            "keeps its description until a file is uploaded over it"
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
        refs.forClient(built).refs.keys shouldBe setOf("crest", "own")
        // The template's ref to a client's own file is, for the client built on it, that client's file and not
        // the template's -- one declaration, a picture each.
        static(memberOf(built), "own").rptResponseBytes!!.toList() shouldBe png.toList()
        listed(adminOf(built)).getValue("own").let {
            it[CCT.source] shouldBe FREF.sourceClientLocal
            it[FREF.name] shouldBe nameOf("own", png)
            // Inherited from the template's source configuration: not stored, whatever its source says of where the file is.
            it[FREF.stored] shouldBe false
        }
        listed(adminOf(built)).getValue("crest")[FREF.stored] shouldBe false
        val crest = static(memberOf(built), nameOf("crest", svg))
        crest.rptStatusCode shouldBe EXC.ok
        crest.rptResponseBytes!!.toList() shouldBe svg.toList()
        listed(adminOf(built)).getValue("crest")[CCT.source] shouldBe FREF.sourceLocal
    }

    "source configuration with a local ref passes the slot gate in its stored form" {
        val fixture = setOf(shipped, LocalFileRefComponent.template, LocalFileRefComponent.built)
        val configs = SchemaCollector.get(cxt).shouldNotBeNull().gedraConfigs.configs
            .filter { it.gedraId.client in fixture && !it.isStored && it.fileRefs.any { ref -> ref.source in FREF.localSources } }
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

    // --- Issue #1164: a root the deployment opens to clients -----------------------------------------------------------

    /** Names a local file as [user]'s ref; the whole envelope, so a refusal can be read from it as a success can. */
    fun nameLocal(user: TestUser, refId: String, path: String, root: String = openRoot, client: String? = null, description: String? = null): Map<String, Any?> =
        user.client.sendJsonPostRequest(
            UADEP.clientFilesLocal,
            buildMap {
                put(CCT.refId, refId)
                put(CCT.root, root)
                put(CCT.path, path)
                client?.let { put(FREF.client, it) }
                description?.let { put(CCT.description, it) }
            },
        )

    fun named(user: TestUser, refId: String, path: String, root: String = openRoot, client: String? = null, description: String? = null): Map<String, Any?> =
        nameLocal(user, refId, path, root, client, description).also { withClue(it) { it[EP.status].shouldBeNull() } }[EP.results].toJsonMapOrEmpty()

    fun notNamed(user: TestUser, status: Int, refId: String, path: String, root: String = openRoot): String {
        val env = nameLocal(user, refId, path, root)
        withClue(env) { (env[EP.status] as? Number)?.toInt() shouldBe status }
        return env[EP.errorMessage] as String
    }

    "a client's own administrator names a file in a root open to clients, and it is the one in the client's own directory" {
        val (client, peer) = "lfropen" to "lfrpeer"
        defineClient(client)
        defineClient(peer)
        val admin = adminOf(client)
        val peerAdmin = adminOf(peer)
        approve(client, "hero.png", png)
        approve(peer, "hero.png", otherPng)
        approve(peer, "only-theirs.png", png)
        fun copyConfig() = svc.readLatest(cxt.mkSubContext("read", client), GedraId.of(GedraConfigType.configDoc, client, CPY.copyConfigName))

        // What they may name from is said, by name and nothing more -- to the administrator of a client, of their
        // own client, as every call beside it is.
        admin.getItems(UADEP.clientFilesRoots) shouldBe listOf(mapOf(CCT.root to openRoot))
        admin.getItems(UADEP.clientFilesRoots, mapOf(FREF.client to client)) shouldBe listOf(mapOf(CCT.root to openRoot))
        // And among what a ref takes, which is what a page asks before it offers to add one (issue #1176).
        admin.getItem(UADEP.clientFilesTakes)[FREF.roots] shouldBe listOf(openRoot)
        admin.expectError(EXC.notAuthorized, UADEP.clientFilesTakes, args = mapOf(FREF.client to peer))
        admin.expectError(EXC.notAuthorized, UADEP.clientFilesRoots, args = mapOf(FREF.client to peer))
        memberOf(client).expectError(EXC.notAuthorized, UADEP.clientFilesRoots)
        TestUser.create(cxt, "solo@lfrpublic.test", level = ROLE.admin, userClient = CL.public).expectError(EXC.notAuthorized, UADEP.clientFilesRoots)
        full.getItems(UADEP.clientFilesRoots, mapOf(FREF.client to client)) shouldBe listOf(mapOf(CCT.root to openRoot))
        full.expectError(EXC.notFound, UADEP.clientFilesRoots, args = mapOf(FREF.client to "nosuchclient1164"))

        // Nothing of the other client's can be named: not by a path that climbs, and not by one that is only theirs.
        notNamed(admin, EXC.badInput, "theirs", "../$peer/only-theirs.png") shouldContain "is not a path a local file may have"
        notNamed(admin, EXC.badInput, "theirs", "$peer/only-theirs.png") shouldBe
            "File ref 'theirs' of client '$client' names the local file '$openRoot/$peer/only-theirs.png' in the client's own directory of that root, which is not there to be served as an image."
        notNamed(admin, EXC.badInput, "theirs", "only-theirs.png") shouldContain "which is not there to be served as an image"
        // Nor by naming the other client: the call acts on the caller's own client, or for one who sees every client a named one.
        nameLocal(admin, "hero", "hero.png", client = peer).let { withClue(it) { (it[EP.status] as? Number)?.toInt() shouldBe EXC.notAuthorized } }
        nameLocal(full, "hero", "hero.png", client = "nosuchclient1164").let { withClue(it) { (it[EP.status] as? Number)?.toInt() shouldBe EXC.notFound } }
        // A file of their own that is no picture is refused in the same words.
        approve(client, "notes.png", "password=hunter2".toByteArray())
        notNamed(admin, EXC.badInput, "notes", "notes.png").let {
            it shouldContain "which is not there to be served as an image"
            it shouldNotContain "hunter2"
        }
        // Each of those was refused before anything was written: a mistyped path leaves no configuration behind.
        copyConfig().shouldBeNull()

        val change = named(admin, "hero", "hero.png", description = "The hero.")
        change[FREF.client] shouldBe client
        change[FREF.configName] shouldBe CPY.copyConfigName
        change[FREF.mode] shouldBe EDM.live
        // The entry is a client's own ref, and names the root and the path as written: the client's directory is
        // the kind's to add, not the entry's.
        storedRefs(client, CPY.copyConfigName) shouldBe listOf(ownEntry("hero", "hero.png") + (CCT.description to "The hero."))
        val shown = listed(admin).getValue("hero")
        shown[CCT.source] shouldBe FREF.sourceClientLocal
        shown[FREF.stored] shouldBe true
        shown[CCT.root] shouldBe openRoot
        shown[CCT.path] shouldBe "hero.png"
        shown[FREF.name] shouldBe nameOf("hero", png)
        shown[FREF.live] shouldBe true
        static(memberOf(client), nameOf("hero", png)).let {
            it.rptStatusCode shouldBe EXC.ok
            it.rptResponseBytes!!.toList() shouldBe png.toList()
            it.header(ContentResources.cacheControlHeader) shouldBe ContentResources.cacheControl
        }

        // The same root and path, named by another client's administrator, is that client's own file.
        named(peerAdmin, "hero", "hero.png")
        static(memberOf(peer), "hero").rptResponseBytes!!.toList() shouldBe otherPng.toList()
        static(memberOf(client), "hero").rptResponseBytes!!.toList() shouldBe png.toList()
        refs.forClient(client).refs.keys shouldBe setOf("hero")

        // The description is kept when none is said, as an upload keeps it, and cleared by a blank one; and the
        // ref is theirs to take away.
        approve(client, "hero2.png", otherPng)
        named(admin, "hero", "hero2.png")
        storedRefs(client, CPY.copyConfigName).single() shouldBe ownEntry("hero", "hero2.png") + (CCT.description to "The hero.")
        static(memberOf(client), "hero").rptResponseBytes!!.toList() shouldBe otherPng.toList()
        named(admin, "hero", "hero2.png", description = "")
        storedRefs(client, CPY.copyConfigName).single() shouldBe ownEntry("hero", "hero2.png")
        posted(admin, UADEP.clientFilesRemove, mapOf(CCT.refId to "hero"))[CCT.refId] shouldBe "hero"
        refs.forClient(client).refs.isEmpty() shouldBe true
    }

    "a root that is not open to clients is refused in the same words whether or not there is such a root, and leaves nothing behind" {
        val client = "lfrshut"
        defineClient(client)
        val admin = adminOf(client)
        // `brand` is a root, with this very file in it -- and in a directory named for the client, even; `nowhere`
        // is no root at all.
        File(brand, client).mkdirs()
        File(brand, "$client/logo.png").writeBytes(png)
        val there = notNamed(admin, EXC.notAuthorized, "logo", "logo.png", root = LocalFileRefComponent.root)
        val notThere = notNamed(admin, EXC.notAuthorized, "logo", "logo.png", root = "nowhere")
        there shouldBe "The root '${LocalFileRefComponent.root}' is not one a client's own administrator may name a file in. The roots open to clients are listed; any other file is uploaded."
        notThere shouldBe there.replace(LocalFileRefComponent.root, "nowhere")
        // Refused before anything was made: no configuration of the editors' was created for it.
        svc.readLatest(cxt.mkSubContext("read", client), GedraId.of(GedraConfigType.configDoc, client, CPY.copyConfigName)).shouldBeNull()
        // A root's name that is no name, and a path that is no path, are no good from anybody.
        notNamed(admin, EXC.badInput, "logo", "logo.png", root = "../$openRoot") shouldContain "cannot name a root of local files"
        notNamed(admin, EXC.badInput, "logo", ".hidden/logo.png") shouldContain "is not a path a local file may have"
        notNamed(full, EXC.badInput, "logo", "logo.png", root = "my root") shouldContain "cannot name a root of local files"
        // One who sees every client is told what this call is for: it writes a client's own ref, and those are
        // read only in an open root. Any other file they name by an entry of the ordinary kind.
        notNamed(full, EXC.badInput, "logo", "logo.png", root = LocalFileRefComponent.root) shouldContain "is not open to clients (${LFENV.clientRoots.name})"

        // Typed into the configuration by hand, the rule is the same one, ref by ref. A client's own ref into an
        // open root is taken...
        approve(client, "typed.png", png)
        posted(admin, CFEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, ownEntry("typed", "typed.png")))
        fun typedRefused(entry: Map<String, Any?>) =
            admin.expectError(EXC.notAuthorized, CFEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, entry))[EP.errorMessage] as String
        // ...and beside it, in the same configuration: one into a root that is not open is not, though the
        // client's directory there holds the file; and an ordinary local ref is not, into any root -- the open one
        // with them. That kind names a root whole, and is an operator's.
        typedRefused(ownEntry("other", "logo.png", root = LocalFileRefComponent.root)) shouldContain "names a local file for the file ref 'other'"
        typedRefused(localEntry("other", "logo.png")) shouldContain "names a local file for the file ref 'other'"
        typedRefused(localEntry("other", "$client/typed.png", root = openRoot)) shouldContain "names a local file for the file ref 'other'"
        // Nor may the one they have be turned into the other kind, which would name the same words from the root's top.
        typedRefused(localEntry("typed", "typed.png", root = openRoot))
        storedRefs(client) shouldBe listOf(ownEntry("typed", "typed.png"))
    }

    // What a ref reaches is said by its kind, and not by the root or by who wrote it. An operator's ordinary local
    // ref names a root whole, open or not; a client's own names the client's directory, whoever wrote it.
    "a client's own ref is its client's file whoever wrote it, an operator's ordinary ref still names the root whole, and a sandbox reads its parent's directory" {
        val client = "lfrsbx"
        defineClient(client)
        val admin = adminOf(client)
        approve(client, "pic.png", png)
        File(files, "$openRoot/top.png").writeBytes(otherPng)

        // A client's own ref written in-process, and by one who sees every client, is looked for in the client's
        // directory as any is: the root's top is not there for it.
        svc.writeConfig(writerOf(client), gedraConfig(cxt, "art", clientNamespace(client), client) { fileRef("pic") { clientLocal(openRoot, "pic.png") } }, trial = true)
        full.expectError(EXC.badInput, ACEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, ownEntry("top", "top.png"), client = client))[EP.errorMessage] as String shouldContain
            "which is not there to be served as an image"
        // Their ordinary local ref names the root whole, as it did before the root was opened.
        posted(full, ACEP.bundlePatch, slotEdit(GedraEditAction.addOrReplace, localEntry("top", "top.png", root = openRoot), config = "art", client = client))
        svc.publish(writerOf(client), GedraId.of(GedraConfigType.configDoc, client, "art"))
        GedraConfigReload.reloadClient(cxt, client)
        static(memberOf(client), "pic").rptResponseBytes!!.toList() shouldBe png.toList()
        static(memberOf(client), "top").rptResponseBytes!!.toList() shouldBe otherPng.toList()
        // Stored is about which configuration holds the entry, not where the file is: an operator's local ref,
        // typed into stored configuration, is stored -- and so is the client's to reword and to remove.
        listed(admin).getValue("top").let {
            it[CCT.source] shouldBe FREF.sourceLocal
            it[FREF.stored] shouldBe true
        }
        posted(admin, UADEP.clientFilesDescribe, mapOf(CCT.refId to "top", CCT.description to "The top of the root."))[FREF.ref]
            .toJsonMapOrEmpty()[CCT.description] shouldBe "The top of the root."
        storedRefs(client, "art").single { it[CCT.refId] == "top" } shouldBe localEntry("top", "top.png", root = openRoot) + (CCT.description to "The top of the root.")
        // One who sees every client names a client's own file by the call, for the client they name.
        approve(client, "named.png", png)
        named(full, "named", "named.png", client = client)[FREF.client] shouldBe client
        storedRefs(client, CPY.copyConfigName) shouldBe listOf(ownEntry("named", "named.png"))

        // With a sandbox, what its administrator names is a draft, and the sandbox is served the parent's file: the
        // directory is the client's whose configuration it is, and a sandbox has none of its own.
        posted(admin, UADEP.clientSandbox, mapOf(CLD.sandbox to true))
        approve(client, "draft.png", otherPng)
        // Naming one is held to what the page told its user, as every change is: one it called live that is a draft
        // goes ahead, and says it is a draft (issue #1191).
        posted(
            admin, UADEP.clientFilesLocal,
            mapOf(CCT.refId to "draft", CCT.root to openRoot, CCT.path to "draft.png", FREF.mode to EDM.live),
        )[FREF.mode] shouldBe EDM.draft
        named(admin, "draft", "draft.png")[FREF.mode] shouldBe EDM.draft
        refs.forClient(sandboxOf(client)).refs.keys shouldBe setOf("pic", "top", "named", "draft")
        refs.forClient(client).refs.keys shouldBe setOf("pic", "top", "named")
        listed(admin).getValue("draft").let {
            it[FREF.live] shouldBe false
            it[FREF.name] shouldBe nameOf("draft", otherPng)
        }
        admin.client.sendGetRequest(UADEP.clientFilesDownload, mapOf(CCT.refId to "draft")).rptResponseBytes!!.toList() shouldBe otherPng.toList()
        // Somebody in the sandbox is served both, from the parent's directory -- and names a file there the same way.
        val inSandbox = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = sandboxOf(client))
        static(inSandbox, nameOf("draft", otherPng)).rptResponseBytes!!.toList() shouldBe otherPng.toList()
        static(inSandbox, "pic").rptResponseBytes!!.toList() shouldBe png.toList()
        named(inSandbox, "pic2", "pic.png")[FREF.mode] shouldBe EDM.draft
        static(inSandbox, "pic2").rptResponseBytes!!.toList() shouldBe png.toList()
        static(memberOf(client), "pic2").rptStatusCode shouldBe EXC.notFound
    }

    // A client's own ref is read only where its root is open. Where it is not -- the deployment closed the root, or
    // this node was started without knowing of it -- the ref names nothing: it does not come to name the same path
    // from the root's top, which could be another client's directory.
    "on a node where the root is not open, a client's own ref is left unserved and never read from the root's top" {
        val dir = location().also { dirs.add(it) }
        val root = LocalFileRefComponent.openRoot
        File(dir, "$root/lfrclosed/globex").mkdirs()
        File(dir, "$root/globex").mkdirs()
        File(dir, "$root/lfrclosed/globex/hero.png").writeBytes(png)
        File(dir, "$root/globex/hero.png").writeBytes(otherPng)
        // An instance of its own (issue #1075): what is open is instance-wide. Stored and source problems reported
        // and not refused, as in production, so the node starts over refs it cannot serve.
        val shut = Startup.mkTestBootCxt(
            "localClosed", "localFileRefsClosed1164",
            mapOf(
                LFENV.dir.name to dir.absolutePath, GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name,
                GCFG.checkEnvVar.name to BootCheckMode.warn.name,
            ),
            listOf(LocalFileRefComponent()),
        )
        val client = "lfrclosed"
        val shutSvc = GedraConfigService.get(shut)
        val writer = shut.mkSubContext("write", client).also { it.userId = 11640L }
        // The entry as it was written while the root was open, here by a write that is not trialled.
        shutSvc.writeConfig(
            writer,
            gedraConfig(shut, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = "Closed", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    ),
                )
                fileRef("hero") { clientLocal(root, "globex/hero.png") }
            },
        )
        shutSvc.publish(writer, GedraId.of(GedraConfigType.configDoc, client, "main"))
        GedraConfigReload.reloadClient(shut, client)

        ClientConfigIssues.get(shut).issuesFor(client).single().message shouldBe
            "File ref 'hero' of client '$client' (config 'main') names the local file '$root/globex/hero.png' in the client's own directory of that root, which is not there to be served as an image."
        val member = TestUser.create(shut, "member@$client.test", userClient = client)
        member.client.sendGetRequestRaw("/${ContextRoot.st}" + FREF.staticPath("kdr.en", "hero")).rptStatusCode shouldBe EXC.notFound
        // And nobody may name one there: nothing is open.
        val admin = TestUser.create(shut, "chief@$client.test", level = ROLE.admin, userClient = client)
        admin.getItems(UADEP.clientFilesRoots).shouldBeEmpty()
        admin.expectError(EXC.notAuthorized, UADEP.clientFilesLocal, mapOf(CCT.refId to "x", CCT.root to root, CCT.path to "globex/hero.png"))
        // The template's own ref is no better served there, for the same reason.
        FileRefService.get(shut).let { it.read(shut, it.forClient(LocalFileRefComponent.built).refs.getValue("own")).shouldBeNull() }
    }

    "a local ref whose file is not there refuses the boot where a mistake in source is caught, and so does a root declared wrongly" {
        val dir = location().also { dirs.add(it) }
        // Each its own instance (issue #1075): the boot itself is under test.
        shouldThrow<KdrException> {
            Startup.mkTestBootCxt(
                "localGhost", "localFileRefsGhost1154", mapOf(LFENV.dir.name to dir.absolutePath, LFENV.clientRoots.name to openRoot),
                listOf(LocalFileRefComponent(missing = true)),
            )
        }.message.orEmpty() shouldContain "File ref 'ghost' of client '${LocalFileRefComponent.shipped}' (config '${LocalFileRefComponent.shippedConfig}') names the local file 'brand/ghost.png', which is not there to be served as an image."
        // With nowhere to look, every one of them is missing.
        shouldThrow<KdrException> {
            Startup.mkTestBootCxt("localNowhere", "localFileRefsNowhere1154", additionalComponents = listOf(LocalFileRefComponent()))
        }.message.orEmpty() shouldContain "which is not there to be served as an image"
        // So is opening a root whose name is none.
        shouldThrow<KdrException> {
            Startup.mkTestBootCxt("localOpenMalformed", "localFileRefsOpenMalformed1164", mapOf(LFENV.dir.name to dir.absolutePath, LFENV.clientRoots.name to "approved/acme"))
        }.message.orEmpty() shouldContain "Refusing to start: ${LFENV.clientRoots.name} opens a root whose name is no name"
        // A declaration that is wrong is a fence down, and refuses whatever the configuration holds.
        shouldThrow<KdrException> {
            Startup.mkTestBootCxt("localMalformed", "localFileRefsMalformed1154", mapOf(LFENV.dir.name to dir.absolutePath, LFENV.roots.name to "brand"))
        }.message.orEmpty() shouldContain "Refusing to start: ${LFENV.roots.name} holds 'brand', which is not `name=location`."
    }

    // --- A template's file as a client's default (issue #1180), and a stored override that is left out (#1170) ---

    val template = LocalFileRefComponent.template

    /** Creates [client] on the template through the API, as an operator does, and answers the envelope. */
    fun createOnTemplate(client: String): Map<String, Any?> = full.client.sendJsonPostRequest(
        ADEP.clientCreate,
        mapOf(
            CLD.clientId to client, CLD.name to "Client $client", CLD.usageType to ClientUsageType.dev.name,
            CLD.audience to ClientAudience.internal.name, CLD.enabledEnvironments to listOf(ENV.unit, ENV.local),
            CLD.extendsFromClientId to template,
        ),
    )

    "a client created on a template is served the template's file where it has none of its own, and its own once it has" {
        val client = "lfrfresh"
        // Nothing of the client's is on the server: its creation is not refused for a file only the template has.
        createOnTemplate(client).let { withClue(it) { it[EP.status].shouldBeNull() } }
        val member = memberOf(client)
        val own = listed(adminOf(client)).getValue("own")
        own[FREF.fromTemplate] shouldBe template
        own[FREF.missing] shouldBe false
        own[FREF.name] shouldBe nameOf("own", svg)
        static(member, nameOf("own", svg)).let {
            it.rptStatusCode shouldBe EXC.ok
            it.rptResponseBytes!!.toList() shouldBe svg.toList()
        }
        ClientConfigIssues.get(cxt).issuesFor(client).filter { it.message.contains("'own'") }.shouldBeEmpty()
        // The record is the template's file's, whoever reads it first: what a node remembers of a file is shared by the
        // template and every client served it, so it names the client whose directory it is in. Asked of a reader that
        // has seen nothing yet, so the client's ref is the first to read it.
        val fresh = LocalFileSource(refs.localRoots.shouldNotBeNull(), FREF.sourceClientLocal)
        fresh.meta(cxt, refs.forClient(client).refs.getValue("own")).shouldNotBeNull().client shouldBe template

        // Given a file of its own, it is that, at a new address; the template's address is now another file's.
        approve(client, "own.png", png)
        val mine = listed(adminOf(client)).getValue("own")
        mine.containsKey(FREF.fromTemplate) shouldBe false
        mine[FREF.name] shouldBe nameOf("own", png)
        static(member, nameOf("own", png)).rptResponseBytes!!.toList() shouldBe png.toList()
        static(member, nameOf("own", svg)).header(ContentResources.cacheControlHeader) shouldBe ContentResources.noStore
    }

    "a client's own file that cannot be served is its own problem, not covered by the template's" {
        val client = "lfrbroken"
        createOnTemplate(client)[EP.status].shouldBeNull()
        approve(client, "own.png", "not an image".toByteArray())

        val own = listed(adminOf(client)).getValue("own")
        own[FREF.missing] shouldBe true
        own.containsKey(FREF.fromTemplate) shouldBe false
        static(memberOf(client), "own").rptStatusCode shouldBe EXC.notFound

        // Nor is one too large to read: it is there, and the client's.
        approve(client, "own.png", png + ByteArray(FREF.maxSize.toInt()))
        listed(adminOf(client)).getValue("own").let {
            it[FREF.missing] shouldBe true
            it.containsKey(FREF.fromTemplate) shouldBe false
        }
        static(memberOf(client), "own").rptStatusCode shouldBe EXC.notFound
    }

    "a template whose own file is missing leaves a client with none of its own nothing to serve, and says where it looked" {
        val client = "lfrnone"
        createOnTemplate(client)[EP.status].shouldBeNull()
        val templateFile = File(files, "$openRoot/$template/own.png")
        val kept = templateFile.readBytes()
        try {
            templateFile.delete() shouldBe true
            GedraConfigReload.reloadClient(cxt, client)
            ClientConfigIssues.get(cxt).issuesFor(client).single { it.message.contains("'own'") }.message shouldContain
                "in the client's own directory of that root, or the template '$template''s, which is not there to be served as an image."
            static(memberOf(client), "own").rptStatusCode shouldBe EXC.notFound
        } finally {
            templateFile.writeBytes(kept)
        }
    }

    "a ref the client holds itself never reads the template's directory" {
        val client = "lfrownref"
        createOnTemplate(client)[EP.status].shouldBeNull()
        // The template has own.png, the client does not: naming it as the client's own finds nothing there.
        notNamed(adminOf(client), EXC.badInput, "mine", "own.png") shouldContain "in the client's own directory of that root"
        approve(client, "own.png", png)
        named(adminOf(client), "mine", "own.png")[FREF.ref].toJsonMapOrEmpty()[FREF.name] shouldBe nameOf("mine", png)
    }

    "a stored override of an inherited ref that is left out reveals the template's, as an own shipped ref's does" {
        val client = "lfroverride"
        createOnTemplate(client)[EP.status].shouldBeNull()
        static(memberOf(client), nameOf("crest", svg)).rptStatusCode shouldBe EXC.ok
        // Stored in-process, past the write's own checks: an entry the load cannot serve (a hash that is none), as a
        // restore or an older release could leave it.
        svc.writeConfig(
            writerOf(client),
            gedraConfig(cxt, "over", clientNamespace(client), client, GedraConfigOrigin.stored) { fileRef("crest", "abc") },
            trial = false,
        )
        svc.publish(writerOf(client), GedraId.of(GedraConfigType.configDoc, client, "over"))
        GedraConfigReload.reloadClient(cxt, client)

        ClientConfigIssues.get(cxt).issuesFor(client).single { it.message.contains("'crest'") }.message shouldContain "the hash 'abc', which is not one"
        val crest = listed(adminOf(client)).getValue("crest")
        crest[FREF.stored] shouldBe false
        crest[CCT.source] shouldBe FREF.sourceLocal
        static(memberOf(client), nameOf("crest", svg)).rptResponseBytes!!.toList() shouldBe svg.toList()
    }

    "a sandbox of a client built on a template is served what its parent is: the template's file, then the parent's own" {
        val client = "lfrtplsbx"
        createOnTemplate(client).let { withClue(it) { it[EP.status].shouldBeNull() } }
        adminOf(client).postData(UADEP.clientSandbox, mapOf(CLD.sandbox to true))
        val inSandbox = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = sandboxOf(client))

        // The sandbox's ref is its parent's: the parent has no file of its own, so the template's, by the same name.
        listed(inSandbox).getValue("own")[FREF.fromTemplate] shouldBe template
        static(inSandbox, nameOf("own", svg)).rptResponseBytes!!.toList() shouldBe svg.toList()

        // Given the parent a file of its own, the sandbox reads it -- the parent's directory, not one of the sandbox's.
        approve(client, "own.png", png)
        listed(inSandbox).getValue("own").let {
            it.containsKey(FREF.fromTemplate) shouldBe false
            it[FREF.name] shouldBe nameOf("own", png)
        }
        static(inSandbox, nameOf("own", png)).rptResponseBytes!!.toList() shouldBe png.toList()
    }
})
