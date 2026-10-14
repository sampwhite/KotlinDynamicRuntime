package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.fileref.FREF
import com.dynamicruntime.common.fileref.FileRefService
import com.dynamicruntime.common.fileref.LFENV
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Files

/** Two clients shipped with as many local refs as a client is served, and more: every ref names one picture. */
class RefLimitComponent : ComponentDefinition {
    override val providerName: String = "refLimitFixture"

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        shippedWith(cxt, over, FREF.maxRefs + 5),
        shippedWith(cxt, full, FREF.maxRefs),
    )

    private fun shippedWith(cxt: KdrCxt, client: String, count: Int): GedraConfig =
        gedraConfig(cxt, "art", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = "Client $client", usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            for (n in 1..count) fileRef("s$n") { local(root, "pic.png") }
        }

    companion object {
        const val root = "art"
        const val over = "reflimitover"
        const val full = "reflimitfull"
    }
}

/**
 * The limit on how many file refs a client is served (issue #1169): over it, the client has **one** problem, the same
 * however its refs are held and whichever configurations hold them -- so a write that only takes refs away, or only
 * replaces a file, is never refused for it -- and a client at it is told what it can do, which for refs it was shipped
 * with is not "remove one".
 *
 * **An instance of its own** (issue #1075): its fixture ships clients over the limit, a problem in source that the
 * instance is told to report rather than refuse (`KDR_GEDRA_CONFIG_CHECK=warn`), and local files are instance-wide.
 */
class FileRefLimitTest : StringSpec({
    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    val otherPng = png + byteArrayOf(9, 9)
    val files = Files.createTempDirectory("kdrRefLimit").toFile()
    File(files, RefLimitComponent.root).apply { mkdirs() }.let { File(it, "pic.png").writeBytes(png) }
    afterSpec { files.deleteRecursively() }

    val cxt = Startup.mkTestBootCxt(
        "refLimit", "fileRefLimit1169",
        mapOf(
            LFENV.dir.name to files.absolutePath,
            GCFG.checkEnvVar.name to BootCheckMode.warn.name,
            GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name,
        ),
        listOf(RefLimitComponent()),
    )
    val svc = GedraConfigService.get(cxt)
    val refs = FileRefService.get(cxt)

    fun writerOf(client: String): KdrCxt = cxt.mkSubContext("write", client).also { it.userId = 11690L }

    fun adminOf(client: String) = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)

    fun image(bytes: ByteArray) = ContentData(bytes, "image/png", saveAsFilename = "pic.png", inLine = false)

    fun upload(user: TestUser, refId: String, bytes: ByteArray): Map<String, Any?> =
        user.upload(UADEP.clientFilesUpload, mapOf(CCT.refId to refId, FREF.file to image(bytes)))

    fun remove(user: TestUser, refId: String): Map<String, Any?> =
        user.client.sendJsonPostRequest(UADEP.clientFilesRemove, mapOf(CCT.refId to refId))

    /** The limit's problem among [client]'s, as its last load recorded them. */
    fun overLimit(client: String) = ClientConfigIssues.get(cxt).issuesFor(client).filter { it.message.contains("as many as a client is served") }

    fun publish(client: String, name: String) {
        svc.publish(writerOf(client), GedraId.of(GedraConfigType.configDoc, client, name))
    }

    "emptying the first stored configuration that holds refs, while over the limit, is not refused" {
        val client = "reflimitempty"
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
        publish(client, "main")
        FileStoreService.get(cxt).put(writerOf(client), FileRefService.bucket, FileRefService.keyOf(client, png.sha256Hex()), ContentData(png, "image/png"))
        val hash = png.sha256Hex()
        // Over the limit across two configurations, the first of which -- as a load orders them -- holds only three.
        svc.writeConfig(writerOf(client), gedraConfig(cxt, "afew", clientNamespace(client), client) { for (n in 1..3) fileRef("r$n", hash) })
        svc.writeConfig(writerOf(client), gedraConfig(cxt, "bmany", clientNamespace(client), client) { for (n in 4..FREF.maxRefs + 5) fileRef("r$n", hash) })
        publish(client, "afew")
        publish(client, "bmany")
        GedraConfigReload.reloadClient(cxt, client)
        overLimit(client) shouldHaveSize 1
        val admin = adminOf(client)

        // Taking every ref out of the first: the last of them leaves the over-the-limit state held by the other.
        for (n in 1..3) remove(admin, "r$n")[EP.status].shouldBeNull()

        refs.forClient(client).refs.size shouldBe FREF.maxRefs
        overLimit(client) shouldHaveSize 1
    }

    "a client over the limit on refs it was shipped with takes an upload over one of them" {
        val client = RefLimitComponent.over
        overLimit(client).single().origin shouldBe GedraConfigOrigin.source
        val admin = adminOf(client)

        // The first upload over a shipped id: it adds no ref, and starts the client's own stored configuration.
        upload(admin, "s1", otherPng)[EP.status].shouldBeNull()

        refs.forClient(client).refs.size shouldBe FREF.maxRefs
        refs.forClient(client).refs.getValue("s1").def.stored shouldBe true
        // Still one problem, now judged as stored configuration's: the client's own data holds refs.
        overLimit(client).single().origin shouldBe GedraConfigOrigin.stored

        // A new one cannot be made room for by removing what it may: one stored ref, of the six that would have to go.
        val refused = upload(admin, "extra", otherPng)[EP.errorMessage].toOptStr().orEmpty()
        refused shouldContain "holds ${FREF.maxRefs + 5} file refs, more than the ${FREF.maxRefs} a client is served"
        refused shouldContain "only 1 of them are held in stored configuration -- fewer than the 6 that would have to go"
        refused shouldContain "Upload over one of them to replace its file"
    }

    "a client shipped with more refs than it is served refuses a strict boot, where a mistake in source is caught" {
        val refused = runCatching {
            // Its own instance: the boot itself is under test, under the checks' own strict mode, and is expected to
            // be refused for the client shipped over the limit -- its refs held by source code alone.
            Startup.mkTestBootCxt("refLimitStrict", "fileRefLimitStrict1169", mapOf(LFENV.dir.name to files.absolutePath), listOf(RefLimitComponent()))
        }.exceptionOrNull()

        generateSequence(refused) { it.cause }.mapNotNull { it.message }.joinToString(" | ") shouldContain
            "Client '${RefLimitComponent.over}' has more than ${FREF.maxRefs} file refs"
    }

    "a client full of refs it was shipped with is told what it can do, which is not removing one" {
        val client = RefLimitComponent.full
        val admin = adminOf(client)

        val refused = upload(admin, "extra", otherPng)

        (refused[EP.status] as Number).toInt() shouldBe 400
        val message = refused[EP.errorMessage].toOptStr().orEmpty()
        message shouldContain "has ${FREF.maxRefs} file refs, which is as many as a client is served"
        message shouldContain "all ${FREF.maxRefs} were shipped with it"
        message shouldContain "Upload over one of them to replace its file"
        // Which it can.
        upload(admin, "s7", otherPng)[EP.status].shouldBeNull()
        // And with one of its own among them, it is told which it can remove.
        val partly = upload(admin, "extra", otherPng)[EP.errorMessage].toOptStr().orEmpty()
        partly shouldContain "Remove one of the 1 it holds in stored configuration"
        partly shouldContain "${FREF.maxRefs - 1} were shipped with it"
        // The over-the-limit problem was never this client's: it is at the limit, not over it.
        overLimit(client) shouldBe emptyList()
        refs.forClient(client).refs.getValue("s7").def.stored shouldBe true
        refs.forClient(client).refs["extra"].shouldBeNull()
    }
})
