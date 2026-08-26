package com.dynamicruntime.kdn

import com.dynamicruntime.common.gedra.ClientUsageType
import com.dynamicruntime.common.gedra.ClientDef
import com.dynamicruntime.common.gedra.ClientAudience
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.ENVGRP
import com.dynamicruntime.common.context.EnvVarDef
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.gedra.GU
import com.dynamicruntime.common.context.CL
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GedraConfig
import com.dynamicruntime.common.gedra.gedraConfig
import com.dynamicruntime.common.schema.SCT
import com.dynamicruntime.common.startup.ComponentDefinition
import com.dynamicruntime.common.startup.InstanceRegistry
import com.dynamicruntime.common.startup.SchemaService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * A client's schema variant, built through the real startup path (issue #356).
 *
 * The unit tests over `overlayDefs` and `narrowingProblems` cover what an overlay means; this covers that a
 * booted instance actually carries one -- that a client's own config becomes an **overlay** rather than a
 * change to the shared document, which is the distinction a mistake here would erase silently and for
 * everybody.
 */
class ClientSchemaVariantTest : StringSpec({

    fun boot(name: String): KdrCxt {
        InstanceRegistry.register(listOf(VariantFixtureComponent()))
        return Startup.mkTestBootCxt("variants", name, mapOf(VariantFixtureComponent.loadFlag.name to "true"))
    }

    fun schema(cxt: KdrCxt): SchemaService =
        SchemaService.get(cxt).also { it.checkInit(cxt) }

    "a client's alteration applies to that client and to nobody else" {
        val service = schema(boot("variantTest"))
        // Global keeps both properties -- the client declared a type under the same name, and that did not
        // change the shared document.
        service.storeFor(null).types.getValue(VariantFixtureComponent.sampleType)
            .properties.keys shouldContainExactly setOf("keep", "drop")
        // The client sees only what it kept.
        service.storeFor(CL.hub).types.getValue(VariantFixtureComponent.sampleType)
            .properties.keys shouldContainExactly setOf("keep")
    }

    // The case `overlayDefs` exists for: the ref names the original, and for this client it has to resolve to
    // the altered form. Nothing in the referring type was edited -- it does not know.
    "a ref to an altered type resolves to the client's version" {
        val service = schema(boot("variantTest"))
        fun refFrom(store: com.dynamicruntime.common.context.KdrSchemaStore) =
            store.types.getValue(VariantFixtureComponent.holderType)
                .properties.getValue("sample").valueType.properties.keys

        refFrom(service.storeFor(null)) shouldContainExactly setOf("keep", "drop")
        refFrom(service.storeFor(CL.hub)) shouldContainExactly setOf("keep")
    }

    // Identity, not equality: a client that varies nothing costs no parse and no memory.
    "a client that varies nothing shares the global store" {
        val service = schema(boot("variantTest"))
        service.storeFor(CL.public) shouldBeSameInstanceAs service.storeFor(null)
        service.storeFor("nosuchclient") shouldBeSameInstanceAs service.storeFor(null)
    }

    // Endpoints stay global on a variant, which is what keeps `RequestService`'s path-keyed type caches
    // sound -- case (a) in `client-definition.md`.
    "a variant shares the global endpoints and tables" {
        val service = schema(boot("variantTest"))
        val variant = service.storeFor(CL.hub)
        variant.endpoints shouldBeSameInstanceAs service.storeFor(null).endpoints
        variant.tables shouldBeSameInstanceAs service.storeFor(null).tables
    }

    // Refused at boot, in a strict environment: an alteration that widens would let this client store data
    // that is invalid to everybody else.
    "an alteration that widens refuses the boot" {
        val ex = shouldThrow<KdrException> {
            InstanceRegistry.register(listOf(WideningFixtureComponent()))
            Startup.mkTestBootCxt("widening", "wideningVariantTest", mapOf(WideningFixtureComponent.loadFlag.name to "true"))
        }
        val message = ex.fullMessage()
        message shouldContain "does not narrow"
        message shouldContain "adds the property 'extra'"
    }

    // The other half of a variant: which traits are *in* the union, as opposed to what those traits accept.
    // `offsite` declares no `includedTraits`, so it supports nothing -- an ordinary state for a client that
    // has not declared any yet, and one the union has to be able to express.
    // Looped over **every** entry kind rather than written for `formDoc`, which is the guard the drift needed:
    // the global pass and the per-client pass kept their own lists of kinds, and when `wfData` was added to
    // one and not the other every client silently kept the global workflow-data union. One shared list now,
    // and this fails if a kind is ever regenerated globally and not per client.
    "a client supporting no traits gets a union that recognizes none, for every entry kind" {
        val service = schema(boot("variantTest"))
        GU.entryKinds.forEach { kind ->
            val unionName = "globalconfig." + GU.unionName(kind)
            // Global selects between its traits by branch...
            service.storeFor(null).types.getValue(unionName).variants.shouldNotBeNull()
            // ...and a client supporting none has nothing to select between, so every entry lands on the open
            // shape as plain JSON rather than the boot refusing a `oneOf` with no branches in it.
            val theirs = service.storeFor(VariantFixtureComponent.narrowClient).types.getValue(unionName)
            theirs.variants shouldBe null
            theirs.additionalProperties shouldBe true
        }
    }
})

