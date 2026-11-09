package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.ClientSchemaSource
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.flavor.FLV
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.GCFG
import com.dynamicruntime.common.gedra.GE
import com.dynamicruntime.common.gedra.GID
import com.dynamicruntime.common.gedra.GU
import com.dynamicruntime.common.gedra.GedraConfigOrigin
import com.dynamicruntime.common.gedra.GedraConfigTrial
import com.dynamicruntime.common.gedra.configSlotFailures
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.gedra.isSandboxClient
import com.dynamicruntime.common.schema.SchMetaSchema
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.startup.SchemaService
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import com.dynamicruntime.sample.SampleTestInstances
import io.kotest.core.spec.style.StringSpec
import io.kotest.core.spec.style.stringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Invariants every sample configuration keeps (issue #1736 gathered these): the meta-schema covers every schema, each
 * config slot has its shape, every source trait is named for a person, and a trial reload leaves the live
 * configuration as it was. Each part below is one of the specs that used to be a file of its own, kept whole.
 */
class SampleInvariantsTest : StringSpec({
    include(metaSchemaCoverageTests)
    include(sampleConfigSlotShapeTests)
    include(sourceTraitNamesTests)
    include(configTrialIsolationTests)
})

/**
 * Every schema this repository declares passes the generated schema for schema (issue #1056) -- the half of the
 * test that holds it to the parser from the other side: the fault fixtures show it refuses what the parser
 * refuses, and this shows it does not refuse what the parser reads. A keyword table that grew a shape nothing
 * here satisfies, or a document stricter than its table, fails here on the first type that uses the keyword.
 *
 * Both readings are held to every type: the **walk**, which a write is judged by, and the **document**, validated
 * with our own validator.
 */
private val metaSchemaCoverageTests = stringSpec {
    val cxt = SampleTestInstances.default("metaSchemaCoverage")

    fun faults(name: String, body: Map<String, Any?>, stored: Boolean): List<String> =
        SchMetaSchema.structureFailures(body, directivesStandAtTop = stored).map { "$name: walk: ${it.path}: ${it.message}" } +
            validate(SchMetaSchema.nodeType, body).map { "$name: document: ${it.path}: ${it.message}" }

    "every type the node compiled -- the core's, the sample's and each client's -- passes the walk and the document" {
        val clients = (cxt.instanceConfig.get(KdrSchemaStore.clientSourceKey) as? ClientSchemaSource).shouldNotBeNull()
        val stores = listOf(SC.acme, SC.globex).map { clients.variantFor(it).shouldNotBeNull() } + cxt.getGlobalSchema()
        val defs = stores.flatMap { it.defs.entries }.associate { it.key to it.value.toJsonMapOrEmpty() }
        defs.size shouldBeGreaterThan 100
        defs.flatMap { (name, body) -> faults(name, body, stored = false) }.shouldBeEmpty()
    }

    "every schema body a source configuration stores passes the walk and the document" {
        val configs = SchemaCollector.get(cxt).shouldNotBeNull().gedraConfigs.configs
            .filter { it.configTraits.isEmpty() && it.stateTraits.isEmpty() }
        val bodies = configs.flatMap { config ->
            val slots = gedraConfigToEntries(config)
            slots[CCT.schemaDef].orEmpty().map { "${config.gedraId} ${it[CCT.typeName]}" to it[CCT.schema].toJsonMapOrEmpty() } +
                slots[CCT.traitDef].orEmpty().map { "${config.gedraId} ${it[CCT.traitId]}" to it[CCT.dataSchema].toJsonMapOrEmpty() }
        }
        bodies.size shouldBeGreaterThan 10
        bodies.flatMap { (name, body) -> faults(name, body, stored = true) }.shouldBeEmpty()
    }
}

/**
 * Every configuration the sample declares -- clients with workflows, reports, usage rules, overlays and types of
 * their own -- passes the config slot gate (issue #1052) in the form the serializer stores it in. The gate judges a
 * whole configuration on a patch, so anything the serializer can write must be something the gate accepts: a
 * configuration promoted from source, or read and written back, is never refused for its own canonical form.
 */
private val sampleConfigSlotShapeTests = stringSpec {
    val cxt = SampleTestInstances.default("sampleSlotShape")

    "the stored form of every source configuration is one the gate accepts" {
        val configs = SchemaCollector.get(cxt).shouldNotBeNull().gedraConfigs.configs
            // What a client's configuration may hold: the serializer refuses a config declaring config or state traits.
            .filter { it.configTraits.isEmpty() && it.stateTraits.isEmpty() }
        // The sample's clients are among them, not only the core's.
        configs.any { it.gedraId.client == SC.acme && it.workflows.isNotEmpty() } shouldBe true
        configs.any { it.reports.isNotEmpty() } shouldBe true
        configs.any { it.usages.isNotEmpty() } shouldBe true
        for (config in configs) {
            configSlotFailures(cxt, gedraConfigToEntries(config)).map { "${config.gedraId}: ${it.path}: ${it.message}" }.shouldBeEmpty()
        }
    }
}

/**
 * Every trait declared in source code has a name of its own (issue #1520's rule, `traitNameOf`): a layout heading, or
 * a title on its data type. One with neither is named by its id humanized -- "Kdr:name" -- in the forms listing's
 * Contains column, a form's section heading, the workflow editor, and Design View. Booted with the widest set a test
 * reaches: the sample, and the reference flavor's demo clients.
 */
private val sourceTraitNamesTests = stringSpec {

    "every trait declared in source code is named by a heading or a title, not by its id" {
        val cxt = SampleTestInstances.reference("sourceTraitNames")
        val schema = SchemaService.get(cxt)
        // A sandbox's configurations are its parent's, copied: checked once, as the parent's.
        val declared = SchemaCollector.get(cxt)!!.gedraConfigs.configs
            .filter { it.origin == GedraConfigOrigin.source && !isSandboxClient(it.gedraId.client) }
        val unnamed = declared.flatMap { config ->
            val client = config.gedraId.client
            val store = schema.storeFor(client.takeUnless { it == GID.globalClient })
            config.traits.values.filter { trait ->
                // The trait's data type, found as a form's entries carry it: the union of a kind it applies to.
                val dataType = trait.appliesTo.firstNotNullOfOrNull { kind ->
                    store.types["${GCFG.globalNamespace}.${GU.unionName(kind)}"]?.variants?.byValue?.get(trait.traitId)
                        ?.properties?.get(GE.data)?.valueType
                }
                val heading = dataType?.name?.let { store.layouts[it] }?.label
                dataType?.title.isNullOrBlank() && heading.isNullOrBlank()
            }.map { "${config.gedraId.client}: ${it.traitId}" }
        }
        // Compared with the empty list, so a failure names every trait that has no name of its own.
        unnamed shouldBe emptyList()
    }
}

/**
 * A trial reload (issue #843) leaves the running node exactly as it found it. The case worth pinning is a client with
 * **source-code** bundles -- sample `acme` -- because the trial's scratch collector shares those bundle objects with
 * the node: resolving their workflow functions there once reassigned each definition's `resolvedFunctions`, so a
 * refused write could still change what the node's workflows run.
 */
private val configTrialIsolationTests = stringSpec {
    val cxt = SampleTestInstances.default("trialIso")

    "a trial does not touch the resolved functions of a client's source-code workflows" {
        val bundles = SchemaCollector.get(cxt)!!.gedraConfigs.configs
            .filter { it.gedraId.client == SC.acme && !it.isStored }
        val defs = bundles.flatMap { it.workflows.values }
        val before = defs.associateWith { def -> def.resolvedFunctions to def.tasks.map { it.resolvedFunctions } }
        (defs.flatMap { d -> d.tasks.flatMap { it.resolvedFunctions } }.isNotEmpty()) shouldBe true

        // acme has no stored config here, so the trial judges its source bundles alone -- which is all it takes to
        // walk their workflows.
        GedraConfigTrial.trial(cxt, SC.acme).shouldBeEmpty()

        for (def in defs) {
            val (defFns, taskFns) = before.getValue(def)
            (def.resolvedFunctions === defFns) shouldBe true
            def.tasks.map { it.resolvedFunctions }.zip(taskFns).all { (now, was) -> now === was } shouldBe true
        }
    }
}
