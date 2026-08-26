package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Collecting Gedra config bundles, and what happens when two of them disagree (issue #299).
 *
 * Nearly all of this survives #301's fixture, because a boot-time refusal is exactly what a *successful*
 * call can never reach. The environment split is the part worth the most care: it decides whether a
 * production node starts at all, and it is keyed on something easy to key wrongly.
 */
class GedraConfigCollectorTest : StringSpec({

    // A context in a named environment, built without booting: what the check reads is the environment and an
    // optional env var, both of which live on the instance config.
    fun cxtIn(env: String, override: String? = null): KdrCxt {
        val config = KdrInstanceConfig("collect-$env-$override", env, ENV.liveSource)
        override?.let { config.put(GCFG.checkEnvVar.name, it) }
        return KdrCxt("collect", config)
    }

    val devCxt = cxtIn(ENV.local)

    fun nameConfig(configName: String = "coreTraits", traitId: String = "name", namespace: String = GCFG.globalNamespace, client: String = GID.globalClient) =
        gedraConfig(devCxt, configName, namespace, client) {
            trait("${traitId.replaceFirstChar { it.uppercase() }}Entry", traitId, setOf(GedraDataType.formDoc)) {
                property(traitId, "Something.", required = true)
            }
        }

    "a config is taken, and its entry types come with it" {
        val collector = GedraConfigCollector()
        collector.add(devCxt, nameConfig()) shouldBe true
        collector.configs.map { it.name } shouldContainExactly listOf("coreTraits")
        collector.traits shouldContainKey "name"
        collector.defs().keys shouldContain "globalconfig.NameEntry"
        collector.issues.shouldBeEmpty()
    }

    // --- the checks ----------------------------------------------------------

    // The rule that lets stored data carry a bare trait id: unique across every namespace and every kind. A
    // second claim is refused whichever namespace it came from, which is the whole point.
    "one trait id cannot be claimed by two configs, even in different namespaces" {
        val collector = GedraConfigCollector()
        collector.add(devCxt, nameConfig())
        val message = shouldThrow<KdrException> {
            collector.add(devCxt, nameConfig(configName = "extraTraits", namespace = "other"))
        }.message
        // Both sides named, so the reader does not have to go looking for the other half.
        message.shouldNotBeNull() shouldContain "gc.cd.global.coreTraits"
        message shouldContain "gc.cd.global.extraTraits"
        message shouldContain "'name'"
    }

    // The generalized form of reserving `globalconfig`: an owner claims a namespace, and nobody else writes
    // into it. Stated this way because it is the check that has to hold when clients define their own config
    // -- reaching into somebody else's namespace is how one owner's definitions become visible to another.
    "a namespace has exactly one owner" {
        val collector = GedraConfigCollector()
        val message = shouldThrow<KdrException> {
            collector.add(devCxt, nameConfig(client = "acme"))
        }.message
        message.shouldNotBeNull() shouldContain GCFG.globalNamespace
        message shouldContain "belongs to 'global'"
    }

    "a client may own a namespace of its own" {
        val collector = GedraConfigCollector()
        collector.add(devCxt, nameConfig(configName = "acmeTraits", traitId = "costCentre", namespace = "acme", client = "acme")) shouldBe true
        // And nobody else may then write into it.
        shouldThrow<KdrException> {
            collector.add(devCxt, nameConfig(configName = "betaTraits", traitId = "budget", namespace = "acme", client = "beta"))
        }
    }

    "the same config contributed twice is refused" {
        val collector = GedraConfigCollector()
        collector.add(devCxt, nameConfig())
        shouldThrow<KdrException> { collector.add(devCxt, nameConfig()) }
            .message.shouldNotBeNull() shouldContain "contributed twice"
    }

    // --- the environment split ----------------------------------------------

    // The paradigm #296 established. A configuration defect on the side degrades in production and refuses
    // everywhere else: silence while the author is at the keyboard is how the defect reaches production, and
    // refusing to boot production over one bad trait takes down every endpoint that had nothing to do with it.
    "production keeps the first contributor and carries on" {
        val prodCxt = cxtIn(ENV.prod)
        val collector = GedraConfigCollector()
        collector.add(prodCxt, nameConfig()) shouldBe true
        collector.add(prodCxt, nameConfig(configName = "extraTraits", namespace = "other")) shouldBe false

        // First wins, deterministically -- component load order is loadPriority then registration, so the
        // winner is the same across restarts rather than whichever config happened to arrive first today.
        collector.configs.map { it.name } shouldContainExactly listOf("coreTraits")
        collector.traits.getValue("name").typeName shouldBe "globalconfig.NameEntry"

        // And the node can say what it dropped, which is what stops a degraded boot from being silent.
        collector.issues.size shouldBe 1
        collector.issues.first().message shouldContain "Trait 'name' is declared by both"
        collector.issues.first().degradedTo shouldContain "dropping"
    }

    // Keyed on the ENVIRONMENT, never on isTestInstance -- that flag is inferred from in-memory-ness and the
    // unit environment, so an ordinary local run against a real database is not a test instance, and keying
    // on it would hand a developer production behavior on their own machine. #296 got this wrong-footed
    // first and left a warning; this is that warning made executable.
    "the split follows the environment, not the test-instance flag" {
        gedraConfigCheckMode(cxtIn(ENV.prod)) shouldBe BootCheckMode.warn
        for (env in listOf(ENV.local, ENV.unit, ENV.dev, ENV.integration)) {
            gedraConfigCheckMode(cxtIn(env)) shouldBe BootCheckMode.strict
        }
        // An in-memory local instance is a "test instance" by inference and still gets the strict answer.
        val inMemoryLocal = KdrInstanceConfig("inMem", ENV.local, ENV.liveSource)
            .apply { put(ACFG.inMemoryOnly, true) }
        gedraConfigCheckMode(KdrCxt("collect", inMemoryLocal)) shouldBe BootCheckMode.strict
    }

    "an explicit override decides it either way" {
        gedraConfigCheckMode(cxtIn(ENV.prod, BootCheckMode.strict.name)) shouldBe BootCheckMode.strict
        gedraConfigCheckMode(cxtIn(ENV.local, BootCheckMode.warn.name)) shouldBe BootCheckMode.warn
        gedraConfigCheckMode(cxtIn(ENV.local, BootCheckMode.off.name)) shouldBe BootCheckMode.off
        // Anything unrecognized is not an override; the environment still decides.
        gedraConfigCheckMode(cxtIn(ENV.local, "maybe")) shouldBe BootCheckMode.strict
    }

    "strict mode names the way past itself" {
        val collector = GedraConfigCollector()
        collector.add(devCxt, nameConfig())
        shouldThrow<KdrException> { collector.add(devCxt, nameConfig(configName = "extraTraits", namespace = "other")) }
            .message.shouldNotBeNull() shouldContain GCFG.checkEnvVar.name
    }

    "off takes everything, checks nothing" {
        val offCxt = cxtIn(ENV.local, BootCheckMode.off.name)
        val collector = GedraConfigCollector()
        collector.add(offCxt, nameConfig()) shouldBe true
        collector.add(offCxt, nameConfig(configName = "extraTraits", namespace = "other")) shouldBe true
        // The later claim wins under `off`, which is what "no checking" means rather than a second policy.
        collector.traits.getValue("name").typeName shouldBe "other.NameEntry"
        collector.issues.shouldBeEmpty()
    }
})
