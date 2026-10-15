package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.context.UserProfile
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.fileref.ClientFileRefEdit
import com.dynamicruntime.common.fileref.FREF
import com.dynamicruntime.common.fileref.FileRefService
import com.dynamicruntime.common.fileref.LFENV
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientStoredEdit
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.MNU
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptLong
import com.dynamicruntime.common.util.toOptStr
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * What an editor does when the publish that would make its change live is refused (issue #1179): its undo puts back
 * what the configuration held, so the next reload does not take the refused change live after all, and the caller is
 * told the publish's refusal. Each editor -- copy, menu, definition, file refs -- builds its own undo over the one
 * `ClientStoredEdit.publishAndReload`, so each is tested. Then the smaller rows of the file-ref editor that nothing
 * else pins.
 *
 * A client that runs its latest configuration does not trial a publish, so a change the write's trial passed is never
 * refused there on its own. **An instance of its own** carries a [ClientStoredEdit.PublishCheck] that refuses the
 * publish of the clients in [refusing] -- and local files are instance-wide.
 */
class PublishRefusalUndoTest : StringSpec({
    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    val otherPng = png + byteArrayOf(9, 9)
    val brandRoot = "brand"
    val openRoot = "approved"
    val refusal = "Publishing is refused for this test."

    val files = Files.createTempDirectory("kdrPublishRefusal").toFile()
    File(files, brandRoot).apply { mkdirs() }.let { File(it, "logo.png").writeBytes(png) }
    afterSpec { files.deleteRecursively() }

    /** The clients whose publish is refused, as a refused publish would be: after the write, before going live. */
    val refusing: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val cxt = Startup.mkTestBootCxt(
        "publishRefusal", "publishRefusal1179",
        mapOf(
            LFENV.dir.name to files.absolutePath,
            LFENV.clientRoots.name to openRoot,
            ClientStoredEdit.publishCheckConfigKey to ClientStoredEdit.PublishCheck { _, client, _ ->
                if (client in refusing) throw KdrException.mkInput(refusal)
            },
        ),
        emptyList(),
    )
    val svc = GedraConfigService.get(cxt)
    val refs = FileRefService.get(cxt)

    /** A context that writes [client]'s configuration in-process, with no signed-in caller: a script, a simulation. */
    fun writerOf(client: String): KdrCxt = cxt.mkSubContext("write", client).also { it.userId = 11790L }

    /** Defines [client] in stored configuration, published and loaded, and returns its administrator. */
    fun defineClient(client: String, sandbox: Boolean = false): TestUser {
        svc.writeConfig(
            writerOf(client),
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = "Client $client", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = sandbox,
                    ),
                )
            },
        )
        svc.publish(writerOf(client), GedraId.of(GedraConfigType.configDoc, client, "main"))
        GedraConfigReload.reloadClient(cxt, client)
        return TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
    }

    /** Puts [bytes] at [path] in [client]'s own directory of the root open to clients, as the deployment would. */
    fun approve(client: String, path: String, bytes: ByteArray = png) {
        File(files, "$openRoot/$client/$path").also { it.parentFile.mkdirs() }.writeBytes(bytes)
    }

    /** Sends [data] to [path] as [user], expecting a refusal with [status]; its message. */
    fun refused(user: TestUser, path: String, data: Map<String, Any?>, status: Int = EXC.badInput): String {
        val env = user.client.sendJsonPostRequest(path, data)
        return withClue(env) {
            (env[EP.status] as? Number)?.toInt() shouldBe status
            env[EP.errorMessage].toOptStr().shouldNotBeNull()
        }
    }

    fun ok(user: TestUser, path: String, data: Map<String, Any?>): Map<String, Any?> =
        user.client.sendJsonPostRequest(path, data).also { withClue(it) { it[EP.status].shouldBeNull() } }[EP.results].toJsonMapOrEmpty()

    fun image(bytes: ByteArray) = ContentData(bytes, "image/png", saveAsFilename = "pic.png", inLine = false)

    fun upload(user: TestUser, refId: String, bytes: ByteArray): Map<String, Any?> =
        user.upload(UADEP.clientFilesUpload, mapOf(CCT.refId to refId, FREF.file to image(bytes)))

    fun nameLocal(refId: String, path: String, description: String? = null): Map<String, Any?> = buildMap {
        put(CCT.refId, refId)
        put(CCT.root, openRoot)
        put(CCT.path, path)
        description?.let { put(CCT.description, it) }
    }

    fun storedRefs(client: String, config: String): List<Map<String, Any?>> =
        svc.readLatest(cxt.mkSubContext("read", client), GedraId.of(GedraConfigType.configDoc, client, config))
            ?.entriesBySlot()?.get(CCT.fileRefDef).orEmpty()

    fun brand(client: String): String? =
        MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, HFRAG.home, client)?.content?.get("home")?.get("brand")

    fun setBrand(value: String) = mapOf(COV.fileId to HFRAG.home, COV.namespaceField to "home", COV.key to "brand", COV.value to value)

    fun docsLabel(user: TestUser): Any? = user.getItems(MNU.itemsPath).single { it[COV.itemId] == HMENU.docs }[MNU.label]

    // --- Each editor's undo -----------------------------------------------------------------------------------------

    "copy: a refused publish is undone, whether it changed a key the editors' config held or made that config" {
        val client = "undocopy"
        val admin = defineClient(client)
        val shipped = brand(client)

        // The first edit makes the editors' config; refused, its key is taken out again.
        refusing += client
        refused(admin, CPY.setPath, setBrand("Never")) shouldBe refusal
        GedraConfigReload.reloadClient(cxt, client)
        brand(client) shouldBe shipped

        refusing -= client
        ok(admin, CPY.setPath, setBrand("First"))
        brand(client) shouldBe "First"

        // A key it holds is put back as it was. The reload is what the undo is for: without it, the client -- which
        // runs its latest configuration -- would take the refused value live at its next load.
        refusing += client
        refused(admin, CPY.setPath, setBrand("Second")) shouldBe refusal
        refused(admin, CPY.resetPath, mapOf(COV.fileId to HFRAG.home, COV.namespaceField to "home", COV.key to "brand")) shouldBe refusal
        GedraConfigReload.reloadClient(cxt, client)
        brand(client) shouldBe "First"
    }

    "menu: a refused publish is undone, for a first edit, a change to a held item, and a reset" {
        val client = "undomenu"
        val admin = defineClient(client)
        val shipped = docsLabel(admin)

        refusing += client
        refused(admin, MNU.setPath, mapOf(COV.itemId to HMENU.docs, MNU.label to "Never")) shouldBe refusal
        GedraConfigReload.reloadClient(cxt, client)
        docsLabel(admin) shouldBe shipped

        refusing -= client
        ok(admin, MNU.setPath, mapOf(COV.itemId to HMENU.docs, MNU.label to "Papers"))
        docsLabel(admin) shouldBe "Papers"

        refusing += client
        refused(admin, MNU.setPath, mapOf(COV.itemId to HMENU.docs, MNU.label to "Files")) shouldBe refusal
        refused(admin, MNU.resetPath, mapOf(COV.itemId to HMENU.docs)) shouldBe refusal
        GedraConfigReload.reloadClient(cxt, client)
        docsLabel(admin) shouldBe "Papers"
    }

    "definition: a refused publish is undone, and the configuration it lands in is published again" {
        val client = "undodef"
        val admin = defineClient(client)
        refusing += client
        refused(admin, UADEP.clientDefinitionSet, mapOf(CLD.name to "Renamed")) shouldBe refusal
        val main = svc.readLatest(writerOf(client), GedraId.of(GedraConfigType.configDoc, client, "main")).shouldNotBeNull()
        // Put back, and published: left a draft, the next definition edit would be refused for it.
        main.isPublished shouldBe true
        GedraConfigReload.reloadClient(cxt, client)
        ClientService.get(cxt).known(client)?.name shouldBe "Client $client"
    }

    // The undo of an upload over a ref somebody else named: an operator's local ref, which the client's own
    // administrator could not have written. The undo writes it back as what was there (`patchConfig(restoring)`);
    // without that, putting it back is refused, and the caller would be told that instead of why their upload failed.
    "file refs: an upload over an operator's local ref, refused at the publish, puts the local ref back" {
        val client = "undoupload"
        val admin = defineClient(client)
        val local = mapOf(CCT.refId to "logo", CCT.source to FREF.sourceLocal, CCT.root to brandRoot, CCT.path to "logo.png")
        val art = svc.writeConfig(writerOf(client), gedraConfig(cxt, "art", clientNamespace(client), client) { fileRefFromMap(local) })
        svc.publish(writerOf(client), art.configId)
        GedraConfigReload.reloadClient(cxt, client)

        refusing += client
        val env = upload(admin, "logo", otherPng)
        withClue(env) {
            (env[EP.status] as? Number)?.toInt() shouldBe EXC.badInput
            env[EP.errorMessage] shouldBe refusal
        }
        storedRefs(client, "art") shouldBe listOf(local)
        GedraConfigReload.reloadClient(cxt, client)
        refs.forClient(client).refs.getValue("logo").def.source shouldBe FREF.sourceLocal
    }

    "file refs: naming a local file, removing a ref, and rewording one are each undone when the publish is refused" {
        val client = "undorefs"
        val admin = defineClient(client)
        approve(client, "one.png")
        approve(client, "two.png", otherPng)
        ok(admin, UADEP.clientFilesLocal, nameLocal("own", "one.png", description = "The first."))
        val before = storedRefs(client, CPY.copyConfigName)

        refusing += client
        refused(admin, UADEP.clientFilesLocal, nameLocal("own", "two.png")) shouldBe refusal
        storedRefs(client, CPY.copyConfigName) shouldBe before
        refused(admin, UADEP.clientFilesRemove, mapOf(CCT.refId to "own")) shouldBe refusal
        storedRefs(client, CPY.copyConfigName) shouldBe before
        refused(admin, UADEP.clientFilesDescribe, mapOf(CCT.refId to "own", CCT.description to "Reworded.")) shouldBe refusal
        storedRefs(client, CPY.copyConfigName) shouldBe before
        // And a new ref, whose undo takes its entry out again.
        refused(admin, UADEP.clientFilesLocal, nameLocal("added", "two.png")) shouldBe refusal
        storedRefs(client, CPY.copyConfigName) shouldBe before

        GedraConfigReload.reloadClient(cxt, client)
        val own = refs.forClient(client).refs.getValue("own").def
        own.path shouldBe "one.png"
        own.description shouldBe "The first."
        refs.forClient(client).refs.keys shouldBe setOf("own")
    }

    // --- The file-ref editor's other rows ----------------------------------------------------------------------------

    // A field a later release wrote is carried into the entry an upload replaces, not dropped (`FileRefDef.extra`).
    // This release's slot gate holds a changed entry to the shape it knows, so carried, the upload is refused naming
    // the field -- the entry is never written back without it.
    "an upload over a stored ref carries the fields of its entry this release does not read, and is refused for them" {
        val client = "undoextra"
        val admin = defineClient(client)
        FileStoreService.get(cxt).put(writerOf(client), FileRefService.bucket, FileRefService.keyOf(client, png.sha256Hex()), image(png))
        val entry = mapOf(CCT.refId to "logo", CCT.source to FREF.sourceStore, CCT.hash to png.sha256Hex(), "laterField" to "kept")
        val art = svc.writeConfig(writerOf(client), gedraConfig(cxt, "art", clientNamespace(client), client) { fileRefFromMap(entry) })
        svc.publish(writerOf(client), art.configId)

        val env = upload(admin, "logo", otherPng)
        withClue(env) { env[EP.errorMessage].toString() shouldContain "kdr:fileRefDef[logo].laterField" }
        storedRefs(client, "art") shouldBe listOf(entry)
    }

    "naming a local file: a blank description clears it" {
        val client = "namedblank"
        val admin = defineClient(client)
        approve(client, "own.png")
        ok(admin, UADEP.clientFilesLocal, nameLocal("own", "own.png", description = "Before."))
        storedRefs(client, CPY.copyConfigName).single()[CCT.description] shouldBe "Before."
        ok(admin, UADEP.clientFilesLocal, nameLocal("own", "own.png", description = ""))
        storedRefs(client, CPY.copyConfigName).single()[CCT.description].shouldBeNull()
    }

    "naming a local file over a ref whose configuration carries somebody's unpublished changes is refused, saying so" {
        val client = "nameddraft"
        val admin = defineClient(client)
        approve(client, "own.png")
        fun art(description: String) = gedraConfig(cxt, "art", clientNamespace(client), client) {
            fileRef("own", description) { clientLocal(openRoot, "own.png") }
        }
        val art = svc.writeConfig(writerOf(client), art("Published."))
        svc.publish(writerOf(client), art.configId)
        svc.writeConfig(writerOf(client), art("A draft."))
        refused(admin, UADEP.clientFilesLocal, nameLocal("own", "own.png")) shouldContain
            "Configuration 'art' of client '$client' has unpublished changes, which naming a local file would publish with it."
    }

    "naming a local file as a new ref, for a client served as many refs as it may have, is refused before anything is written" {
        val client = "namedfull"
        val admin = defineClient(client)
        approve(client, "own.png")
        val hash = png.sha256Hex()
        FileStoreService.get(cxt).put(writerOf(client), FileRefService.bucket, FileRefService.keyOf(client, hash), image(png))
        val many = svc.writeConfig(writerOf(client), gedraConfig(cxt, "many", clientNamespace(client), client) { for (n in 1..FREF.maxRefs) fileRef("r$n", hash) })
        svc.publish(writerOf(client), many.configId)
        GedraConfigReload.reloadClient(cxt, client)

        refused(admin, UADEP.clientFilesLocal, nameLocal("own", "own.png")) shouldContain "which is as many as a client is served"
        // The editors' own configuration is not made for it.
        ClientStoredEdit.editConfig(writerOf(client), client).shouldBeNull()
    }

    // The endpoints' description field refuses a long one first; the editor's own bound is for a caller in process.
    "in process, a description longer than a ref may have is refused, before an upload stores anything" {
        val client = "longdesc"
        defineClient(client)
        approve(client, "own.png")
        val asAdmin = cxt.mkSubContext("asAdmin", client).also {
            it.userProfile = UserProfile(authId = "chief-$client", userId = 11791L, client = client, roles = setOf(ROLE.user, ROLE.admin))
        }
        val long = "x".repeat(FREF.maxDescriptionLength + 1)
        fun said(block: () -> Unit) = shouldThrow<KdrException> { block() }.also { it.code shouldBe EXC.badInput }.message.orEmpty()

        said { ClientFileRefEdit.upload(asAdmin, client, "logo", image(otherPng), long) } shouldContain "at most ${FREF.maxDescriptionLength} characters"
        FileStoreService.get(cxt).get(writerOf(client), FileRefService.bucket, FileRefService.keyOf(client, otherPng.sha256Hex())).shouldBeNull()
        said { ClientFileRefEdit.nameLocal(asAdmin, client, "own", openRoot, "own.png", long) } shouldContain "at most"
        said { ClientFileRefEdit.describe(asAdmin, client, "own", long) } shouldContain "at most"
        ClientStoredEdit.editConfig(writerOf(client), client).shouldBeNull()
    }

    // From inside a sandbox, the request's caller is the sandbox user and the write is made as their administrator in
    // the parent: two user rows. The question whether a file may be named is the write's, so it is asked of the
    // second -- here a sandbox user whose profile claims more than the parent's administrator holds.
    "naming a local file from a sandbox is judged for the parent's administrator the write is made as" {
        val parent = "namedsbx"
        val admin = defineClient(parent, sandbox = true)
        val sandboxUserId = admin.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong().shouldNotBeNull()
        val inSandbox = cxt.mkSubContext("inSandbox", sandboxOf(parent)).also {
            it.userProfile = UserProfile(
                authId = "chief-$parent", userId = sandboxUserId, client = sandboxOf(parent),
                roles = setOf(ROLE.user, ROLE.admin, ROLE.allClients),
            )
        }
        // A root not open to clients: refused for the parent's administrator, who does not see every client.
        val ex = shouldThrow<KdrException> { ClientFileRefEdit.nameLocal(inSandbox, sandboxOf(parent), "logo", brandRoot, "logo.png", null) }
        ex.code shouldBe EXC.notAuthorized
        ex.message.orEmpty() shouldContain "is not one a client's own administrator may name a file in"
    }
})
