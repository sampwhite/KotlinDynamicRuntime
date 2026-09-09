package com.dynamicruntime.kdn

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.EXC
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.CFEP
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.GedraConfigService
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GedraId
import com.dynamicruntime.common.gedra.GedraConfigType
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.http.request.ROLE
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.user.TestUser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * The configuration protection tiers (issue #617): what the runtime **consumes** by tier -- free takes the
 * latest revision, published-only takes the latest published one -- and that a `staticConfig` client refuses
 * the toggle while still consuming published-only. Verified through the tier-aware consumption read
 * (`currentConfigs`), which the reload loads from; its own client per case.
 */
class GedraConfigTierTest : StringSpec({
    val cxt = Startup.mkTestBootCxt("gedraCfgTier", "gedraCfgTierTest")

    fun svc(): GedraConfigService = GedraConfigService.get(cxt)
    fun asClient(client: String): KdrCxt = cxt.mkSubContext("tier", client).also { it.userId = 12000L }

    /** A config for [client] declaring the named traits; `impliedDelete` default replaces the whole config. */
    fun write(client: String, vararg traits: String): GedraConfig {
        val config = gedraConfig(cxt, "${client}cfg", "${client}config", client) {
            for (t in traits) {
                trait("${t}Entry", t, setOf(GedraDataType.formDoc), "The $t trait.") { property("v", "A value.") }
            }
        }
        svc().writeConfig(asClient(client), config)
        return config
    }

    /** The trait ids the consumption read would build this client's schema from now. */
    fun consumedTraits(client: String): List<String> =
        svc().currentConfigs(asClient(client), client).flatMap { it.entriesBySlot()["traitDef"].orEmpty() }
            .mapNotNull { it["traitId"] as? String }

    "the free tier consumes the latest revision, published or not" {
        val client = "tierfree"
        write(client, "fA")                 // v1, unpublished
        consumedTraits(client) shouldContain "fA"
        svc().publish(asClient(client), GedraId.of(GedraConfigType.configDoc, client, "${client}cfg"))
        write(client, "fA", "fB")           // v2, unpublished, on top of published v1
        // Free (no toggle): the latest revision, so the unpublished v2 with fB.
        consumedTraits(client) shouldContain "fB"
    }

    "the published-only tier consumes the latest published revision, not a newer unpublished one" {
        val client = "tierpub"
        write(client, "pA")
        svc().publish(asClient(client), GedraId.of(GedraConfigType.configDoc, client, "${client}cfg"))
        write(client, "pA", "pB")           // v2 unpublished adds pB

        svc().setPublishedOnly(asClient(client), client, true)
        // Published-only: consume v1 (pA), not the unpublished v2 (pB).
        consumedTraits(client).let {
            it shouldContain "pA"
            it shouldNotContain "pB"
        }
        // Toggling back to free brings the unpublished revision back into consumption.
        svc().setPublishedOnly(asClient(client), client, false)
        consumedTraits(client) shouldContain "pB"
    }

    "a published-only client with nothing published consumes nothing for that class" {
        val client = "tiernopub"
        write(client, "nA")                 // never published
        svc().setPublishedOnly(asClient(client), client, true)
        consumedTraits(client) shouldBe emptyList()
    }

    "the toggle endpoint sets the caller's own client's tier" {
        val own = Startup.mkTestBootCxt("gedraCfgTierEp", "gedraCfgTierEpTest", mapOf("KDR_DB_NAME" to "cfgTier_ep"))
        val admin = TestUser.create(own, "tieradmin@example.com", level = ROLE.admin)
        val result = admin.postData(CFEP.publishedOnly, mapOf(CFEP.publishedOnlyField to true))
        result[CFEP.publishedOnlyField] shouldBe true
        GedraConfigService.get(own).publishedOnly(admin.cxt, admin.selfClient()!!) shouldBe true
    }

    "a static client refuses the toggle and is published-only regardless" {
        val tcxt = Startup.mkTestBootCxt(
            "gedraCfgTierStatic", "gedraCfgTierStaticTest",
            mapOf(StaticClientComponent.loadFlag.name to "true", "KDR_DB_NAME" to "cfgTier_static"),
            additionalComponents = listOf(StaticClientComponent()),
        )
        val client = StaticClientComponent.clientId
        val svc = GedraConfigService.get(tcxt)
        // staticConfig comes from the source ClientDef, so it is published-only with no toggle needed.
        svc.publishedOnly(tcxt.mkSubContext("s", client).also { it.userId = 1L }, client) shouldBe true
        // And the toggle is refused.
        val ex = shouldThrow<KdrException> {
            svc.setPublishedOnly(tcxt.mkSubContext("s", client).also { it.userId = 1L }, client, false)
        }
        ex.code shouldBe EXC.badInput
    }
})

/** A fixture component whose source-code client is `staticConfig` -- the top tier, set only in source (#617). */
class StaticClientComponent : ComponentDefinition {
    override val providerName: String = "staticClientFixture"
    override fun isLoaded(cxt: KdrCxt): Boolean = cxt.getEnvBool(loadFlag) == true
    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "staticClient", "staticconfig", clientId) {
            defineClient(
                ClientDef(
                    clientId = clientId, name = "Static", usageType = ClientUsageType.production,
                    audience = ClientAudience.internal, enabledEnvironments = setOf(ENV.unit, ENV.local),
                    staticConfig = true,
                ),
            )
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        val loadFlag = EnvVarDef(
            "KDR_LOAD_STATIC_CLIENT", group = ENVGRP.application, defaultDoc = "off",
            description = "Test-only flag that loads the static-client fixture regardless of environment.",
        )
        const val clientId = "staticclient"
    }
}
