package com.dynamicruntime.common.gedra

import com.dynamicruntime.common.context.ACFG
import com.dynamicruntime.common.context.ENV
import com.dynamicruntime.common.context.KdrCxt
import com.dynamicruntime.common.startup.BootCheckMode
import com.dynamicruntime.common.context.KdrInstanceConfig
import com.dynamicruntime.common.exception.KdrException
import com.dynamicruntime.common.gedra.workflow.WfEntry
import com.dynamicruntime.common.gedra.workflow.WfSaveKind
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

    /** A config of one trait; a global one's id is rooted (issue #951), so its type and field take the local part. */
    fun nameConfig(configName: String = "coreTraits", traitId: String = "kdr:name", namespace: String = GCFG.globalNamespace, client: String = GID.globalClient) =
        gedraConfig(devCxt, configName, namespace, client) {
            val local = traitId.substringAfter(':')
            trait("${local.replaceFirstChar { it.uppercase() }}Entry", traitId, setOf(GedraDataType.formDoc)) {
                property(local, "Something.", required = true)
            }
        }

    "a config is taken, and its entry types come with it" {
        val collector = GedraConfigCollector()
        collector.add(devCxt, nameConfig()) shouldBe true
        collector.configs.map { it.name } shouldContainExactly listOf("coreTraits")
        collector.globalTraits() shouldContainKey "kdr:name"
        collector.defs().keys shouldContain "kdr.core.NameEntry"
        collector.issues.shouldBeEmpty()
    }

    // --- the checks ----------------------------------------------------------

    // The rule that lets stored data carry a bare trait id: unique across every namespace and every kind. A
    // second claim is refused whichever namespace it came from, which is the whole point.
    "one trait id cannot be claimed by two configs, even in different namespaces" {
        val collector = GedraConfigCollector()
        collector.add(devCxt, nameConfig())
        val message = shouldThrow<KdrException> {
            collector.add(devCxt, nameConfig(configName = "extraTraits", namespace = "kdr.other"))
        }.message
        // Both sides named, so the reader does not have to go looking for the other half.
        message.shouldNotBeNull() shouldContain "gc.cd.global.coreTraits"
        message shouldContain "gc.cd.global.extraTraits"
        message shouldContain "'kdr:name'"
    }

    // A client declares into its own namespace (issue #949), `client.<clientId>` or beneath it, so its types can
    // never land where a global name -- or another client's -- lives. That is what replaced "who claimed it first".
    "a client config declaring outside its own namespace is refused" {
        val message = shouldThrow<KdrException> {
            GedraConfigCollector().add(devCxt, nameConfig(client = "acme"))
        }.fullMessage()
        message shouldContain "declares its types in '${GCFG.globalNamespace}'"
        message shouldContain "its own namespace, 'client.acme'"
        // Another client's is no better than a global one.
        shouldThrow<KdrException> {
            GedraConfigCollector().add(devCxt, nameConfig(configName = "betaTraits", traitId = "budget", namespace = "client.acme", client = "beta"))
        }.fullMessage() shouldContain "'client.beta'"
    }

    "a client declares into its own namespace, or beneath it" {
        val collector = GedraConfigCollector()
        collector.add(devCxt, nameConfig(configName = "acmeTraits", traitId = "costCentre", namespace = "client.acme", client = "acme")) shouldBe true
        collector.add(devCxt, nameConfig(configName = "acmeForms", traitId = "formNote", namespace = "client.acme.forms", client = "acme")) shouldBe true
        // A sandbox's copy declares into its parent's.
        collector.add(devCxt, SandboxConfigs.rebind(nameConfig(configName = "acmeMore", traitId = "more", namespace = "client.acme", client = "acme"))) shouldBe true
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
        collector.add(prodCxt, nameConfig(configName = "extraTraits", namespace = "kdr.other")) shouldBe false

        // First wins, deterministically -- component load order is loadPriority then registration, so the
        // winner is the same across restarts rather than whichever config happened to arrive first today.
        collector.configs.map { it.name } shouldContainExactly listOf("coreTraits")
        collector.globalTraits().getValue("kdr:name").typeName shouldBe "kdr.core.NameEntry"

        // And the node can say what it dropped, which is what stops a degraded boot from being silent.
        collector.issues.size shouldBe 1
        collector.issues.first().message shouldContain "Trait 'kdr:name' is declared by both"
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
        shouldThrow<KdrException> { collector.add(devCxt, nameConfig(configName = "extraTraits", namespace = "kdr.other")) }
            .message.shouldNotBeNull() shouldContain GCFG.checkEnvVar.name
    }

    // --- stored config (issue #839) --------------------------------------------

    // The inverse default: stored data is forgiven everywhere a person could be locked out of repairing it, and
    // strict only in unit tests, where a bad fixture should fail loudly.
    "stored config warns everywhere but unit, and has its own override" {
        fun storedIn(env: String, override: String? = null): KdrCxt {
            val config = KdrInstanceConfig("stored-$env-$override", env, ENV.liveSource)
            override?.let { config.put(GCFG.storedCheckEnvVar.name, it) }
            return KdrCxt("collect", config)
        }
        storedConfigCheckMode(storedIn(ENV.unit)) shouldBe BootCheckMode.strict
        for (env in listOf(ENV.local, ENV.dev, ENV.integration, ENV.prod)) {
            storedConfigCheckMode(storedIn(env)) shouldBe BootCheckMode.warn
        }
        storedConfigCheckMode(storedIn(ENV.unit, BootCheckMode.warn.name)) shouldBe BootCheckMode.warn
        storedConfigCheckMode(storedIn(ENV.prod, BootCheckMode.strict.name)) shouldBe BootCheckMode.strict
        // The two variables are independent: the source one does not move the stored mode, nor the reverse.
        storedConfigCheckMode(cxtIn(ENV.local, BootCheckMode.strict.name)) shouldBe BootCheckMode.warn
        gedraConfigCheckMode(storedIn(ENV.local, BootCheckMode.warn.name)) shouldBe BootCheckMode.strict
        configCheckMode(devCxt, GedraConfigOrigin.stored) shouldBe BootCheckMode.warn
        configCheckMode(devCxt, GedraConfigOrigin.source) shouldBe BootCheckMode.strict
    }

    fun storedNameConfig(configName: String, namespace: String) =
        gedraConfig(devCxt, configName, namespace, GID.globalClient, GedraConfigOrigin.stored) {
            trait("NameEntry", "kdr:name", setOf(GedraDataType.formDoc)) { property("name", "Something.", required = true) }
        }

    // The arriving config is the one refused, so its origin decides: a stored config colliding with a source one
    // is dropped with a warning on a local node, where the same collision between two source configs refuses.
    "a problem is judged by the origin of the config that holds it" {
        val collector = GedraConfigCollector()
        collector.add(devCxt, nameConfig())
        collector.add(devCxt, storedNameConfig("storedTraits", "kdr.stored")) shouldBe false
        val issue = collector.issues.single()
        issue.origin shouldBe GedraConfigOrigin.stored
        issue.storedConfigId.shouldNotBeNull() shouldContain "storedTraits"
        issue.client shouldBe GID.globalClient
        issue.elementKind shouldBe GCEL.config
        // The source config keeps the trait; the stored one never displaced it.
        collector.globalTraits().getValue("kdr:name").typeName shouldBe "kdr.core.NameEntry"

        shouldThrow<KdrException> { collector.add(devCxt, nameConfig(configName = "extraTraits", namespace = "kdr.other")) }
            .message.shouldNotBeNull() shouldContain GCFG.checkEnvVar.name
    }

    "a strict stored-config refusal names the stored config and its own variable" {
        val unitCxt = KdrCxt("collect", KdrInstanceConfig("stored-unit", ENV.unit, ENV.liveSource))
        val collector = GedraConfigCollector()
        collector.add(unitCxt, nameConfig())
        val message = shouldThrow<KdrException> { collector.add(unitCxt, storedNameConfig("storedTraits", "kdr.stored")) }
            .message.shouldNotBeNull()
        message shouldContain GCFG.storedCheckEnvVar.name
        message shouldContain "storedTraits"
    }

    "off takes everything, checks nothing" {
        val offCxt = cxtIn(ENV.local, BootCheckMode.off.name)
        val collector = GedraConfigCollector()
        collector.add(offCxt, nameConfig()) shouldBe true
        collector.add(offCxt, nameConfig(configName = "extraTraits", namespace = "kdr.other")) shouldBe true
        // The later claim wins under `off`, which is what "no checking" means rather than a second policy.
        collector.globalTraits().getValue("kdr:name").typeName shouldBe "kdr.other.NameEntry"
        collector.issues.shouldBeEmpty()
    }

    // The union-feeding accessors partition by trait flavor (issue #316): `traitsFor` and `stateTraits`
    // manufacture the data and state unions, and a config trait must reach neither, or a `ClientDefEntry` branch
    // would land in a form document's data union. Asserted at the accessors, not just at the config's maps,
    // because the accessors are what the union builders actually read.
    "the collector keeps config traits out of the data and state accessors" {
        val collector = GedraConfigCollector()
        val mixed = gedraConfig(devCxt, "mixed", GCFG.globalNamespace) {
            trait("DName", "kdr:dname", setOf(GedraDataType.formDoc)) { property("dname", "D.", required = true) }
            stateTrait("SName", "kdr:sname", setOf(GedraDataType.formDoc), StateTraitClass.asserted) { property("sname", "S.") }
            configTrait("CName", "kdr:cname", setOf(GedraConfigType.configDoc)) { property("cname", "C.") }
        }
        collector.add(devCxt, mixed) shouldBe true
        collector.traitsFor(GID.globalClient).map { it.traitId } shouldContainExactly listOf("kdr:dname")
        collector.stateTraits().map { it.traitId } shouldContainExactly listOf("kdr:sname")
        collector.configTraits().map { it.traitId } shouldContainExactly listOf("kdr:cname")
    }

    // Config traits (issue #316) join the one global id space: a config trait may not reuse a data trait's id
    // declared by another config, any more than a state trait may.
    "a config trait id cannot reuse a data trait's id, across configs" {
        val collector = GedraConfigCollector()
        collector.add(devCxt, nameConfig()) shouldBe true
        val clash = gedraConfig(devCxt, "storedConfig", GCFG.globalNamespace) {
            configTrait("NameCfgEntry", "kdr:name", setOf(GedraConfigType.configDoc)) { property("x", "X.") }
        }
        val ex = shouldThrow<KdrException> { collector.add(devCxt, clash) }
        (ex.message ?: "") shouldContain "Trait 'kdr:name'"
        collector.configTraits().shouldBeEmpty()
    }

    // --- owner names (issue #921) ----------------------------------------------

    // A client's own names are bare: a colon would read as another owner's definition, and it is what global names
    // are rooted with -- so refusing it on the client's side is what keeps the two disjoint by construction.
    "a client config declaring a rooted name is refused, whatever the kind" {
        fun clientConfig(build: GedraConfigBuilder.() -> Unit) = gedraConfig(devCxt, "acmeMain", "client.acme", "acme") {
            trait("AcmeNoteEntry", "acmeNote", setOf(GedraDataType.formDoc)) { property("text", "A note.") }
            build()
        }
        val cases = mapOf(
            "trait id" to clientConfig {
                trait("KdrNoteEntry", "kdr:note", setOf(GedraDataType.formDoc)) { property("text", "A note.") }
            },
            "cfact name" to clientConfig { cfact("kdr:ready", "acme", "When acme is set up") },
            "task id" to clientConfig {
                workflow("acmeWf", WfEntry.survey) { task("kdr:first", "First") { trait("acmeNote"); save("s", "Save", WfSaveKind.edit) } }
            },
        )
        for ((kind, config) in cases) {
            val message = shouldThrow<KdrException> { GedraConfigCollector().add(devCxt, config) }.fullMessage()
            message shouldContain "A client's own $kind may not hold ':'"
        }
        // In production the config is dropped and the node carries on, saying so.
        val collector = GedraConfigCollector()
        collector.add(cxtIn(ENV.prod), cases.getValue("trait id")) shouldBe false
        collector.issues.single().message shouldContain "declares a name its client may not use"
    }

    "a client trait id is held to letters, digits and underscores" {
        val config = gedraConfig(devCxt, "acmeMain", "client.acme", "acme") {
            trait("AcmeNoteEntry", "acme.note", setOf(GedraDataType.formDoc)) { property("text", "A note.") }
        }
        shouldThrow<KdrException> { GedraConfigCollector().add(devCxt, config) }.fullMessage() shouldContain
            "letters, digits and underscores"
    }

    // A task id is persisted -- a CTA task and an approval are recorded by it -- so it is a variable name like a
    // workflow id, refused as the workflow is built.
    "a task id has to be a variable name" {
        shouldThrow<KdrException> {
            gedraConfig(devCxt, "acmeMain", "client.acme", "acme") {
                trait("AcmeNoteEntry", "acmeNote", setOf(GedraDataType.formDoc)) { property("text", "A note.") }
                workflow("acmeWf", WfEntry.survey) { task("review-1", "First") { trait("acmeNote"); save("s", "Save", WfSaveKind.edit) } }
            }
        }.fullMessage() shouldContain "'review-1' cannot be a task id"
    }

    // A global trait id is rooted (issue #951), with the root its config's namespace is under: whoever owns `kdr`
    // owns every `kdr:` trait, data, state and config traits alike.
    "a global config's trait id is rooted, under its namespace's root" {
        shouldThrow<KdrException> { GedraConfigCollector().add(devCxt, nameConfig(configName = "bareTraits", traitId = "bare")) }
            .fullMessage() shouldContain "'bare' is not a rooted trait id"
        shouldThrow<KdrException> { GedraConfigCollector().add(devCxt, nameConfig(configName = "abcTraits", traitId = "abc:thing")) }
            .fullMessage() shouldContain "'abc:thing' is under the root 'abc', not 'kdr'"
        val stateOnly = gedraConfig(devCxt, "stateTraits", GCFG.globalNamespace) {
            stateTrait("BareStateEntry", "bareState", setOf(GedraDataType.formDoc), StateTraitClass.asserted) { property("x", "X.") }
        }
        shouldThrow<KdrException> { GedraConfigCollector().add(devCxt, stateOnly) }.fullMessage() shouldContain
            "'bareState' is not a rooted trait id"
    }

    // A config trait is a slot of the config store, global as state is (issue #951): a client's would land in the one
    // global registry under an id no owner rule judges, where a later client's same-named data trait would meet it.
    "a client config's config trait is refused, and in production dropped while the rest stands" {
        val clientSlot = gedraConfig(devCxt, "acmeMain", "client.acme", "acme") {
            trait("AcmeNoteEntry", "acmeNote", setOf(GedraDataType.formDoc)) { property("text", "A note.") }
            configTrait("AcmeSlotEntry", "acmeSlot", setOf(GedraConfigType.configDoc)) { property("x", "X.") }
        }
        shouldThrow<KdrException> { GedraConfigCollector().add(devCxt, clientSlot) }
            .fullMessage() shouldContain "declares the config trait 'acmeSlot'"

        val collector = GedraConfigCollector()
        collector.add(cxtIn(ENV.prod), clientSlot) shouldBe true
        collector.configTraits().shouldBeEmpty()
        collector.traitsOwnedBy("acme").map { it.traitId } shouldContainExactly listOf("acmeNote")
        collector.issues.single().degradedTo shouldContain "Dropping the config trait"
        // The case that once reached the cross-owner assertion: another client's data trait of the same id.
        collector.add(cxtIn(ENV.prod), nameConfig(configName = "otherMain", traitId = "acmeSlot", namespace = "client.other", client = "other")) shouldBe true
    }

    // The rooted form is the global side's, so a global config may already declare one; renaming core's own names
    // under `kdr` is the rest of #921.
    "a global config may declare a rooted trait id" {
        val rooted = gedraConfig(devCxt, "rootedTraits", GCFG.globalNamespace) {
            trait("RootedEntry", "kdr:rooted", setOf(GedraDataType.formDoc)) { property("value", "Something.") }
        }
        GedraConfigCollector().add(devCxt, rooted) shouldBe true
    }
})

/** The global data traits kept, by id -- unique, since a global trait id is unique across every client (#807). */
private fun GedraConfigCollector.globalTraits(): Map<String, GedraTrait> =
    traitsOwnedBy(GID.globalClient).associateBy { it.traitId }
