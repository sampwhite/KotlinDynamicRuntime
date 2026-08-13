package com.dynamicruntime.kdn

import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GT
import com.dynamicruntime.common.gedra.GedraDataType
import com.dynamicruntime.common.schema.SchFailCode
import com.dynamicruntime.common.schema.SchOpts
import com.dynamicruntime.common.schema.coerceAndValidate
import com.dynamicruntime.common.startup.SchemaCollector
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * The first real trait, and the first time a component contributes a config (issues #299, #300).
 *
 * Booted rather than built in place, because what is being checked is the *chain*: a component declares a
 * bundle, the registry collects it, the collector checks and folds in its types, and the schema service
 * compiles them. Any of those links could be broken while every part passed its own tests.
 *
 * Nearly all of this is superseded the moment #301's fixture round-trips a `name` entry, which is what a
 * fixture exercising the whole chain is *for*. The tags say so; #301 removes them.
 */
class CoreTraitsTest : StringSpec({

    val cxt = Startup.mkTestBootCxt("coreTraits", "coreTraitsTest")

    // SCAFFOLD(P5): superseded by the fixture round trip, which cannot succeed unless all of this held.
    "the name trait arrives, through every link of the chain" {
        val collector = SchemaCollector.get(cxt).shouldNotBeNull()

        // The component declared a bundle, and the collector took it.
        collector.gedraConfigs.configs.map { it.gedraId.fullId } shouldContainExactly
            listOf("gc.cd.global.${GT.coreTraits}")

        // Its trait is registered under the id stored data will carry.
        val trait = collector.gedraConfigs.traits[GT.name].shouldNotBeNull()
        trait.typeName shouldBe "${GCFG.globalNamespace}.${GT.nameEntry}"
        trait.appliesTo shouldContainExactly listOf(GedraDataType.formDoc)

        // And the entry type it generated compiled with everything else, rather than sitting in a side store.
        cxt.getSchema().types shouldContainKey "${GCFG.globalNamespace}.${GT.nameEntry}"
    }

    // SCAFFOLD(P5): superseded by the fixture round trip.
    "an entry validates in the documented shape" {
        val entry = cxt.getSchema().types.getValue("${GCFG.globalNamespace}.${GT.nameEntry}")
        val sent = mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to "My Expense Form"))
        coerceAndValidate(entry, sent, SchOpts(forInput = true)).failures.shouldBeEmpty()
    }

    // SCAFFOLD(P5): needs #301 to carry a too-long name as a failing case first -- see the note on that issue.
    // Until it does, deleting this would lose the only check that the bound is declared at all.
    "a name longer than the bound is refused" {
        val entry = cxt.getSchema().types.getValue("${GCFG.globalNamespace}.${GT.nameEntry}")
        val tooLong = "x".repeat(GT.nameMaxLength + 1)
        val failures = coerceAndValidate(
            entry,
            mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to tooLong)),
            SchOpts(forInput = true),
        ).failures
        failures.map { it.path to it.code } shouldContainExactly
            listOf("${GE.data}.${GT.name}" to SchFailCode.aboveMaximum)

        // The bound itself, so a change to it is a decision rather than a surprise.
        coerceAndValidate(
            entry,
            mapOf(GE.traitId to GT.name, GE.data to mapOf(GT.name to "x".repeat(GT.nameMaxLength))),
            SchOpts(forInput = true),
        ).failures.shouldBeEmpty()
    }
})
