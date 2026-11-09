package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientStatus
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigBuilder
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.GedraConfigReload
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UADEP
import com.dynamicruntime.common.util.toJsonListOfMaps
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The client registry as a booted instance sees it, and the administrative listing over it (issue #343).
 *
 * The case worth the setup is the **absent client**: one defined in source code and *not enabled here*. It is
 * precisely a client that is known and not present, and it is reachable today with one instance, no database
 * and no configuration reload -- where the disappearance it stands in for (a client un-enabled while a
 * deployment runs) is not. The code cannot tell the two apart, which is the point.
 *
 * [OffsiteClientComponent] is registered VM-globally, as every component is, and gates itself on a flag so
 * that instances booted by other tests are unaffected -- the second instrument the design notes for a test
 * needing two populations rather than one.
 *
 * Also, since issue #1739, the tests that were `ClientStatusListingTest`, each kept whole below.
 */
class ClientRegistryTest : StringSpec({
    include(clientRegistryTests)
    include(clientStatusListingTests)
})

/** This file's own tests, as the class describes them. */
private val clientRegistryTests = stringSpec {

    // Its own instance (issue #1075): OffsiteClientComponent, a fixture component no shared entry has; a case lists
    // every client the instance carries.
    fun boot(name: String): KdrCxt {
        return Startup.mkTestBootCxt("clients", name, mapOf(OffsiteClientComponent.loadFlag.name to "true"), additionalComponents = listOf(OffsiteClientComponent()))
    }

    "the clients every deployment has are present, with what they declared" {
        val service = ClientService.get(boot("clientRegistryTest"))
        service.presentClients.map { it.clientId } shouldContainExactly listOf(CL.hub, CL.public)
        val hub = service.present(CL.hub).shouldNotBeNull()
        hub.usageType shouldBe ClientUsageType.production
        hub.audience shouldBe ClientAudience.internal
        hub.includedTraits shouldContain CLD.allGlobal
    }

    // Known and not present are different questions, and the registry answers them separately: an
    // administrator asking why a client is not working has to be able to see a client that is not working.
    "a client enabled only in local is known here, and not present" {
        val service = ClientService.get(boot("clientRegistryTest"))
        service.known(OffsiteClientComponent.clientId).shouldNotBeNull()
        service.isPresent(OffsiteClientComponent.clientId) shouldBe false
        service.present(OffsiteClientComponent.clientId) shouldBe null
        service.presentClients.map { it.clientId } shouldNotContain OffsiteClientComponent.clientId
    }

    "a full-scope administrator is served the present clients, and only those" {
        val cxt = boot("clientRegistryTest")
        val admin = TestUser.createFullAdmin(cxt, "client-admin@example.com")
        val listed = admin.getItems(ADEP.clients)
        listed.map { it[CLD.clientId].toOptStr() } shouldContainExactly listOf(CL.hub, CL.public)
        val hub = listed.first { it[CLD.clientId].toOptStr() == CL.hub }
        hub[CLD.name].toOptStr() shouldBe "Hub"
        hub[CLD.usageType].toOptStr() shouldBe ClientUsageType.production.name
        hub[CLD.audience].toOptStr() shouldBe ClientAudience.internal.name
    }

    // The `admin` section takes the level *and* the capability. A client-scoped administrator does not get a
    // narrowed version of this listing -- the only client they could be shown is the one they already know
    // they are in -- so they get nothing.
    "an administrator without allClients is refused" {
        val cxt = boot("clientRegistryTest")
        val scoped = TestUser.create(cxt, "client-scoped-admin@example.com", level = ROLE.admin)
        scoped.selfRoles() shouldNotContain ROLE.allClients
        scoped.expectError(EXC.notAuthorized, ADEP.clients)
    }
}

/**
 * The client summary listing's `allKnown` option (issue #828): every client this node knows of, each with its
 * status, so an administrator asking why a client is not working can see one that is not. Without it the listing
 * is as it was -- present clients only. One client per status, on a node where stored config is forgiven (as it is
 * everywhere but unit's strict default), so the dropped and refused ones load as issues rather than refusing.
 */
private val clientStatusListingTests = stringSpec {

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
}

// Declared with what was `ClientRegistryTest`.
/**
 * A component contributing one client enabled in `local` only, so a `unit` boot has a client it knows and does
 * not carry.
 *
 * Self-gating on [loadFlag] the way `SampleComponent` gates on `KDR_LOAD_SAMPLE`: components are registered
 * once per VM and shared by every instance, so a fixture that loaded unconditionally would put its client into
 * every other test's boot.
 */
class OffsiteClientComponent : ComponentDefinition {
    override val providerName: String = "offsiteClientFixture"

    override fun isLoaded(cxt: KdrCxt): Boolean = cxt.getEnvBool(loadFlag) == true

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "offsiteClient", clientNamespace(clientId), clientId) {
            defineClient(
                ClientDef(
                    clientId = clientId,
                    name = "Offsite",
                    description = "A client this node knows about and does not carry.",
                    usageType = ClientUsageType.dev,
                    audience = ClientAudience.customer,
                    enabledEnvironments = setOf(ENV.local),
                ),
            )
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        val loadFlag = EnvVarDef(
            "KDR_LOAD_OFFSITE_CLIENT", group = ENVGRP.application, defaultDoc = "off",
            description = "Test-only flag that loads this fixture component regardless of environment.",
        )
        const val clientId = "offsite"
    }
}
