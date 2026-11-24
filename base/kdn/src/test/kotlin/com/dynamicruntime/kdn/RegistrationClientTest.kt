package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.user.AuthUserRow
import com.dynamicruntime.common.user.REGC
import com.dynamicruntime.common.user.TestUser
import com.dynamicruntime.common.user.UserService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The registration client (issue #1867): a deployment naming the one client every new user lands in, and the role a
 * person registering there gets. A code registration, an auto-admin address, and a test's provisioning each land
 * there; unset, nothing changes. The env gate's Google sign-in is in `EnvGateTest`.
 */
class RegistrationClientTest : StringSpec({
    val adminDomain = "reg1867.test"

    /** A node naming [RegistrationClientComponent.clientId] its registration client, with the role `lead`. */
    val cxt = Startup.mkTestBootCxt(
        "regClient", "registrationClient",
        mapOf(REGC.client to RegistrationClientComponent.clientId, REGC.role to "lead", ACFG.adminEmailDomain to adminDomain),
        additionalComponents = listOf(RegistrationClientComponent()),
    )

    fun row(cxt: KdrCxt, userId: Long): AuthUserRow = UserService.get(cxt).queryByUserId(cxt, userId).shouldNotBeNull()

    "a visitor registering by code lands in the registration client with its role" {
        val visitor = TestUser.register(cxt, "visitor@leads1867.test", "visitor1867")
        val user = row(cxt, visitor.userId)
        user.client shouldBe RegistrationClientComponent.clientId
        user.roles shouldContainExactlyInAnyOrder listOf(ROLE.user, "lead")
    }

    "an auto-admin address lands there too, with the auto-admin roles and not the registration role" {
        val admin = TestUser.register(cxt, "boss@$adminDomain", "boss1867")
        val user = row(cxt, admin.userId)
        user.client shouldBe RegistrationClientComponent.clientId
        user.roles shouldContainExactlyInAnyOrder listOf(ROLE.user, ROLE.admin, ROLE.allClients)
    }

    "an administrator provisioned with no client named lands there as well" {
        val made = TestUser.create(cxt, "made@leads1867.test", level = ROLE.admin)
        row(cxt, made.userId).client shouldBe RegistrationClientComponent.clientId
    }

    "unset, a registration lands in public as before" {
        val plain = TestInstances.default("regUnset")
        val visitor = TestUser.register(plain, "visitor@unset1867.test", "unset1867")
        val user = row(plain, visitor.userId)
        user.client shouldBe CL.public
        user.roles shouldContainExactlyInAnyOrder listOf(ROLE.user, ROLE.admin)
    }

    "the boot refuses a registration client that is not present, public or hub, and a role it may not grant" {
        fun refusal(name: String, settings: Map<String, Any?>): String = shouldThrow<KdrException> {
            Startup.mkTestBootCxt(name, "${name}Inst", settings, additionalComponents = listOf(RegistrationClientComponent()))
        }.message.orEmpty()

        refusal("regMissing", mapOf(REGC.client to "nowhere1867")) shouldContain "no config declares it"
        refusal("regPublic", mapOf(REGC.client to CL.public)) shouldContain "one of the clients it replaces"
        refusal("regHub", mapOf(REGC.client to CL.hub)) shouldContain "one of the clients it replaces"
        val leads = RegistrationClientComponent.clientId
        refusal("regAdmin", mapOf(REGC.client to leads, REGC.role to ROLE.admin)) shouldContain "may not grant"
        refusal("regAll", mapOf(REGC.client to leads, REGC.role to ROLE.allClients)) shouldContain "may not grant"
        refusal("regEditor", mapOf(REGC.client to leads, REGC.role to ROLE.editor)) shouldContain "may not grant"
        refusal("regName", mapOf(REGC.client to leads, REGC.role to "a lead")) shouldContain "not a role's name"
        refusal("regRoleAlone", mapOf(REGC.role to "lead")) shouldContain "would mean nothing"
    }
})

/** A component declaring one client present everywhere, for a node to name as its registration client. */
class RegistrationClientComponent : ComponentDefinition {
    override val providerName: String = "registrationClientFixture"

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "leadsClient", clientNamespace(clientId), clientId) {
            defineClient(
                ClientDef(
                    clientId = clientId,
                    name = "Leads",
                    description = "The one client a deployment's new users land in.",
                    usageType = ClientUsageType.production,
                    audience = ClientAudience.customer,
                    enabledEnvironments = ENV.names.toSet(),
                ),
            )
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        const val clientId = "leads1867"
    }
}
