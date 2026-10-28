package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCH
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonListOfStrings
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

/**
 * The bundle editor page's read (issue #1436): every configuration a client stores, its latest revision with the
 * published one beside it when the latest is a draft over it, and each slot's declared shape; and a bundle read of the
 * published revision alone. A client per case, with a sandbox, so a change is a draft over what is published.
 */
class ConfigEditorReadTest : StringSpec({
    val cxt = TestInstances.default("configEditor1436")
    val svc = GedraConfigService.get(cxt)

    fun write(client: String, name: String, build: GedraConfigBuilder.() -> Unit) =
        svc.writeConfig(cxt.mkSubContext("setup", client).also { it.userId = 14360L }, gedraConfig(cxt, name, clientNamespace(client), client, build = build))

    /** [client], with a sandbox: its definition in `main`, a trait in `traits`, both published. Returns its administrator. */
    fun defineClient(client: String): TestUser {
        write(client, "main") {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                    enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = true,
                ),
            )
        }
        write(client, "traits") {
            trait("MemoEntry", "memo", setOf(GedraDataType.formDoc), "A memo.") { property("text", "What it says.") }
        }
        svc.publish(cxt.mkSubContext("setup", client).also { it.userId = 14360L }, listOf("main", "traits").map { GedraId.of(GedraConfigType.configDoc, client, it) })
        GedraConfigReload.reloadClient(cxt, client)
        return TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
    }

    /** Changes `memo`'s description in a draft over the published `traits`. */
    fun draftMemo(admin: TestUser) {
        val memo = admin.getItem(CFEP.bundle, mapOf(CFEP.name to "traits"))[CFEP.slots].toJsonMapOrEmpty()[CCT.traitDef]
            .toJsonListOfMaps().single()
        admin.postData(
            CFEP.bundlePatch,
            mapOf(
                CFEP.configs to listOf(
                    mapOf(
                        CFEP.name to "traits",
                        CFEP.edits to listOf(
                            mapOf(CFEP.slot to CCT.traitDef, GED.action to GedraEditAction.addOrReplace.name, GE.data to memo + (CCT.description to "Drafted.")),
                        ),
                    ),
                ),
            ),
        )
    }

    fun memoDescription(bundle: Map<String, Any?>): Any? =
        bundle[CFEP.slots].toJsonMapOrEmpty()[CCT.traitDef].toJsonListOfMaps().single()[CCT.description]

    "the editor answers each configuration's latest revision, the published one beside a draft, and every slot's shape" {
        val admin = defineClient("editor1436")
        draftMemo(admin)

        val editor = admin.getItem(CFEP.editor)
        val byName = editor[CFEP.configs].toJsonListOfMaps().associateBy { it[CFEP.name] }
        byName.keys shouldBe setOf("main", "traits")
        // `traits` has a draft over its published revision: both are there.
        val traits = byName.getValue("traits")
        traits[CFEP.revisionLatest].toJsonMapOrEmpty().let {
            it[CFEP.version] shouldBe 2L
            it[CFEP.published] shouldBe false
            memoDescription(it) shouldBe "Drafted."
        }
        traits[CFEP.revisionPublished].toJsonMapOrEmpty().let {
            it[CFEP.version] shouldBe 1L
            memoDescription(it) shouldBe "A memo."
        }
        // `main` is published as it stands: its latest is the published revision, so nothing stands beside it.
        (CFEP.revisionPublished in byName.getValue("main")) shouldBe false

        // Every slot, its entry type and key fields, with the types behind them.
        val slotTypes = editor[CFEP.slotTypes].toJsonMapOrEmpty()
        slotTypes.keys shouldContainAll listOf(CCT.clientDef, CCT.traitDef, CCT.workflowDef, CCT.schemaDef, CCT.fileRefDef)
        val defs = editor[SCH.dDefs].toJsonMapOrEmpty()
        slotTypes.values.map { it.toString() }.filterNot { it in defs } shouldBe emptyList()
        editor[CFEP.slotKeys].toJsonMapOrEmpty()[CCT.traitDef].toJsonListOfStrings() shouldBe listOf(CCT.traitId)
        editor[CFEP.slotKeys].toJsonMapOrEmpty()[CCT.clientDef].toJsonListOfStrings() shouldBe emptyList()
    }

    "a bundle read answers the published revision when asked, and says when there is none" {
        val admin = defineClient("published1436")
        draftMemo(admin)
        memoDescription(admin.getItem(CFEP.bundle, mapOf(CFEP.name to "traits"))) shouldBe "Drafted."
        memoDescription(admin.getItem(CFEP.bundle, mapOf(CFEP.name to "traits", CFEP.revision to CFEP.revisionPublished))) shouldBe "A memo."

        // A configuration made since, and never published, has no published revision.
        admin.postData(
            CFEP.bundleWrite,
            mapOf(
                CFEP.name to "extra", CFEP.namespaceField to clientNamespace("published1436"),
                CFEP.slots to mapOf(CCT.cfactDef to listOf(mapOf(CCT.name to "ready", CCT.group to "flags", CCT.description to "Ready."))),
            ),
        )
        admin.expectError(EXC.notFound, CFEP.bundle, args = mapOf(CFEP.name to "extra", CFEP.revision to CFEP.revisionPublished))
    }

    "an administrator of every client reads any client's editor" {
        val client = "across1436"
        defineClient(client)
        val operator = TestUser.createFullAdmin(cxt, "editor@op1436.test")
        operator.getItem(ACEP.editor, mapOf(CFEP.client to client))[CFEP.configs].toJsonListOfMaps()
            .map { it[CFEP.name] }.toSet() shouldBe setOf("main", "traits")
    }
})
