package com.dynamicruntime.kdn

import com.dynamicruntime.common.content.MarkdownFragmentService
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.UPF
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.COV
import com.dynamicruntime.common.gedra.CPY
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.EDM
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.MNU
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.sandboxOf
import com.dynamicruntime.common.home.HFRAG
import com.dynamicruntime.common.home.HMENU
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.AEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toOptLong
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Configuration edits made from inside a Shadow Sandbox act on its parent, as drafts (issue #930). A sandbox runs its
 * parent's **latest** configuration, so an edit there writes the parent's editable revision and reloads: the sandbox
 * shows it at once, a published-only parent keeps running what it published, and publishing -- from either side --
 * makes it live. The editors (copy, #918; menu, #919) save drafts for any client with a sandbox, and publish at once
 * for one without, as before. One booted instance; a parent client per case.
 */
class SandboxEditsTest : StringSpec({
    val cxt = TestInstances.default("sandboxEdits930")
    val svc = GedraConfigService.get(cxt)

    /** Defines [parent] -- with a sandbox unless [sandbox] is false -- publishes it, and makes it published-only when asked. */
    fun defineParent(parent: String, sandbox: Boolean = true, publishedOnly: Boolean = false) {
        val setup = cxt.mkSubContext("setup", parent).also { it.userId = 9301L }
        val config = gedraConfig(cxt, "main", clientNamespace(parent), parent) {
            defineClient(
                ClientDef(
                    clientId = parent, name = "Client $parent", usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = sandbox,
                ),
            )
        }
        svc.writeConfig(setup, config)
        svc.publish(setup, GedraId.of(GedraConfigType.configDoc, parent, "main"))
        if (publishedOnly) svc.setPublishedOnly(setup, parent, true)
        GedraConfigReload.reloadClient(cxt, parent)
    }

    /** What [client]'s people read for the home brand. */
    fun brand(client: String): String? =
        MarkdownFragmentService.get(cxt).effectiveFragmentsFor(cxt, HFRAG.home, client)?.content?.get("home")?.get("brand")

    fun setBrand(user: TestUser, value: String, client: String? = null): Map<String, Any?> = user.postData(
        CPY.setPath,
        buildMap {
            client?.let { put(COV.client, it) }
            put(COV.fileId, HFRAG.home); put(COV.namespaceField, "home"); put(COV.key, "brand"); put(COV.value, value)
        },
    )

    fun copyPublished(client: String): Boolean? =
        svc.readLatest(cxt.mkSubContext("check", client), GedraId.of(GedraConfigType.configDoc, client, CPY.copyConfigName))?.isPublished

    fun cfacts(client: String): Set<String> = SchemaService.get(cxt).cfactsFor(client).names

    "a copy edit for a client with a sandbox stays a draft the sandbox shows, until it is published" {
        val parent = "sbxdraft"
        defineParent(parent, publishedOnly = true)
        val shipped = brand(parent)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)

        val saved = setBrand(admin, "Draft Brand")
        saved[CPY.mode] shouldBe EDM.draft
        saved[COV.value] shouldBe "Draft Brand"
        brand(sandboxOf(parent)) shouldBe "Draft Brand"
        brand(parent) shouldBe shipped
        copyPublished(parent) shouldBe false

        admin.postData(CFEP.bundlePublish, mapOf(CFEP.name to CPY.copyConfigName))
        admin.postData(CFEP.reload, emptyMap())
        brand(parent) shouldBe "Draft Brand"
    }

    "a copy edit for a client without a sandbox still publishes at once" {
        val parent = "sbxnolive"
        defineParent(parent, sandbox = false, publishedOnly = true)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        val saved = setBrand(admin, "Live Brand")
        saved[CPY.mode] shouldBe EDM.live
        brand(parent) shouldBe "Live Brand"
        copyPublished(parent) shouldBe true
    }

    "a copy edit made in the sandbox lands in the parent's configuration, attributed to the parent's user" {
        val parent = "sbxattr"
        defineParent(parent, publishedOnly = true)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        val parentUserId = admin.userId
        val sandboxUserId = admin.postData(AEP.openSandbox, emptyMap())[UPF.userId].toOptLong()

        val saved = setBrand(admin, "From The Sandbox")
        saved[CPY.mode] shouldBe EDM.draft
        brand(sandboxOf(parent)) shouldBe "From The Sandbox"
        copyPublished(parent) shouldBe false

        // The parent's listing, read from the sandbox (which has none of its own), shows the edit by the parent's user.
        val entries = admin.getItems(CFEP.traits, mapOf(CFEP.name to CPY.copyConfigName))
        val fragment = entries.single { it[GE.traitId] == CCT.fragmentDef }
        fragment[GE.updatedBy].toOptLong() shouldBe parentUserId
        sandboxUserId shouldNotBe parentUserId
    }

    "a config write in the sandbox runs there while a published-only parent stays put, and publishing moves the parent" {
        val parent = "sbxbundle"
        defineParent(parent, publishedOnly = true)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        admin.postData(AEP.openSandbox, emptyMap())
        val fact = "${parent}Ready"
        val written = admin.postData(
            CFEP.bundleWrite,
            mapOf(
                CFEP.name to "extra", CFEP.namespaceField to clientNamespace(parent),
                CFEP.slots to mapOf(
                    CCT.cfactDef to listOf(mapOf(CCT.name to fact, CCT.group to "grp", CCT.description to "Ready.", CCT.toFrontend to false)),
                ),
            ),
        )
        written[CFEP.client] shouldBe parent
        written[CFEP.published] shouldBe false
        (fact in cfacts(sandboxOf(parent))) shouldBe true
        (fact in cfacts(parent)) shouldBe false

        admin.postData(CFEP.bundlePublish, mapOf(CFEP.name to "extra"))[CFEP.published] shouldBe true
        (fact in cfacts(parent)) shouldBe true
    }

    "the tier set from the sandbox is the parent's" {
        val parent = "sbxtier"
        defineParent(parent)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        admin.postData(AEP.openSandbox, emptyMap())
        admin.postData(CFEP.publishedOnly, mapOf(CFEP.publishedOnlyField to true))[CFEP.client] shouldBe parent
        svc.publishedOnly(cxt, parent) shouldBe true
    }

    // A client with a sandbox is published-only (issue #930): previewing there before going live is the point, so
    // nothing an editor saves is live in the client before it is published -- whatever its tier toggle says.
    "a published definition asking for a sandbox makes the client published-only, toggle or not" {
        val parent = "sbximply"
        defineParent(parent)
        svc.publishedOnly(cxt, parent) shouldBe true
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        val shipped = brand(parent)
        setBrand(admin, "Not Yet")[CPY.mode] shouldBe EDM.draft
        brand(parent) shouldBe shipped
        brand(sandboxOf(parent)) shouldBe "Not Yet"
        // Switching the toggle off does not take it off the tier: the answer says what the client runs.
        admin.postData(CFEP.publishedOnly, mapOf(CFEP.publishedOnlyField to false))[CFEP.publishedOnlyField] shouldBe true
    }

    // The published definition decides: one asking for a sandbox only in a draft has nothing published to protect.
    "a definition asking for a sandbox only in a draft leaves the client on its latest revision" {
        val parent = "sbxdraftdef"
        val setup = cxt.mkSubContext("setup", parent).also { it.userId = 9301L }
        svc.writeConfig(
            setup,
            gedraConfig(cxt, "main", clientNamespace(parent), parent) {
                defineClient(
                    ClientDef(
                        clientId = parent, name = "Client $parent", usageType = ClientUsageType.dev,
                        audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = true,
                    ),
                )
            },
        )
        svc.publishedOnly(cxt, parent) shouldBe false
        svc.publish(setup, GedraId.of(GedraConfigType.configDoc, parent, "main"))
        svc.publishedOnly(cxt, parent) shouldBe true
    }

    "a menu edit for a client with a sandbox stays a draft too" {
        val parent = "sbxmenu"
        defineParent(parent, publishedOnly = true)
        val admin = TestUser.create(cxt, "chief@$parent.test", level = ROLE.admin, userClient = parent)
        admin.postData(MNU.setPath, mapOf(COV.itemId to HMENU.docs, MNU.label to "Papers"))[CPY.mode] shouldBe EDM.draft
    }

    "a sandbox user whose admin role does not take effect is refused" {
        val parent = "sbxrefuse"
        defineParent(parent)
        val member = "member@$parent.test"
        TestUser.create(cxt, member, userClient = parent)
        val granted = TestUser.create(cxt, member, level = ROLE.admin, userClient = sandboxOf(parent))
        granted.expectError(EXC.notAuthorized, CPY.setPath, mapOf(COV.fileId to HFRAG.home, COV.namespaceField to "home", COV.key to "brand", COV.value to "x"))
        granted.expectError(EXC.notAuthorized, CFEP.bundles)
    }
})
