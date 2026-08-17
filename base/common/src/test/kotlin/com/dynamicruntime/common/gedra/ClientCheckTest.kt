package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Declaring a client, and what happens when a declaration does not hold up (issue #343).
 *
 * Refusals are nearly the whole of this file, and none of them is scaffolding: a boot-time refusal is exactly
 * what a *successful* call can never reach, so each one is only ever exercised here. The degrading path
 * matters as much as the strict one -- it decides what a production node carries when a definition is wrong,
 * and "carries on without that client" is a very different answer from "does not start".
 */
class ClientCheckTest : StringSpec({

    // A context in a named environment, built without booting: the checks read the environment and an
    // optional env var, both of which live on the instance config.
    fun cxtIn(env: String): KdrCxt =
        KdrCxt("clients", KdrInstanceConfig("clients-$env", env, ENV.liveSource))

    val devCxt = cxtIn(ENV.local)
    val prodCxt = cxtIn(ENV.prod)

    /**
     * A config declaring one client. [declaredId] defaults to [clientId] so the two agree; passing them apart
     * is how the disagreement -- and the charset check, which only a *declared* id can fail -- is reached.
     */
    fun clientConfig(
        clientId: String,
        declaredId: String = clientId,
        usageType: ClientUsageType = ClientUsageType.dev,
        audience: ClientAudience = ClientAudience.internal,
        environments: Set<String> = setOf(ENV.unit, ENV.local),
        extendsFrom: String? = null,
        included: List<String> = emptyList(),
        configName: String = "${clientId}Client",
    ): GedraConfig = gedraConfig(devCxt, configName, "${clientId}config", clientId) {
        defineClient(
            ClientDef(
                clientId = declaredId,
                name = clientId.replaceFirstChar { it.uppercase() },
                usageType = usageType,
                audience = audience,
                enabledEnvironments = environments,
                extendsFromClientId = extendsFrom,
                includedTraits = included,
            ),
        )
    }

    /** A global config carrying one trait, so `includedTraits` has something real to name. */
    fun traitConfig(traitId: String): GedraConfig =
        gedraConfig(devCxt, "${traitId}Traits", GCFG.globalNamespace, GID.globalClient) {
            trait("${traitId.replaceFirstChar { it.uppercase() }}Entry", traitId, setOf(GedraDataType.formDoc)) {
                property(traitId, "Something.", required = true)
            }
        }

    /** A collector holding [configs], each already accepted. */
    fun collectorOf(vararg configs: GedraConfig): GedraConfigCollector =
        GedraConfigCollector().apply { configs.forEach { add(devCxt, it) } }

    /** The message from the refusal [configs] provoke in a strict environment. */
    fun refusal(vararg configs: GedraConfig): String =
        shouldThrow<KdrException> { checkClientDefs(devCxt, collectorOf(*configs)) }.fullMessage()

    "a client is declared, and comes back keyed by its id" {
        val result = checkClientDefs(devCxt, collectorOf(clientConfig("acme")))
        result.clients shouldContainKey "acme"
        result.clients.getValue("acme").name shouldBe "Acme"
        result.issues.shouldBeEmpty()
    }

    "a config declaring no client contributes none" {
        checkClientDefs(devCxt, collectorOf(traitConfig("name"))).clients.shouldBeEmpty()
    }

    // --- what a definition can be judged on by itself -------------------------

    // Only a *declared* id can fail this: the config's own id was held to the same rule when `GedraId.of`
    // built it. Which is the point of declaring it -- when a definition arrives as data the two halves
    // travel separately, and this is the check that notices.
    "a client id that a gedra id could not carry is refused" {
        refusal(clientConfig("acme", declaredId = "ac-me")) shouldContain "holds '-'"
    }

    "a client id starting with a digit is refused" {
        refusal(clientConfig("acme", declaredId = "1acme")) shouldContain "starts with '1'"
    }

    "a config filed under one client cannot declare another" {
        val message = refusal(clientConfig("acme", declaredId = "beta"))
        message shouldContain "declares the client 'beta'"
        message shouldContain "filed under 'acme'"
    }

    "one client cannot be defined twice" {
        val message = refusal(
            clientConfig("acme", configName = "acmeClient"),
            clientConfig("acme", configName = "acmeExtras"),
        )
        message shouldContain "'acme' is defined twice"
        message shouldContain "gc.cd.acme.acmeExtras"
    }

    "a client enabled nowhere is refused" {
        refusal(clientConfig("acme", environments = emptySet())) shouldContain "names no environments"
    }

    "a client naming something that is not an environment is refused" {
        val message = refusal(clientConfig("acme", environments = setOf(ENV.unit, ENV.local, "staging")))
        message shouldContain "'staging'"
        message shouldContain "is not an environment"
    }

    // The rule reads as bureaucratic until the reason is beside it: a client in active use must never be one
    // that local development and the unit tests are locked out of.
    "a client enabled beyond local must also be enabled in unit and local" {
        val message = refusal(clientConfig("acme", environments = setOf(ENV.dev, ENV.prod)))
        message shouldContain "enabled in dev, prod"
        message shouldContain "not in unit or local"
    }

    "unit and local alone need nothing else" {
        checkClientDefs(devCxt, collectorOf(clientConfig("acme", environments = setOf(ENV.unit))))
            .clients shouldContainKey "acme"
    }

    // --- the functional group, and why it takes both conditions ---------------

    "a customer client in production may not include a functional group" {
        val message = refusal(
            clientConfig(
                "acme",
                usageType = ClientUsageType.production,
                audience = ClientAudience.customer,
                environments = ENV.names.toSet(),
                included = listOf(CLD.allGlobal),
            ),
        )
        message shouldContain CLD.allGlobal
        message shouldContain "somebody other than us depends on it"
    }

    // Each half of the condition does real work, so each half alone has to be allowed. A customer's dev client
    // tracking new global traits is how they preview what is coming; an internal production client -- which is
    // what `hub` is -- has no second party to surprise.
    "a customer dev client and an internal production client may both include one" {
        val result = checkClientDefs(
            devCxt,
            collectorOf(
                traitConfig("name"),
                clientConfig(
                    "acme",
                    usageType = ClientUsageType.dev,
                    audience = ClientAudience.customer,
                    included = listOf(CLD.allGlobal),
                ),
                clientConfig(
                    "ourHub",
                    usageType = ClientUsageType.production,
                    audience = ClientAudience.internal,
                    environments = ENV.names.toSet(),
                    included = listOf(CLD.allGlobal),
                ),
            ),
        )
        result.clients.keys shouldContainExactly setOf("acme", "ourHub")
    }

    "a group that does not exist is refused" {
        refusal(clientConfig("acme", included = listOf("#everything"))) shouldContain "#everything"
    }

    // --- what needs every other client, and every trait, to be present ---------

    "a client extending one this deployment does not define is refused" {
        refusal(clientConfig("acme", extendsFrom = "template")) shouldContain "does not define"
    }

    "extension is one level" {
        val message = refusal(
            clientConfig("base"),
            clientConfig("middle", extendsFrom = "base"),
            clientConfig("leaf", extendsFrom = "middle"),
        )
        message shouldContain "'leaf' extends 'middle', which itself extends 'base'"
    }

    "a client naming itself as its base is refused, being its own parent" {
        refusal(clientConfig("acme", extendsFrom = "acme")) shouldContain "'acme' extends 'acme'"
    }

    "one level of extension is fine" {
        val result = checkClientDefs(
            devCxt,
            collectorOf(clientConfig("base"), clientConfig("leaf", extendsFrom = "base")),
        )
        result.clients.getValue("leaf").extendsFromClientId shouldBe "base"
    }

    // A client sees its own traits and global's and nobody else's, so this catches a typo, a trait that was
    // never declared, and somebody else's trait, with one check.
    "a client including a trait it cannot see is refused" {
        val message = refusal(traitConfig("name"), clientConfig("acme", included = listOf("nmae")))
        message shouldContain "'nmae'"
        message shouldContain "is not a trait it can see"
    }

    "a client including a trait it can see is taken" {
        val result = checkClientDefs(
            devCxt,
            collectorOf(traitConfig("name"), clientConfig("acme", included = listOf("name"))),
        )
        result.clients.getValue("acme").includedTraitIds shouldContainExactly listOf("name")
    }

    // Dropping a client drops the ones built on it, which is the reason the second pass runs against the
    // survivors of the first rather than against everything declared.
    "a client extending one that was itself dropped is dropped too" {
        val result = checkClientDefs(
            prodCxt,
            collectorOf(
                clientConfig("base", environments = setOf(ENV.dev)),
                clientConfig("leaf", extendsFrom = "base"),
            ),
        )
        result.clients.shouldBeEmpty()
        result.issues.size shouldBe 2
        result.issues[1].message shouldContain "'leaf' extends 'base', which this deployment does not define"
    }

    // --- what a problem does, which is not the same everywhere -----------------

    // The paradigm #296 established and #299 applied to configs: a configuration defect degrades in
    // production and refuses everywhere else. Here the degradation is dropping the *client* -- the bundle's
    // traits stay declared, and nothing can reach them, because reaching them goes through the client.
    "in production a bad definition is logged and the client dropped, and the rest are kept" {
        val result = checkClientDefs(
            prodCxt,
            collectorOf(clientConfig("acme", declaredId = "ac-me"), clientConfig("beta")),
        )
        result.clients.keys shouldContainExactly setOf("beta")
        result.issues.size shouldBe 1
        result.issues[0].message shouldContain "holds '-'"
        result.issues[0].degradedTo shouldContain "Dropping the client"
    }

    "the refusal says how to start anyway" {
        refusal(clientConfig("acme", declaredId = "ac-me")) shouldContain "${GCFG.checkEnvVar}=${GCFG.warn}"
    }

    // --- the clients every deployment has --------------------------------------

    "hub and public are declared, and hold up" {
        val result = checkClientDefs(devCxt, collectorOf(coreTraits(devCxt), *coreClients(devCxt).toTypedArray()))
        result.issues.shouldBeEmpty()
        val hub = result.clients["hub"].shouldNotBeNull()
        hub.usageType shouldBe ClientUsageType.production
        hub.audience shouldBe ClientAudience.internal
        hub.includedTraits shouldContainExactly listOf(CLD.allGlobal)
        val public = result.clients["public"].shouldNotBeNull()
        public.audience shouldBe ClientAudience.customer
        // Enabled everywhere, both of them: `hub` is the acting default for a context that names no client,
        // and `public` holds every self-registered user.
        ENV.names.forEach { hub.isEnabledIn(it) shouldBe true }
        ENV.names.forEach { public.isEnabledIn(it) shouldBe true }
    }

    // Thrown by the builder rather than collected as a config-set problem: two clients in one bundle is an
    // authoring mistake with no coherent reading, since the config's id can file only one of them.
    "a config cannot declare two clients" {
        fun def(clientId: String) = ClientDef(
            clientId = clientId,
            name = clientId.replaceFirstChar { it.uppercase() },
            usageType = ClientUsageType.dev,
            audience = ClientAudience.internal,
        )
        val message = shouldThrow<KdrException> {
            gedraConfig(devCxt, "twoClients", "twoconfig", "acme") {
                defineClient(def("acme"))
                defineClient(def("beta"))
            }
        }.fullMessage()
        message shouldContain "already defines the client 'acme'"
    }
})
