package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.naming.clientNamespace
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.schema.SchLayoutMode
import com.dynamicruntime.common.schema.layout
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A client that alters a type may add to what it requires, and a global **authoritative** layout it inherits could
 * then omit a field this client's form must show -- a form nobody can submit (issue #811). The inherited layout is
 * checked against the client's own type: outside production that refuses the boot, as any faulty source config
 * does; in production the client renders the type without a layout, and says why.
 */
class InheritedLayoutRequiredTest : StringSpec({

    val overlay = mapOf(InheritedLayoutFixture.loadFlag.name to "true")

    "outside production, an inherited authoritative layout omitting a field the client requires refuses the boot" {
        val message = shouldThrow<KdrException> {
            Startup.mkTestBootCxt(
                "inheritedLayout", "inheritedLayoutTest", overlay, listOf(InheritedLayoutFixture()),
            )
        }.fullMessage()
        message shouldContain "client '${InheritedLayoutFixture.client}'"
        message shouldContain "omits 'nick'"
    }

    "in production, that client renders the type without a layout, and global keeps its own" {
        val prod: KdrCxt = Startup.mkBootCxt(
            "inheritedLayoutProd", "inheritedLayoutProdTest",
            overlay + mapOf(ACFG.env to ENV.prod, ACFG.isTestInstance to false, ACFG.inMemoryOnly to true),
            listOf(InheritedLayoutFixture()),
        )
        val schema = SchemaService.get(prod)
        val type = InheritedLayoutFixture.profileType
        schema.storeFor(null).layouts[type].shouldNotBeNull().mode shouldBe SchLayoutMode.authoritative
        schema.storeFor(InheritedLayoutFixture.client).layouts[type].shouldBeNull()
        // The alteration itself stands: the client still requires what it said it does.
        schema.storeFor(InheritedLayoutFixture.client).types.getValue(type).required shouldBe setOf("name", "nick")
    }
})

/** A global type with an authoritative layout listing only `name`, and a client alteration that requires `nick`. */
class InheritedLayoutFixture : ComponentDefinition {
    override val providerName: String = "inheritedLayoutFixture"

    /** The fixture's own owner root (issue #950). */
    override val ownerRoot: String = baseNamespace

    override fun isLoaded(cxt: KdrCxt): Boolean = cxt.getEnvBool(loadFlag) == true

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "inheritedLayoutBase", baseNamespace, GID.globalClient) {
            type("Profile") {
                type = SCT.kObject
                property("name", "The name.", required = true)
                property("nick", "A nickname.")
                layout(mode = SchLayoutMode.authoritative) { field("name", label = "Name") }
            }
        },
        gedraConfig(cxt, "inheritedLayoutClient", clientNamespace(client), client) {
            defineClient(
                ClientDef(
                    clientId = client, name = "Inherited layout", usageType = ClientUsageType.dev,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local, ENV.prod),
                ),
            )
            // Narrowing rule 3, more required: legitimate on its own, and it leaves the inherited layout short.
            type(profileType) {
                type = SCT.kObject
                property("name", "The name.", required = true)
                property("nick", "A nickname.", required = true)
            }
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        val loadFlag = EnvVarDef(
            "KDR_LOAD_INHERITED_LAYOUT_FIXTURE", group = ENVGRP.application, defaultDoc = "off",
            description = "Test-only flag that loads this fixture component regardless of environment.",
        )
        const val baseNamespace = "inheritedlayout"
        const val client = "inheritedlayoutclient"
        const val profileType = "$baseNamespace.Profile"
    }
}
