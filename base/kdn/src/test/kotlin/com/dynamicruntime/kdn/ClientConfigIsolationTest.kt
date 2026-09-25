package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.endpoint.EP
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ACEP
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientConfigIssues
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GC
import com.dynamicruntime.common.gedra.GCEL
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GCT
import com.dynamicruntime.common.gedra.GD
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.StateTraitClass
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.gedra.gedraConfigTopic
import com.dynamicruntime.common.sql.PF
import com.dynamicruntime.common.sql.SqlStmtUtil
import com.dynamicruntime.common.sql.SqlTopicService
import com.dynamicruntime.common.sql.SqlTopicUtil
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.user.TestUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A client's configuration reaches no other client's (issue #873). Two ways it could, closed here:
 *
 * - **State traits.** State is global -- one set, declared by components, the same for every client -- so a
 *   client's config may not declare one: it would join every client's state, and point the global state union at a
 *   type only that client has. A source config's is refused outside production and dropped in it; a stored config
 *   has no slot for one, and a row still carrying the old slot loads without it.
 * - **A `global`-owned stored row.** The write refuses one; a load, which a restore or a hand edit can reach without
 *   a write, now drops it too rather than folding it into the configuration every client shares.
 */
class ClientConfigIsolationTest : StringSpec({

    val warn = mapOf(GCFG.storedCheckEnvVar.name to BootCheckMode.warn.name)

    "outside production a client's source config declaring a state trait refuses the boot" {
        val failure = shouldThrow<KdrException> {
            Startup.mkTestBootCxt(
                "isoStateUnit", "isoStateUnitTest", additionalComponents = listOf(ClientStateComponent()),
            )
        }
        failure.message.shouldNotBeNull() shouldContain "state trait '${ClientStateComponent.stateTraitId}'"
    }

    "in production a client's state trait is dropped, and the rest of its config stands" {
        val cxt: KdrCxt = Startup.mkBootCxt(
            "isoStateProd", "isoStateProdTest",
            mapOf(ACFG.env to ENV.prod, ACFG.isTestInstance to false, ACFG.inMemoryOnly to true),
            additionalComponents = listOf(ClientStateComponent()),
        )
        val schema = SchemaService.get(cxt)
        schema.gedraStateTraits().map { it.traitId }.contains(ClientStateComponent.stateTraitId) shouldBe false
        schema.storeFor(ClientStateComponent.client).types
            .containsKey("${ClientStateComponent.namespace}.IsoNoteEntry") shouldBe true
        val issue = ClientConfigIssues.get(cxt).issuesFor(ClientStateComponent.client).single()
        issue.elementKind shouldBe GCEL.type
        issue.elementId shouldBe "${ClientStateComponent.namespace}.IsoStateEntry"
    }

    "a bundle write naming a state-trait slot is refused as an unknown slot" {
        val cxt = Startup.mkTestBootCxt("isoSlot", "isoSlotTest")
        val admin = TestUser.createFullAdmin(cxt, "chief@iso873.test")
        val refused = admin.expectError(
            EXC.badInput, ACEP.bundleWrite,
            mapOf(
                CFEP.client to "iso873slot", CFEP.name to "main", CFEP.namespaceField to "iso873slotconfig",
                CFEP.slots to mapOf("stateTraitDef" to listOf(mapOf("traitId" to "iso873State"))),
            ),
        )
        refused[EP.errorMessage].toString() shouldContain "Unknown config slot(s): stateTraitDef"
    }

    // Both rows are planted with SQL, as a restore or a hand edit would leave them, and read by a restart -- the
    // boot load reads the table directly.
    "a load drops a global-owned row, and ignores a slot it does not read, saying so" {
        val db = mapOf("KDR_DB_NAME" to "clientIsolation_restart", "KDR_LOAD_STORED_CONFIG" to "true")
        val client = "iso873"
        val first = Startup.mkTestBootCxt("iso1", "clientIsolation1", db + warn)
        val writer = first.mkSubContext("isoWrite", client).also { it.userId = 8730L }
        val svc = GedraConfigService.get(first)
        val main = svc.writeConfig(writer, isoConfig(first, client, "main") {
            defineClient(
                ClientDef(
                    clientId = client, name = client, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            cfact("iso873Own", "iso", "The client's own.")
        })
        val rogue = svc.writeConfig(writer, isoConfig(first, client, "rogue") { cfact("iso873Rogue", "iso", "Rogue.") })

        val sqlCxt = SqlTopicService.mkSqlCxt(first, gedraConfigTopic)
        val table = first.getSchema().tables.getValue(GCT.gedraConfig)
        sqlCxt.sqlDb.withSession(first) {
            // The client's row gains a retired state-trait slot.
            val plantSlot = SqlStmtUtil.prepareSql(
                sqlCxt, "plantIsoSlot", table.columns,
                "update t:${GCT.gedraConfig} set c:${GC.data} = :${GC.data}, c:${PF.updatedAt} = :${PF.updatedAt} " +
                    "where c:${GC.gedraId} = :${GC.gedraId}",
            )
            val stateEntry = mapOf(GE.traitId to "stateTraitDef", GE.data to mapOf("traitId" to "iso873State"))
            sqlCxt.sqlDb.executeStatement(
                first, plantSlot,
                mapOf(
                    GC.gedraId to main.gedraId.fullId,
                    GC.data to mapOf(GD.entries to main.entries + stateEntry, GC.namespace to "${client}config"),
                    PF.updatedAt to SqlTopicUtil.nextUpdatedAt(first, main.updatedAt),
                ),
            ) shouldBe 1
            // The 'rogue' row is moved to the runtime's own client.
            val globalClass = GedraId.of(GedraConfigType.configDoc, GID.globalClient, "rogue")
            val plantOwner = SqlStmtUtil.prepareSql(
                sqlCxt, "plantIsoOwner", table.columns,
                "update t:${GCT.gedraConfig} set c:${GC.gedraId} = :newId, c:${GC.configId} = :${GC.configId}, " +
                    "c:${PF.client} = :${PF.client} where c:${GC.gedraId} = :${GC.gedraId}",
            )
            sqlCxt.sqlDb.executeStatement(
                first, plantOwner,
                mapOf(
                    GC.gedraId to rogue.gedraId.fullId, "newId" to globalClass.withRevision(1).fullId,
                    GC.configId to globalClass.fullId, PF.client to GID.globalClient,
                ),
            ) shouldBe 1
        }

        val restarted = Startup.mkTestBootCxt("iso2", "clientIsolation2", db + warn)
        val issues = ClientConfigIssues.get(restarted)
        issues.issuesFor(client).single().message shouldContain "stateTraitDef"
        SchemaService.get(restarted).cfactsFor(client).names.contains("iso873Own") shouldBe true
        issues.issuesFor(GID.globalClient).single { it.elementId == "gc.cd.global.rogue" }.message shouldContain
            "owned by the '${GID.globalClient}' client"
        SchemaService.get(restarted).cfactsFor(null).names.contains("iso873Rogue") shouldBe false
    }
})

private fun isoConfig(cxt: KdrCxt, client: String, name: String, build: GedraConfigBuilder.() -> Unit): GedraConfig =
    gedraConfig(cxt, name, "${client}config", client, build = build)

/** A component contributing a client-owned config that declares a state trait beside a data trait. */
class ClientStateComponent : ComponentDefinition {
    override val providerName: String = "clientStateFixture"

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "isoState", namespace, client) {
            trait("IsoNoteEntry", "iso873Note", setOf(GedraDataType.formDoc), "A note.") { property("text", "Text.") }
            stateTrait("IsoStateEntry", stateTraitId, setOf(GedraDataType.formDoc), StateTraitClass.asserted) {
                property("by", "Who.")
            }
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        const val client = "iso873src"
        const val namespace = "iso873srcconfig"
        const val stateTraitId = "iso873SrcState"
    }
}
