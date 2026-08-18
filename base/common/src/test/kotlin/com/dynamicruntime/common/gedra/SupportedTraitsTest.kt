package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.context.KdrInstanceConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly

/**
 * Which traits a client may **use**, as opposed to which it can see (issue #356).
 *
 * The distinction is the substance: all of the global schema stays visible, because `$ref`s have to resolve,
 * while support is an opt-in allowlist. A client that mentions nothing supports nothing, however much it can
 * see -- which is the half that reads backwards and so is asserted directly.
 */
class SupportedTraitsTest : StringSpec({

    val cxt = KdrCxt("traits", KdrInstanceConfig("supported", ENV.local, ENV.liveSource))

    fun traitConfig(client: String, name: String, vararg traitIds: String): GedraConfig =
        gedraConfig(cxt, name, "${client}ns", client) {
            for (id in traitIds) {
                trait("${id.replaceFirstChar { it.uppercase() }}Entry", id, setOf(GedraDataType.formDoc)) {
                    property(id, "Something.", required = true)
                }
            }
        }

    fun collectorOf(vararg configs: GedraConfig): GedraConfigCollector =
        GedraConfigCollector().apply { configs.forEach { add(cxt, it) } }

    fun def(vararg included: String): ClientDef = ClientDef(
        clientId = "acme",
        name = "Acme",
        usageType = ClientUsageType.dev,
        audience = ClientAudience.customer,
        includedTraits = included.toList(),
    )

    val global = traitConfig(GID.globalClient, "globalTraits", "name", "address")

    fun ids(traits: List<GedraTrait>) = traits.map { it.traitId }.sorted()

    "a client that mentions nothing supports nothing, however much it can see" {
        val configs = collectorOf(global)
        // It can see both -- visibility is what makes `$ref`s resolve...
        ids(configs.traitsFor("acme")) shouldContainExactly listOf("address", "name")
        // ...and supports neither.
        supportedTraits(configs, "acme", def(), emptySet()).shouldBeEmpty()
    }

    "naming a trait supports it, and only it" {
        ids(supportedTraits(collectorOf(global), "acme", def("name"), emptySet())) shouldContainExactly listOf("name")
    }

    // Functional: membership computed from what is global, never written down, so it cannot fall out of step
    // the way a hand-applied tag would.
    "the global group supports everything global declares" {
        ids(supportedTraits(collectorOf(global), "acme", def(CLD.allGlobal), emptySet()))
            .shouldContainExactly(listOf("address", "name"))
    }

    // Supported by having been declared at all: a client does not include itself.
    "a client's own traits are supported without being named" {
        val configs = collectorOf(global, traitConfig("acme", "acmeTraits", "loyalty"))
        ids(supportedTraits(configs, "acme", def(), emptySet())) shouldContainExactly listOf("loyalty")
    }

    // Altering a trait's entry type is as clear a statement of intent as naming it, and requiring both would
    // make the declared list a place to forget.
    "a trait the client customized is supported without a second mention" {
        val configs = collectorOf(global)
        val nameEntry = configs.traits.getValue("name").typeName
        ids(supportedTraits(configs, "acme", def(), setOf(nameEntry))) shouldContainExactly listOf("name")
    }

    "the group and an own trait combine rather than replacing each other" {
        val configs = collectorOf(global, traitConfig("acme", "acmeTraits", "loyalty"))
        ids(supportedTraits(configs, "acme", def(CLD.allGlobal), emptySet()))
            .shouldContainExactly(listOf("address", "loyalty", "name"))
    }

    // Only reachable for a client whose definition was dropped in a degraded production boot; behaving as it
    // did before clients existed is the safer of the two answers.
    "a client with no definition at all supports what it can see" {
        ids(supportedTraits(collectorOf(global), "acme", null, emptySet())) shouldContainExactly
            listOf("address", "name")
    }
})
