package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GDF
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GEP
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.string.shouldContain

/**
 * A trait keyed by its data type's `g-primaryKey` alone (issue #1521), the one way a key is declared: stored as the
 * client's configuration and reloaded from it, so the key rides in the stored data shape. A second year is a second
 * entry, and a repeated year is refused -- for a trait whose data is written inline and for one whose data is a `$ref`
 * to a type carrying the key.
 */
class TraitKeyTest : StringSpec({
    val cxt = TestInstances.default("traitKey1521")
    val client = "traitkey1521"

    val config = gedraConfig(cxt, "${client}cfg", clientNamespace(client), client) {
        defineClient(
            ClientDef(
                clientId = client, name = client, usageType = ClientUsageType.dev,
                audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
            ),
        )
        trait("YearlyEntry", "yearly", setOf(GedraDataType.formDoc), "One record a year.") {
            primaryKey("year")
            property("year", "The year.", required = true) { type = SCT.integer }
            property("note", "A note.")
        }
        // The key on a shared data type, which a trait refers to rather than declaring inline.
        type("BudgetData") {
            type = SCT.kObject
            primaryKey("year")
            property("year", "The year.", required = true) { type = SCT.integer }
            property("amount", "The amount.") { type = SCT.number }
        }
        trait("BudgetEntry", "budget", setOf(GedraDataType.formDoc), "BudgetData", "A budget a year.")
    }
    GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", client), config)
    GedraConfigReload.reloadClient(cxt, client)
    val user = TestUser.create(cxt, "keys@$client.test", userClient = client)

    fun entry(traitId: String, year: Any) = mapOf(GE.traitId to traitId, GE.data to mapOf("year" to year))

    for (traitId in listOf("yearly", "budget")) {
        "'$traitId': a second year is a second entry, and a repeated year is refused" {
            val created = user.postItem(GEP.formDocCreate, mapOf(GDF.entries to listOf(entry(traitId, 2024), entry(traitId, 2025))))
            created[GDF.entries].toJsonListOfMaps().map { it[GE.data].toJsonMapOrEmpty()["year"] } shouldContainExactlyInAnyOrder
                listOf(2024L, 2025L)
            user.expectError(EXC.badInput, GEP.formDocCreate, mapOf(GDF.entries to listOf(entry(traitId, 2024), entry(traitId, 2024))))
                .toString() shouldContain "share the primary key"
        }
    }

    // Judged where its types compile (issue #1521), so a stored declaration is held to it as source is: in a unit test,
    // stored configuration is strict, and the reload is refused.
    "a stored trait whose key names an optional field is refused" {
        val bad = "traitkeybad1521"
        val badConfig = gedraConfig(cxt, "${bad}cfg", clientNamespace(bad), bad) {
            defineClient(
                ClientDef(
                    clientId = bad, name = bad, usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
            trait("LooseEntry", "loose", setOf(GedraDataType.formDoc), "A loosely keyed record.") {
                primaryKey("year")
                property("year", "The year, but optional.") { type = SCT.integer }
            }
        }
        GedraConfigService.get(cxt).writeConfig(cxt.mkSubContext("setup", bad), badConfig)
        shouldThrow<KdrException> { GedraConfigReload.reloadClient(cxt, bad) }.message.orEmpty() shouldContain "not required"
    }
})
