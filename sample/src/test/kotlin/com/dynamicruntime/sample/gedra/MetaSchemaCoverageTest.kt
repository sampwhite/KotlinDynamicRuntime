package com.dynamicruntime.sample.gedra

import com.dynamicruntime.common.context.ClientSchemaSource
import com.dynamicruntime.common.context.KdrSchemaStore
import com.dynamicruntime.common.gedra.CCT
import com.dynamicruntime.common.gedra.gedraConfigToEntries
import com.dynamicruntime.common.schema.SchMetaSchema
import com.dynamicruntime.common.schema.validate
import com.dynamicruntime.common.startup.SchemaCollector
import com.dynamicruntime.common.util.toJsonMapOrEmpty
import com.dynamicruntime.kdn.Startup
import com.dynamicruntime.sample.SampleComponent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull

/**
 * Every schema this repository declares passes the generated schema for schema (issue #1056) -- the half of the
 * test that holds it to the parser from the other side: the fault fixtures show it refuses what the parser
 * refuses, and this shows it does not refuse what the parser reads. A keyword table that grew a shape nothing
 * here satisfies, or a document stricter than its table, fails here on the first type that uses the keyword.
 *
 * Both readings are held to every type: the **walk**, which a write is judged by, and the **document**, validated
 * with our own validator.
 */
class MetaSchemaCoverageTest : StringSpec({
    val cxt = Startup.mkTestBootCxt(
        "metaSchemaCoverage", "metaSchemaCoverageTest", mapOf("KDR_LOAD_SAMPLE" to "true"),
        additionalComponents = listOf(SampleComponent()),
    )

    fun faults(name: String, body: Map<String, Any?>, stored: Boolean): List<String> =
        SchMetaSchema.structureFailures(body, directivesStandAtTop = stored).map { "$name: walk: ${it.path}: ${it.message}" } +
            validate(SchMetaSchema.nodeType, body).map { "$name: document: ${it.path}: ${it.message}" }

    "every type the node compiled -- the core's, the sample's and each client's -- passes the walk and the document" {
        val clients = (cxt.instanceConfig.get(KdrSchemaStore.clientSourceKey) as? ClientSchemaSource).shouldNotBeNull()
        val stores = listOf(SC.acme, SC.globex).map { clients.storeFor(it).shouldNotBeNull() } + cxt.getGlobalSchema()
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
})
