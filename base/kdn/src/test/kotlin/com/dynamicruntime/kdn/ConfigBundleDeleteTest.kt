package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GED
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraEditAction
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Deleting a configuration (issue #1461): only an empty one -- its latest and its published revision both holding no
 * entries -- and only as it was read. A deleted configuration is no longer listed or read, and one made again under its
 * name starts past every revision it had. A client per case.
 */
class ConfigBundleDeleteTest : StringSpec({
    val cxt = TestInstances.default("configDelete1461")

    /** [client] defined in stored configuration, with a sandbox when [sandbox]; returns its administrator. */
    fun defineClient(client: String, sandbox: Boolean = false): TestUser {
        GedraConfigService.get(cxt).writeConfig(
            cxt.mkSubContext("setup", client).also { it.userId = 14610L },
            gedraConfig(cxt, "main", clientNamespace(client), client) {
                defineClient(
                    ClientDef(
                        clientId = client, name = client, usageType = ClientUsageType.dev, audience = ClientAudience.internal,
                        enabledEnvironments = setOf(ENV.unit, ENV.local), sandbox = sandbox,
                    ),
                )
            },
        )
        if (sandbox) {
            GedraConfigService.get(cxt).publish(
                cxt.mkSubContext("setup", client).also { it.userId = 14610L },
                listOf(GedraId.of(GedraConfigType.configDoc, client, "main")),
            )
        }
        GedraConfigReload.reloadClient(cxt, client)
        return TestUser.create(cxt, "chief@$client.test", level = ROLE.admin, userClient = client)
    }

    val ready = mapOf(CCT.name to "ready", CCT.group to "flags", CCT.description to "Ready.")

    fun TestUser.writeExtra(client: String) = postData(
        CFEP.bundleWrite,
        mapOf(CFEP.name to "extra", CFEP.namespaceField to clientNamespace(client), CFEP.slots to mapOf(CCT.cfactDef to listOf(ready))),
    )

    fun TestUser.emptyExtra() = postData(
        CFEP.bundlePatch,
        mapOf(
            CFEP.configs to listOf(
                mapOf(CFEP.name to "extra", CFEP.edits to listOf(mapOf(CFEP.slot to CCT.cfactDef, GED.action to GedraEditAction.deleteOrNoOp.name, GE.data to mapOf(CCT.name to "ready")))),
            ),
        ),
    )

    fun TestUser.extra(): Map<String, Any?> = getItem(CFEP.bundle, mapOf(CFEP.name to "extra"))

    fun delete(name: String, read: Any?) = mapOf(CFEP.name to name, CFEP.expectUpdatedAt to read, CFEP.reloadField to true)

    fun TestUser.listed(): Set<Any?> = getItems(CFEP.bundles).map { it[CFEP.name] }.toSet()

    /** The listing's row for `extra`: what the publishing table shows and deletes from. */
    fun TestUser.extraListed(): Map<String, Any?> = getItems(CFEP.bundles).first { it[CFEP.name] == "extra" }

    /** A listing row's counts, latest then published, as `slots/entries`; `-` where no revision is published. */
    fun counts(row: Map<String, Any?>): String =
        "${row[CFEP.slotCount]}/${row[CFEP.entryCount]} ${row[CFEP.publishedSlotCount] ?: "-"}/${row[CFEP.publishedEntryCount] ?: "-"}"

    "an empty configuration is deleted as it was read, and one made again under its name starts past it" {
        val admin = defineClient("del1461")
        admin.writeExtra("del1461")
        counts(admin.extraListed()) shouldBe "1/1 -/-"
        // Holding an entry, it is refused.
        admin.expectError(EXC.badInput, CFEP.bundleDelete, delete("extra", admin.extra()[CFEP.updatedAt]))[EP.errorMessage]
            .toString() shouldContain "latest revision (version 1) holds 1 entry"

        val beforeEmptied = admin.extra()[CFEP.updatedAt]
        admin.emptyExtra()
        // Read before it was emptied, it is refused as stale.
        admin.expectError(EXC.conflict, CFEP.bundleDelete, delete("extra", beforeEmptied))[EP.errorCode] shouldBe CFEP.staleCode

        // Empty, and deleted as the listing read it.
        val listed = admin.extraListed()
        counts(listed) shouldBe "0/0 -/-"
        admin.postData(CFEP.bundleDelete, delete("extra", listed[CFEP.updatedAt]))[CFEP.version] shouldBe 1L
        // Gone from the listing, the reads, and the editor.
        admin.listed() shouldBe setOf("main")
        admin.expectError(EXC.notFound, CFEP.bundle, args = mapOf(CFEP.name to "extra"))
        admin.getItem(CFEP.editor)[CFEP.configs].toJsonListOfMaps().map { it[CFEP.name] } shouldBe listOf("main")
        admin.expectError(EXC.notFound, CFEP.bundleDelete, delete("extra", beforeEmptied))

        // Made again, it is a new configuration whose first revision is past the one deleted.
        admin.writeExtra("del1461")[CFEP.version] shouldBe 2L
        admin.listed() shouldBe setOf("main", "extra")
    }

    "a published configuration is emptied, the empty revision published, and then deleted" {
        val admin = defineClient("pub1461", sandbox = true)
        admin.writeExtra("pub1461")
        admin.postData(CFEP.bundlePublish, mapOf(CFEP.names to listOf("extra")))
        counts(admin.extraListed()) shouldBe "1/1 1/1"
        admin.emptyExtra()
        // The draft is empty; what is published is not.
        counts(admin.extraListed()) shouldBe "0/0 1/1"
        admin.expectError(EXC.badInput, CFEP.bundleDelete, delete("extra", admin.extra()[CFEP.updatedAt]))[EP.errorMessage]
            .toString() shouldContain "published revision (version 1) holds 1 entry. Publish the emptied revision first"

        admin.postData(CFEP.bundlePublish, mapOf(CFEP.names to listOf("extra")))
        counts(admin.extraListed()) shouldBe "0/0 0/0"
        admin.postData(CFEP.bundleDelete, delete("extra", admin.extra()[CFEP.updatedAt]))[CFEP.reloadField].toJsonMapOrEmpty().isNotEmpty() shouldBe true
        admin.listed() shouldBe setOf("main")
    }

    "an administrator of every client deletes a named client's empty configuration" {
        val client = "across1461"
        val admin = defineClient(client)
        admin.writeExtra(client)
        admin.emptyExtra()
        val operator = TestUser.createFullAdmin(cxt, "ops@across1461.test")
        val read = operator.getItem(ACEP.bundle, mapOf(CFEP.client to client, CFEP.name to "extra"))[CFEP.updatedAt]
        operator.postData(ACEP.bundleDelete, delete("extra", read) + (CFEP.client to client))
        admin.listed() shouldBe setOf("main")
    }
})
