package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientService
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.CLD
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.user.ADEP
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.util.toOptStr
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
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
 */
class ClientRegistryTest : StringSpec({

    fun boot(name: String): KdrCxt {
        InstanceRegistry.register(listOf(OffsiteClientComponent()))
        return Startup.mkTestBootCxt("clients", name, mapOf(OffsiteClientComponent.loadFlag.name to "true"))
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
})

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
        gedraConfig(cxt, "offsiteClient", "offsiteconfig", clientId) {
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
