package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.util.toJsonListOfMaps
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * The client summary listing's `allKnown` option (issue #828): every client this node knows of, each with its
 * status, so an administrator asking why a client is not working can see one that is not. Without it the listing
 * is as it was -- present clients only. One client per status, on a node where stored config is forgiven (as it is
 * everywhere but unit's strict default), so the dropped and refused ones load as issues rather than refusing.
 */
class ClientStatusListingTest : StringSpec({

    val cxt = TestInstances.storedConfigWarn("statusListing")
    val present = "status828ok"
    val notEnabled = "status828off"
    val dropped = "status828drop"
    val storedOnly = "status828bare"

    fun def(
        client: String,
        envs: Set<String> = setOf(ENV.unit, ENV.local),
        includedTraits: List<String> = emptyList(),
    ) = ClientDef(
        clientId = client, name = "Name of $client", usageType = ClientUsageType.production,
        audience = ClientAudience.customer, enabledEnvironments = envs, includedTraits = includedTraits,
    )

    fun store(client: String, build: GedraConfigBuilder.() -> Unit) {
        val config = gedraConfig(cxt, "main", clientNamespace(client), client, build = build)
        val writer = cxt.mkSubContext("status", client).also { it.userId = 8280L }
        GedraConfigService.get(cxt).writeConfig(writer, config)
        GedraConfigReload.reloadClient(cxt, client)
    }

    store(present) { defineClient(def(present)) }
    store(notEnabled) { defineClient(def(notEnabled, envs = setOf(ENV.local))) }
    // A customer production client may not include a functional group: the checks drop it.
    store(dropped) { defineClient(def(dropped, includedTraits = listOf(CLD.allGlobal))) }
    // Stored configuration that declares no client at all.
    store(storedOnly) {
        trait("BareEntry", "status828Bare", setOf(GedraDataType.formDoc), "A trait with no client.") {
            property("text", "Text.")
        }
    }

    val admin = TestUser.createFullAdmin(cxt, "chief@status828.test")
    fun rows(allKnown: Boolean?): Map<String, Map<String, Any?>> =
        admin.getItems(ADEP.clientSummaries, allKnown?.let { mapOf(CLD.allKnown to it) })
            .associateBy { it[CLD.clientId] as String }

    "without the option, only present clients are listed" {
        val listed = rows(allKnown = null)
        listed.getValue(present)[CLD.status] shouldBe ClientStatus.present.name
        listed.containsKey(notEnabled) shouldBe false
        listed.containsKey(dropped) shouldBe false
        listed.containsKey(storedOnly) shouldBe false
    }

    "with it, every known client is listed with its status" {
        val listed = rows(allKnown = true)
        listed.getValue(present)[CLD.status] shouldBe ClientStatus.present.name

        val off = listed.getValue(notEnabled)
        off[CLD.status] shouldBe ClientStatus.notEnabled.name
        off[CLD.name] shouldBe "Name of $notEnabled"
        off[CLD.traitIds].toJsonListOfMaps().shouldBeEmpty()

        val drop = listed.getValue(dropped)
        drop[CLD.status] shouldBe ClientStatus.dropped.name
        drop[CLD.name] shouldBe "Name of $dropped"
        drop[CLD.issues].toJsonListOfMaps().shouldNotBeEmpty()

        val bare = listed.getValue(storedOnly)
        bare[CLD.status] shouldBe ClientStatus.storedOnly.name
        bare[CLD.name] shouldBe storedOnly
    }

    "the administrators' overview lists the same clients, each defined in stored configuration (#904)" {
        val overview = admin.getItems(UADEP.clientsOverview).associateBy { it[CLD.clientId] as String }
        for ((client, status) in listOf(present to ClientStatus.present, notEnabled to ClientStatus.notEnabled, dropped to ClientStatus.dropped)) {
            val row = overview.getValue(client)
            row[CLD.status] shouldBe status.name
            // Declared by a stored configuration, which this node loaded: one for each.
            row[CLD.origin] shouldBe GedraConfigOrigin.stored.name
            row[CLD.storedConfigs] shouldBe 1L
        }
        // Nothing declares the bare client; it is known from stored configuration alone.
        val bare = overview.getValue(storedOnly)
        bare[CLD.status] shouldBe ClientStatus.storedOnly.name
        bare[CLD.origin] shouldBe GedraConfigOrigin.stored.name
        // The count is of loaded configurations, not definitions: the trait-only one is what makes it known.
        bare[CLD.storedConfigs] shouldBe 1L
        bare[CLD.workflowCount] shouldBe 0L
    }
})