/** Contributes a global type and a `hub`-owned alteration of it, so a boot has a variant to build. */
class VariantFixtureComponent : ComponentDefinition {
    override val providerName: String = "variantFixture"

    override fun isLoaded(cxt: KdrCxt): Boolean = cxt.getEnvBool(loadFlag) == true

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        // Global: the shared document, with a type and something that refers to it.
        gedraConfig(cxt, "variantBase", baseNamespace, GID.globalClient) {
            type("Sample") {
                type = SCT.kObject
                property("keep", "Kept.", required = true)
                property("drop", "Dropped.")
            }
            type("Holder") {
                type = SCT.kObject
                property("sample", "Refers to the sample.") { ref("Sample") }
            }
        },
        // A client that declares no `includedTraits` at all: it supports nothing, which is an ordinary state
        // for a client nobody has finished setting up, and one the union has to be able to say.
        gedraConfig(cxt, "narrowClient", "${narrowClient}config", narrowClient) {
            defineClient(
                ClientDef(
                    clientId = narrowClient,
                    name = "Narrow",
                    usageType = ClientUsageType.dev,
                    audience = ClientAudience.customer,
                    enabledEnvironments = setOf(ENV.unit, ENV.local),
                ),
            )
        },
        // `hub`'s own: the same name, which makes it an alteration of that type for `hub` alone.
        gedraConfig(cxt, "variantOverlay", "${baseNamespace}hub", CL.hub) {
            // Qualified through the companion, not "$namespace.Sample": inside a builder block `namespace`
            // is the *builder's* own, so that template would name a type in this config's namespace and
            // quietly declare a new type instead of altering the global one.
            type(sampleType) {
                type = SCT.kObject
                property("keep", "Kept.", required = true)
            }
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        val loadFlag = EnvVarDef(
            "KDR_LOAD_VARIANT_FIXTURE", group = ENVGRP.application, defaultDoc = "off",
            description = "Test-only flag that loads this fixture component regardless of environment.",
        )
        const val baseNamespace = "variantfixture"
        const val narrowClient = "narrowfixture"
        const val sampleType = "$baseNamespace.Sample"
        const val holderType = "$baseNamespace.Holder"
    }
}

/** Contributes an alteration that adds a property, which widens and must be refused. */
class WideningFixtureComponent : ComponentDefinition {
    override val providerName: String = "wideningFixture"

    override fun isLoaded(cxt: KdrCxt): Boolean = cxt.getEnvBool(loadFlag) == true

    override fun gedraConfigs(cxt: KdrCxt): List<GedraConfig> = listOf(
        gedraConfig(cxt, "wideningBase", baseNamespace, GID.globalClient) {
            type("Sample") {
                type = SCT.kObject
                property("keep", "Kept.", required = true)
            }
        },
        gedraConfig(cxt, "wideningOverlay", "${baseNamespace}hub", CL.hub) {
            type("$baseNamespace.Sample") {
                type = SCT.kObject
                property("keep", "Kept.", required = true)
                property("extra", "Not theirs to add.")
            }
        },
    )

    @Suppress("ConstPropertyName")
    companion object {
        val loadFlag = EnvVarDef(
            "KDR_LOAD_WIDENING_FIXTURE", group = ENVGRP.application, defaultDoc = "off",
            description = "Test-only flag that loads this fixture component regardless of environment.",
        )
        const val baseNamespace = "wideningfixture"
    }
}
