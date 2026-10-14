package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.content.UIC
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.endpoint.HttpMethod
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.fileref.FREF
import com.dynamicruntime.common.fileref.FileRefService
import com.dynamicruntime.common.filestore.FileStoreService
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.home.HEP
import com.dynamicruntime.common.home.HFLD
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.http.request.ContentData
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.mail.MCOPY
import com.dynamicruntime.common.mail.MailCopy
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.user.AFRAG
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.jsonMap
import com.dynamicruntime.common.util.sha256Hex
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Copy naming a client's file ref as an image, `![Our logo](ref:logo)` (issue #1186): the shell config hands each
 * caller the names of their client's refs to draw them by; a replaced image is a new name with no edit to the copy;
 * a sandbox is handed its draft; and copy naming a ref the client does not have -- written, or left behind by a ref
 * taken away -- is refused at the write. A mail draws a ref as its alt text. One booted instance; a client per case.
 */
class FileRefCopyTest : StringSpec({
    val cxt = TestInstances.default("fileRefCopy1186")
    val svc = GedraConfigService.get(cxt)
    val fragments = MarkdownFragmentService.get(cxt)
    val refs = FileRefService.get(cxt)
    val files = FileStoreService.get(cxt)
    val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    val otherPng = png + byteArrayOf(9, 9)

    /** Defines [client] in stored configuration, published and reloaded, and returns its administrator. */
    fun defineClient(client: String, sandbox: Boolean = false): TestUser {
        val setup = cxt.mkSubContext("setup", client).also { it.userId = 11860L }
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = "Client $client", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = sandbox,
                    ),
                )
            },
        )
        svc.publish(setup, GedraId.of(GedraConfigType.configDoc, client, "main"))
        GedraConfigReload.reloadClient(cxt, client)
        return TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
    }

    fun image(bytes: ByteArray) = ContentData(bytes, "image/png", saveAsFilename = "pic.png", inLine = false)

    /** Uploads [bytes] as [user]'s client's ref [refId]; the whole envelope. */
    fun upload(user: TestUser, refId: String, bytes: ByteArray): Map<String, Any?> =
        user.upload(UADEP.clientFilesUpload, mapOf(CCT.refId to refId, FREF.file to image(bytes)))

    fun uploaded(user: TestUser, refId: String, bytes: ByteArray) = upload(user, refId, bytes).also { it[EP.status].shouldBeNull() }

    fun set(ns: String, key: String, value: String) = mapOf(COV.namespaceField to ns, COV.key to key, COV.value to value)

    /** Saves [value] as `home.intro` of [user]'s client's copy; the whole envelope, so a refusal can be read. */
    fun writeIntro(user: TestUser, value: String): Map<String, Any?> =
        user.client.sendEditRequest(CPY.applyPath, null, mapOf(COV.fileId to HFRAG.home, CPY.changes to listOf(set("home", "intro", value))), HttpMethod.POST)
            .rptResponseData?.jsonMap() ?: emptyMap()

    /** The refs the shell config hands [user], by id. */
    fun shellRefs(user: TestUser): Map<String, Any?> =
        user.getData(HEP.homeUiConfig)[UIC.state].toJsonMapOrEmpty()[HFLD.fileRefs].toJsonMapOrEmpty()

    fun served(client: String, ns: String = "home", key: String = "intro", fileId: String = HFRAG.home): String? =
        fragments.effectiveFragmentsFor(cxt, fileId, client)?.content?.get(ns)?.get(key)

    "copy names a ref the client has, and the shell hands its people the name to draw it by" {
        val client = "refcopylive"
        val admin = defineClient(client)
        val member = TestUser.create(cxt, "member@$client.test", userClient = client)
        uploaded(admin, "logo", png)

        writeIntro(admin, "Welcome. ![Our logo](ref:logo){width=120}")[EP.status].shouldBeNull()

        served(client) shouldBe "Welcome. ![Our logo](ref:logo){width=120}"
        shellRefs(member) shouldBe mapOf("logo" to FREF.versionedName("logo", png.sha256Hex()))
        // Another client's people are handed none of it.
        defineClient("refcopyother")
        val stranger = TestUser.create(cxt, "member@refcopyother.test", userClient = "refcopyother")
        shellRefs(stranger) shouldBe emptyMap()
    }

    "a replaced image is a new name, with no edit to the copy" {
        val client = "refcopyswap"
        val admin = defineClient(client)
        uploaded(admin, "logo", png)
        writeIntro(admin, "![Logo](ref:logo)")
        val before = shellRefs(admin)["logo"]

        uploaded(admin, "logo", otherPng)

        served(client) shouldBe "![Logo](ref:logo)"
        before shouldBe FREF.versionedName("logo", png.sha256Hex())
        shellRefs(admin)["logo"] shouldBe FREF.versionedName("logo", otherPng.sha256Hex())
    }

    "copy naming a ref the client does not have is refused where it is written, and changes nothing" {
        val client = "refcopymissing"
        val admin = defineClient(client)
        uploaded(admin, "logo", png)
        writeIntro(admin, "Before.")

        val refused = writeIntro(admin, "See ![Our banner](ref:banner).")

        (refused[EP.status] as Number).toInt() shouldBe EXC.badInput
        val message = refused[EP.errorMessage].toOptStr().orEmpty()
        // It leads with the key, as a copy refusal does, which is how the copy editor marks it.
        message shouldContain "home.intro (file 'home' of client '$client'"
        message shouldContain "shows the image ref:banner, and the client has no file ref 'banner'."
        served(client) shouldBe "Before."
    }

    "a ref the copy still names is not taken away, until the copy stops naming it" {
        val client = "refcopyremove"
        val admin = defineClient(client)
        uploaded(admin, "logo", png)
        writeIntro(admin, "![Logo](ref:logo)")

        val refused = admin.expectError(EXC.badInput, UADEP.clientFilesRemove, mapOf(CCT.refId to "logo"))
        refused[EP.errorMessage].toOptStr().orEmpty() shouldContain "shows the image ref:logo, and the client has no file ref 'logo'."
        shellRefs(admin).keys shouldBe setOf("logo")

        writeIntro(admin, "No picture now.")[EP.status].shouldBeNull()
        admin.postData(UADEP.clientFilesRemove, mapOf(CCT.refId to "logo"))
        shellRefs(admin) shouldBe emptyMap()
    }

    "a sandbox is handed its draft image, and the client's people what is published" {
        val client = "refcopysbx"
        val admin = defineClient(client, sandbox = true)
        val member = TestUser.create(cxt, "member@$client.test", userClient = client)
        val inSandbox = TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = sandboxOf(client))
        val shipped = served(client)

        // Both drafts of the parent's configuration: the image, and the copy naming it.
        upload(admin, "hero", png)[EP.results].toJsonMapOrEmpty()[FREF.mode] shouldBe EDM.draft
        writeIntro(admin, "![Hero](ref:hero)")[EP.results].toJsonMapOrEmpty()[CPY.mode] shouldBe EDM.draft

        shellRefs(inSandbox) shouldBe mapOf("hero" to FREF.versionedName("hero", png.sha256Hex()))
        served(sandboxOf(client)) shouldBe "![Hero](ref:hero)"
        // The client's own people are served neither yet: no image, and the copy as shipped.
        shellRefs(member) shouldBe emptyMap()
        served(client) shouldBe shipped

        // Published together, they are the client's.
        val owner = cxt.mkSubContext("publish", client).also { it.userId = 11861L }
        svc.publish(owner, GedraId.of(GedraConfigType.configDoc, client, CPY.copyConfigName))
        GedraConfigReload.reloadClient(cxt, client)
        shellRefs(member) shouldBe mapOf("hero" to FREF.versionedName("hero", png.sha256Hex()))
        served(client) shouldBe "![Hero](ref:hero)"
    }

    "a ref whose file is gone is left out of the shell, so copy naming it draws its alt text" {
        val client = "refcopygone"
        val admin = defineClient(client)
        uploaded(admin, "logo", png)
        files.delete(cxt, FileRefService.bucket, FileRefService.keyOf(client, png.sha256Hex())) shouldBe true

        // What the store last said stands for a while, as it does for a listing; then the store is asked again.
        try {
            refs.nanoTime = { System.nanoTime() + (FileRefService.askedMemoMs + 1) * 1_000_000 }
            shellRefs(admin) shouldBe emptyMap()
        } finally {
            refs.nanoTime = System::nanoTime
        }
    }

    "a client shipped with copy naming a ref it does not have refuses the boot, where a mistake in source is caught" {
        val refused = runCatching {
            // Its own instance: the boot itself is under test, and is expected to be refused.
            Startup.mkTestBootCxt("refCopyBoot", "fileRefCopyBootRefused", emptyMap(), listOf(RefCopyFixtureComponent()))
        }.exceptionOrNull()

        generateSequence(refused) { it.cause }.mapNotNull { it.message }.joinToString(" | ") shouldContain
            "shows the image ref:crest, and the client has no file ref 'crest'."
    }

    "a mail draws a ref as its alt text: it is read where no address of the client's answers" {
        val client = "refcopymail"
        val admin = defineClient(client)
        uploaded(admin, "logo", png)
        admin.postData(
            CPY.applyPath,
            mapOf(COV.fileId to AFRAG.mail, CPY.changes to listOf(set(MCOPY.common, MCOPY.footer, "Signed. ![The Mail Co logo](ref:logo)"))),
        )

        val rendered = MailCopy.render(cxt, client, MCOPY.verifyCode, mapOf(MCOPY.codeParam to "123456"))

        rendered.html shouldContain "Signed. The Mail Co logo"
        rendered.html shouldNotContain "<img"
        rendered.text shouldContain "Signed. The Mail Co logo"
    }
})

/** A client defined in source whose copy names a file ref it was not shipped with. */
class RefCopyFixtureComponent : ComponentDefinition {
    override val providerName: String = "refCopyFixture"

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "main", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = "Ref copy fixture", usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            fragmentOverlay(HFRAG.home, mapOf("home" to mapOf("intro" to "Welcome. ![Our crest](ref:crest)")))
        },
    )

    companion object {
        const val client = "refcopyfixture"
    }
}
